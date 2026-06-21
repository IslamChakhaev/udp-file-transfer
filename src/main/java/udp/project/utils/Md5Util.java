package udp.project.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Md5Util {

    private Md5Util() {
    }

    public static byte[] calculateFile(String filePath) throws IOException, NoSuchAlgorithmException {
        return calculateFile(Path.of(filePath));
    }

    public static byte[] calculateFile(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("MD5");

        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        return digest.digest();
    }

    public static byte[] calculateBytes(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        return digest.digest(data);
    }

    public static String md5Hex(Path path) throws IOException, NoSuchAlgorithmException {
        return toHex(calculateFile(path));
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value & 0xFF));
        }
        return builder.toString();
    }
}
