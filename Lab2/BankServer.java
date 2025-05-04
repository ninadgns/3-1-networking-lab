import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Time;
import java.util.Arrays;
import java.util.Vector;

class UserAccountData {
  String cardNo;
  String pin;
  Double balance = 0.0;
  public long lastTransaction = -1;

  public UserAccountData(String cardNo, String pin, Double balance) {
    this.cardNo = cardNo;
    this.pin = pin;
    this.balance = balance;
  }
}

public class BankServer {
  static Vector<UserAccountData> userAccountDatas = new Vector<>();
  static Vector<String> transactionIDs = new Vector<>();

  public static void main(String[] args) throws IOException {
    BankServer.userAccountDatas.add(new UserAccountData("123123", "123", 500.0));
    BankServer.userAccountDatas.add(new UserAccountData("456456", "456", 1000.0));
    ServerSocket ss = new ServerSocket(5000);
    System.out.println("Server is connected at port no:" + ss.getLocalPort());
    System.out.println("Waiting for the client");
    Vector<ATMThread> atmThreads = new Vector<ATMThread>();
    while (true) {
      Socket s = ss.accept();
      System.out.println("Client Accepted on port" + s.getPort());
      var dataIn = new DataInputStream(s.getInputStream());
      var dataOut = new DataOutputStream(s.getOutputStream());
      ATMThread temp = new ATMThread(s, dataIn, dataOut);
      temp.start();
      atmThreads.add(temp);
    }
  }
}

class ATMThread extends Thread {
  Socket s;
  DataInputStream dataIn;
  DataOutputStream dataOut;

  ATMThread(Socket s, DataInputStream dataIn, DataOutputStream dataOut) {
    this.s = s;
    this.dataIn = dataIn;
    this.dataOut = dataOut;
  }

  @Override
  public void run() {
    super.run();
    try {
      while (true) {
        UserAccountData uad = null;
        var authData = dataIn.readUTF();
        String[] authDataSplitted = authData.split(":");
        for (UserAccountData uData : BankServer.userAccountDatas) {
          if (uData.cardNo.equals(authDataSplitted[1]) && uData.pin.equals(authDataSplitted[2])) {
            uad = uData;
          }
        }
        if (uad == null) {
          dataOut.writeUTF("AUTH_FAIL");
          continue;
        }
        dataOut.writeUTF("AUTH_OK");
        while (true) {
          var request = dataIn.readUTF();
          String[] requestData = request.split(":");
          System.out.println(requestData[0]);
          switch (requestData[0]) {
            case "BALANCE_REQ":
              dataOut.writeUTF("BALANCE_RES:" + uad.balance.toString());
              // System.out.println(dataIn.readUTF());
              if (dataIn.readUTF().equals("ACK"))
                System.out.println("Operation Successful");
              break;
            case "WITHDRAW":
              if (BankServer.transactionIDs.indexOf(requestData[2]) != -1) {
                dataOut.writeUTF("WITHDRAW_OK:" + uad.balance.toString());
              }
              if (uad.balance > Double.parseDouble(requestData[1])) {
                if ((Long.parseLong(requestData[2]) - uad.lastTransaction) < 10000) {
                  dataOut.writeUTF("TRY_AGAIN_LATER");
                  break;
                }
                uad.balance -= Double.parseDouble(requestData[1]);
                uad.lastTransaction = Long.parseLong(requestData[2]);
                dataOut.writeUTF("WITHDRAW_OK:" + uad.balance.toString());
              } else {
                dataOut.writeUTF("INSUFFICIENT_FUNDS");
              }
              if (dataIn.readUTF().equals("ACK"))
                System.out.println("Operation Successful");
              break;
            default:
              break;
          }

        }
      }

    } catch (Exception e) {
      System.out.println(e.toString());
    }
  }

}