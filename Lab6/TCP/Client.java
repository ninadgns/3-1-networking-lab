import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Client {
    private Random random = new Random();
    private long sequenceNumber;
    private long ackNumber;
    private int serverWindowSize;

    private Map<Long, UnackedPacket> unackedPackets = new ConcurrentHashMap<>();
    private Timer retransmissionTimer = new Timer(true);
    private long baseSequenceNumber;
    private volatile boolean ackReceiverRunning = true;

    private static final double PACKET_LOSS_RATE = 0.15;
    private int totalPacketsSent = 0;
    private int packetsDropped = 0;

    private long lastAckReceived = -1;
    private int duplicateAckCount = 0;
    private static final int FAST_RETRANSMIT_THRESHOLD = 3;

    private double estimatedRTT = 1000.0;
    private double devRTT = 0.0;
    private static final double ALPHA = 0.125;
    private static final double BETA = 0.25;

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

            performHandshake(in, out);

            Thread ackReceiver = new Thread(() -> handleAcks(in));
            ackReceiver.setDaemon(true);
            ackReceiver.start();

            sendFileWithSlidingWindow(out);

            waitForAllAcks();

            ackReceiverRunning = false;

            closeConnection(in, out);

        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            retransmissionTimer.cancel();
        }
    }

    private void performHandshake(DataInputStream in, DataOutputStream out) throws IOException {
        sequenceNumber = random.nextInt(1000000);
        Packet synPacket = new Packet();
        synPacket.setSourcePort(Constants.CLIENT_PORT);
        synPacket.setDestinationPort(Constants.SERVER_PORT);
        synPacket.setSequenceNumber(sequenceNumber);
        synPacket.setAckNumber(0);
        synPacket.setSynFlag(true);
        synPacket.setWindowSize(Constants.CLIENT_WINDOW_SIZE);

        System.out.println("Sending SYN packet with seq: " + sequenceNumber);
        synPacket.sendPacket(out);
        System.out.println("Sent SYN packet:");
        synPacket.printPacketInfo();

        System.out.println("Waiting for SYN-ACK packet...");
        Packet synAckPacket = Packet.receivePacket(in);

        System.out.println("Received packet with flags - SYN: " + synAckPacket.getSynFlag() +
                ", ACK: " + synAckPacket.getAckFlag());
        System.out.println("Expected ACK number: " + (sequenceNumber + 1) +
                ", Received ACK number: " + synAckPacket.getAckNumber());

        if (!synAckPacket.getSynFlag() || !synAckPacket.getAckFlag()) {
            System.err.println("Expected SYN-ACK packet but didn't receive one");
            return;
        }

        if (synAckPacket.getAckNumber() != sequenceNumber + 1) {
            System.err.println("Received incorrect ACK number in SYN-ACK");
            return;
        }

        System.out.println("Received SYN-ACK packet:");
        synAckPacket.printPacketInfo();

        sequenceNumber++;
        ackNumber = synAckPacket.getSequenceNumber() + 1;
        serverWindowSize = synAckPacket.getWindowSize();
        baseSequenceNumber = sequenceNumber;
        Packet ackPacket = new Packet();
        ackPacket.setSourcePort(Constants.CLIENT_PORT);
        ackPacket.setDestinationPort(Constants.SERVER_PORT);
        ackPacket.setSequenceNumber(sequenceNumber);
        ackPacket.setAckNumber(ackNumber);
        ackPacket.setAckFlag(true);
        ackPacket.setWindowSize(Constants.CLIENT_WINDOW_SIZE);

        ackPacket.sendPacket(out);
        System.out.println("Sent ACK packet:");
        ackPacket.printPacketInfo();
        System.out.println("Connection established!");
        System.out.println("Server window size: " + serverWindowSize);
        System.out.println("Client window size: " + Constants.CLIENT_WINDOW_SIZE);
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
        System.out.println(
                "Effective window size: " + Math.min(serverWindowSize, Constants.CLIENT_WINDOW_SIZE) + " bytes");

        while (bytesSent < fileData.length) {

            int effectiveWindowSize = Math.min(serverWindowSize, Constants.CLIENT_WINDOW_SIZE);

            long windowBase = baseSequenceNumber;
            long windowTop = windowBase + effectiveWindowSize;

            boolean sentPacket = false;

            while (getBytesInFlight() + Constants.MAX_SEGMENT_SIZE <= effectiveWindowSize &&
                    bytesSent < fileData.length) {

                int remainingBytes = fileData.length - bytesSent;
                int chunkSize = Math.min(Constants.MAX_SEGMENT_SIZE, remainingBytes);

                byte[] chunk = new byte[chunkSize];
                System.arraycopy(fileData, bytesSent, chunk, 0, chunkSize);

                Packet dataPacket = new Packet();
                dataPacket.setSourcePort(Constants.CLIENT_PORT);
                dataPacket.setDestinationPort(Constants.SERVER_PORT);
                dataPacket.setSequenceNumber(sequenceNumber);
                dataPacket.setAckNumber(ackNumber);
                dataPacket.setAckFlag(true);
                dataPacket.setPshFlag(true);
                dataPacket.setWindowSize(Constants.CLIENT_WINDOW_SIZE);
                dataPacket.setPayload(chunk);

                sendPacketReliably(dataPacket, out);

                chunkNumber++;
                bytesSent += chunkSize;
                sequenceNumber += chunkSize;
                sentPacket = true;

                System.out.println("Sent chunk " + chunkNumber + "/" + totalChunks +
                        " (seq: " + dataPacket.getSequenceNumber() + ", " + chunkSize + " bytes) - " +
                        "Bytes in flight: " + getBytesInFlight() + "/" + effectiveWindowSize +
                        " - Total sent: " + bytesSent + "/" + fileData.length +
                        " - Base: " + baseSequenceNumber);
                System.out.flush();
            }

            if (sentPacket) {
                System.out.println("Window filled. Waiting for ACKs to slide window...");
            }

            if (sequenceNumber >= windowTop) {
                long waitStart = System.currentTimeMillis();
                long maxWait = 2000;

                while (baseSequenceNumber == windowBase &&
                        (System.currentTimeMillis() - waitStart) < maxWait &&
                        bytesSent < fileData.length) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                if (baseSequenceNumber > windowBase) {
                    System.out.println("Window slid from [" + windowBase + "] to [" + baseSequenceNumber + "]");
                } else if (bytesSent < fileData.length) {
                    System.out.println("Timeout waiting for ACKs, continuing...");
                }
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

    private boolean shouldDropPacket() {
        return random.nextDouble() < PACKET_LOSS_RATE;
    }

    private void sendPacketReliably(Packet packet, DataOutputStream out) throws IOException {
        long seqNum = packet.getSequenceNumber();
        totalPacketsSent++;

        if (shouldDropPacket()) {
            packetsDropped++;
            System.out.println("*** SIMULATED PACKET LOSS *** Dropping packet with seq: " + seqNum +
                    " (Loss rate: " + packetsDropped + "/" + totalPacketsSent + ")");

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

                    if (ackNum == lastAckReceived) {
                        duplicateAckCount++;
                        System.out.println("Duplicate ACK received (" + duplicateAckCount + "/3) for seq: " + ackNum);

                        if (duplicateAckCount >= FAST_RETRANSMIT_THRESHOLD) {
                            System.out.println(
                                    "*** FAST RETRANSMIT *** Triple duplicate ACK detected for seq: " + ackNum);
                            triggerFastRetransmit(ackNum);
                            duplicateAckCount = 0;
                        }
                    } else {

                        if (ackNum > lastAckReceived) {
                            duplicateAckCount = 0;
                            lastAckReceived = ackNum;
                            processAck(ackNum);
                        }
                    }

                    serverWindowSize = ackPacket.getWindowSize();
                }
            }
        } catch (IOException e) {
            if (ackReceiverRunning) {
                System.out.println("ACK handler thread terminated: " + e.getMessage());
            }
        }
    }

    private void triggerFastRetransmit(long ackNum) {

        for (Map.Entry<Long, UnackedPacket> entry : unackedPackets.entrySet()) {
            long seqNum = entry.getKey();
            if (seqNum >= ackNum) {
                System.out.println("Fast retransmitting packet with seq: " + seqNum);
                retransmitPacketImmediately(seqNum);
                break;
            }
        }
    }

    private void retransmitPacketImmediately(long seqNum) {
        UnackedPacket unackedPacket = unackedPackets.get(seqNum);
        if (unackedPacket == null) {
            return;
        }

        try {
            unackedPacket.retryCount++;
            unackedPacket.timestamp = System.currentTimeMillis();

            if (shouldDropPacket()) {
                packetsDropped++;
                System.out.println("*** FAST RETRANSMISSION DROPPED *** seq: " + seqNum);
                return;
            }

            unackedPacket.packet.sendPacket(getCurrentOutputStream());
            System.out.println("Fast retransmitted packet (seq: " + seqNum + ")");

        } catch (IOException e) {
            System.err.println("Error in fast retransmit: " + e.getMessage());
        }
    }

    private DataOutputStream currentOutputStream;

    private DataOutputStream getCurrentOutputStream() {
        return currentOutputStream;
    }

    private void processAck(long ackNum) {
        Iterator<Map.Entry<Long, UnackedPacket>> iterator = unackedPackets.entrySet().iterator();
        int ackedPackets = 0;
        long oldBase = baseSequenceNumber;

        while (iterator.hasNext()) {
            Map.Entry<Long, UnackedPacket> entry = iterator.next();
            long seqNum = entry.getKey();
            UnackedPacket packet = entry.getValue();

            long endSeqNum = seqNum + packet.packet.getPayload().length;

            if (endSeqNum <= ackNum) {

                if (packet.retryCount == 0) {
                    long currentTime = System.currentTimeMillis();
                    double sampleRTT = currentTime - packet.sendTime;
                    updateRTTEstimates(sampleRTT);
                }

                iterator.remove();
                ackedPackets++;

                if (endSeqNum > baseSequenceNumber) {
                    baseSequenceNumber = endSeqNum;
                }
            }
        }

        if (ackedPackets > 0) {
            System.out.println("ACK received for " + ackedPackets + " packet(s), ACK num: " + ackNum +
                    " - Window slid from " + oldBase + " to " + baseSequenceNumber +
                    " - EstRTT: " + String.format("%.2f", estimatedRTT) + "ms");
        }
    }

    private void updateRTTEstimates(double sampleRTT) {
        if (estimatedRTT == 1000.0) {

            estimatedRTT = sampleRTT;
            devRTT = sampleRTT / 2.0;
        } else {

            devRTT = (1 - BETA) * devRTT + BETA * Math.abs(sampleRTT - estimatedRTT);
            estimatedRTT = (1 - ALPHA) * estimatedRTT + ALPHA * sampleRTT;
        }

        System.out.println("RTT Update - Sample: " + String.format("%.2f", sampleRTT) +
                "ms, Estimated: " + String.format("%.2f", estimatedRTT) +
                "ms, Dev: " + String.format("%.2f", devRTT) + "ms");
    }

    private long calculateTimeoutInterval() {
        double timeoutInterval = estimatedRTT + 4 * devRTT;

        return Math.max(100, Math.min(5000, (long) timeoutInterval));
    }

    private void scheduleRetransmission(long seqNum, DataOutputStream out) {
        long timeout = calculateTimeoutInterval();
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

            if (shouldDropPacket()) {
                packetsDropped++;
                System.out.println("*** RETRANSMISSION DROPPED *** Dropping retransmitted packet with seq: " + seqNum +
                        " (retry: " + unackedPacket.retryCount + ")");
                scheduleRetransmission(seqNum, out);
                return;
            }

            unackedPacket.packet.sendPacket(out);
            System.out.println("Retransmitting packet (seq: " + seqNum +
                    ", retry: " + unackedPacket.retryCount + ", timeout: " + calculateTimeoutInterval() + "ms)");

            scheduleRetransmission(seqNum, out);

        } catch (IOException e) {
            System.err.println("Error retransmitting packet: " + e.getMessage());
        }
    }

    private void waitForAllAcks() {
        System.out.println("Waiting for all packets to be acknowledged...");
        System.out.println("Packet loss statistics - Dropped: " + packetsDropped + "/" + totalPacketsSent +
                " (" + String.format("%.1f", (packetsDropped * 100.0 / totalPacketsSent)) + "%)");

        long waitStart = System.currentTimeMillis();
        long maxWaitTime = 15000;

        while (!unackedPackets.isEmpty() &&
                (System.currentTimeMillis() - waitStart) < maxWaitTime) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (unackedPackets.isEmpty()) {
            System.out.println("All packets acknowledged successfully despite packet loss!");
            System.out.println("Final statistics - Total sent: " + totalPacketsSent +
                    ", Dropped: " + packetsDropped +
                    " (" + String.format("%.1f", (packetsDropped * 100.0 / totalPacketsSent)) + "%)");
        } else {
            System.err.println("Timeout waiting for acknowledgments. " +
                    unackedPackets.size() + " packets still unacked.");
        }
    }

    private void closeConnection(DataInputStream in, DataOutputStream out) throws IOException {
        Packet finPacket = new Packet();
        finPacket.setSourcePort(Constants.CLIENT_PORT);
        finPacket.setDestinationPort(Constants.SERVER_PORT);
        finPacket.setSequenceNumber(sequenceNumber);
        finPacket.setAckNumber(ackNumber);
        finPacket.setFinFlag(true);
        finPacket.setAckFlag(true);
        finPacket.setWindowSize(Constants.CLIENT_WINDOW_SIZE);

        finPacket.sendPacket(out);
        System.out.println("Sent FIN packet");

        try {
            Packet finAckPacket = Packet.receivePacket(in);
            if (finAckPacket.getFinFlag() && finAckPacket.getAckFlag()) {
                System.out.println("Received FIN-ACK packet");

                sequenceNumber++;
                ackNumber = finAckPacket.getSequenceNumber() + 1;
                Packet finalAckPacket = new Packet();
                finalAckPacket.setSourcePort(Constants.CLIENT_PORT);
                finalAckPacket.setDestinationPort(Constants.SERVER_PORT);
                finalAckPacket.setSequenceNumber(sequenceNumber);
                finalAckPacket.setAckNumber(ackNumber);
                finalAckPacket.setAckFlag(true);
                finalAckPacket.setWindowSize(Constants.CLIENT_WINDOW_SIZE);

                finalAckPacket.sendPacket(out);
                System.out.println("Sent final ACK packet - Connection closed");

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (IOException e) {
            System.out.println("Connection closed by server");
        }
    }

    private long getBytesInFlight() {
        return sequenceNumber - baseSequenceNumber;
    }
}