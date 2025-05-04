import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Scanner;
import java.util.Vector;

public class server {
    public static Vector<chatClient> chatClients = new Vector<>();
    public static Scanner scanner = new Scanner(System.in);

    public static void main(String[] args) throws IOException, InterruptedException {
        ServerThread serverThread = new ServerThread();
        serverThread.start();
        System.out.println("Server started on port 5000");
        while (true) {
            if (chatClients.size() == 0) {
                System.out.println("No clients connected. Waiting for clients...");
                Thread.sleep(5000);
                continue;
            }
            System.out.println("Enter the client number to send a message (or '-1' to quit): ");
            new Thread(() -> {
                int prevClientSize = chatClients.size();
                for (int i = 0; i < chatClients.size(); i++) {
                    System.out.println("Client " + i + ": " + chatClients.get(i).clientName);
                }
                while (true) {
                    while (prevClientSize == chatClients.size()) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    for (int i = prevClientSize; i < chatClients.size(); i++) {
                        System.out.println("Client " + i + ": " + chatClients.get(i).clientName);
                    }
                    // Update the previous client size to the current size
                    prevClientSize = chatClients.size();
                }

            }).start();
            int clientNumber = Integer.parseInt(scanner.nextLine());
            if (clientNumber == -1) {
                System.out.println("Exiting...");
                break;
            }

            new Thread(() -> {
                System.out.println("Messages of client " + clientNumber + ": ");
                int prevMsgSize = chatClients.get(clientNumber).messages.size();
                for (Message message : chatClients.get(clientNumber).messages) {
                    if (message.sent) {
                        System.out.println("You: " + message.message);
                    } else {
                        System.out.println(chatClients.get(clientNumber).clientName + ": " + message.message);
                    }
                }
                while (true) {
                    while (prevMsgSize == chatClients.get(clientNumber).messages.size()) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    for (int i = prevMsgSize; i < chatClients.get(clientNumber).messages.size(); i++) {
                        Message message = chatClients.get(clientNumber).messages.get(i);
                        if (!message.sent) {
                            System.out.println(chatClients.get(clientNumber).clientName + ": " + message.message);
                        }
                    }
                    // Update the previous message size to the current size
                    prevMsgSize = chatClients.get(clientNumber).messages.size();

                }
            }).start();
            System.out.println("Chatting with " + chatClients.get(clientNumber).clientName + "...");
            System.out.println("Type 'exit' to stop chatting with this client.");
            while (true) {
                String message = scanner.nextLine();
                if (message.equals("exit")) {
                    System.out.println("Exiting...");
                    break;
                }
                if (clientNumber >= 0 && clientNumber < chatClients.size()) {
                    try {
                        DataOutputStream dataOut = new DataOutputStream(
                                chatClients.get(clientNumber).socket.getOutputStream());
                        dataOut.writeUTF(message);
                        chatClients.get(clientNumber).messages.add(new Message(true, message));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    System.out.println("Invalid client number. Please try again.");
                }
            }
        }
    }

}

class ServerThread extends Thread {

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(5000)) {
            System.out.println("Server started on port 5000");
            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("Client connected: " + socket.getInetAddress().getHostAddress());
                chatClient client = new chatClient(socket);
                client.start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}

class chatClient extends Thread {
    Socket socket = null;
    Vector<Message> messages = new Vector<>();
    String clientName = null;

    chatClient(Socket socket) {
        this.socket = socket;
    }

    public void run() {
        try {
            var dataIn = new DataInputStream(socket.getInputStream());
            String clientName = dataIn.readUTF();
            this.clientName = clientName;
            server.chatClients.add(this);
            while (true) {
                messages.add(new Message(false, dataIn.readUTF()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}

class Message {
    Boolean sent;
    String time;
    String message;

    Message(Boolean sent, String message) {
        this.sent = sent;
        this.time = String.valueOf(System.currentTimeMillis());
        this.message = message;
    }
}