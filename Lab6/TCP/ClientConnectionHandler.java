import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Handles individual client connections for the TCP server
 */
public class ClientConnectionHandler {
    private final Socket clientSocket;
    private final Random random = new Random();
    private final int clientId;
    private final String outputFile;
    private static int clientCounter = 0;

    private long sequenceNumber;
    private long expectedSeqNumber;
    private ByteArrayOutputStream receivedData;
    private int clientWindowSize;

    private Map<Long, byte[]> outOfOrderBuffer = new TreeMap<>();
    private Set<Long> acknowledgedSequences = new HashSet<>();

    public ClientConnectionHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
        this.clientId = ++clientCounter;
        this.outputFile = Constants.OUTPUT_FILE_PREFIX + clientId + ".txt";
        this.receivedData = new ByteArrayOutputStream();
    }

    public void handleConnection() {
        try (DataInputStream in = new DataInputStream(clientSocket.getInputStream());
                DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())) {

            System.out.println("Handling client " + clientId);

            performHandshake(in, out);

            receiveFile(in, out);

            handleConnectionClose(in, out);

        } catch (IOException e) {
            System.err.println("Error handling client " + clientId + ": " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
                System.out.println("Client " + clientId + " disconnected");
            } catch (IOException e) {
                System.err.println("Error closing client socket: " + e.getMessage());
            }
        }
    }

    private void performHandshake(DataInputStream in, DataOutputStream out) throws IOException {

        Packet synPacket = Packet.receivePacket(in);

        if (!synPacket.getSynFlag()) {
            System.err.println("Expected SYN packet but didn't receive one");
            return;
        }

        System.out.println("Client " + clientId + " - Received SYN packet:");
        synPacket.printPacketInfo();

        sequenceNumber = random.nextInt(1000000);
        expectedSeqNumber = synPacket.getSequenceNumber() + 1;
        clientWindowSize = synPacket.getWindowSize();

        Packet synAckPacket = new Packet();
        synAckPacket.setSourcePort(Constants.SERVER_PORT);
        synAckPacket.setDestinationPort(synPacket.getSourcePort());
        synAckPacket.setSequenceNumber(sequenceNumber);
        synAckPacket.setAckNumber(expectedSeqNumber);
        synAckPacket.setSynFlag(true);
        synAckPacket.setAckFlag(true);
        synAckPacket.setWindowSize(Constants.WINDOW_SIZE);

        synAckPacket.sendPacket(out);
        System.out.println("Client " + clientId + " - Sent SYN-ACK packet:");
        synAckPacket.printPacketInfo();

        sequenceNumber++;

        Packet ackPacket = Packet.receivePacket(in);

        if (!ackPacket.getAckFlag() || ackPacket.getSynFlag()) {
            System.err.println("Expected ACK packet but didn't receive one");
            return;
        }

        if (ackPacket.getAckNumber() != sequenceNumber) {
            System.err.println("Received incorrect ACK number");
            return;
        }

        System.out.println("Client " + clientId + " - Received ACK packet:");
        ackPacket.printPacketInfo();

        System.out.println("Client " + clientId + " - Connection established!");
        System.out.println("Client window size: " + clientWindowSize);
        System.out.println("Server window size: " + Constants.WINDOW_SIZE);
    }

    private void saveReceivedFile() {
        try {
            byte[] fileData = receivedData.toByteArray();
            Files.write(Paths.get(outputFile), fileData);
            System.out.println(
                    "Client " + clientId + " - File saved as: " + outputFile + " (" + fileData.length + " bytes)");

            String content = new String(fileData);
            System.out.println("File content preview:");
            System.out.println(content.substring(0, Math.min(100, content.length())) +
                    (content.length() > 100 ? "..." : ""));

        } catch (IOException e) {
            System.err.println("Error saving file: " + e.getMessage());
        }
    }

    private void handleConnectionClose(DataInputStream in, DataOutputStream out) throws IOException {

    }

    private void receiveFile(DataInputStream in, DataOutputStream out) throws IOException {
        System.out.println("Client " + clientId + " - Starting file reception...");
        int totalBytesReceived = 0;
        int packetsReceived = 0;

        while (true) {
            try {
                Packet dataPacket = Packet.receivePacket(in);

                if (dataPacket.getFinFlag()) {
                    System.out.println("Client " + clientId + " - Received FIN packet - file transfer completed");

                    processOutOfOrderPackets();

                    saveReceivedFile();

                    handleFinPacket(dataPacket, in, out);
                    return;
                }

                byte[] payload = dataPacket.getPayload();
                if (payload.length == 0) {
                    continue;
                }

                packetsReceived++;
                long packetSeqNum = dataPacket.getSequenceNumber();

                if (acknowledgedSequences.contains(packetSeqNum)) {
                    System.out
                            .println("Client " + clientId + " - Received duplicate packet (seq: " + packetSeqNum + ")");

                    sendCumulativeAck(out, dataPacket.getSourcePort());
                    continue;
                }

                if (packetSeqNum == expectedSeqNumber) {

                    receivedData.write(payload);
                    totalBytesReceived += payload.length;
                    expectedSeqNumber += payload.length;
                    acknowledgedSequences.add(packetSeqNum);

                    System.out.println("Client " + clientId + " - Received in-order packet " + packetsReceived +
                            " (seq: " + packetSeqNum + ", " + payload.length + " bytes) - Total: " + totalBytesReceived
                            + " bytes");

                    processOutOfOrderPackets();

                    sendCumulativeAck(out, dataPacket.getSourcePort());

                } else if (packetSeqNum < expectedSeqNumber) {

                    System.out.println("Client " + clientId + " - Received old packet (seq: " + packetSeqNum +
                            ", expected: " + expectedSeqNumber + ")");

                    sendCumulativeAck(out, dataPacket.getSourcePort());

                } else {

                    System.out.println("Client " + clientId + " - Received out-of-order packet (seq: " + packetSeqNum +
                            ", expected: " + expectedSeqNumber + ") - buffering");

                    if (outOfOrderBuffer.size() < Constants.BUFFER_SIZE / Constants.MAX_SEGMENT_SIZE) {
                        outOfOrderBuffer.put(packetSeqNum, payload);
                        acknowledgedSequences.add(packetSeqNum);
                    } else {
                        System.out.println("Out-of-order buffer full, dropping packet");
                    }

                    sendCumulativeAck(out, dataPacket.getSourcePort());
                }

                Thread.sleep(5);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("Client " + clientId + " - File reception completed:");
        System.out.println("Total packets received: " + packetsReceived);
        System.out.println("Total bytes received: " + totalBytesReceived);
    }

    private void sendCumulativeAck(DataOutputStream out, int clientPort) throws IOException {
        Packet ackPacket = new Packet();
        ackPacket.setSourcePort(Constants.SERVER_PORT);
        ackPacket.setDestinationPort(clientPort);
        ackPacket.setSequenceNumber(sequenceNumber);
        ackPacket.setAckNumber(expectedSeqNumber);
        ackPacket.setAckFlag(true);
        ackPacket.setWindowSize(Constants.WINDOW_SIZE);

        ackPacket.sendPacket(out);
        System.out.println("Client " + clientId + " - Sent cumulative ACK for sequence: " + expectedSeqNumber);
    }

    private void processOutOfOrderPackets() throws IOException {

        while (outOfOrderBuffer.containsKey(expectedSeqNumber)) {
            byte[] payload = outOfOrderBuffer.remove(expectedSeqNumber);
            receivedData.write(payload);
            System.out.println("Client " + clientId + " - Processed buffered packet (seq: " + expectedSeqNumber +
                    ", " + payload.length + " bytes)");
            expectedSeqNumber += payload.length;
        }
    }

    private void handleFinPacket(Packet finPacket, DataInputStream in, DataOutputStream out) throws IOException {
        System.out.println("Client " + clientId + " - Handling connection close...");

        long finAckNumber = finPacket.getSequenceNumber() + 1;

        Packet finAckPacket = new Packet();
        finAckPacket.setSourcePort(Constants.SERVER_PORT);
        finAckPacket.setDestinationPort(finPacket.getSourcePort());
        finAckPacket.setSequenceNumber(sequenceNumber);
        finAckPacket.setAckNumber(finAckNumber);
        finAckPacket.setFinFlag(true);
        finAckPacket.setAckFlag(true);
        finAckPacket.setWindowSize(Constants.WINDOW_SIZE);

        finAckPacket.sendPacket(out);
        System.out.println("Client " + clientId + " - Sent FIN-ACK packet");

        sequenceNumber++;

        try {
            Packet finalAckPacket = Packet.receivePacket(in);
            if (finalAckPacket.getAckFlag() && finalAckPacket.getAckNumber() == sequenceNumber) {
                System.out.println("Client " + clientId + " - Received final ACK - Connection closed gracefully");
            } else {
                System.out.println("Client " + clientId + " - Received unexpected packet during close");
            }
        } catch (IOException e) {
            System.out.println("Client " + clientId + " - Client closed connection or timeout occurred");
        }
    }
}
