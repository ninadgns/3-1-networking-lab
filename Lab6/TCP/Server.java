import java.io.*;
import java.net.*;

public class Server {

    public static void main(String[] args) {

        System.setOut(new PrintStream(System.out, true));
        Server server = new Server();
        server.start();

    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(Constants.SERVER_PORT)) {
            System.out.println("TCP Server listening on port " + Constants.SERVER_PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket.getInetAddress());

                new Thread(() -> handleClient(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private void handleClient(Socket clientSocket) {

        ClientConnectionHandler handler = new ClientConnectionHandler(clientSocket);
        handler.handleConnection();
    }
}