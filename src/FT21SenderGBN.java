
import java.io.File;
import java.io.RandomAccessFile;
import java.util.LinkedList;
import java.util.List;

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
    private int windowsize;
    private int lastACKRecieved;
    private boolean negativeACK;
    private boolean repeatedACK;
    private List<Integer> times;

    private State state;
   // private int lastPacketSent;

    public FT21SenderGBN() {
        super(true, "FT21SenderGBN");
    }

    public int initialise(int now, int node_id, Node nodeObj, String[] args) {
        super.initialise(now, node_id, nodeObj, args);

        raf = null;
        file = new File(args[0]);
        BlockSize = Integer.parseInt(args[1]);
        windowsize = Integer.parseInt(args[2]);
        times = new LinkedList<Integer>();
        repeatedACK = false;
        negativeACK = false;
        lastACKRecieved = -1;

        state = State.BEGINNING;
        lastPacketSeqN = (int) Math.ceil(file.length() / (double) BlockSize);

        //lastPacketSent = -1;
        return 1;
    }

    public void on_clock_tick(int now) {
        boolean canSend = times.size()<=windowsize && state != State.FINISHED && nextPacketSeqN<=lastPacketSeqN;

        //repeatedACK();

        receivedNegativeACK();

        if(canSend && timer(now)){
            changeState();
            sendNextPacket(now);
            nextPacketSeqN++;
            repeatedACK = false;
        }else if(canSend &&  !repeatedACK){
            changeState();
            sendNextPacket(now);
            nextPacketSeqN++;
        }


        /*
        if (state != State.FINISHED && canSend && times.size()<=windowsize) {
            if (state == State.FINISHING && canSend) {
                sendNextPacket(now);
            } else {
                if (lastACKRecieved < 0) {
                    nextPacketSeqN = 0;
                    state = State.BEGINNING;
                }
                sendNextPacket(now);
                nextPacketSeqN++;
            }
            times.add(now);
            lastPacketSent= times.get(0);
        }

        if (lastACKRecieved < 0)
            state = State.BEGINNING;

        if(nextPacketSeqN>0)
            state = State.UPLOADING;

        if (nextPacketSeqN > lastPacketSeqN)
            state = State.FINISHING;

        */
    }

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

   /* private void repeatedACK(){
        if(repeatedACK){
            nextPacketSeqN = lastACKRecieved + 1;
            times.clear();
            repeatedACK = false;
        }
    }*/

    private void receivedNegativeACK(){
        if(negativeACK){
            nextPacketSeqN = lastACKRecieved + 1;
            times.clear();
            negativeACK = false;
        }
    }

    private boolean timer(int now){
        boolean timeout=false;
        if(!times.isEmpty()) {
            if ((now - times.get(0)) > TIMEOUT) {
                nextPacketSeqN = lastACKRecieved + 1;
                times.clear();
                timeout=true;
            }
        }
        return timeout;
    }

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
        times.add(now);
    }

    @Override
    public void on_receive_ack(int now, int client, FT21_AckPacket ack) {
        if(lastACKRecieved == ack.cSeqN){
            repeatedACK = true;
        } else {
            if(ack.cSeqN<0){
                negativeACK = true;
            }else {
                lastACKRecieved = ack.cSeqN;
                times.remove(0);
            }
        }

        if(ack.cSeqN == lastPacketSeqN + 1){
            if(state == State.FINISHING){
                super.log(now, "All Done. Transfer complete...");
                super.printReport(now);
                state = State.FINISHED;
                //return;
            }
        }


       // times.remove(0);
        //lastPacketSent = times.get(0);

    }

   /* private void checkAKC(FT21_AckPacket ack){
        if(ack.cSeqN+ windowsize < nextPacketSeqN){
            nextPacketSeqN = ack.cSeqN + 1;
        }
    }*/

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
