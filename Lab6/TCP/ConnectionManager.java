import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Random;

public class ConnectionManager {
    private Random random = new Random();
    private long sequenceNumber;
    private long ackNumber;
    private int serverWindowSize;
    
    public Socket connect() throws IOException {
        Socket socket = new Socket(Constants.SERVER_HOST, Constants.SERVER_PORT);
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        DataInputStream in = new DataInputStream(socket.getInputStream());
        
        performHandshake(in, out);
        return socket;
    }
    
    private void performHandshake(DataInputStream in, DataOutputStream out) throws IOException {
        sequenceNumber = random.nextInt(1000000);
        
        // Send SYN packet
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

        // Receive SYN-ACK packet
        System.out.println("[HANDSHAKE] Waiting for SYN-ACK packet...");
        Packet synAckPacket = Packet.receivePacket(in);

        System.out.println("[HANDSHAKE] Received packet - SYN: " + synAckPacket.getSynFlag() + 
                          ", ACK: " + synAckPacket.getAckFlag());
        System.out.println("[HANDSHAKE] Expected ACK: " + (sequenceNumber + 1) + 
                          ", Received ACK: " + synAckPacket.getAckNumber());

        if (!synAckPacket.getSynFlag() || !synAckPacket.getAckFlag()) {
            throw new IOException("Expected SYN-ACK packet but didn't receive one");
        }

        if (synAckPacket.getAckNumber() != sequenceNumber + 1) {
            throw new IOException("Received incorrect ACK number in SYN-ACK");
        }

        System.out.println("[HANDSHAKE] SYN-ACK packet received:");
        synAckPacket.printPacketInfo();

        // Update connection state
        sequenceNumber++;
        ackNumber = synAckPacket.getSequenceNumber() + 1;
        serverWindowSize = synAckPacket.getWindowSize();

        // Send ACK packet
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
    
    public void closeConnection(DataInputStream in, DataOutputStream out) throws IOException {
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
                System.out.println("[CLOSE] FIN-ACK received, connection closed gracefully");
            }
        } catch (IOException e) {
            System.out.println("[CLOSE] Connection closed (may not have received FIN-ACK)");
        }
    }
    
    // Getters for connection state
    public long getSequenceNumber() {
        return sequenceNumber;
    }
    
    public long getAckNumber() {
        return ackNumber;
    }
    
    public int getServerWindowSize() {
        return serverWindowSize;
    }
    
    public void incrementSequenceNumber() {
        sequenceNumber++;
    }
    
    public void setSequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }
    
    public void setAckNumber(long ackNumber) {
        this.ackNumber = ackNumber;
    }
}