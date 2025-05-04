package Lab1;
import java.io.*;
import java.net.*;
import java.util.Arrays;

public class Server {
    public static void main(String[] args) throws IOException {
        ServerSocket ss = new ServerSocket(5000);
        System.out.println("Server is connected at port no:" + ss.getLocalPort());
        System.out.println("Waiting for the client");
        Socket s = ss.accept();
        System.out.println("Client request is accepted at port no: " + s.getPort());
        DataInputStream input = new DataInputStream(s.getInputStream());
        byte[] byteArray = new byte[6000];
        int i=0;
        try {
            for (i = 0; byteArray[i]!=-1; i++) {
                byteArray[i] = input.readByte();
            }
        } catch (Exception e) {
            System.out.println("file shesh");
        }
        finally{
            try(FileOutputStream hehe = new FileOutputStream("output.png")){
                hehe.write(Arrays.copyOfRange(byteArray, 0, i));
                hehe.close();
            }

        }
}
}