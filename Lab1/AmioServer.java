package Lab1;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class AmioServer {

    static boolean palindromeChecker(String number) {
        for (int i = 0; i < number.length() / 2; i++) {
            if (number.charAt(i) != number.charAt(number.length() - 1 - i)) {
                return false;
            }

        }
        return true;
    }

    static boolean primeChecker(String number) {
        int a = Integer.parseInt(number);
        for (int i = 2; i * i <= a; i++) {
            if (a % i == 0) {
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        ServerSocket ss = new ServerSocket(5000);
        System.out.println("Server is connected at port no:" + ss.getLocalPort() + "\nWaiting for the client");
        Socket s = ss.accept();
        System.out.println("Client request is accepted at port no: " + s.getPort());
        DataInputStream dataInputStream = new DataInputStream(s.getInputStream());
        DataOutputStream dataOutputStream = new DataOutputStream(s.getOutputStream());
        while (true) {
            String op = dataInputStream.readUTF();
            if (op.equals("1")) {
                String str = dataInputStream.readUTF();
                System.out.println(str);
                dataOutputStream.writeUTF(str.toLowerCase());
            } else if (op.equals("2")) {
                String number = dataInputStream.readUTF();
                String operation = dataInputStream.readUTF();
                System.out.println(number);
                System.out.println(operation);
                if (operation.equals("prime")) {
                    if (primeChecker(number))
                        dataOutputStream.writeUTF("shabbash, prime number");
                    else
                        dataOutputStream.writeUTF("hehe, prime na");
                }
                else if (operation.equals("palindrome")) {
                    if (palindromeChecker(number))
                        dataOutputStream.writeUTF("shabbash, palindrome");
                    else
                        dataOutputStream.writeUTF("hehe, palindrome na");
                }
                else {
                    dataOutputStream.writeUTF("Please enter valid operation");
                }
            } else {
                ss.close();
                break;
            }
        }

    }
}