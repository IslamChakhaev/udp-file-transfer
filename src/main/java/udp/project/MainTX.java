package udp.project;

import udp.project.sender.Sender;

public class MainTX {

    public static void main(String[] args) {

        String host = "localhost";

        int port = 9000;

        String filePath = "test.txt";

        if (args.length > 0) {
            filePath = args[0];
        }

        try {
            Sender sender = new Sender(host, port);
            sender.sendFile(filePath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}