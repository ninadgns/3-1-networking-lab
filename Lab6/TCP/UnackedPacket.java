/**
 * Class to track unacknowledged packets for reliable data transfer
 */
public class UnackedPacket {
    Packet packet;
    long timestamp;
    long sendTime;
    int retryCount;

    UnackedPacket(Packet packet) {
        this.packet = packet;
        this.timestamp = System.currentTimeMillis();
        this.sendTime = System.currentTimeMillis();
        this.retryCount = 0;
    }
}
