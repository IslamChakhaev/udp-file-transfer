package udp.project;

import udp.project.receiver.Receiver;

import java.nio.file.Path;
import java.util.Scanner;

public class MainRX {

    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    private static final String DEFAULT_OUTPUT_DIR = ".";
    private static final String USAGE = "Usage: MainRX <port> [outputDirectory] [idleTimeoutMs]";

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                runInteractive();
            } else if (args.length <= 3) {
                runFromArgs(args);
            } else {
                throw new IllegalArgumentException(USAGE);
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("RX failed: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void runFromArgs(String[] args) throws Exception {
        int port = parsePort(args[0]);
        Path outputDir = args.length >= 2 ? Path.of(args[1]) : Path.of(DEFAULT_OUTPUT_DIR);
        long timeoutMs = args.length == 3
                ? parsePositiveLong(args[2], "idleTimeoutMs")
                : DEFAULT_TIMEOUT_MS;

        startReceiver(port, outputDir, timeoutMs);
    }

    private static void runInteractive() throws Exception {
        Scanner scanner = new Scanner(System.in);

        int port = readPort(scanner);
        Path outputDir = readOutputDir(scanner);
        long timeoutMs = readTimeout(scanner);

        startReceiver(port, outputDir, timeoutMs);
    }

    private static void startReceiver(int port, Path outputDir, long timeoutMs) throws Exception {
        try (Receiver receiver = new Receiver(port, outputDir, timeoutMs)) {
            receiver.start();
        }
    }

    private static int readPort(Scanner scanner) {
        System.out.print("Enter port: ");
        return parsePort(scanner.nextLine());
    }

    private static Path readOutputDir(Scanner scanner) {
        System.out.print("Enter output directory (empty = current): ");
        String input = scanner.nextLine().trim();

        return input.isBlank() ? Path.of(DEFAULT_OUTPUT_DIR) : Path.of(input);
    }

    private static long readTimeout(Scanner scanner) {
        System.out.print("Enter idle timeout ms (empty = 30000): ");
        String input = scanner.nextLine().trim();

        return input.isBlank()
                ? DEFAULT_TIMEOUT_MS
                : parsePositiveLong(input, "idleTimeoutMs");
    }

    private static int parsePort(String value) {
        int port = parseInt(value, "port");

        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be 1..65535");
        }

        return port;
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    private static long parsePositiveLong(String value, String name) {
        try {
            long number = Long.parseLong(value.trim());

            if (number <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }

            return number;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }
}