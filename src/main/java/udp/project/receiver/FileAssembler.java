package udp.project.receiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import udp.project.utils.Md5Util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;

public class FileAssembler {

    private static final Logger log = LoggerFactory.getLogger(FileAssembler.class);

    public Path assembleAndVerify(TransferSession session, Path outputDirectory) throws Exception {
        Files.createDirectories(outputDirectory);

        String safeName = sanitizeFileName(session.getFileName());
        Path finalPath = createUniqueFileName(outputDirectory, "received_" + safeName);
        Path tempPath = Files.createTempFile(outputDirectory, ".udp-transfer-", ".tmp");

        byte[] actualMd5;

        try {
            actualMd5 = assembleToTempFile(session.getChunks(), tempPath, session.getMaxSeq());

            if (!Arrays.equals(actualMd5, session.getExpectedMd5())) {
                Files.deleteIfExists(tempPath);
                throw new IOException("MD5 mismatch: actual=" + Md5Util.toHex(actualMd5)
                        + ", expected=" + Md5Util.toHex(session.getExpectedMd5()));
            }

            Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE);
            log.info("MD5 OK: {}", Md5Util.toHex(actualMd5));
            log.info("File saved: {}", finalPath.toAbsolutePath());
            return finalPath;
        } catch (Exception e) {
            Files.deleteIfExists(tempPath);
            throw e;
        }
    }

    public byte[] assembleToTempFile(Map<Integer, byte[]> chunks, Path tempPath, int maxSeq)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("MD5");

        try (OutputStream output = Files.newOutputStream(tempPath)) {
            for (int sequence = 1; sequence <= maxSeq; sequence++) {
                byte[] data = chunks.get(sequence);
                if (data == null) {
                    throw new IOException("Missing DATA packet seq=" + sequence);
                }
                output.write(data);
                digest.update(data);
            }
        }

        return digest.digest();
    }

    public String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "file.bin";
        }

        String baseName = Path.of(fileName).getFileName().toString();
        String sanitized = baseName
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("[\\p{Cntrl}]", "_")
                .trim();

        if (sanitized.isBlank() || sanitized.equals(".") || sanitized.equals("..")) {
            return "file.bin";
        }

        return sanitized;
    }

    public Path createUniqueFileName(Path outputDirectory, String fileName) throws IOException {
        Path candidate = outputDirectory.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        }

        String name = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            name = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }

        for (int i = 1; i < 10_000; i++) {
            candidate = outputDirectory.resolve(name + "_" + i + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }

        throw new IOException("Cannot create unique filename for " + fileName);
    }

    public void buildFile(Map<Integer, byte[]> chunks, String outputPath, int maxSeq) throws IOException {
        try (OutputStream output = Files.newOutputStream(Path.of(outputPath))) {
            for (int sequence = 1; sequence <= maxSeq; sequence++) {
                byte[] data = chunks.get(sequence);
                if (data == null) {
                    throw new IOException("Missing DATA packet seq=" + sequence);
                }
                output.write(data);
            }
        }
    }
}
