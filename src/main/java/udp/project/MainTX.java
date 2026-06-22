package udp.project;

import udp.project.sender.Sender;

import java.util.Scanner;

public class MainTX {

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final String USAGE = "Usage: MainTX <host> <port> <filePath> [delayMs] [txId]";

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                interactive();
            } else if (args.length >= 3) {
                fromArgs(args);
            } else {
                usage();
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            usage();
        } catch (Exception e) {
            System.err.println("TX failed: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void fromArgs(String[] args) throws Exception {
        String host = args[0];
        int port = parsePort(args[1]);
        String filePath = args[2];
        int delayMs = args.length > 3 ? parseInt(args[3], "delayMs") : 0;
        Short txId = args.length > 4 ? parseTxId(args[4]) : null;

        try (Sender sender = new Sender(host, port, delayMs)) {
            sender.sendFile(filePath, txId);
        }
    }

    private static void interactive() throws Exception {
        Scanner sc = new Scanner(System.in);

        System.out.print("Enter host (empty = 127.0.0.1): ");
        String host = sc.nextLine().trim();
        if (host.isBlank()) host = DEFAULT_HOST;

        System.out.print("Enter port: ");
        int port = parsePort(sc.nextLine());

        System.out.print("Enter delay ms (0 = no delay): ");
        int delayMs = parseInt(sc.nextLine(), "delayMs");

        try (Sender sender = new Sender(host, port, delayMs)) {
            while (true) {
                System.out.print("Enter file path or 'exit': ");
                String filePath = sc.nextLine().trim();
                if (filePath.equalsIgnoreCase("exit")) return;
                if (filePath.isBlank()) continue;

                try {
                    sender.sendFile(filePath);
                } catch (Exception e) {
                    System.err.println("TX failed: " + e.getMessage());
                }
            }
        }
    }

    private static int parsePort(String value) {
        int port = parseInt(value, "port");
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("port must be 1..65535");
        return port;
    }

    private static Short parseTxId(String value) {
        int txId = parseInt(value, "txId");
        if (txId < 0 || txId > 65_535) throw new IllegalArgumentException("txId must be 0..65535");
        return (short) txId;
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    private static void usage() {
        System.err.println(USAGE);
        System.exit(1);
    }
}