import java.util.Random;

public class PacketLossSimulator {
    private final Random random = new Random();
    private static final double PACKET_LOSS_RATE = 0.2;
    private int totalPacketsSent = 0;
    private int packetsDropped = 0;
    
    public boolean shouldDropPacket() {
        return random.nextDouble() < PACKET_LOSS_RATE;
    }
    
    public void incrementTotalPackets() {
        totalPacketsSent++;
    }
    
    public void incrementDroppedPackets() {
        packetsDropped++;
    }
    
    public int getTotalPacketsSent() {
        return totalPacketsSent;
    }
    
    public int getPacketsDropped() {
        return packetsDropped;
    }
}