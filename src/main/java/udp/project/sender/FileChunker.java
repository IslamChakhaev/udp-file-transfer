package udp.project.sender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class FileChunker {

    private static final Logger log = LoggerFactory.getLogger(FileChunker.class);

    private final int chunkSize;

    public FileChunker(int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Chunk size must be positive");
        }
        this.chunkSize = chunkSize;
    }

    public List<byte[]> splitFile(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("File not found: " + file);
        }

        List<byte[]> chunks = new ArrayList<>();

        log.info("File size: {} bytes, chunk size: {} bytes", file.length(), chunkSize);

        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] buffer = new byte[chunkSize];
            int bytesRead;

            while ((bytesRead = stream.read(buffer)) != -1) {
                byte[] chunk = new byte[bytesRead];
                System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                chunks.add(chunk);
            }
        }

        log.info("DATA packets: {}", chunks.size());
        return chunks;
    }

    public int getChunkSize() {
        return chunkSize;
    }
}
