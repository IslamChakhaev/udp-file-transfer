package udp.project;

import udp.project.sender.Sender;

import java.util.Scanner;

public class MainTX {

    public static void main(String[] args) throws Exception {
        if (args.length >= 3) {
            String host = args[0];
            int port = Integer.parseInt(args[1]);
            String filePath = args[2];
            int delayMs = args.length >= 4 ? Integer.parseInt(args[3]) : 0;
            Short txId = args.length >= 5 ? (short) Integer.parseInt(args[4]) : null;

            Sender sender = new Sender(host, port, delayMs);
            try {
                sender.sendFile(filePath, txId);
            } finally {
                sender.close();
            }
            return;
        }

        Scanner scanner = new Scanner(System.in);

        System.out.print("Enter host (for local 127.0.0.1): ");
        String host = scanner.nextLine().trim();
        if (host.isBlank()) {
            host = "127.0.0.1";
        }

        System.out.print("Enter port: ");
        int port = Integer.parseInt(scanner.nextLine().trim());

        System.out.print("Enter delay (ms, 0 = no delay): ");
        int delayMs = Integer.parseInt(scanner.nextLine().trim());

        Sender sender = new Sender(host, port, delayMs);

        try {
            while (true) {
                System.out.print("Enter file path or 'exit': ");
                String filePath = scanner.nextLine().trim();

                if (filePath.equalsIgnoreCase("exit")) {
                    break;
                }

                try {
                    sender.sendFile(filePath);
                } catch (Exception e) {
                    System.out.println("TX failed: " + e.getMessage());
                }
            }
        } finally {
            sender.close();
        }
    }
}
