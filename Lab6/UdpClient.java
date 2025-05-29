import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class UdpClient {

    public static void main(String[] args) throws Exception {
        DatagramSocket clientSocket = new DatagramSocket();
        InetAddress serverAddress = InetAddress.getByName("localhost");

        clientSocket.setSoTimeout(1000);

        String[] messages = { "Hello", "This", "is", "UDP", "Flow", "Control" };

        for (String message : messages) {
            boolean ackReceived = false;
            byte[] sendData = message.getBytes();
            while (!ackReceived) {
                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, serverAddress, 1);
                clientSocket.send(sendPacket);

                byte[] ackBuffer = new byte[1024];
                DatagramPacket ackPacket = new DatagramPacket(ackBuffer, ackBuffer.length);
                try {
                    clientSocket.receive(ackPacket);
                    String ack = new String(ackPacket.getData(), 0, ackPacket.getLength());

                    if (ack.contains(message)) {
                        System.out.println("ACK Received for: " + message);
                        ackReceived = true;
                    }

                } catch (Exception e) {
                    System.out.println("oops vul hoye geche: " + e.getMessage());
                }
            }
            Thread.sleep(100);
        }
        clientSocket.close();
    }
    
}