import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

public class Client {
    // Add this field to track the last window size before any timeout in the current sequence
    private int lastGoodCongestionWindow = -1;
    private long lastTimeoutTime = 0;
    private static final long TIMEOUT_SEQUENCE_WINDOW = 5000; // 5 seconds

    // TCP Variant Selection
    private enum TcpVariant {
        TAHOE, RENO
    }
    
    private TcpVariant tcpVariant = TcpVariant.RENO; // Default to Reno

    private static class CwndLogEntry {
        public final long timestamp;
        public final int packetNumber;
        public final int cwndValue;
        public final int ssthresh;
        public final String event;
        public final CongestionState state;
        public final double RTT;

        public CwndLogEntry(long timestamp, int packetNumber, int cwndValue, String event, CongestionState state,
                int ssthresh, double rtt) {
            this.timestamp = timestamp;
            this.packetNumber = packetNumber;
            this.cwndValue = cwndValue;
            this.ssthresh = ssthresh;
            this.event = event;
            this.state = state;
            this.RTT = rtt;
        }
    }

    private List<CwndLogEntry> cwndLog = new ArrayList<>();
    private long startTime;

    private ConnectionManager connectionManager = new ConnectionManager();
    private Random random = new Random();
    private long sequenceNumber;
    private long ackNumber;
    private int serverWindowSize;

    private Map<Long, UnackedPacket> unackedPackets = new ConcurrentHashMap<>();
    private Timer retransmissionTimer = new Timer(true);
    private long baseSequenceNumber;
    private volatile boolean ackReceiverRunning = true;

    private static final double PACKET_LOSS_RATE = 0.05;
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
    private int slowStartThreshold = 730*10; // Initial high value

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
        client.selectTcpVariant(); // Add variant selection
        client.connect();
    }

    private void selectTcpVariant() {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("=== TCP Congestion Control Variant Selection ===");
        System.out.println("1. TCP Tahoe (no fast recovery)");
        System.out.println("2. TCP Reno (with fast recovery)");
        System.out.print("Enter your choice (1 or 2): ");
        
        while (true) {
            try {
                int choice = scanner.nextInt();
                
                if (choice == 1) {
                    tcpVariant = TcpVariant.TAHOE;
                    System.out.println("[TCP-VARIANT] Selected: TCP Tahoe");
                    System.out.println("[TCP-VARIANT] Fast recovery: DISABLED");
                    System.out.println("[TCP-VARIANT] Triple duplicate ACKs will trigger timeout behavior");
                    break;
                } else if (choice == 2) {
                    tcpVariant = TcpVariant.RENO;
                    System.out.println("[TCP-VARIANT] Selected: TCP Reno");
                    System.out.println("[TCP-VARIANT] Fast recovery: ENABLED");
                    System.out.println("[TCP-VARIANT] Triple duplicate ACKs will trigger fast retransmit and fast recovery");
                    break;
                } else {
                    System.out.print("Invalid choice. Please enter 1 or 2: ");
                }
            } catch (Exception e) {
                System.out.print("Invalid input. Please enter 1 or 2: ");
                scanner.nextLine(); // Clear invalid input
            }
        }
        
        System.out.println("================================================\n");
    }

    public void connect() {
        try (Socket socket = connectionManager.connect();
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream())) {

            currentOutputStream = out;
            startTime = System.currentTimeMillis();
            // performHandshake(in, out);

            sequenceNumber = connectionManager.getSequenceNumber();
            ackNumber = connectionManager.getAckNumber();
            serverWindowSize = connectionManager.getServerWindowSize();
            baseSequenceNumber = sequenceNumber;

            System.out.println("[TCP-VARIANT] Running with: " + tcpVariant);

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
            logCwndHistory();
            connectionManager.setSequenceNumber(sequenceNumber);
            connectionManager.setAckNumber(ackNumber);
            connectionManager.closeConnection(in, out);
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
                connectionManager.setSequenceNumber(sequenceNumber); // Keep ConnectionManager in sync
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
                                        "[ACK-RECEIVER] Triple duplicate ACK detected - triggering " + 
                                        (tcpVariant == TcpVariant.RENO ? "fast retransmit" : "timeout behavior") +
                                        " | ACK seq: " + ackNum +
                                        " | Duplicate count: " + duplicateAckCount);
                                
                                if (tcpVariant == TcpVariant.RENO) {
                                    handleFastRetransmit(ackNum);
                                } else { // TCP Tahoe
                                    handleTahoeTripleDupAck(ackNum);
                                }
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
            // Check if this is part of a timeout sequence
            long currentTime = System.currentTimeMillis();
            boolean isSequentialTimeout = (currentTime - lastTimeoutTime) < TIMEOUT_SEQUENCE_WINDOW;
            
            int windowForSsthresh;
            if (isSequentialTimeout && lastGoodCongestionWindow > 0) {
                // Use the last good window size before the timeout sequence started
                windowForSsthresh = lastGoodCongestionWindow;
                System.out.println("[CONGESTION] Sequential timeout detected - using last good CWND: " + 
                        windowForSsthresh + " bytes for ssthresh calculation");
            } else {
                // First timeout in sequence - save current window as last good window
                windowForSsthresh = congestionWindow;
                lastGoodCongestionWindow = congestionWindow;
                System.out.println("[CONGESTION] First timeout in sequence - saving CWND: " + 
                        windowForSsthresh + " bytes as last good window");
            }
            
            lastTimeoutTime = currentTime;
            
            slowStartThreshold = Math.max(windowForSsthresh / 2, Constants.MAX_SEGMENT_SIZE);
            congestionWindow = Constants.MAX_SEGMENT_SIZE; // This should be 1 MSS
            congestionState = CongestionState.SLOW_START;
            packetsSinceLastIncrease = 0;

            System.out.println("[CONGESTION] Timeout - Resetting to Slow Start (" + tcpVariant + ")");
            System.out.println("[CONGESTION] Window used for ssthresh calc: " + windowForSsthresh + " bytes");
            System.out.println("[CONGESTION] New ssthresh: " + slowStartThreshold + " bytes (" +
                    (slowStartThreshold / Constants.MAX_SEGMENT_SIZE) + " MSS)");
            System.out.println("[CONGESTION] New CWND: " + congestionWindow + " bytes (1 MSS)"); // Updated log
            System.out.println("[CONGESTION] State: SLOW_START");
            
            cwndLog.add(new CwndLogEntry(
                    System.currentTimeMillis() - startTime,
                    totalPacketsSent,
                    congestionWindow,
                    "TIMEOUT",
                    congestionState, slowStartThreshold, estimatedRTT));
            return;
        } else {
            // Successful ACK received - reset the timeout sequence tracking
            if (ackedBytes > 0) {
                lastGoodCongestionWindow = -1; // Reset - we're making progress again
                System.out.println("[CONGESTION] Progress made - resetting timeout sequence tracking");
            }
        }

        switch (congestionState) {
            case SLOW_START:
                // Exponential growth: increase CWND by MSS for each ACK
                congestionWindow += ackedBytes; // Use actual bytes acknowledged

                cwndLog.add(new CwndLogEntry(
                        System.currentTimeMillis() - startTime,
                        totalPacketsSent,
                        congestionWindow,
                        "SLOW_START_INCREASE",
                        congestionState, slowStartThreshold, estimatedRTT));

                System.out.println("[CONGESTION] Slow Start - CWND increased to " + congestionWindow +
                        " bytes (" + (congestionWindow / Constants.MAX_SEGMENT_SIZE) + " MSS)");

                // Check if we should switch to congestion avoidance
                if (congestionWindow >= slowStartThreshold) {
                    congestionState = CongestionState.CONGESTION_AVOIDANCE;
                    packetsSinceLastIncrease = 0;
                    System.out.println("[CONGESTION] Switching to Congestion Avoidance (CWND >= ssthresh)");
                    cwndLog.add(new CwndLogEntry(
                            System.currentTimeMillis() - startTime,
                            totalPacketsSent,
                            congestionWindow,
                            "TRANSITION_TO_CA",
                            congestionState, slowStartThreshold, estimatedRTT));
                }
                break;

            case CONGESTION_AVOIDANCE:
                // Linear growth: increase CWND by MSS per RTT
                // In practice: increase by (MSS * MSS) / CWND for each ACK
                packetsSinceLastIncrease += ackedBytes;

                // When we've acknowledged a full congestion window worth of data,
                // increase CWND by one MSS
                if (packetsSinceLastIncrease >= congestionWindow) {
                    congestionWindow += Constants.MAX_SEGMENT_SIZE;
                    packetsSinceLastIncrease = 0;

                    System.out.println("[CONGESTION] Congestion Avoidance - CWND increased to " +
                            congestionWindow + " bytes (" +
                            (congestionWindow / Constants.MAX_SEGMENT_SIZE) + " MSS)");
                    cwndLog.add(new CwndLogEntry(
                            System.currentTimeMillis() - startTime,
                            totalPacketsSent,
                            congestionWindow,
                            "CA_INCREASE",
                            congestionState, slowStartThreshold, estimatedRTT));
                } else {
                    // Log the progress towards next increase
                    System.out.println("[CONGESTION] CA Progress: " + packetsSinceLastIncrease + 
                            "/" + congestionWindow + " bytes toward next increase");
                }
                break;

            case FAST_RECOVERY:
                // Only TCP Reno should reach here
                if (tcpVariant == TcpVariant.RENO) {
                    // Check if we can exit fast recovery
                    if (lastAckReceived > fastRecoverySequence) {
                        // New ACK received, exit fast recovery
                        congestionWindow = slowStartThreshold;
                        congestionState = CongestionState.CONGESTION_AVOIDANCE;
                        packetsSinceLastIncrease = 0;

                        System.out.println("[CONGESTION] Exiting Fast Recovery (TCP Reno)");
                        System.out.println("[CONGESTION] CWND set to ssthresh: " + congestionWindow + " bytes");
                        System.out.println("[CONGESTION] State: CONGESTION_AVOIDANCE");
                        cwndLog.add(new CwndLogEntry(
                                System.currentTimeMillis() - startTime,
                                totalPacketsSent,
                                congestionWindow,
                                "EXIT_FAST_RECOVERY",
                                congestionState, slowStartThreshold, estimatedRTT));
                    }
                } else {
                    // TCP Tahoe should never be in fast recovery
                    System.err.println("[ERROR] TCP Tahoe should not be in FAST_RECOVERY state!");
                    congestionState = CongestionState.CONGESTION_AVOIDANCE;
                }
                break;
        }

        // Ensure minimum window size
        if (congestionWindow < Constants.MAX_SEGMENT_SIZE) {
            congestionWindow = Constants.MAX_SEGMENT_SIZE;
        }
    }

    private void updateCongestionControlWithSavedCwnd(int ackedBytes, boolean isTimeout, int oldCongestionWindow) {
        if (isTimeout) {
            // Check if this is part of a timeout sequence
            long currentTime = System.currentTimeMillis();
            boolean isSequentialTimeout = (currentTime - lastTimeoutTime) < TIMEOUT_SEQUENCE_WINDOW;
            
            int windowForSsthresh;
            if (isSequentialTimeout && lastGoodCongestionWindow > 0) {
                // Use the last good window size before the timeout sequence started
                windowForSsthresh = lastGoodCongestionWindow;
                System.out.println("[CONGESTION] Sequential timeout detected - using last good CWND: " + 
                        windowForSsthresh + " bytes for ssthresh calculation");
            } else {
                // First timeout in sequence - use the provided old window
                windowForSsthresh = oldCongestionWindow;
                lastGoodCongestionWindow = oldCongestionWindow;
                System.out.println("[CONGESTION] First timeout in sequence - saving old CWND: " + 
                        windowForSsthresh + " bytes as last good window");
            }
            
            lastTimeoutTime = currentTime;
            
            // Use the appropriate window size for ssthresh calculation
            slowStartThreshold = Math.max(windowForSsthresh / 2, Constants.MAX_SEGMENT_SIZE);
            congestionWindow = Constants.MAX_SEGMENT_SIZE; // This should be 1 MSS
            congestionState = CongestionState.SLOW_START;
            packetsSinceLastIncrease = 0;

            System.out.println("[CONGESTION] Timeout - Resetting to Slow Start");
            System.out.println("[CONGESTION] Window used for ssthresh calc: " + windowForSsthresh + " bytes");
            System.out.println("[CONGESTION] New ssthresh: " + slowStartThreshold + " bytes (" +
                    (slowStartThreshold / Constants.MAX_SEGMENT_SIZE) + " MSS)");
            System.out.println("[CONGESTION] New CWND: " + congestionWindow + " bytes (1 MSS)"); // Updated log
            System.out.println("[CONGESTION] State: SLOW_START");

            cwndLog.add(new CwndLogEntry(
                    System.currentTimeMillis() - startTime,
                    totalPacketsSent,
                    congestionWindow,
                    "TIMEOUT",
                    congestionState, slowStartThreshold, estimatedRTT));
            return;
        }

        // For non-timeout cases, use the regular method
        updateCongestionControl(ackedBytes, isTimeout);
    }

    private void handleFastRetransmit(long ackNum) {
        // Only for TCP Reno - enter fast recovery
        if (tcpVariant == TcpVariant.RENO) {
            if (congestionState != CongestionState.FAST_RECOVERY) {
                // FIXED: Save old congestion window first
                int oldCongestionWindow = congestionWindow;
                slowStartThreshold = Math.max(oldCongestionWindow / 2, Constants.MAX_SEGMENT_SIZE);
                congestionWindow = slowStartThreshold + 3 * Constants.MAX_SEGMENT_SIZE;
                congestionState = CongestionState.FAST_RECOVERY;
                fastRecoverySequence = ackNum;

                cwndLog.add(new CwndLogEntry(
                        System.currentTimeMillis() - startTime,
                        totalPacketsSent,
                        congestionWindow,
                        "FAST_RETRANSMIT_RENO",
                        congestionState, slowStartThreshold, estimatedRTT));

                System.out.println("[FAST-RETRANSMIT] Entering Fast Recovery (TCP Reno)");
                System.out.println("[FAST-RETRANSMIT] Old CWND: " + oldCongestionWindow + " bytes");
                System.out.println("[FAST-RETRANSMIT] New ssthresh: " + slowStartThreshold + " bytes");
                System.out.println("[FAST-RETRANSMIT] New CWND: " + congestionWindow + " bytes");
            } else {
                // Already in fast recovery - just increment CWND for additional duplicate ACKs
                congestionWindow += Constants.MAX_SEGMENT_SIZE;
                System.out.println("[FAST-RETRANSMIT] Already in Fast Recovery - inflating CWND to: " + 
                        congestionWindow + " bytes (ssthresh unchanged: " + slowStartThreshold + " bytes)");
            }
        }

        // Trigger the actual retransmission (both Reno and Tahoe)
        triggerFastRetransmit(ackNum);
    }

    private void handleTahoeTripleDupAck(long ackNum) {
        // TCP Tahoe behavior: treat triple duplicate ACK like a timeout
        System.out.println("[FAST-RETRANSMIT] TCP Tahoe - treating triple dup ACK as timeout");
        
        // FIXED: Save old congestion window first
        int oldCongestionWindow = congestionWindow;
        slowStartThreshold = Math.max(oldCongestionWindow / 2, Constants.MAX_SEGMENT_SIZE);
        congestionWindow = Constants.MAX_SEGMENT_SIZE; // This should be 1 MSS
        congestionState = CongestionState.SLOW_START;
        packetsSinceLastIncrease = 0;

        cwndLog.add(new CwndLogEntry(
                System.currentTimeMillis() - startTime,
                totalPacketsSent,
                congestionWindow,
                "FAST_RETRANSMIT_TAHOE",
                congestionState, slowStartThreshold, estimatedRTT));

        System.out.println("[FAST-RETRANSMIT] TCP Tahoe - Resetting to Slow Start");
        System.out.println("[FAST-RETRANSMIT] Old CWND: " + oldCongestionWindow + " bytes");
        System.out.println("[FAST-RETRANSMIT] New ssthresh: " + slowStartThreshold + " bytes");
        System.out.println("[FAST-RETRANSMIT] New CWND: " + congestionWindow + " bytes (1 MSS)"); // Updated log
        System.out.println("[FAST-RETRANSMIT] State: SLOW_START");

        // Trigger the actual retransmission
        triggerFastRetransmit(ackNum);
    }

    private boolean shouldDropPacket() {
        return random.nextDouble() < PACKET_LOSS_RATE;
    }

    private void sendPacketReliably(Packet packet, DataOutputStream out) throws IOException {
        long seqNum = packet.getSequenceNumber();
        totalPacketsSent++;
        cwndLog.add(new CwndLogEntry(
                System.currentTimeMillis() - startTime,
                totalPacketsSent,
                congestionWindow,
                "PACKET_SENT",
                congestionState, slowStartThreshold, estimatedRTT));

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

            unackedPacket.packet.sendPacket(currentOutputStream);
            System.out.println("[FAST-RETRANSMIT] Packet retransmitted - seq: " + seqNum);

        } catch (IOException e) {
            System.err.println("[ERROR] Fast retransmit failed: " + e.getMessage());
        }
    }

    private int processAck(long ackNum) {
        System.out.println("[ACK-PROCESSOR] Starting ACK processing" +
                " | ACK num: " + ackNum +
                " | Current base: " + baseSequenceNumber +
                " | Unacked packets: " + unackedPackets.size());

        int ackedPackets = 0;
        int ackedBytes = 0;

        // Create a list to track which packets to remove
        List<Long> packetsToRemove = new ArrayList<>();

        // Process cumulative acknowledgment - ACK acknowledges all bytes up to ackNum
        for (Map.Entry<Long, UnackedPacket> entry : unackedPackets.entrySet()) {
            long seqNum = entry.getKey();
            UnackedPacket packet = entry.getValue();
            long endSeqNum = seqNum + packet.packet.getPayload().length;

            // If this packet is completely acknowledged by the cumulative ACK
            if (endSeqNum <= ackNum) {
                ackedPackets++;
                ackedBytes += packet.packet.getPayload().length;

                // Calculate RTT only for the first packet being ACKed in this batch
                // to avoid RTT measurement issues with cumulative ACKs
                if (ackedPackets == 1) {
                    double sampleRTT = System.currentTimeMillis() - packet.sendTime;
                    updateRTTEstimates(sampleRTT);
                    System.out.println("[RTT] Measured from packet seq: " + seqNum +
                            " | Sample RTT: " + String.format("%.2f", sampleRTT) + "ms");
                }

                packetsToRemove.add(seqNum);

                System.out.println("[ACK-PROCESSOR] Packet acknowledged - seq: " + seqNum +
                        " | bytes: " + packet.packet.getPayload().length +
                        " | was buffered at server: " + (seqNum > baseSequenceNumber + Constants.MAX_SEGMENT_SIZE));
            }
        }

        // Remove all acknowledged packets
        for (Long seqNum : packetsToRemove) {
            unackedPackets.remove(seqNum);
        }

        // Handle duplicate ACK detection
        if (ackedBytes > 0) {
            // New data acknowledged - reset duplicate count and update base
            long oldLastAck = lastAckReceived;
            lastAckReceived = ackNum;
            baseSequenceNumber = ackNum;

            if (duplicateAckCount > 0) {
                System.out.println("[ACK-PROCESSOR] Resetting duplicate ACK count from " + duplicateAckCount +
                        " (new data acknowledged)");
                duplicateAckCount = 0;
            }

            System.out.println("[ACK-PROCESSOR] Cumulative ACK processed" +
                    " | Previous ACK: " + oldLastAck +
                    " | New ACK: " + ackNum +
                    " | Packets acknowledged: " + ackedPackets +
                    " | Bytes acknowledged: " + ackedBytes +
                    " | New base: " + baseSequenceNumber);

        } else if (ackNum == lastAckReceived && !unackedPackets.isEmpty()) {
            // Same ACK with no new data acknowledged AND we have unacked packets
            duplicateAckCount++;

            System.out.println("[DUPLICATE-ACK] Count: " + duplicateAckCount +
                    " | ACK: " + ackNum +
                    " | Unacked packets: " + unackedPackets.size());

            // Only trigger fast retransmit if we have packets that could be lost
            if (duplicateAckCount >= FAST_RETRANSMIT_THRESHOLD) {
                // Find the earliest unacked packet
                Long earliestUnacked = unackedPackets.keySet().stream().min(Long::compare).orElse(null);
                if (earliestUnacked != null) {
                    System.out.println("[FAST-RETRANSMIT] Triggering for ACK: " + ackNum +
                            " | Earliest unacked: " + earliestUnacked);
                    handleFastRetransmit(ackNum);
                    duplicateAckCount = 0; // Reset after fast retransmit
                }
            }
        }
        // If ackNum < lastAckReceived, it's an old ACK - ignore it

        return ackedBytes;
    }

    private void updateRTTEstimates(double sampleRTT) {
        if (estimatedRTT == 1000.0) { // Initial value check
            estimatedRTT = sampleRTT;
            devRTT = sampleRTT / 2.0;
        } else {
            devRTT = (1 - BETA) * devRTT + BETA * Math.abs(sampleRTT - estimatedRTT);
            estimatedRTT = (1 - ALPHA) * estimatedRTT + ALPHA * sampleRTT;
        }

        System.out.println("[RTT] Sample: " + String.format("%.2f", sampleRTT) + "ms" +
                " | Estimated: " + String.format("%.2f", estimatedRTT) + "ms" +
                " | Deviation: " + String.format("%.2f", devRTT) + "ms" +
                " | Timeout would be: " + String.format("%.2f", (estimatedRTT + 4 * devRTT)) + "ms");
    }

    private long calculateTimeoutInterval() {
        double timeoutInterval = estimatedRTT + 4 * devRTT;

        // Less aggressive minimum timeouts
        if (totalPacketsSent <= 3) {
            timeoutInterval = Math.max(1000, timeoutInterval); // 2 seconds for first few packets
        } else if (congestionWindow <= 2 * Constants.MAX_SEGMENT_SIZE) {
            timeoutInterval = Math.max(200, timeoutInterval); // 1.5 seconds for small windows
        } else {
            timeoutInterval = Math.max(100, timeoutInterval); // 500ms minimum for normal operation
        }

        // More reasonable maximum timeout
        timeoutInterval = Math.min(8000, timeoutInterval); // Cap at 8 seconds instead of 15ms

        return (long) timeoutInterval;
    }

    private void scheduleRetransmission(long seqNum, DataOutputStream out) {
        long timeout = calculateTimeoutInterval();

        // Add some debug info
        UnackedPacket packet = unackedPackets.get(seqNum);
        if (packet != null) {
            System.out.println("[RETRANSMIT-TIMER] Scheduling retransmission for seq: " + seqNum +
                    " | Timeout: " + timeout + "ms" +
                    " | EstRTT: " + String.format("%.2f", estimatedRTT) + "ms" +
                    " | DevRTT: " + String.format("%.2f", devRTT) + "ms");
        }

        retransmissionTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                retransmitPacket(seqNum, out);
            }
        }, timeout);
    }

    // Also add more detailed logging to track when timeouts occur vs when ACKs
    // arrive
    private void retransmitPacket(long seqNum, DataOutputStream out) {
        UnackedPacket unackedPacket = unackedPackets.get(seqNum);
        if (unackedPacket == null) {
            System.out.println("[RETRANSMIT] Packet already ACKed - seq: " + seqNum);
            return;
        }

        if (unackedPacket.retryCount >= Constants.MAX_RETRIES) {
            System.err.println("[ERROR] Max retries exceeded for seq: " + seqNum);
            unackedPackets.remove(seqNum);
            return;
        }

        long timeSinceSent = System.currentTimeMillis() - unackedPacket.sendTime;
        System.out.println("[RETRANSMIT] TIMEOUT occurred for seq: " + seqNum +
                " | Time since sent: " + timeSinceSent + "ms" +
                " | Expected timeout: " + calculateTimeoutInterval() + "ms" +
                " | EstRTT: " + String.format("%.2f", estimatedRTT) + "ms" +
                " | Retry count: " + unackedPacket.retryCount +
                " | Current state: " + congestionState);

        try {
            unackedPacket.retryCount++;
            unackedPacket.timestamp = System.currentTimeMillis();
            unackedPacket.sendTime = System.currentTimeMillis();

            // FIXED: Only apply timeout congestion control if NOT in fast recovery
            // and timeout hasn't been processed yet
            if (!unackedPacket.timeoutProcessed && congestionState != CongestionState.FAST_RECOVERY) {
                int cwndBeforeTimeout = congestionWindow; // Capture current CWND
                updateCongestionControlWithSavedCwnd(0, true, cwndBeforeTimeout);
                unackedPacket.timeoutProcessed = true;
                System.out.println("[CONGESTION] Timeout congestion control applied for seq: " + seqNum);
            } else if (congestionState == CongestionState.FAST_RECOVERY) {
                System.out.println("[CONGESTION] In Fast Recovery - timeout for seq: " + seqNum + 
                        " does NOT modify ssthresh (preserved: " + slowStartThreshold + " bytes)");
            } else {
                System.out.println("[CONGESTION] Timeout already processed for seq: " + seqNum + 
                        " - skipping congestion control");
            }

            unackedPacket.packet.sendPacket(out);
            System.out.println("[RETRANSMIT] Packet retransmitted - seq: " + seqNum +
                    " | Retry: " + unackedPacket.retryCount +
                    " | New timeout: " + calculateTimeoutInterval() + "ms");

            scheduleRetransmission(seqNum, out);

        } catch (IOException e) {
            System.err.println("[ERROR] Retransmission failed: " + e.getMessage());
        }
    }

    // Add this new method to handle timeout with saved CWND
    // private void updateCongestionControlWithSavedCwnd(int ackedBytes, boolean isTimeout, int oldCongestionWindow) {
    //     if (isTimeout) {
    //         // Check if this is part of a timeout sequence
    //         long currentTime = System.currentTimeMillis();
    //         boolean isSequentialTimeout = (currentTime - lastTimeoutTime) < TIMEOUT_SEQUENCE_WINDOW;
            
    //         int windowForSsthresh;
    //         if (isSequentialTimeout && lastGoodCongestionWindow > 0) {
    //             // Use the last good window size before the timeout sequence started
    //             windowForSsthresh = lastGoodCongestionWindow;
    //             System.out.println("[CONGESTION] Sequential timeout detected - using last good CWND: " + 
    //                     windowForSsthresh + " bytes for ssthresh calculation");
    //         } else {
    //             // First timeout in sequence - use the provided old window
    //             windowForSsthresh = oldCongestionWindow;
    //             lastGoodCongestionWindow = oldCongestionWindow;
    //             System.out.println("[CONGESTION] First timeout in sequence - saving old CWND: " + 
    //                     windowForSsthresh + " bytes as last good window");
    //         }
            
    //         lastTimeoutTime = currentTime;
            
    //         // Use the appropriate window size for ssthresh calculation
    //         slowStartThreshold = Math.max(windowForSsthresh / 2, Constants.MAX_SEGMENT_SIZE);
    //         congestionWindow = Constants.MAX_SEGMENT_SIZE; // This should be 1 MSS
    //         congestionState = CongestionState.SLOW_START;
    //         packetsSinceLastIncrease = 0;

    //         System.out.println("[CONGESTION] Timeout - Resetting to Slow Start");
    //         System.out.println("[CONGESTION] Window used for ssthresh calc: " + windowForSsthresh + " bytes");
    //         System.out.println("[CONGESTION] New ssthresh: " + slowStartThreshold + " bytes (" +
    //                 (slowStartThreshold / Constants.MAX_SEGMENT_SIZE) + " MSS)");
    //         System.out.println("[CONGESTION] New CWND: " + congestionWindow + " bytes (1 MSS)"); // Updated log
    //         System.out.println("[CONGESTION] State: SLOW_START");

    //         cwndLog.add(new CwndLogEntry(
    //                 System.currentTimeMillis() - startTime,
    //                 totalPacketsSent,
    //                 congestionWindow,
    //                 "TIMEOUT",
    //                 congestionState, slowStartThreshold, estimatedRTT));
    //         return;
    //     }

    //     // For non-timeout cases, use the regular method
    //     updateCongestionControl(ackedBytes, isTimeout);
    // }

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

    private void logCwndHistory() {
        String csvFileName = "cwnd_log_"  + System.currentTimeMillis() + ".csv";
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(csvFileName))) {
            // Write CSV header with TCP variant info
            // writer.println("# TCP Variant: " + tcpVariant);
            writer.println("Time_ms,Packet_Number,CWND_bytes,CWND_MSS,SSThresh,RTT_ms,Event,State");
            
            // Write data rows
            for (CwndLogEntry entry : cwndLog) {
                int cwndInMss = entry.cwndValue / Constants.MAX_SEGMENT_SIZE;
                writer.printf("%d,%d,%d,%d,%d,%.2f,%s,%s%n",
                        entry.timestamp,
                        entry.packetNumber,
                        entry.cwndValue,
                        cwndInMss,
                        entry.ssthresh,
                        entry.RTT,
                        entry.event,
                        entry.state);
            }
            
            System.out.println("[CWND-LOG] CWND history saved to: " + csvFileName);
            System.out.println("[CWND-LOG] TCP Variant: " + tcpVariant);
            System.out.println("[CWND-LOG] Total entries logged: " + cwndLog.size());
            
            // Print summary statistics to console
            if (!cwndLog.isEmpty()) {
                int maxCwnd = cwndLog.stream().mapToInt(e -> e.cwndValue).max().orElse(0);
                int minCwnd = cwndLog.stream().mapToInt(e -> e.cwndValue).min().orElse(0);
                double avgCwnd = cwndLog.stream().mapToInt(e -> e.cwndValue).average().orElse(0);
                long duration = cwndLog.get(cwndLog.size() - 1).timestamp;
                
                System.out.println("[CWND-LOG] Transfer Statistics (" + tcpVariant + "):");
                System.out.println("[CWND-LOG]   Duration: " + duration + "ms");
                System.out.println("[CWND-LOG]   Max CWND: " + maxCwnd + " bytes (" + (maxCwnd / Constants.MAX_SEGMENT_SIZE) + " MSS)");
                System.out.println("[CWND-LOG]   Min CWND: " + minCwnd + " bytes (" + (minCwnd / Constants.MAX_SEGMENT_SIZE) + " MSS)");
                System.out.println("[CWND-LOG]   Avg CWND: " + String.format("%.2f", avgCwnd) + " bytes (" + String.format("%.2f", avgCwnd / Constants.MAX_SEGMENT_SIZE) + " MSS)");
                
                // Count different event types
                long timeouts = cwndLog.stream().filter(e -> e.event.equals("TIMEOUT")).count();
                long fastRetransmitsReno = cwndLog.stream().filter(e -> e.event.equals("FAST_RETRANSMIT_RENO")).count();
                long fastRetransmitsTahoe = cwndLog.stream().filter(e -> e.event.equals("FAST_RETRANSMIT_TAHOE")).count();
                long slowStartIncreases = cwndLog.stream().filter(e -> e.event.equals("SLOW_START_INCREASE")).count();
                long caIncreases = cwndLog.stream().filter(e -> e.event.equals("CA_INCREASE")).count();
                
                System.out.println("[CWND-LOG]   Events Summary:");
                System.out.println("[CWND-LOG]     Timeouts: " + timeouts);
                if (tcpVariant == TcpVariant.RENO) {
                    System.out.println("[CWND-LOG]     Fast Retransmits (Reno): " + fastRetransmitsReno);
                } else {
                    System.out.println("[CWND-LOG]     Fast Retransmits (Tahoe): " + fastRetransmitsTahoe);
                }
                System.out.println("[CWND-LOG]     Slow Start Increases: " + slowStartIncreases);
                System.out.println("[CWND-LOG]     Congestion Avoidance Increases: " + caIncreases);
            }
            
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to write CWND log to CSV file: " + e.getMessage());
            // Fall back to console logging
            System.out.println("[CWND-LOG] Falling back to console output:");
            logCwndToConsole();
        }
    }
    
    private void logCwndToConsole() {
        System.out.println("\n[CWND-LOG] ==================== CONGESTION WINDOW HISTORY ====================");
        System.out.println(
                "[CWND-LOG]  Format: Time(ms) | Packet#  | CWND(bytes)  | CWND(MSS) | SSTHRESH | RTT | Event  | State");
        System.out.println("[CWND-LOG] =====================================================================");

        for (CwndLogEntry entry : cwndLog) {
            int cwndInMss = entry.cwndValue / Constants.MAX_SEGMENT_SIZE;
            System.out.printf("[CWND-LOG] %8d | %7d | %10d | %8d | %8d | %8.2f | %-20s | %s%n",
                    entry.timestamp,
                    entry.packetNumber,
                    entry.cwndValue,
                    cwndInMss,
                    entry.ssthresh,
                    entry.RTT,
                    entry.event,
                    entry.state);
        }
        System.out.println("[CWND-LOG] =====================================================================\n");
    }

    private long getBytesInFlight() {
        return sequenceNumber - baseSequenceNumber;
    }

    private static class UnackedPacket {
        Packet packet;
        long timestamp;
        long sendTime;
        int retryCount;
        boolean timeoutProcessed; // Add this flag

        public UnackedPacket(Packet packet) {
            this.packet = packet;
            this.timestamp = System.currentTimeMillis();
            this.sendTime = System.currentTimeMillis();
            this.retryCount = 0;
            this.timeoutProcessed = false; // Initialize to false
        }
    }
}