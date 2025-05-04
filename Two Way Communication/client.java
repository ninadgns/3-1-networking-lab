import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Scanner;

public class client {

    public static String ClientName = null;
    public static Scanner scanner = new Scanner(System.in);

    public static void main(String[] args) {
        try {
            Socket socket = new Socket("localhost", 5000);
            System.out.println("Connected to server at port " + socket.getPort());
            DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());
            ClientName = scanner.nextLine();
            dataOut.writeUTF(ClientName);
            DataInputStream dataIn = new DataInputStream(socket.getInputStream());

            receivingThread rt = new receivingThread(socket, dataIn);
            rt.start();

            System.out.println("Enter the message to send. Enter 'exit' to go back");
            while (true) {
                String message = scanner.nextLine();
                if (message.equals("exit")) {
                    System.out.println("Exiting...");
                    socket.close();
                    return;
                }
                dataOut.writeUTF(message);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

class receivingThread extends Thread {
    Socket socket;
    DataInputStream dataIn;

    receivingThread(Socket socket, DataInputStream dataIn) {
        this.socket = socket;
        this.dataIn = dataIn;
    }

    @Override
    public void run() {
        super.run();
        try {
            while (true) {
                String message = dataIn.readUTF();
                if (message.equals("exit")) {
                    System.out.println("Exiting...");
                    socket.close();
                    break;
                } else {
                    System.out.println("Received message: " + message);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}