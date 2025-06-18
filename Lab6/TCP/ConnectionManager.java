import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

public class ConnectionManager {
    private final Random random = new Random();
    private long sequenceNumber;
    private long ackNumber;
    private int serverWindowSize;
    private long baseSequenceNumber;
    
    public void performHandshake(DataInputStream in, DataOutputStream out) throws IOException {
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
    
    public void closeConnection(DataInputStream in, DataOutputStream out) throws IOException {
        // Connection close implementation would go here
        // This method should contain the existing closeConnection logic
    }
    
    // Getters and setters
    public long getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(long sequenceNumber) { this.sequenceNumber = sequenceNumber; }
    public long getAckNumber() { return ackNumber; }
    public void setAckNumber(long ackNumber) { this.ackNumber = ackNumber; }
    public int getServerWindowSize() { return serverWindowSize; }
    public void setServerWindowSize(int serverWindowSize) { this.serverWindowSize = serverWindowSize; }
    public long getBaseSequenceNumber() { return baseSequenceNumber; }
    public void setBaseSequenceNumber(long baseSequenceNumber) { this.baseSequenceNumber = baseSequenceNumber; }
    public void incrementSequenceNumber(int amount) { this.sequenceNumber += amount; }
}