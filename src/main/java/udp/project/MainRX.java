package udp.project;

import udp.project.receiver.Receiver;

import java.nio.file.Path;
import java.util.Scanner;

public class MainRX {

    public static void main(String[] args) throws Exception {
        if (args.length >= 1) {
            int port = Integer.parseInt(args[0]);
            Path outputDirectory = args.length >= 2 ? Path.of(args[1]) : Path.of(".");
            long idleTimeoutMs = args.length >= 3 ? Long.parseLong(args[2]) : 10_000;
            new Receiver(port, outputDirectory, idleTimeoutMs).start();
            return;
        }

        Scanner scanner = new Scanner(System.in);

        System.out.print("Enter port: ");
        int port = Integer.parseInt(scanner.nextLine().trim());

        System.out.print("Enter output directory (empty = current): ");
        String output = scanner.nextLine().trim();
        Path outputDirectory = output.isBlank() ? Path.of(".") : Path.of(output);

        new Receiver(port, outputDirectory, 10_000).start();
    }
}
