import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;

public class FastRetransmitHandler {
    private long lastAckReceived = -1;
    private int duplicateAckCount = 0;
    private static final int FAST_RETRANSMIT_THRESHOLD = 3;

    public boolean checkForFastRetransmit(long ackNum) {
        if (ackNum == lastAckReceived) {
            duplicateAckCount++;
            System.out.println("Duplicate ACK received (" + duplicateAckCount + "/3) for seq: " + ackNum);

            if (duplicateAckCount >= FAST_RETRANSMIT_THRESHOLD) {
                System.out.println("*** FAST RETRANSMIT *** Triple duplicate ACK detected for seq: " + ackNum);
                duplicateAckCount = 0; // Reset counter
                return true;
            }
        } else {
            // New ACK received
            if (ackNum > lastAckReceived) {
                duplicateAckCount = 0; // Reset duplicate count
                lastAckReceived = ackNum;
            }
        }
        return false;
    }

    public void triggerFastRetransmit(long ackNum, Map<Long, UnackedPacket> unackedPackets,
            PacketLossSimulator lossSimulator, DataOutputStream out) {
        // Find the first unacked packet after ackNum
        for (Map.Entry<Long, UnackedPacket> entry : unackedPackets.entrySet()) {
            long seqNum = entry.getKey();
            if (seqNum >= ackNum) {
                System.out.println("Fast retransmitting packet with seq: " + seqNum);
                retransmitPacketImmediately(seqNum, unackedPackets, lossSimulator, out);
                break; // Only retransmit the first missing packet
            }
        }
    }

    private void retransmitPacketImmediately(long seqNum, Map<Long, UnackedPacket> unackedPackets,
            PacketLossSimulator lossSimulator, DataOutputStream out) {
        UnackedPacket unackedPacket = unackedPackets.get(seqNum);
        if (unackedPacket == null) {
            return;
        }

        try {
            unackedPacket.retryCount++;
            unackedPacket.timestamp = System.currentTimeMillis();

            if (lossSimulator.shouldDropPacket()) {
                lossSimulator.incrementDroppedPackets();
                System.out.println("*** FAST RETRANSMISSION DROPPED *** seq: " + seqNum);
                return;
            }

            unackedPacket.packet.sendPacket(out);
            System.out.println("Fast retransmitted packet (seq: " + seqNum + ")");

        } catch (IOException e) {
            System.err.println("Error in fast retransmit: " + e.getMessage());
        }
    }

    public long getLastAckReceived() {
        return lastAckReceived;
    }
}