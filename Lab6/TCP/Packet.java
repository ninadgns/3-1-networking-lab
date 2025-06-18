import java.io.*;

public class Packet {
    private byte[] packet;

    private static final int MIN_HEADER_SIZE = 20;

    public Packet() {
        this.packet = new byte[MIN_HEADER_SIZE];

        setHeaderLength(5);
    }

    public Packet(int size) {
        this.packet = new byte[size];
    }

    public Packet(byte[] packet) {
        this.packet = packet;
    }

    public int getSourcePort() {
        return ((packet[0] & 0xFF) << 8) | (packet[1] & 0xFF);
    }

    public void setSourcePort(int port) {
        packet[0] = (byte) ((port >> 8) & 0xFF);
        packet[1] = (byte) (port & 0xFF);
    }

    public int getDestinationPort() {
        return ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
    }

    public void setDestinationPort(int port) {
        packet[2] = (byte) ((port >> 8) & 0xFF);
        packet[3] = (byte) (port & 0xFF);
    }

    public long getSequenceNumber() {
        return ((long) (packet[4] & 0xFF) << 24) |
                ((long) (packet[5] & 0xFF) << 16) |
                ((long) (packet[6] & 0xFF) << 8) |
                (packet[7] & 0xFF);
    }

    public void setSequenceNumber(long seqNum) {
        packet[4] = (byte) ((seqNum >> 24) & 0xFF);
        packet[5] = (byte) ((seqNum >> 16) & 0xFF);
        packet[6] = (byte) ((seqNum >> 8) & 0xFF);
        packet[7] = (byte) (seqNum & 0xFF);
    }

    public long getAckNumber() {
        return ((long) (packet[8] & 0xFF) << 24) |
                ((long) (packet[9] & 0xFF) << 16) |
                ((long) (packet[10] & 0xFF) << 8) |
                (packet[11] & 0xFF);
    }

    public void setAckNumber(long ackNum) {
        packet[8] = (byte) ((ackNum >> 24) & 0xFF);
        packet[9] = (byte) ((ackNum >> 16) & 0xFF);
        packet[10] = (byte) ((ackNum >> 8) & 0xFF);
        packet[11] = (byte) (ackNum & 0xFF);
    }

    public int getHeaderLength() {
        return (packet[12] & 0xF0) >> 4;
    }

    public void setHeaderLength(int length) {
        packet[12] = (byte) ((packet[12] & 0x0F) | ((length & 0x0F) << 4));
    }

    public boolean getUrgFlag() {
        return (packet[13] & 0x20) != 0;
    }

    public void setUrgFlag(boolean flag) {
        if (flag) {
            packet[13] |= 0x20;
        } else {
            packet[13] &= ~0x20;
        }
    }

    public boolean getAckFlag() {
        return (packet[13] & 0x10) != 0;
    }

    public void setAckFlag(boolean flag) {
        if (flag) {
            packet[13] |= 0x10;
        } else {
            packet[13] &= ~0x10;
        }
    }

    public boolean getPshFlag() {
        return (packet[13] & 0x08) != 0;
    }

    public void setPshFlag(boolean flag) {
        if (flag) {
            packet[13] |= 0x08;
        } else {
            packet[13] &= ~0x08;
        }
    }

    public boolean getRstFlag() {
        return (packet[13] & 0x04) != 0;
    }

    public void setRstFlag(boolean flag) {
        if (flag) {
            packet[13] |= 0x04;
        } else {
            packet[13] &= ~0x04;
        }
    }

    public boolean getSynFlag() {
        return (packet[13] & 0x02) != 0;
    }

    public void setSynFlag(boolean flag) {
        if (flag) {
            packet[13] |= 0x02;
        } else {
            packet[13] &= ~0x02;
        }
    }

    public boolean getFinFlag() {
        return (packet[13] & 0x01) != 0;
    }

    public void setFinFlag(boolean flag) {
        if (flag) {
            packet[13] |= 0x01;
        } else {
            packet[13] &= ~0x01;
        }
    }

    public int getWindowSize() {
        return ((packet[14] & 0xFF) << 8) | (packet[15] & 0xFF);
    }

    public void setWindowSize(int windowSize) {
        packet[14] = (byte) ((windowSize >> 8) & 0xFF);
        packet[15] = (byte) (windowSize & 0xFF);
    }

    public int getChecksum() {
        return ((packet[16] & 0xFF) << 8) | (packet[17] & 0xFF);
    }

    public void setChecksum(int checksum) {
        packet[16] = (byte) ((checksum >> 8) & 0xFF);
        packet[17] = (byte) (checksum & 0xFF);
    }

    public int getUrgentPointer() {
        return ((packet[18] & 0xFF) << 8) | (packet[19] & 0xFF);
    }

    public void setUrgentPointer(int urgentPointer) {
        packet[18] = (byte) ((urgentPointer >> 8) & 0xFF);
        packet[19] = (byte) (urgentPointer & 0xFF);
    }

    public byte[] getPayload() {
        int headerSize = getHeaderLength() * 4;
        if (headerSize >= packet.length) {
            return new byte[0];
        }
        byte[] payload = new byte[packet.length - headerSize];
        System.arraycopy(packet, headerSize, payload, 0, payload.length);
        return payload;
    }

    public void setPayload(byte[] payload) {
        int headerSize = getHeaderLength() * 4;
        if (headerSize + payload.length > packet.length) {

            byte[] newPacket = new byte[headerSize + payload.length];
            System.arraycopy(packet, 0, newPacket, 0, headerSize);
            packet = newPacket;
        }
        System.arraycopy(payload, 0, packet, headerSize, payload.length);
    }

    public byte[] getPacket() {
        return packet;
    }

    public void setPacket(byte[] packet) {
        this.packet = packet;
    }

    public int getPacketLength() {
        return packet.length;
    }

    public void sendPacket(DataOutputStream out) throws IOException {
        byte[] packetData = this.getPacket();
        out.writeInt(packetData.length);
        out.write(packetData);
        out.flush();
    }

    public static Packet receivePacket(DataInputStream in) throws IOException {
        int length = in.readInt();
        byte[] packetData = new byte[length];
        in.readFully(packetData);
        return new Packet(packetData);
    }

    public void printPacketInfo() {
        System.out.println("  Source Port: " + getSourcePort());
        System.out.println("  Destination Port: " + getDestinationPort());
        System.out.println("  Sequence Number: " + getSequenceNumber());
        System.out.println("  ACK Number: " + getAckNumber());
        System.out.println("  Window Size: " + getWindowSize());
        System.out.println("  Flags: SYN=" + getSynFlag() +
                " ACK=" + getAckFlag() +
                " FIN=" + getFinFlag());
        System.out.println();
        System.out.flush();
    }

}