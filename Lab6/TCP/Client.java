import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

public class Client {

    private ConnectionManager connectionManager = new ConnectionManager();
    private Random random = new Random();
    private long sequenceNumber;
    private long ackNumber;
    private int serverWindowSize;

    private Map<Long, UnackedPacket> unackedPackets = new ConcurrentHashMap<>();
    private Timer retransmissionTimer = new Timer(true);
    private long baseSequenceNumber;
    private volatile boolean ackReceiverRunning = true;

    private static final double PACKET_LOSS_RATE = 0.5;
    private int totalPacketsSent = 0;
    private int packetsDropped = 0;

    private long lastAckReceived = -1;
    private int duplicateAckCount = 0;
    private static final int FAST_RETRANSMIT_THRESHOLD = 3;

    private double estimatedRTT = 1000.0;
    private double devRTT = 0.0;
    private static final double ALPHA = 0.125;
    private static final double BETA = 0.25;

    // Congestion Control Variables
    private int congestionWindow = Constants.MAX_SEGMENT_SIZE; // Start with 1 MSS
    private int slowStartThreshold = 65535; // Initial high value

    private enum CongestionState {
        SLOW_START, CONGESTION_AVOIDANCE, FAST_RECOVERY
    }

    private CongestionState congestionState = CongestionState.SLOW_START;
    // private int fastRecoveryInflight = 0;
    private long fastRecoverySequence = -1;
    private int packetsSinceLastIncrease = 0;

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

            currentOutputStream = out;
            performHandshake(in, out);

            // Start ACK receiver thread BEFORE sending any data
            Thread ackThread = new Thread(() -> handleAcks(in));
            ackThread.setDaemon(true);
            ackThread.start();

            // Give ACK thread time to start
            Thread.sleep(200);

            // Verify thread is running
            if (!ackThread.isAlive()) {
                throw new RuntimeException("ACK receiver thread failed to start");
            }

            sendFileWithSlidingWindow(out);
            waitForAllAcks();
            closeConnection(in, out);

        } catch (IOException e) {
            System.err.println("[ERROR] Connection error: " + e.getMessage());
            e.printStackTrace();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[ERROR] Thread interrupted: " + e.getMessage());
        } finally {
            ackReceiverRunning = false;
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

        System.out.println("[HANDSHAKE] Sending SYN packet with seq: " + sequenceNumber);
        synPacket.sendPacket(out);
        System.out.println("[HANDSHAKE] SYN packet sent successfully");
        synPacket.printPacketInfo();

        System.out.println("[HANDSHAKE] Waiting for SYN-ACK packet...");
        Packet synAckPacket = Packet.receivePacket(in);

        System.out.println("[HANDSHAKE] Received packet - SYN: " + synAckPacket.getSynFlag() +
                ", ACK: " + synAckPacket.getAckFlag());
        System.out.println("[HANDSHAKE] Expected ACK: " + (sequenceNumber + 1) +
                ", Received ACK: " + synAckPacket.getAckNumber());

        if (!synAckPacket.getSynFlag() || !synAckPacket.getAckFlag()) {
            System.err.println("[ERROR] Expected SYN-ACK packet but didn't receive one");
            return;
        }

        if (synAckPacket.getAckNumber() != sequenceNumber + 1) {
            System.err.println("[ERROR] Received incorrect ACK number in SYN-ACK");
            return;
        }

        System.out.println("[HANDSHAKE] SYN-ACK packet received:");
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
        System.out.println("[HANDSHAKE] ACK packet sent:");
        ackPacket.printPacketInfo();

        System.out.println("[HANDSHAKE] Connection established successfully!");
        System.out.println("[HANDSHAKE] Server window size: " + serverWindowSize + " bytes");
        System.out.println("[HANDSHAKE] Client window size: " + Constants.CLIENT_WINDOW_SIZE + " bytes");
    }

    private void sendFileWithSlidingWindow(DataOutputStream out) throws IOException {
        byte[] fileData;
        try {
            fileData = Files.readAllBytes(Paths.get(Constants.FILE_PATH));
            System.out.println("[FILE] File size: " + fileData.length + " bytes");
        } catch (IOException e) {
            System.err.println("[ERROR] Error reading file: " + e.getMessage());
            return;
        }

        int totalChunks = (int) Math.ceil((double) fileData.length / Constants.MAX_SEGMENT_SIZE);
        int bytesSent = 0;
        int chunkNumber = 0;

        System.out.println("[TRANSFER] Starting file transfer with congestion control...");

        // Make sure ACK receiver thread is fully started and ready
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        System.out.println("[TRANSFER] ACK receiver thread status: " + ackReceiverRunning);

        while (bytesSent < fileData.length) {
            // Calculate effective window size using congestion control
            int effectiveWindowSize = Math.min(Math.min(serverWindowSize, Constants.CLIENT_WINDOW_SIZE),
                    congestionWindow);

            boolean sentPacket = false;
            boolean windowFull = false;

            // Send packets within the congestion window
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

                System.out.println("[TRANSFER] Chunk " + chunkNumber + "/" + totalChunks +
                        " sent (seq: " + dataPacket.getSequenceNumber() +
                        ", size: " + chunkSize + " bytes)" +
                        " | CWND: " + congestionWindow + " bytes (" +
                        (congestionWindow / Constants.MAX_SEGMENT_SIZE) + " MSS)" +
                        " | State: " + congestionState +
                        " | In-flight: " + getBytesInFlight() + "/" + effectiveWindowSize);
            }

            // Check why we stopped sending packets
            if (sentPacket) {
                if (bytesSent >= fileData.length) {
                    System.out.println("[TRANSFER] All packets sent. Waiting for ACKs...");
                } else if (getBytesInFlight() + Constants.MAX_SEGMENT_SIZE > effectiveWindowSize) {
                    System.out.println("[TRANSFER] Congestion window filled. Waiting for ACKs...");
                    windowFull = true;
                }
            }

            // Wait for ACKs to open the window only if window is actually full
            if (windowFull && getBytesInFlight() >= effectiveWindowSize) {
                long waitStart = System.currentTimeMillis();
                long maxWait = 8000;
                long initialBytesInFlight = getBytesInFlight();

                while (getBytesInFlight() >= effectiveWindowSize &&
                        (System.currentTimeMillis() - waitStart) < maxWait &&
                        bytesSent < fileData.length) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }

                    // Check if we received any ACKs during waiting
                    if (getBytesInFlight() < initialBytesInFlight) {
                        System.out.println("[TRANSFER] ACK received during wait, window opening");
                        break;
                    }
                }

                if (getBytesInFlight() < initialBytesInFlight) {
                    System.out.println("[TRANSFER] ACKs received, congestion window opened");
                } else if (bytesSent < fileData.length) {
                    System.out.println("[TRANSFER] Wait timeout, continuing to avoid deadlock...");
                }
            }

            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("[TRANSFER] File transfer completed successfully!");
        System.out.println("[TRANSFER] Final congestion window: " + congestionWindow + " bytes");
        System.out.println("[TRANSFER] Final state: " + congestionState);
        System.out.println("[TRANSFER] Total chunks sent: " + chunkNumber);
    }

    private void handleAcks(DataInputStream in) {
        try {
            System.out.println("[ACK-RECEIVER] Thread started successfully");

            while (ackReceiverRunning) {
                try {
                    Packet ackPacket = Packet.receivePacket(in);
                    long receivedTime = System.currentTimeMillis();

                    System.out.println("[ACK-RECEIVER] Received ACK for seq: " + ackPacket.getAckNumber() +
                            " | Window: " + ackPacket.getWindowSize() +
                            " | Time: "
                            + new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date(receivedTime)));

                    if (ackPacket.getAckFlag()) {
                        long ackNum = ackPacket.getAckNumber();

                        // Check for duplicate ACKs
                        if (ackNum == lastAckReceived) {
                            duplicateAckCount++;
                            System.out.println("[ACK-RECEIVER] Duplicate ACK #" + duplicateAckCount +
                                    " for seq: " + ackNum +
                                    " | Total duplicates: " + duplicateAckCount);

                            if (duplicateAckCount >= FAST_RETRANSMIT_THRESHOLD) {
                                System.out.println(
                                        "[ACK-RECEIVER] Triple duplicate ACK detected - triggering fast retransmit" +
                                                " | ACK seq: " + ackNum +
                                                " | Duplicate count: " + duplicateAckCount);
                                handleFastRetransmit(ackNum);
                                duplicateAckCount = 0;
                            }
                        } else {
                            // New ACK received
                            System.out.println("[ACK-RECEIVER] New ACK received" +
                                    " | Previous: " + lastAckReceived +
                                    " | Current: " + ackNum +
                                    " | Resetting duplicate count from: " + duplicateAckCount);

                            duplicateAckCount = 0;
                            lastAckReceived = ackNum;

                            int ackedBytes = processAck(ackNum);
                            if (ackedBytes > 0) {
                                updateCongestionControl(ackedBytes, false);
                                System.out.println("[ACK-RECEIVER] Successfully processed ACK" +
                                        " | Bytes acked: " + ackedBytes +
                                        " | New CWND: " + congestionWindow + " bytes" +
                                        " | In-flight: " + getBytesInFlight() + " bytes" +
                                        " | State: " + congestionState);
                            } else {
                                System.out.println("[ACK-RECEIVER] No new bytes acknowledged" +
                                        " | ACK num: " + ackNum +
                                        " | Current base: " + baseSequenceNumber);
                            }
                        }
                    } else {
                        System.out.println("[ACK-RECEIVER] Received packet without ACK flag set" +
                                " | Seq: " + ackPacket.getSequenceNumber() +
                                " | ACK: " + ackPacket.getAckNumber());
                    }
                } catch (IOException e) {
                    if (ackReceiverRunning) {
                        System.err.println("[ACK-RECEIVER] Error receiving ACK packet: " + e.getMessage());
                        e.printStackTrace();
                    } else {
                        System.out.println("[ACK-RECEIVER] Thread stopping - connection closed");
                    }
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("[ACK-RECEIVER] Unexpected error in ACK receiver thread: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("[ACK-RECEIVER] Thread terminated" +
                " | Final state - Running: " + ackReceiverRunning +
                " | Unacked packets: " + unackedPackets.size());
    }

    private void updateCongestionControl(int ackedBytes, boolean isTimeout) {
        if (isTimeout) {
            // Timeout occurred - reset to slow start
            slowStartThreshold = Math.max(congestionWindow / 2, Constants.MAX_SEGMENT_SIZE);
            congestionWindow = Constants.MAX_SEGMENT_SIZE;
            congestionState = CongestionState.SLOW_START;
            packetsSinceLastIncrease = 0;

            System.out.println("[CONGESTION] Timeout - Resetting to Slow Start");
            System.out.println("[CONGESTION] New ssthresh: " + slowStartThreshold + " bytes");
            System.out.println("[CONGESTION] New CWND: " + Constants.MAX_SEGMENT_SIZE + " bytes");
            System.out.println("[CONGESTION] State: SLOW_START");
            return;
        }

        switch (congestionState) {
            case SLOW_START:
                // Exponential growth: increase CWND by MSS for each ACK
                congestionWindow += Constants.MAX_SEGMENT_SIZE;

                System.out.println("[CONGESTION] Slow Start - CWND increased to " + congestionWindow +
                        " bytes (" + (congestionWindow / Constants.MAX_SEGMENT_SIZE) + " MSS)");

                // Check if we should switch to congestion avoidance
                if (congestionWindow >= slowStartThreshold) {
                    congestionState = CongestionState.CONGESTION_AVOIDANCE;
                    packetsSinceLastIncrease = 0;
                    System.out.println("[CONGESTION] Switching to Congestion Avoidance (CWND >= ssthresh)");
                }
                break;

            case CONGESTION_AVOIDANCE:
                // Linear growth: increase CWND by MSS/CWND for each ACK
                packetsSinceLastIncrease += ackedBytes;

                if (packetsSinceLastIncrease >= congestionWindow) {
                    congestionWindow += Constants.MAX_SEGMENT_SIZE;
                    packetsSinceLastIncrease = 0;

                    System.out.println("[CONGESTION] Congestion Avoidance - CWND increased to " +
                            congestionWindow + " bytes (" +
                            (congestionWindow / Constants.MAX_SEGMENT_SIZE) + " MSS)");
                }
                break;

            case FAST_RECOVERY:
                // Check if we can exit fast recovery
                if (lastAckReceived > fastRecoverySequence) {
                    // New ACK received, exit fast recovery
                    congestionWindow = slowStartThreshold;
                    congestionState = CongestionState.CONGESTION_AVOIDANCE;
                    packetsSinceLastIncrease = 0;

                    System.out.println("[CONGESTION] Exiting Fast Recovery");
                    System.out.println("[CONGESTION] CWND set to ssthresh: " + congestionWindow + " bytes");
                    System.out.println("[CONGESTION] State: CONGESTION_AVOIDANCE");
                }
                break;
        }

        // Ensure minimum window size
        if (congestionWindow < Constants.MAX_SEGMENT_SIZE) {
            congestionWindow = Constants.MAX_SEGMENT_SIZE;
        }
    }

    private void handleFastRetransmit(long ackNum) {
        // Enter fast recovery
        if (congestionState != CongestionState.FAST_RECOVERY) {
            slowStartThreshold = Math.max(congestionWindow / 2, Constants.MAX_SEGMENT_SIZE);
            congestionWindow = slowStartThreshold + 3 * Constants.MAX_SEGMENT_SIZE;
            congestionState = CongestionState.FAST_RECOVERY;
            fastRecoverySequence = ackNum;

            System.out.println("[FAST-RETRANSMIT] Entering Fast Recovery");
            System.out.println("[FAST-RETRANSMIT] New ssthresh: " + slowStartThreshold + " bytes");
            System.out.println("[FAST-RETRANSMIT] New CWND: " + congestionWindow + " bytes");
        }

        // Trigger the actual retransmission
        triggerFastRetransmit(ackNum);
    }

    private boolean shouldDropPacket() {
        return random.nextDouble() < PACKET_LOSS_RATE;
    }

    private void sendPacketReliably(Packet packet, DataOutputStream out) throws IOException {
        long seqNum = packet.getSequenceNumber();
        totalPacketsSent++;

        if (shouldDropPacket()) {
            packetsDropped++;
            System.out.println("[LOSS] Simulated packet loss - seq: " + seqNum +
                    " | Loss rate: " + packetsDropped + "/" + totalPacketsSent +
                    " (" + String.format("%.1f", (packetsDropped * 100.0 / totalPacketsSent)) + "%)");

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

    private void triggerFastRetransmit(long ackNum) {
        // Find the packet that the receiver is expecting (the missing packet)
        Long seqToRetransmit = null;

        // Look for the earliest unacknowledged packet
        for (Map.Entry<Long, UnackedPacket> entry : unackedPackets.entrySet()) {
            long seqNum = entry.getKey();
            if (seqToRetransmit == null || seqNum < seqToRetransmit) {
                seqToRetransmit = seqNum;
            }
        }

        if (seqToRetransmit != null) {
            System.out.println("[FAST-RETRANSMIT] Retransmitting seq: " + seqToRetransmit +
                    " (ACK expecting: " + ackNum + ")");
            retransmitPacketImmediately(seqToRetransmit);
        } else {
            System.out.println("[FAST-RETRANSMIT] No packet found for retransmission (ACK: " + ackNum + ")");
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
                System.out.println("[LOSS] Fast retransmission dropped - seq: " + seqNum);
                return;
            }

            unackedPacket.packet.sendPacket(getCurrentOutputStream());
            System.out.println("[FAST-RETRANSMIT] Packet retransmitted - seq: " + seqNum);

        } catch (IOException e) {
            System.err.println("[ERROR] Fast retransmit failed: " + e.getMessage());
        }
    }

    private DataOutputStream getCurrentOutputStream() {
        return currentOutputStream;
    }

    private int processAck(long ackNum) {
        System.out.println("[ACK-PROCESSOR] Starting ACK processing" +
                " | ACK num: " + ackNum +
                " | Current base: " + baseSequenceNumber +
                " | Unacked packets: " + unackedPackets.size());

        Iterator<Map.Entry<Long, UnackedPacket>> iterator = unackedPackets.entrySet().iterator();
        int ackedPackets = 0;
        int ackedBytes = 0;
        long oldBase = baseSequenceNumber;
        long newBase = baseSequenceNumber;

        while (iterator.hasNext()) {
            Map.Entry<Long, UnackedPacket> entry = iterator.next();
            long seqNum = entry.getKey();
            UnackedPacket packet = entry.getValue();

            long endSeqNum = seqNum + packet.packet.getPayload().length;

            if (endSeqNum <= ackNum) {
                // This packet is acknowledged
                System.out.println("[ACK-PROCESSOR] Acknowledging packet" +
                        " | Seq: " + seqNum +
                        " | End seq: " + endSeqNum +
                        " | Size: " + packet.packet.getPayload().length + " bytes" +
                        " | Retry count: " + packet.retryCount);

                if (packet.retryCount == 0) {
                    long currentTime = System.currentTimeMillis();
                    double sampleRTT = currentTime - packet.sendTime;
                    updateRTTEstimates(sampleRTT);
                } else {
                    System.out.println("[ACK-PROCESSOR] Skipping RTT calculation for retransmitted packet" +
                            " | Seq: " + seqNum +
                            " | Retry count: " + packet.retryCount);
                }

                ackedBytes += packet.packet.getPayload().length;
                iterator.remove();
                ackedPackets++;

                if (endSeqNum > newBase) {
                    newBase = endSeqNum;
                }
            } else {
                System.out.println("[ACK-PROCESSOR] Packet still unacknowledged" +
                        " | Seq: " + seqNum +
                        " | End seq: " + endSeqNum +
                        " | ACK num: " + ackNum +
                        " | Size: " + packet.packet.getPayload().length + " bytes");
            }
        }

        // Update base sequence number
        if (newBase > oldBase) {
            baseSequenceNumber = newBase;
            System.out.println("[ACK-PROCESSOR] Sliding window advanced" +
                    " | Old base: " + oldBase +
                    " | New base: " + newBase +
                    " | Window moved by: " + (newBase - oldBase) + " bytes");
        }

        if (ackedPackets > 0) {
            System.out.println("[ACK-PROCESSOR] ACK processing completed successfully" +
                    " | Packets acked: " + ackedPackets +
                    " | Bytes acked: " + ackedBytes +
                    " | ACK num: " + ackNum +
                    " | Window: " + oldBase + " -> " + baseSequenceNumber +
                    " | Remaining unacked: " + unackedPackets.size() + " packets" +
                    " | EstRTT: " + String.format("%.2f", estimatedRTT) + "ms" +
                    " | Current CWND: " + congestionWindow + " bytes");
        } else {
            System.out.println("[ACK-PROCESSOR] No packets acknowledged" +
                    " | ACK num: " + ackNum +
                    " | Current base: " + baseSequenceNumber +
                    " | Unacked packets: " + unackedPackets.size() +
                    " | Possible duplicate or out-of-order ACK");
        }

        return ackedBytes;
    }

    private void updateRTTEstimates(double sampleRTT) {
        if (estimatedRTT == 1000.0) {
            estimatedRTT = sampleRTT;
            devRTT = sampleRTT / 2.0;
        } else {
            devRTT = (1 - BETA) * devRTT + BETA * Math.abs(sampleRTT - estimatedRTT);
            estimatedRTT = (1 - ALPHA) * estimatedRTT + ALPHA * sampleRTT;
        }

        System.out.println("[RTT] Sample: " + String.format("%.2f", sampleRTT) + "ms" +
                " | Estimated: " + String.format("%.2f", estimatedRTT) + "ms" +
                " | Deviation: " + String.format("%.2f", devRTT) + "ms");
    }

    private long calculateTimeoutInterval() {
        double timeoutInterval = estimatedRTT + 4 * devRTT;
        // More conservative timeout for initial packets and small windows
        if (totalPacketsSent <= 5 || congestionWindow <= 2 * Constants.MAX_SEGMENT_SIZE) {
            timeoutInterval = Math.max(5000, timeoutInterval); // At least 5 seconds for first few packets or small
                                                               // windows
        } else {
            timeoutInterval = Math.max(1000, timeoutInterval); // At least 1 second for subsequent packets
        }
        return Math.min(15000, (long) timeoutInterval); // Cap at 15 seconds
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
            System.err.println("[ERROR] Max retries exceeded for seq: " + seqNum);
            unackedPackets.remove(seqNum);
            return;
        }

        try {
            unackedPacket.retryCount++;
            unackedPacket.timestamp = System.currentTimeMillis();

            // Only update congestion control on first timeout of a packet
            if (unackedPacket.retryCount == 1) {
                updateCongestionControl(0, true);
            }

            if (shouldDropPacket()) {
                packetsDropped++;
                System.out.println("[LOSS] Retransmission dropped - seq: " + seqNum +
                        " | Retry: " + unackedPacket.retryCount);
                scheduleRetransmission(seqNum, out);
                return;
            }

            unackedPacket.packet.sendPacket(out);
            System.out.println("[RETRANSMIT] Packet retransmitted - seq: " + seqNum +
                    " | Retry: " + unackedPacket.retryCount +
                    " | Timeout: " + calculateTimeoutInterval() + "ms");

            scheduleRetransmission(seqNum, out);

        } catch (IOException e) {
            System.err.println("[ERROR] Retransmission failed: " + e.getMessage());
        }
    }

    private void waitForAllAcks() {
        System.out.println("[TRANSFER] Waiting for all packets to be acknowledged...");
        System.out.println("[TRANSFER] Packet loss statistics - Dropped: " + packetsDropped +
                "/" + totalPacketsSent +
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
            System.out.println("[TRANSFER] All packets acknowledged successfully!");
            System.out.println("[TRANSFER] Final congestion control state:");
            System.out.println("[TRANSFER] Final CWND: " + congestionWindow + " bytes (" +
                    (congestionWindow / Constants.MAX_SEGMENT_SIZE) + " MSS)");
            System.out.println("[TRANSFER] Final ssthresh: " + slowStartThreshold + " bytes");
            System.out.println("[TRANSFER] Final state: " + congestionState);
            System.out.println("[TRANSFER] Total sent: " + totalPacketsSent +
                    " | Dropped: " + packetsDropped +
                    " (" + String.format("%.1f", (packetsDropped * 100.0 / totalPacketsSent)) + "%)");
        } else {
            System.err.println("[ERROR] Timeout waiting for acknowledgments. " +
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
        System.out.println("[CLOSE] FIN packet sent");

        try {
            Packet finAckPacket = Packet.receivePacket(in);
            if (finAckPacket.getFinFlag() && finAckPacket.getAckFlag()) {
                System.out.println("[CLOSE] FIN-ACK packet received");

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
                System.out.println("[CLOSE] Final ACK packet sent - Connection closed successfully");

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (IOException e) {
            System.out.println("[CLOSE] Connection closed by server");
        }
    }

    private long getBytesInFlight() {
        return sequenceNumber - baseSequenceNumber;
    }

    private void debugPacketTiming(long seqNum, String action) {
        long currentTime = System.currentTimeMillis();
        System.out.println("[DEBUG] " + action + " - seq: " + seqNum +
                " | time: " + currentTime +
                " | in-flight: " + getBytesInFlight() +
                " | cwnd: " + congestionWindow);
    }
}