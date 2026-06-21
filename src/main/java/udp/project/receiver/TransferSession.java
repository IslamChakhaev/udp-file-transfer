package udp.project.receiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import udp.project.protocol.Packet;
import udp.project.protocol.PacketType;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class TransferSession {

    private static final Logger log = LoggerFactory.getLogger(TransferSession.class);

    private final int transmissionId;
    private SocketAddress remoteAddress;
    private String fileName;
    private int maxSeq = -1;
    private byte[] expectedMd5;
    private boolean firstReceived;
    private boolean lastReceived;
    private boolean completed;
    private long lastSeenAt = System.currentTimeMillis();
    private long lastNakSentAt;

    private final Map<Integer, byte[]> chunks = new TreeMap<>();

    public TransferSession(int transmissionId) {
        this.transmissionId = transmissionId;
    }

    public void accept(Packet packet, SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
        this.lastSeenAt = System.currentTimeMillis();

        switch (packet.getType()) {
            case FIRST -> acceptFirst(packet);
            case DATA -> acceptData(packet);
            case LAST -> {
                if (firstReceived && packet.getSequenceNumber() != maxSeq + 1) {
                    packet.setType(PacketType.DATA);
                    packet.setData(packet.getMd5());
                    packet.setMd5(null);
                    acceptData(packet);
                } else {
                    acceptLast(packet);
                }
            }
        }
    }

    public void acceptFirst(Packet packet) {
        if (packet.getMaxSequenceNumber() < 0) {
            return;
        }

        this.fileName = packet.getFileName();
        this.maxSeq = packet.getMaxSequenceNumber();
        this.firstReceived = true;

        log.info("FIRST: txId={}, file='{}', maxSeq={}", transmissionId, fileName, maxSeq);
    }

    public void acceptData(Packet packet) {
        if (!firstReceived) {
            return;
        }

        int sequence = packet.getSequenceNumber();
        if (sequence < 1 || sequence > maxSeq || packet.getData() == null) {
            return;
        }

        chunks.putIfAbsent(sequence, packet.getData());
    }

    public void acceptLast(Packet packet) {
        if (!firstReceived) {
            return;
        }

        if (packet.getSequenceNumber() != maxSeq + 1) {
            return;
        }

        if (packet.getMd5() == null || packet.getMd5().length != 16) {
            return;
        }

        this.expectedMd5 = packet.getMd5();
        this.lastReceived = true;
        log.info("LAST: txId={}, received DATA={}/{}", transmissionId, chunks.size(), maxSeq);
    }

    public int getAckBase() {
        if (!firstReceived) {
            return 0;
        }

        int ackBase = 1;
        while (ackBase <= maxSeq && chunks.containsKey(ackBase)) {
            ackBase++;
        }
        return ackBase;
    }

    public List<Integer> getMissingPackets(int limit) {
        if (!firstReceived) {
            return List.of();
        }

        List<Integer> missing = new ArrayList<>();
        for (int sequence = 1; sequence <= maxSeq; sequence++) {
            if (!chunks.containsKey(sequence)) {
                missing.add(sequence);
                if (limit > 0 && missing.size() >= limit) {
                    break;
                }
            }
        }
        return missing;
    }

    public boolean hasAllData() {
        if (!firstReceived) {
            return false;
        }

        if (chunks.size() != maxSeq) {
            return false;
        }

        for (int sequence = 1; sequence <= maxSeq; sequence++) {
            if (!chunks.containsKey(sequence)) {
                return false;
            }
        }
        return true;
    }

    public boolean isReadyForAssembly() {
        return firstReceived && lastReceived && hasAllData() && !completed;
    }

    public int getTransmissionId() {
        return transmissionId;
    }

    public short getTransmissionIdAsShort() {
        return (short) transmissionId;
    }

    public SocketAddress getRemoteAddress() {
        return remoteAddress;
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

    public boolean isFirstReceived() {
        return firstReceived;
    }

    public boolean isLastReceived() {
        return lastReceived;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void markCompleted() {
        this.completed = true;
    }

    public long getLastSeenAt() {
        return lastSeenAt;
    }

    public long getLastNakSentAt() {
        return lastNakSentAt;
    }

    public void markNakSent() {
        this.lastNakSentAt = System.currentTimeMillis();
    }

    public Map<Integer, byte[]> getChunks() {
        return Collections.unmodifiableMap(chunks);
    }
}
