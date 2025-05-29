import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TCPClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8080;
    private static final int CLIENT_PORT = 12345;
    private static final int WINDOW_SIZE = 4096;
    private static final int MAX_SEGMENT_SIZE = 730; // MSS
    private static final String FILE_PATH = "hehe.txt";
    private static final int TIMEOUT_MS = 1000; // 1 second timeout
    private static final int MAX_RETRIES = 5;

    private Random random = new Random();
    private long sequenceNumber;
    private long ackNumber;
    private int serverWindowSize;
    
    // For reliable data transfer
    private Map<Long, UnackedPacket> unackedPackets = new ConcurrentHashMap<>();
    private Timer retransmissionTimer = new Timer(true);
    private long baseSequenceNumber;

    // Class to track unacknowledged packets
    private static class UnackedPacket {
        TCPPacket packet;
        long timestamp;
        int retryCount;
        
        UnackedPacket(TCPPacket packet) {
            this.packet = packet;
            this.timestamp = System.currentTimeMillis();
            this.retryCount = 0;
        }
    }

    public static void main(String[] args) {
        TCPClient client = new TCPClient();
        client.connect();
    }

    public void connect() {
        try (Socket socket = new Socket(SERVER_HOST, SERVER_PORT);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream())) {

            System.out.println("Connected to server at " + SERVER_HOST + ":" + SERVER_PORT);

            // Start ACK receiver thread
            Thread ackReceiver = new Thread(() -> handleAcks(in));
            ackReceiver.setDaemon(true);
            ackReceiver.start();

            // Perform three-way handshake
            performHandshake(in, out);

            // Send file
            sendFile(out);

            // Wait for all packets to be acknowledged
            waitForAllAcks();

            // Close connection
            closeConnection(in, out);

        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        } finally {
            retransmissionTimer.cancel();
        }
    }

    private void performHandshake(DataInputStream in, DataOutputStream out) throws IOException {
        // Step 1: Send SYN to server
        sequenceNumber = random.nextInt(1000000);
        TCPPacket synPacket = new TCPPacket();
        synPacket.setSourcePort(CLIENT_PORT);
        synPacket.setDestinationPort(SERVER_PORT);
        synPacket.setSequenceNumber(sequenceNumber);
        synPacket.setAckNumber(0);
        synPacket.setSynFlag(true);
        synPacket.setWindowSize(WINDOW_SIZE);

        synPacket.sendPacket(out);
        System.out.println("Sent SYN packet:");
        synPacket.printPacketInfo();

        // Step 2: Receive SYN-ACK from server
        TCPPacket synAckPacket = TCPPacket.receivePacket(in);

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

        // Update sequence and ack numbers
        sequenceNumber++;
        ackNumber = synAckPacket.getSequenceNumber() + 1;
        serverWindowSize = synAckPacket.getWindowSize();
        baseSequenceNumber = sequenceNumber; // Initialize base sequence

        // Step 3: Send ACK to server
        TCPPacket ackPacket = new TCPPacket();
        ackPacket.setSourcePort(CLIENT_PORT);
        ackPacket.setDestinationPort(SERVER_PORT);
        ackPacket.setSequenceNumber(sequenceNumber);
        ackPacket.setAckNumber(ackNumber);
        ackPacket.setAckFlag(true);
        ackPacket.setWindowSize(WINDOW_SIZE);

        ackPacket.sendPacket(out);
        System.out.println("Sent ACK packet:");
        ackPacket.printPacketInfo();

        System.out.println("Connection established!");
        System.out.println("Server window size: " + serverWindowSize);
        System.out.println("Client window size: " + WINDOW_SIZE);
    }

    private void sendFile(DataOutputStream out) throws IOException {
        // Read file content
        byte[] fileData;
        try {
            fileData = Files.readAllBytes(Paths.get(FILE_PATH));
            System.out.println("File size: " + fileData.length + " bytes");
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            return;
        }

        int totalChunks = (int) Math.ceil((double) fileData.length / MAX_SEGMENT_SIZE);
        int bytesSent = 0;
        int chunkNumber = 0;

        System.out.println("Starting file transfer...");

        while (bytesSent < fileData.length) {
            // Flow control: check if we can send more data
            long windowBase = baseSequenceNumber;
            long windowTop = windowBase + Math.min(serverWindowSize, WINDOW_SIZE);
            
            if (sequenceNumber >= windowTop) {
                // Window is full, wait a bit
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            // Calculate chunk size
            int remainingBytes = fileData.length - bytesSent;
            int chunkSize = Math.min(MAX_SEGMENT_SIZE, remainingBytes);

            // Create data chunk
            byte[] chunk = new byte[chunkSize];
            System.arraycopy(fileData, bytesSent, chunk, 0, chunkSize);

            // Create and send TCP packet with data
            TCPPacket dataPacket = new TCPPacket();
            dataPacket.setSourcePort(CLIENT_PORT);
            dataPacket.setDestinationPort(SERVER_PORT);
            dataPacket.setSequenceNumber(sequenceNumber);
            dataPacket.setAckNumber(ackNumber);
            dataPacket.setAckFlag(true);
            dataPacket.setPshFlag(true);
            dataPacket.setWindowSize(WINDOW_SIZE);
            dataPacket.setPayload(chunk);

            // Send packet and track it for retransmission
            sendPacketReliably(dataPacket, out);

            chunkNumber++;
            bytesSent += chunkSize;
            sequenceNumber += chunkSize;

            System.out.println("Sent chunk " + chunkNumber + "/" + totalChunks +
                    " (" + chunkSize + " bytes) - Total sent: " + bytesSent + "/" + fileData.length);

            // Small delay to simulate realistic network conditions
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("All data sent, waiting for acknowledgments...");
    }

    private void sendPacketReliably(TCPPacket packet, DataOutputStream out) throws IOException {
        long seqNum = packet.getSequenceNumber();
        
        // Send the packet
        packet.sendPacket(out);
        
        // Track the packet for retransmission
        unackedPackets.put(seqNum, new UnackedPacket(packet));
        
        // Schedule retransmission timer
        scheduleRetransmission(seqNum, out);
    }

    private void scheduleRetransmission(long seqNum, DataOutputStream out) {
        retransmissionTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                retransmitPacket(seqNum, out);
            }
        }, TIMEOUT_MS);
    }

    private void retransmitPacket(long seqNum, DataOutputStream out) {
        UnackedPacket unackedPacket = unackedPackets.get(seqNum);
        if (unackedPacket == null) {
            return; // Packet was already acknowledged
        }

        if (unackedPacket.retryCount >= MAX_RETRIES) {
            System.err.println("Max retries exceeded for packet with seq: " + seqNum);
            unackedPackets.remove(seqNum);
            return;
        }

        try {
            unackedPacket.retryCount++;
            unackedPacket.timestamp = System.currentTimeMillis();
            unackedPacket.packet.sendPacket(out);
            System.out.println("Retransmitting packet (seq: " + seqNum + 
                             ", retry: " + unackedPacket.retryCount + ")");
            
            // Schedule next retransmission
            scheduleRetransmission(seqNum, out);
            
        } catch (IOException e) {
            System.err.println("Error retransmitting packet: " + e.getMessage());
        }
    }

    private void handleAcks(DataInputStream in) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                TCPPacket ackPacket = TCPPacket.receivePacket(in);
                
                if (ackPacket.getAckFlag()) {
                    long ackNum = ackPacket.getAckNumber();
                    processAck(ackNum);
                    
                    // Update server window size
                    serverWindowSize = ackPacket.getWindowSize();
                }
            }
        } catch (IOException e) {
            // Connection closed or error occurred
        }
    }

    private void processAck(long ackNum) {
        // Remove all acknowledged packets (cumulative ACK)
        Iterator<Map.Entry<Long, UnackedPacket>> iterator = unackedPackets.entrySet().iterator();
        int ackedPackets = 0;
        
        while (iterator.hasNext()) {
            Map.Entry<Long, UnackedPacket> entry = iterator.next();
            long seqNum = entry.getKey();
            UnackedPacket packet = entry.getValue();
            
            // Calculate the end sequence number for this packet
            long endSeqNum = seqNum + packet.packet.getPayload().length;
            
            if (endSeqNum <= ackNum) {
                iterator.remove();
                ackedPackets++;
                
                // Update base sequence number (slide window)
                if (seqNum == baseSequenceNumber) {
                    baseSequenceNumber = endSeqNum;
                }
            }
        }
        
        if (ackedPackets > 0) {
            System.out.println("ACK received for " + ackedPackets + " packet(s), ACK num: " + ackNum);
        }
    }

    private void waitForAllAcks() {
        System.out.println("Waiting for all packets to be acknowledged...");
        
        long waitStart = System.currentTimeMillis();
        long maxWaitTime = 10000; // 10 seconds max wait
        
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
            System.out.println("All packets acknowledged successfully!");
        } else {
            System.err.println("Timeout waiting for acknowledgments. " + 
                             unackedPackets.size() + " packets still unacked.");
        }
    }

    private void closeConnection(DataInputStream in, DataOutputStream out) throws IOException {
        // Send FIN packet
        TCPPacket finPacket = new TCPPacket();
        finPacket.setSourcePort(CLIENT_PORT);
        finPacket.setDestinationPort(SERVER_PORT);
        finPacket.setSequenceNumber(sequenceNumber);
        finPacket.setAckNumber(ackNumber);
        finPacket.setFinFlag(true);
        finPacket.setAckFlag(true);
        finPacket.setWindowSize(WINDOW_SIZE);

        finPacket.sendPacket(out);
        System.out.println("Sent FIN packet");

        // Receive FIN-ACK with timeout
        try {
            TCPPacket finAckPacket = TCPPacket.receivePacket(in);
            if (finAckPacket.getFinFlag() && finAckPacket.getAckFlag()) {
                System.out.println("Received FIN-ACK packet");

                // Send final ACK
                sequenceNumber++;
                ackNumber = finAckPacket.getSequenceNumber() + 1;

                TCPPacket finalAckPacket = new TCPPacket();
                finalAckPacket.setSourcePort(CLIENT_PORT);
                finalAckPacket.setDestinationPort(SERVER_PORT);
                finalAckPacket.setSequenceNumber(sequenceNumber);
                finalAckPacket.setAckNumber(ackNumber);
                finalAckPacket.setAckFlag(true);
                finalAckPacket.setWindowSize(WINDOW_SIZE);

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
}