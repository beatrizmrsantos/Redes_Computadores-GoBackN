
import java.io.File;
import java.io.RandomAccessFile;
import java.util.SortedMap;
import java.util.TreeMap;

import cnss.simulator.Node;
import ft21.FT21AbstractSenderApplication;
import ft21.FT21_AckPacket;
import ft21.FT21_DataPacket;
import ft21.FT21_FinPacket;
import ft21.FT21_UploadPacket;

public class FT21SenderGBN extends FT21AbstractSenderApplication {

    private static final int TIMEOUT = 1000;

    static int RECEIVER = 1;

    enum State {
        BEGINNING, UPLOADING, FINISHING, FINISHED
    };

    static int DEFAULT_TIMEOUT = 1000;

    private File file;
    private RandomAccessFile raf;
    private int BlockSize;
    private int nextPacketSeqN, lastPacketSeqN;

    //size of window.
    private int windowsize;

    //last ack received.
    private int lastACKReceived;

    //value of the negative ack received.
    private int negativeACK;

    //true if received repeated acks, false if not.
    private boolean repeatedACK;

    // true if the last package (fin) was sent, false if not.
    private boolean lastSent = false;

    //true if the first package (upload) was sent, false if not.
    private boolean firstSent = false;

    //map of the times of when the packages were sent.
    // The key is the number of the package and the object is the time.
    private SortedMap<Integer, Integer> times;

    private State state;

    public FT21SenderGBN() {
        super(true, "FT21SenderGBN");
    }

    public int initialise(int now, int node_id, Node nodeObj, String[] args) {
        super.initialise(now, node_id, nodeObj, args);

        raf = null;
        file = new File(args[0]);
        BlockSize = Integer.parseInt(args[1]);
        windowsize = Integer.parseInt(args[2]);
        times = new TreeMap<>();
        repeatedACK = false;
        negativeACK = -1;
        lastACKReceived = -1;

        state = State.BEGINNING;
        lastPacketSeqN = (int) Math.ceil(file.length() / (double) BlockSize);

        return 1;
    }

    // on each time clock checks if occurred a time out of the first package of the map, checks if it has all the conditions needed to send the next package
    public void on_clock_tick(int now) {

        boolean timeout = timer(now);

        boolean canSend = ((times.size()<windowsize) && (state != State.FINISHED) && (nextPacketSeqN<=lastPacketSeqN));

        receivedNegativeACK();


        sendFirst(now);

        sendLast(now);

        if(canSend && lastACKReceived >=0) {
            changeState();
            if (timeout) {
                sendNextPacket(now);
                nextPacketSeqN++;
                repeatedACK = false;
            } else if (!repeatedACK) {
                sendNextPacket(now);
                nextPacketSeqN++;
            }
        }


    }

    // sends the first package (Upload)
    private void sendFirst(int now){
        if(!firstSent) {
            if (lastACKReceived == -1) {
                sendNextPacket(now);
                nextPacketSeqN++;
                firstSent =true;
            }
        }
    }

    // sends the last package (Fin)
    private void sendLast(int now){
        if(!lastSent) {
            if (lastPacketSeqN == lastACKReceived) {
                changeState();
                sendNextPacket(now);
                lastSent = true;
            }
        }
    }

    // changes the state of the next packaging being sent depending on the number of the package
    private void changeState(){

        if (nextPacketSeqN == 0){
            state = State.BEGINNING;
        } else {
            if (nextPacketSeqN > lastPacketSeqN){
                state = State.FINISHING;
            } else {
                if(nextPacketSeqN>0){
                    state = State.UPLOADING;
                }
            }
        }

    }

    //the package that was received with negative ack is the next to be sent
    private void receivedNegativeACK(){
        if(negativeACK>0){
            nextPacketSeqN = negativeACK;
            times.clear();
            negativeACK = -1;
        }
    }

    //checks if the first package, that was sent and didn't receive yet its ACK, has past the timeout value.
    //By comparing the time now with the time at it was sent
    private boolean timer(int now){
        boolean timeout=false;
        if(!times.isEmpty()) {
            int first = times.firstKey();
            if ((now - times.get(first)) > TIMEOUT) {
                if(nextPacketSeqN== lastPacketSeqN+1){
                    lastSent =false;
                }
                if(lastACKReceived ==-1){
                    firstSent =false;
                }
                nextPacketSeqN = lastACKReceived + 1;
                times.clear();
                timeout=true;
            }
        }
        return timeout;
    }

    // sends the package and adds the time it was sent to the map of packages that didn't receive their ack
    private void sendNextPacket(int now) {
        switch (state) {
            case BEGINNING:
                super.sendPacket(now, RECEIVER, new FT21_UploadPacket(file.getName()));
                break;
            case UPLOADING:
                super.sendPacket(now, RECEIVER, readDataPacket(file, nextPacketSeqN));
                break;
            case FINISHING:
                super.sendPacket(now, RECEIVER, new FT21_FinPacket(nextPacketSeqN));
                break;
            case FINISHED:
        }
        times.put(nextPacketSeqN, now);
    }


    // receives the ack from the receiver .
    //If the ack is the same as the last it signals so that a package was lost.
    //Also, it can identify if the ack receives was negative
    @Override
    public void on_receive_ack(int now, int client, FT21_AckPacket ack) {
        if(lastACKReceived == ack.cSeqN){
            repeatedACK = true;
        } else {
            if(ack.cSeqN<0){
                negativeACK = ack.cSeqN * (-1) ;
            }else {
                lastACKReceived = ack.cSeqN;
                if(!times.isEmpty()){
                    deleteAckReceived();
                }
            }
        }

        //if the ack received is the fin then state changes to finishing
        if(ack.cSeqN == lastPacketSeqN + 1){
            if(state == State.FINISHING){
                super.log(now, "All Done. Transfer complete...");
                super.printReport(now);
                state = State.FINISHED;
                return;
            }
        }

    }

    //deletes from the map the key with the same value as the ACK
   private void deleteAckReceived(){
        for(int i = 0; i <= lastACKReceived; i++){
            times.remove(i);
        }
   }

    private FT21_DataPacket readDataPacket(File file, int seqN) {
        try {
            if (raf == null)
                raf = new RandomAccessFile(file, "r");

            raf.seek(BlockSize * (seqN - 1));
            byte[] data = new byte[BlockSize];
            int nbytes = raf.read(data);
            return new FT21_DataPacket(seqN, data, nbytes);
        } catch (Exception x) {
            throw new Error("Fatal Error: " + x.getMessage());
        }
    }
}
