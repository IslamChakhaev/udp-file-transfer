package udp.project;

import udp.project.receiver.Receiver;

import java.nio.file.Path;
import java.util.Scanner;

public class MainRX {

    private static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final String USAGE = "Usage: MainRX <port> [outputDirectory] [idleTimeoutMs]";

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                interactive();
            } else {
                fromArgs(args);
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            usage();
        } catch (Exception e) {
            System.err.println("RX failed: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void fromArgs(String[] args) throws Exception {
        int port = parsePort(args[0]);
        Path outputDir = args.length > 1 ? Path.of(args[1]) : Path.of(".");
        long timeoutMs = args.length > 2 ? parsePositiveLong(args[2], "idleTimeoutMs") : DEFAULT_TIMEOUT_MS;

        try (Receiver receiver = new Receiver(port, outputDir, timeoutMs)) {
            receiver.start();
        }
    }

    private static void interactive() throws Exception {
        Scanner sc = new Scanner(System.in);

        System.out.print("Enter port: ");
        int port = parsePort(sc.nextLine());

        System.out.print("Enter output directory (empty = current): ");
        String output = sc.nextLine().trim();
        Path outputDir = output.isBlank() ? Path.of(".") : Path.of(output);

        try (Receiver receiver = new Receiver(port, outputDir, DEFAULT_TIMEOUT_MS)) {
            receiver.start();
        }
    }

    private static int parsePort(String value) {
        int port = parseInt(value, "port");
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("port must be 1..65535");
        return port;
    }

    private static long parsePositiveLong(String value, String name) {
        try {
            long n = Long.parseLong(value.trim());
            if (n <= 0) throw new IllegalArgumentException(name + " must be positive");
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number");
        }
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