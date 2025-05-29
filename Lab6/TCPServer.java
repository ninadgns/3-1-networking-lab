import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class TCPServer {
    private static final int PORT = 5000;
    private static final int RECEIVE_WINDOW_SIZE = 65536;

    public static void main(String[] args) {
        try {

            ServerSocket serverSocket = new ServerSocket(PORT);
            System.out.println("The server started on port " + PORT);

            while (true) {
                try {

                    Socket clientSocket = serverSocket.accept();
                    System.out.println("Client connected: " + clientSocket.getInetAddress());

                    clientSocket.setReceiveBufferSize(RECEIVE_WINDOW_SIZE);
                    DataOutputStream dataOutputStream = new DataOutputStream(clientSocket.getOutputStream());
                    dataOutputStream.writeInt(RECEIVE_WINDOW_SIZE);

                    ClientHandler clientHandler = new ClientHandler(clientSocket);

                    clientHandler.start();
                } catch (IOException e) {
                    System.out.println("Error accepting client connection: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.out.println("Server error: " + e.getMessage());
        }
    }

    private static class ClientHandler extends Thread {
        private Socket clientSocket;
        private int totalBytesReceived = 0;

        public ClientHandler(Socket clientSocket) {
            this.clientSocket = clientSocket;
        }

        @Override
        public void run() {
            try {

                InputStream inputStream = clientSocket.getInputStream();
                OutputStream outputStream = clientSocket.getOutputStream();
                DataOutputStream dataOutputStream = new DataOutputStream(outputStream);

                byte[] buffer = new byte[RECEIVE_WINDOW_SIZE];
                int bytesRead;

                while ((bytesRead = inputStream.read(buffer, 0, RECEIVE_WINDOW_SIZE)) != -1) {
                    totalBytesReceived += bytesRead;
                    System.out.println("Received " + bytesRead + " bytes, Total: " + totalBytesReceived + " bytes");

                    dataOutputStream.writeUTF("ACK for " + totalBytesReceived + " bytes");
                    System.out.println(new String(buffer));
                    buffer = new byte[RECEIVE_WINDOW_SIZE];
                    dataOutputStream.writeInt(RECEIVE_WINDOW_SIZE);
                    dataOutputStream.flush();
                }

                dataOutputStream.close();
                inputStream.close();
                clientSocket.close();
                System.out.println("Client disconnected.");

            } catch (IOException e) {
                System.out.println("Error handling client: " + e.getMessage());
            }
        }
    }
}