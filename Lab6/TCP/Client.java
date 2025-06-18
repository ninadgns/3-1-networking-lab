import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

public class Client {
    // Core components
    private final ConnectionManager connectionManager = new ConnectionManager();
    private final RTTManager rttManager = new RTTManager();
    private final PacketLossSimulator lossSimulator = new PacketLossSimulator();
    private final FastRetransmitHandler fastRetransmitHandler = new FastRetransmitHandler();

    // State management
    private Map<Long, UnackedPacket> unackedPackets = new ConcurrentHashMap<>();
    private Timer retransmissionTimer = new Timer(true);
    private volatile boolean ackReceiverRunning = true;
    private DataOutputStream currentOutputStream;

    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true));
        Client client = new Client();
        client.connect();
    }

    public void connect() {
        try (Socket socket = new Socket(Constants.SERVER_HOST, Constants.SERVER_PORT);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream())) {

            this.currentOutputStream = out;

            System.out.println("Connected to server at " + Constants.SERVER_HOST + ":" + Constants.SERVER_PORT);

            connectionManager.performHandshake(in, out);

            Thread ackReceiver = new Thread(() -> handleAcks(in));
            ackReceiver.setDaemon(true);
            ackReceiver.start();

            sendFileWithSlidingWindow(out);

            waitForAllAcks();

            ackReceiverRunning = false;

            connectionManager.closeConnection(in, out);

        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            retransmissionTimer.cancel();
        }
    }

    private void sendFileWithSlidingWindow(DataOutputStream out) throws IOException {
        byte[] fileData;
        try {
            fileData = Files.readAllBytes(Paths.get(Constants.FILE_PATH));
            System.out.println("File size: " + fileData.length + " bytes");
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            return;
        }

        int totalChunks = (int) Math.ceil((double) fileData.length / Constants.MAX_SEGMENT_SIZE);
        int bytesSent = 0;
        int chunkNumber = 0;

        System.out.println("Starting file transfer with sliding window protocol...");
        System.out.println("Effective window size: "
                + Math.min(connectionManager.getServerWindowSize(), Constants.CLIENT_WINDOW_SIZE) + " bytes");

        while (bytesSent < fileData.length) {
            int effectiveWindowSize = Math.min(connectionManager.getServerWindowSize(), Constants.CLIENT_WINDOW_SIZE);
            long windowBase = connectionManager.getBaseSequenceNumber();
            long windowTop = windowBase + effectiveWindowSize;
            boolean sentPacket = false;

            while (getBytesInFlight() + Constants.MAX_SEGMENT_SIZE <= effectiveWindowSize &&
                    bytesSent < fileData.length) {

                int remainingBytes = fileData.length - bytesSent;
                int chunkSize = Math.min(Constants.MAX_SEGMENT_SIZE, remainingBytes);

                byte[] chunk = new byte[chunkSize];
                System.arraycopy(fileData, bytesSent, chunk, 0, chunkSize);

                Packet dataPacket = createDataPacket(chunk);
                sendPacketReliably(dataPacket, out);

                chunkNumber++;
                bytesSent += chunkSize;
                connectionManager.incrementSequenceNumber(chunkSize);
                sentPacket = true;

                System.out.println("Sent chunk " + chunkNumber + "/" + totalChunks +
                        " (seq: " + dataPacket.getSequenceNumber() + ", " + chunkSize + " bytes) - " +
                        "Bytes in flight: " + getBytesInFlight() + "/" + effectiveWindowSize +
                        " - Total sent: " + bytesSent + "/" + fileData.length +
                        " - Base: " + connectionManager.getBaseSequenceNumber());
                System.out.flush();
            }

            if (sentPacket) {
                System.out.println("Window filled. Waiting for ACKs to slide window...");
            }

            if (connectionManager.getSequenceNumber() >= windowTop) {
                waitForWindowToSlide(windowBase, bytesSent, fileData.length);
            }

            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("All data sent using sliding window protocol!");
        System.out.println("Total chunks sent: " + chunkNumber);
        System.out.println("Waiting for final acknowledgments...");
    }

    private Packet createDataPacket(byte[] chunk) {
        Packet dataPacket = new Packet();
        dataPacket.setSourcePort(Constants.CLIENT_PORT);
        dataPacket.setDestinationPort(Constants.SERVER_PORT);
        dataPacket.setSequenceNumber(connectionManager.getSequenceNumber());
        dataPacket.setAckNumber(connectionManager.getAckNumber());
        dataPacket.setAckFlag(true);
        dataPacket.setPshFlag(true);
        dataPacket.setWindowSize(Constants.CLIENT_WINDOW_SIZE);
        dataPacket.setPayload(chunk);
        return dataPacket;
    }

    private void waitForWindowToSlide(long windowBase, int bytesSent, int totalFileLength) {
        long waitStart = System.currentTimeMillis();
        long maxWait = 2000;

        while (connectionManager.getBaseSequenceNumber() == windowBase &&
                (System.currentTimeMillis() - waitStart) < maxWait &&
                bytesSent < totalFileLength) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (connectionManager.getBaseSequenceNumber() > windowBase) {
            System.out.println(
                    "Window slid from [" + windowBase + "] to [" + connectionManager.getBaseSequenceNumber() + "]");
        } else if (bytesSent < totalFileLength) {
            System.out.println("Timeout waiting for ACKs, continuing...");
        }
    }

    private void sendPacketReliably(Packet packet, DataOutputStream out) throws IOException {
        long seqNum = packet.getSequenceNumber();
        lossSimulator.incrementTotalPackets();

        if (lossSimulator.shouldDropPacket()) {
            lossSimulator.incrementDroppedPackets();
            System.out.println("*** SIMULATED PACKET LOSS *** Dropping packet with seq: " + seqNum +
                    " (Loss rate: " + lossSimulator.getPacketsDropped() + "/" + lossSimulator.getTotalPacketsSent()
                    + ")");

            UnackedPacket unackedPacket = new UnackedPacket(packet);
            unackedPacket.sendTime = System.currentTimeMillis();
            unackedPackets.put(seqNum, unackedPacket);
            scheduleRetransmission(seqNum, out);
            return;
        }

        packet.sendPacket(out);
        UnackedPacket unackedPacket = new UnackedPacket(packet);
        unackedPacket.sendTime = System.currentTimeMillis();
        unackedPackets.put(seqNum, unackedPacket);
        scheduleRetransmission(seqNum, out);
    }

    private void handleAcks(DataInputStream in) {
        try {
            while (ackReceiverRunning && !Thread.currentThread().isInterrupted()) {
                Packet ackPacket = Packet.receivePacket(in);

                if (ackPacket.getAckFlag()) {
                    long ackNum = ackPacket.getAckNumber();

                    // Check for fast retransmit
                    if (fastRetransmitHandler.checkForFastRetransmit(ackNum)) {
                        fastRetransmitHandler.triggerFastRetransmit(ackNum, unackedPackets, lossSimulator,
                                currentOutputStream);
                    } else {
                        // Process normal ACK
                        if (ackNum > fastRetransmitHandler.getLastAckReceived()) {
                            processAck(ackNum);
                        }
                    }
                    connectionManager.setServerWindowSize(ackPacket.getWindowSize());
                }
            }
        } catch (IOException e) {
            if (ackReceiverRunning) {
                System.out.println("ACK handler thread terminated: " + e.getMessage());
            }
        }
    }

    private void processAck(long ackNum) {
        Iterator<Map.Entry<Long, UnackedPacket>> iterator = unackedPackets.entrySet().iterator();
        int ackedPackets = 0;
        long oldBase = connectionManager.getBaseSequenceNumber();

        while (iterator.hasNext()) {
            Map.Entry<Long, UnackedPacket> entry = iterator.next();
            long seqNum = entry.getKey();
            UnackedPacket packet = entry.getValue();

            long endSeqNum = seqNum + packet.packet.getPayload().length;

            if (endSeqNum <= ackNum) {
                // Calculate RTT for this packet if it's not a retransmission
                if (packet.retryCount == 0) {
                    long currentTime = System.currentTimeMillis();
                    double sampleRTT = currentTime - packet.sendTime;
                    rttManager.updateRTTEstimates(sampleRTT);
                }

                iterator.remove();
                ackedPackets++;

                if (endSeqNum > connectionManager.getBaseSequenceNumber()) {
                    connectionManager.setBaseSequenceNumber(endSeqNum);
                }
            }
        }

        if (ackedPackets > 0) {
            System.out.println("ACK received for " + ackedPackets + " packet(s), ACK num: " + ackNum +
                    " - Window slid from " + oldBase + " to " + connectionManager.getBaseSequenceNumber() +
                    " - EstRTT: " + String.format("%.2f", rttManager.getEstimatedRTT()) + "ms");
        }
    }

    private void scheduleRetransmission(long seqNum, DataOutputStream out) {
        long timeout = rttManager.calculateTimeoutInterval();
        retransmissionTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                retransmitPacket(seqNum, out);
            }
        }, timeout);
    }

    private void retransmitPacket(long seqNum, DataOutputStream out) {
        UnackedPacket unackedPacket = unackedPackets.get(seqNum);
        if (unackedPacket == null) {
            return;
        }

        if (unackedPacket.retryCount >= Constants.MAX_RETRIES) {
            System.err.println("Max retries exceeded for packet with seq: " + seqNum);
            unackedPackets.remove(seqNum);
            return;
        }

        try {
            unackedPacket.retryCount++;
            unackedPacket.timestamp = System.currentTimeMillis();

            if (lossSimulator.shouldDropPacket()) {
                lossSimulator.incrementDroppedPackets();
                System.out.println("*** RETRANSMISSION DROPPED *** Dropping retransmitted packet with seq: " + seqNum +
                        " (retry: " + unackedPacket.retryCount + ")");
                scheduleRetransmission(seqNum, out);
                return;
            }

            unackedPacket.packet.sendPacket(out);
            System.out.println("Retransmitting packet (seq: " + seqNum +
                    ", retry: " + unackedPacket.retryCount + ", timeout: " + rttManager.calculateTimeoutInterval()
                    + "ms)");

            scheduleRetransmission(seqNum, out);

        } catch (IOException e) {
            System.err.println("Error retransmitting packet: " + e.getMessage());
        }
    }

    private void waitForAllAcks() {
        System.out.println("Waiting for all acknowledgments...");
        long startTime = System.currentTimeMillis();
        long maxWaitTime = 10000; // 10 seconds

        while (!unackedPackets.isEmpty() &&
                (System.currentTimeMillis() - startTime) < maxWaitTime) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (unackedPackets.isEmpty()) {
            System.out.println("All packets acknowledged!");
        } else {
            System.out.println("Timeout waiting for acknowledgments. Remaining unacked packets: " +
                    unackedPackets.size());
        }
    }

    private long getBytesInFlight() {
        long bytesInFlight = 0;
        for (UnackedPacket packet : unackedPackets.values()) {
            bytesInFlight += packet.packet.getPayload().length;
        }
        return bytesInFlight;
    }
}