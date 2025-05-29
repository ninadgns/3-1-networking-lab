import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class server {

    public static void main(String[] args) throws Exception {
        DatagramSocket serverSocket = new DatagramSocket(1);
        byte[] receiveBuffer = new byte[4096];

        while (true) {
            DatagramPacket datagramPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            serverSocket.receive(datagramPacket);

            String data = new String(datagramPacket.getData(), 0, datagramPacket.getLength());
            System.out.println(data);

            String ack = "ACK: " + data;
            byte[] ackData = ack.getBytes();
            DatagramPacket ackPacket = new DatagramPacket(ackData, ackData.length, datagramPacket.getAddress(), datagramPacket.getPort());
            serverSocket.send(ackPacket);


        }
    }
}