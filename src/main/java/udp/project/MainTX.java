package udp.project;

import udp.project.sender.Sender;

import java.util.Scanner;

public class MainTX {

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_DELAY_MS = 0;
    private static final String USAGE = "Usage: MainTX <host> <port> <filePath> [delayMs]";

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                runInteractive();
            } else if (args.length == 3 || args.length == 4) {
                runFromArgs(args);
            } else {
                throw new IllegalArgumentException(USAGE);
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("TX failed: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void runFromArgs(String[] args) throws Exception {
        String host = args[0];
        int port = parsePort(args[1]);
        String filePath = args[2];
        int delayMs = args.length == 4
                ? parseNonNegativeInt(args[3], "delayMs")
                : DEFAULT_DELAY_MS;

        try (Sender sender = new Sender(host, port, delayMs)) {
            sender.sendFile(filePath);
        }
    }

    private static void runInteractive() throws Exception {
        Scanner scanner = new Scanner(System.in);

        String host = readHost(scanner);
        int port = readPort(scanner);
        int delayMs = readDelay(scanner);

        try (Sender sender = new Sender(host, port, delayMs)) {
            while (true) {
                System.out.print("Enter file path or 'exit': ");
                String filePath = scanner.nextLine().trim();

                if (filePath.equalsIgnoreCase("exit")) {
                    return;
                }

                if (filePath.isBlank()) {
                    continue;
                }

                try {
                    sender.sendFile(filePath);
                } catch (Exception e) {
                    System.err.println("TX failed: " + e.getMessage());
                }
            }
        }
    }

    private static String readHost(Scanner scanner) {
        System.out.print("Enter host (empty = 127.0.0.1): ");
        String input = scanner.nextLine().trim();

        return input.isBlank() ? DEFAULT_HOST : input;
    }

    private static int readPort(Scanner scanner) {
        System.out.print("Enter port: ");
        return parsePort(scanner.nextLine());
    }

    private static int readDelay(Scanner scanner) {
        System.out.print("Enter delay ms (empty = 0): ");
        String input = scanner.nextLine().trim();

        return input.isBlank()
                ? DEFAULT_DELAY_MS
                : parseNonNegativeInt(input, "delayMs");
    }

    private static int parsePort(String value) {
        int port = parseInt(value, "port");

        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be 1..65535");
        }

        return port;
    }

    private static int parseNonNegativeInt(String value, String name) {
        int number = parseInt(value, name);

        if (number < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }

        return number;
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }
}