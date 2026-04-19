package udp.project.receiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

public class FileAssembler {

    private static final Logger log = LoggerFactory.getLogger(FileAssembler.class);

    private final Map<Integer, byte[]> chunks = new TreeMap<>();

    public void addChunk(int seqNr, byte[] data) {
        log.info("[ASSEMBLER] Storing DATA packet seq={} ({} bytes)", seqNr, data.length);
        chunks.put(seqNr, data);
    }

    public void buildFile(String outputPath, int maxSeq) throws IOException {

        log.info("[ASSEMBLER] Building file from {} DATA packets...", chunks.size());

        try (FileOutputStream fos = new FileOutputStream(outputPath)) {

            for (int seq = 1; seq <= maxSeq; seq++) {

                byte[] data = chunks.get(seq);

                if (data == null) {
                    throw new IOException("Missing DATA packet seq=" + seq);
                }

                fos.write(data);
            }
        }

        log.info("[ASSEMBLER] File assembly completed");
    }
}