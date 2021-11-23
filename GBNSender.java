
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Deque;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;
import java.util.ArrayDeque;
import java.util.concurrent.Semaphore;

public class GBNSender {

    private static final int ACK_SIZE = 12;
    private static final int HEADER_SIZE = 12;
    private static final int SEQNUM_MODULO = 256;
    private static final Semaphore available = new Semaphore(10);

    private DatagramSocket socket;
    private InetAddress channelAddress;
    private int port;

    private Deque<Packet> queue;
    private static final Object queueLock = new Object();

    private Timer timer;
    private int timeout;
    private static final Object timerLock = new Object();

    private volatile int base;
    private volatile int nextSeqNum;
    private String[] inputData;

    private volatile boolean sendFinished;

    GBNSender(String[] data, String hostname, int port, int timeout) throws Exception {
        this.port = port;
        this.timeout = timeout;
        base = 0;
        nextSeqNum = 0;
        channelAddress = InetAddress.getByName(hostname);
        queue = new ArrayDeque<>();
        sendFinished = false;
        inputData = data;
    }

    class TimeoutTask extends TimerTask {
        public void run() {
            // when timeout, retransmit all packets in the window
            synchronized (queueLock) {
                Iterator<Packet> itr = queue.iterator();
                while (itr.hasNext()) {
                    Util.sendData(itr.next(), channelAddress, port, socket);
                }
            }

            // schedule a new timeout task
            synchronized (timerLock) {
                timer.schedule(new TimeoutTask(), timeout);
            }
        }
    }

    private void receivePackets() {
        byte[] buffer = new byte[ACK_SIZE];
        DatagramPacket receiveDatagram = new DatagramPacket(buffer, buffer.length);
        Packet packet;

        while (!sendFinished || !queue.isEmpty()) {
            try {
                // get ack number
                socket.receive(receiveDatagram);
                packet = Packet.getPacket(receiveDatagram.getData());
                System.out.println(String.format("PKT RECV ACK %s %s", packet.getLength(), packet.getSeqNum()));
                int ackNum = packet.getSeqNum();

                // calculate the number of received packets
                int rcvNum = ackNum - base + 1;
                if (ackNum < base) {
                    rcvNum += SEQNUM_MODULO;
                }

                // remove the received packet in the window
                if (rcvNum <= 10) {
                    synchronized (queueLock) {
                        for (int i = 0; i < rcvNum; i++) {
                            queue.poll();
                            available.release();
                        }
                    }
                    base = (ackNum + 1) % SEQNUM_MODULO;
                }

                // reset timer
                if (base == nextSeqNum) {
                    timer.cancel();
                } else {
                    startTimer();
                }

            } catch (Exception e) {
                System.out.println("Exception when receiving datagram packet");
            }
        }
    }

    private void startTimer() {
        synchronized (timerLock) {
            timer.cancel();
            timer = new Timer();
            timer.schedule(new TimeoutTask(), timeout);
        }
    }

    public void start() throws Exception {

        // create a new timer
        timer = new Timer();

        // create socket to send and receive data
        socket = new DatagramSocket();

        // create new thread to receive ACK packets
        Thread receiveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                receivePackets();
            }
        });
        receiveThread.start();

        // send data
        System.out.println("Start to send data");
       for(int i = 0;i < inputData.length;i++) {
            // make packet
            String dataPart = inputData[i] + "\n";
            byte[] buffer = dataPart.getBytes();
           
            Packet packet = new Packet(0, buffer.length + HEADER_SIZE, nextSeqNum, buffer);

            // send the packet
            available.acquire();
            synchronized (queueLock) {
                queue.offer(packet);
            }
            Util.sendData(packet, channelAddress, port, socket);

            // reset timer
            if (base == nextSeqNum) {
                startTimer();
            }

            // update nextSeqNum
            nextSeqNum = (nextSeqNum + 1) % SEQNUM_MODULO;
            
            if(i == (inputData.length - 1)) {
            	sendFinished = true;
            	queue.clear();
            }
        }

        // join the receive thread
        receiveThread.join();

        // end sender session
        Util.endSenderSession(base, channelAddress, port, socket);
        System.out.println("Finish sending data");
        socket.close();
        try {
        	timer.cancel();
        } catch(Throwable t) {}
    }
}
