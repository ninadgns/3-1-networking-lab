import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Vector;

public class Server {
    public static Vector<String> fileNames = new Vector<>();

    static void populateFileNames() throws IOException {
        String directoryPath = "./files";
        Path directory = Paths.get(directoryPath);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path file : stream) {
                fileNames.add(file.getFileName().toString());
            }
        }

    }

    public static void main(String[] args) throws IOException {
        populateFileNames();
        ServerSocket ss = new ServerSocket(1);
        while (true) {
            new ClientHandler(ss.accept()).start();
        }
    }
}

class ClientHandler extends Thread {
    Socket s;

    ClientHandler(Socket s) {
        this.s = s;
    }

    @Override
    public void run() {
        super.run();
        System.out.println("Client Connected on port"+s.getPort());
        try {
            DataInputStream dataIn = new DataInputStream(s.getInputStream());
            DataOutputStream dataOut = new DataOutputStream(s.getOutputStream());
            if (dataIn.readUTF().equals("list")) {
                System.out.println("User prompted for list. Sending file list");
                dataOut.writeUTF(Server.fileNames.size() + " files available:");
                dataOut.writeUTF(
                        String.join("\n", Server.fileNames));
                String fileName = dataIn.readUTF();
                System.out.println("User requesting file "+fileName);
                if (!Server.fileNames.contains(fileName)) {
                    System.out.println("File not found");
                    dataOut.writeUTF("NOT_FOUND");
                    s.close();
                    return;
                }
                Path path = Paths.get("./files/" + fileName);
                byte[] arr = Files.readAllBytes(path);

                dataOut.writeUTF(String.valueOf(arr.length));
                dataOut.write(arr   );
                
                // for (byte b : arr) {
                //     dataOut.writeByte(b);
                // }
                System.out.println("Sent " + fileName);
            }
            s.close();
            return;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}