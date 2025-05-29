import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;

public class TCPServer {
    private static final int SERVER_PORT = 8080;
    private static final int WINDOW_SIZE = 2048;
    private static final String OUTPUT_FILE = "received_file.txt";
    
    private Random random = new Random();
    private long sequenceNumber;
    private long expectedSeqNumber;
    private ByteArrayOutputStream receivedData;
    private int clientWindowSize;
    
    public static void main(String[] args) {
        TCPServer server = new TCPServer();
        server.start();
    }
    
    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(SERVER_PORT)) {
            System.out.println("TCP Server listening on port " + SERVER_PORT);
            
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket.getInetAddress());
                
                // Handle each client in a separate thread
                new Thread(() -> handleClient(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }
    
    private void handleClient(Socket clientSocket) {
        receivedData = new ByteArrayOutputStream();
        
        try (DataInputStream in = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())) {
            
            // Perform three-way handshake
            performHandshake(in, out);
            
            // Receive file data
            receiveFile(in, out);
            
            // Handle connection close
            handleConnectionClose(in, out);
            
        } catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
                System.out.println("Client disconnected");
            } catch (IOException e) {
                System.err.println("Error closing client socket: " + e.getMessage());
            }
        }
    }
    
    private void performHandshake(DataInputStream in, DataOutputStream out) throws IOException {
        // Step 1: Receive SYN from client
        TCPPacket synPacket = TCPPacket.receivePacket(in);
        
        if (!synPacket.getSynFlag()) {
            System.err.println("Expected SYN packet but didn't receive one");
            return;
        }
        
        System.out.println("Received SYN packet:");
        synPacket.printPacketInfo();
        
        // Initialize sequence numbers
        sequenceNumber = random.nextInt(1000000);
        expectedSeqNumber = synPacket.getSequenceNumber() + 1;
        clientWindowSize = synPacket.getWindowSize();
        
        // Step 2: Send SYN-ACK to client
        TCPPacket synAckPacket = new TCPPacket();
        synAckPacket.setSourcePort(SERVER_PORT);
        synAckPacket.setDestinationPort(synPacket.getSourcePort());
        synAckPacket.setSequenceNumber(sequenceNumber);
        synAckPacket.setAckNumber(expectedSeqNumber);
        synAckPacket.setSynFlag(true);
        synAckPacket.setAckFlag(true);
        synAckPacket.setWindowSize(WINDOW_SIZE);
        
        synAckPacket.sendPacket(out);
        System.out.println("Sent SYN-ACK packet:");
        synAckPacket.printPacketInfo();
        
        sequenceNumber++;
        
        // Step 3: Receive ACK from client
        TCPPacket ackPacket = TCPPacket.receivePacket(in);
        
        if (!ackPacket.getAckFlag() || ackPacket.getSynFlag()) {
            System.err.println("Expected ACK packet but didn't receive one");
            return;
        }
        
        if (ackPacket.getAckNumber() != sequenceNumber) {
            System.err.println("Received incorrect ACK number");
            return;
        }
        
        System.out.println("Received ACK packet:");
        ackPacket.printPacketInfo();
        
        System.out.println("Connection established!");
        System.out.println("Client window size: " + clientWindowSize);
        System.out.println("Server window size: " + WINDOW_SIZE);
    }
    
    
    private void sendAck(DataOutputStream out, long ackNumber, int clientPort) throws IOException {
        TCPPacket ackPacket = new TCPPacket();
        ackPacket.setSourcePort(SERVER_PORT);
        ackPacket.setDestinationPort(clientPort);
        ackPacket.setSequenceNumber(sequenceNumber);
        ackPacket.setAckNumber(ackNumber);
        ackPacket.setAckFlag(true);
        ackPacket.setWindowSize(WINDOW_SIZE);
        
        ackPacket.sendPacket(out);
        System.out.println("Sent ACK for sequence: " + ackNumber);
    }
    
    private void saveReceivedFile() {
        try {
            byte[] fileData = receivedData.toByteArray();
            Files.write(Paths.get(OUTPUT_FILE), fileData);
            System.out.println("File saved as: " + OUTPUT_FILE + " (" + fileData.length + " bytes)");
            
            // Display file content for verification
            String content = new String(fileData);
            System.out.println("File content preview:");
            System.out.println(content.substring(0, Math.min(100, content.length())) + 
                             (content.length() > 100 ? "..." : ""));
            
        } catch (IOException e) {
            System.err.println("Error saving file: " + e.getMessage());
        }
    }
    
    private void handleConnectionClose(DataInputStream in, DataOutputStream out) throws IOException {
        // This method handles the FIN packet that was already received in receiveFile
        // The actual FIN handling is done in handleFinPacket method
    }
    
    private void receiveFile(DataInputStream in, DataOutputStream out) throws IOException {
    System.out.println("Starting file reception...");
    int totalBytesReceived = 0;
    int packetsReceived = 0;
    
    while (true) {
        try {
            TCPPacket dataPacket = TCPPacket.receivePacket(in);
            
            // Check if this is a FIN packet (connection close)
            if (dataPacket.getFinFlag()) {
                System.out.println("Received FIN packet - file transfer completed");
                
                // Save the received file
                saveReceivedFile();
                
                // Handle FIN packet separately - don't break here
                handleFinPacket(dataPacket, in, out);
                return; // Return instead of break to properly exit
            }
            
            // Check if packet has data
            byte[] payload = dataPacket.getPayload();
            if (payload.length == 0) {
                continue; // Skip packets without data
            }
            
            packetsReceived++;
            
            // Check sequence number for in-order delivery
            if (dataPacket.getSequenceNumber() == expectedSeqNumber) {
                // Packet is in order - accept it
                receivedData.write(payload);
                totalBytesReceived += payload.length;
                expectedSeqNumber += payload.length;
                
                System.out.println("Received packet " + packetsReceived + 
                                 " (" + payload.length + " bytes) - Total: " + totalBytesReceived + " bytes");
                
                // Send ACK for this packet
                sendAck(out, expectedSeqNumber, dataPacket.getSourcePort());
                
            } else if (dataPacket.getSequenceNumber() < expectedSeqNumber) {
                // Duplicate packet - send ACK but don't store data
                System.out.println("Received duplicate packet (seq: " + dataPacket.getSequenceNumber() + 
                                 ", expected: " + expectedSeqNumber + ")");
                sendAck(out, expectedSeqNumber, dataPacket.getSourcePort());
                
            } else {
                // Out of order packet - for simplicity, we'll request retransmission
                System.out.println("Received out-of-order packet (seq: " + dataPacket.getSequenceNumber() + 
                                 ", expected: " + expectedSeqNumber + ")");
                sendAck(out, expectedSeqNumber, dataPacket.getSourcePort());
            }
            
            // Simulate processing delay
            Thread.sleep(5);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
        }
    }
    
    System.out.println("File reception completed:");
    System.out.println("Total packets received: " + packetsReceived);
    System.out.println("Total bytes received: " + totalBytesReceived);
}

private void handleFinPacket(TCPPacket finPacket, DataInputStream in, DataOutputStream out) throws IOException {
    System.out.println("Handling connection close...");
    
    // Send FIN-ACK response
    long finAckNumber = finPacket.getSequenceNumber() + 1;
    
    TCPPacket finAckPacket = new TCPPacket();
    finAckPacket.setSourcePort(SERVER_PORT);
    finAckPacket.setDestinationPort(finPacket.getSourcePort());
    finAckPacket.setSequenceNumber(sequenceNumber);
    finAckPacket.setAckNumber(finAckNumber);
    finAckPacket.setFinFlag(true);
    finAckPacket.setAckFlag(true);
    finAckPacket.setWindowSize(WINDOW_SIZE);
    
    finAckPacket.sendPacket(out);
    System.out.println("Sent FIN-ACK packet");
    
    sequenceNumber++; // Increment after sending FIN
    
    // Wait for final ACK from client with timeout
    try {
        // Set a reasonable timeout for final ACK
        TCPPacket finalAckPacket = TCPPacket.receivePacket(in);
        if (finalAckPacket.getAckFlag() && finalAckPacket.getAckNumber() == sequenceNumber) {
            System.out.println("Received final ACK - Connection closed gracefully");
        } else {
            System.out.println("Received unexpected packet during close");
        }
    } catch (IOException e) {
        System.out.println("Client closed connection or timeout occurred");
    }
}
}