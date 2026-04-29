package udp.project.receiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import udp.project.protocol.Packet;
import udp.project.protocol.PacketType;

import java.util.*;

public class TransferSession {

    private static final Logger log = LoggerFactory.getLogger(TransferSession.class);

    private final int transmissionId;

    private String fileName;
    private int maxSeq;
    private byte[] expectedMd5;

    private boolean firstReceived;
    private boolean lastReceived;

    private final Map<Integer, byte[]> chunks = new TreeMap<>();

    public TransferSession(int transmissionId) {
        this.transmissionId = transmissionId;
    }

    public void accept(Packet packet) {

        switch (packet.getType()) {
            case FIRST -> handleFirst(packet);
            case DATA  -> handleData(packet);
            case LAST  -> {
                // A 22-byte data chunk (payload == 16 bytes) is indistinguishable from the
                // real last packet by size alone. Re-check seq when maxSeq is known.
                if (firstReceived && packet.getSequenceNumber() != maxSeq + 1) {
                    packet.setType(PacketType.DATA);
                    packet.setData(packet.getMd5());
                    handleData(packet);
                } else {
                    handleLast(packet);
                }
            }
        }

    }

    private void handleFirst(Packet p) {
        this.fileName = p.getFileName();
        this.maxSeq = p.getMaxSequenceNumber();
        this.firstReceived = true;

        log.debug("FIRST received: file='{}', maxSeq={}",
                fileName,
                maxSeq
        );
    }

    private void handleData(Packet p) {
        chunks.putIfAbsent(p.getSequenceNumber(), p.getData());

        log.debug("DATA received: seq={}, size={} bytes",
                p.getSequenceNumber(),
                p.getData().length
        );
    }

    private void handleLast(Packet p) {
        this.expectedMd5 = p.getMd5();
        this.lastReceived = true;

        log.debug("LAST received (md5 size={} bytes)", p.getMd5().length);
    }

    public boolean isComplete() {
        return firstReceived && lastReceived && chunks.size() == maxSeq;
    }

    public List<Integer> getMissingPackets() {

        List<Integer> missing = new ArrayList<>();

        for (int i = 1; i <= maxSeq; i++) {
            if (!chunks.containsKey(i)) {
                missing.add(i);
            }
        }

        return missing;
    }

    public Map<Integer, byte[]> getChunks() {
        return chunks;
    }

    public String getFileName() {
        return fileName;
    }

    public int getMaxSeq() {
        return maxSeq;
    }

    public byte[] getExpectedMd5() {
        return expectedMd5;
    }

    public int getTransmissionId() {
        return transmissionId;
    }
}

