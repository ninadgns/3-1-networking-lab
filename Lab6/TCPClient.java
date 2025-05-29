import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Scanner;

public class TCPClient {
    private static final String SERVER_ADDRESS = "127.0.0.1";
    private static final int SERVER_PORT = 5000;

    public static void main(String[] args) {
        Socket socket = null;

        try {

            System.out.println("Server is connected at port no: " + SERVER_PORT);
            System.out.println("Server is connecting");

            socket = new Socket(SERVER_ADDRESS, SERVER_PORT);

            System.out.println("Connected to server!");

            InputStream inputStream = socket.getInputStream();
            OutputStream outputStream = socket.getOutputStream();
            DataInputStream dataInputStream = new DataInputStream(inputStream);
            int SERVER_RECEIVE_WINDOW_SIZE = dataInputStream.readInt();
            Scanner scanner = new Scanner(System.in);
            System.out.print("Enter filename to send: ");
            String filename = scanner.nextLine();

            File file = new File(filename);
            if (!file.exists()) {
                System.out.println("File not found: " + filename);
                socket.close();
                return;
            }

            FileInputStream fileInputStream = new FileInputStream(file);
            byte[] buffer = new byte[SERVER_RECEIVE_WINDOW_SIZE];
            int bytesRead;
            int totalBytesSent = 0;

            System.out.println("Sending file: " + filename);
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {

                outputStream.write(buffer, 0, bytesRead);
                outputStream.flush();
                totalBytesSent += bytesRead;

                String ack = dataInputStream.readUTF();
                System.out.println("Received Acknowledgment: " + ack);
                buffer = new byte[dataInputStream.readInt()];
                System.out.println(buffer.length);
            }

            System.out.println("File sent successfully");
            fileInputStream.close();
            scanner.close();

        } catch (IOException e) {
            System.out.println("Client error: " + e.getMessage());
        } finally {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                System.out.println("Error closing socket: " + e.getMessage());
            }
        }
    }
}