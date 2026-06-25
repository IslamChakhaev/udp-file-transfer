package udp.project.receiver;

import udp.project.protocol.ControlPacket;
import udp.project.protocol.Packet;
import udp.project.protocol.PacketType;
import udp.project.utils.Md5Util;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

public class ReceiveSession {

    private static final int CHUNK_SIZE = 1400;
    private static final int MD5_SIZE = 16;
    private static final int MAX_NAK_SEQUENCES = 350;

    private final short txId;
    private final Path outputDirectory;

    private SocketAddress remoteAddress;
    private String fileName;
    private int maxSeq = -1;
    private BitSet received;
    private int receivedCount;
    private byte[] expectedMd5;
    private Path partFile;
    private RandomAccessFile partRaf;
    private boolean firstReceived;
    private boolean lastReceived;
    private boolean complete;
    private long lastSeenAt = System.currentTimeMillis();
    private long lastNakAt;

    public ReceiveSession(short txId, Path outputDirectory) {
        this.txId = txId;
        this.outputDirectory = outputDirectory;
    }

    // ============================================================
    // Hauptablauf einer Dateiübertragung
    //
    // FIRST -> Transfer vorbereiten
    // DATA  -> Chunk in .part-Datei schreiben
    // LAST  -> erwartete MD5-Prüfsumme merken
    // Danach -> ACK, NAK, COMPLETE oder ERROR zurückgeben
    // ============================================================

    public List<ControlPacket> accept(Packet packet, SocketAddress remote) {
        this.remoteAddress = remote;
        this.lastSeenAt = System.currentTimeMillis();

        try {
            switch (packet.type()) {
                case FIRST -> onFirst(packet);
                case DATA -> onData(packet);
                case LAST -> onLast(packet);
            }

            return response(packet.type());
        } catch (Exception e) {
            cleanPartFile();
            return List.of(ControlPacket.error(txId, e.getMessage()));
        }
    }

    public ControlPacket nak() {
        lastNakAt = System.currentTimeMillis();
        return ControlPacket.nak(txId, missingSequences());
    }

    public boolean shouldSendNak(long now, long intervalMs) {
        return firstReceived && !complete && remoteAddress != null
                && now - lastSeenAt >= intervalMs
                && now - lastNakAt >= intervalMs;
    }

    public void cleanPartFile() {
        closePart();

        if (partFile == null) {
            return;
        }

        try {
            Files.deleteIfExists(partFile);
        } catch (IOException ignored) {
            // Cleanup darf den Receiver nicht beenden.
        }
    }

    public short txId() {
        return txId;
    }

    public int getMaxSeq() {
        return maxSeq;
    }

    public boolean isComplete() {
        return complete;
    }

    public long getLastSeenAt() {
        return lastSeenAt;
    }

    public SocketAddress remoteAddress() {
        return remoteAddress;
    }

    // ============================================================
    // FIRST / DATA / LAST
    // ============================================================

    private void onFirst(Packet packet) throws IOException {
        if (firstReceived) {
            return;
        }

        if (packet.maxSequenceNumber() < 0) {
            throw new IOException("Invalid maxSeq");
        }

        fileName = packet.fileName();
        maxSeq = packet.maxSequenceNumber();
        received = new BitSet(maxSeq + 1);

        partFile = outputDirectory.resolve(".udp-" + Short.toUnsignedInt(txId) + ".part");
        partRaf = new RandomAccessFile(partFile.toFile(), "rw");
        partRaf.setLength(0);

        firstReceived = true;

        System.out.printf("RX FIRST: file=%s, txId=%d, chunks=%d%n", fileName, Short.toUnsignedInt(txId), maxSeq);
    }

    private void onData(Packet packet) throws IOException {
        if (!firstReceived || partRaf == null) {
            return;
        }

        int seq = packet.sequenceNumber();

        if (seq < 1 || seq > maxSeq || packet.data() == null || received.get(seq)) {
            return;
        }

        partRaf.seek((long) (seq - 1) * CHUNK_SIZE);
        partRaf.write(packet.data());

        received.set(seq);
        receivedCount++;
    }

    private void onLast(Packet packet) {
        if (!firstReceived || packet.sequenceNumber() != maxSeq + 1) {
            return;
        }

        if (packet.md5() == null || packet.md5().length != MD5_SIZE) {
            return;
        }

        expectedMd5 = packet.md5();
        lastReceived = true;
    }

    // ============================================================
    // Antwort an den Sender
    //
    // COMPLETE -> Datei vollständig und MD5 korrekt
    // NAK      -> LAST kam, aber DATA fehlen noch
    // ACK      -> nächstes erwartetes DATA-Paket
    // ============================================================

    private List<ControlPacket> response(PacketType latestType) throws Exception {
        if (!firstReceived) {
            return List.of();
        }

        if (readyToFinish()) {
            Path finalPath = finishFile();
            complete = true;

            System.out.printf("RX complete: saved=%s%n", finalPath.toAbsolutePath());
            return List.of(ControlPacket.complete(txId));
        }

        if (latestType == PacketType.LAST || lastReceived) {
            return List.of(nak());
        }

        return List.of(ControlPacket.ack(txId, ackBase()));
    }

    private boolean readyToFinish() {
        return firstReceived && lastReceived && receivedCount == maxSeq && !complete;
    }

    private int ackBase() {
        int seq = 1;

        while (seq <= maxSeq && received.get(seq)) {
            seq++;
        }

        return seq;
    }

    private List<Integer> missingSequences() {
        List<Integer> missing = new ArrayList<>();

        for (int seq = 1; seq <= maxSeq && missing.size() < MAX_NAK_SEQUENCES; seq++) {
            if (!received.get(seq)) {
                missing.add(seq);
            }
        }

        return missing;
    }

    // ============================================================
    // Datei abschließen
    //
    // .part schließen -> MD5 prüfen -> freien Dateinamen finden -> umbenennen
    // ============================================================

    private Path finishFile() throws Exception {
        closePart();

        if (!Arrays.equals(Md5Util.calculateFile(partFile), expectedMd5)) {
            Files.deleteIfExists(partFile);
            throw new IOException("MD5 mismatch");
        }

        Path finalPath = freePath(outputDirectory.resolve(fileName));
        Files.move(partFile, finalPath);

        return finalPath;
    }

    private Path freePath(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();

        if (!path.getParent().equals(outputDirectory)) {
            throw new IOException("Unsafe output path");
        }

        if (!Files.exists(path)) {
            return path;
        }

        String currentName = path.getFileName().toString();
        int dot = currentName.lastIndexOf('.');
        String name = dot > 0 ? currentName.substring(0, dot) : currentName;
        String ext = dot > 0 ? currentName.substring(dot) : "";

        for (int i = 1; i < 10_000; i++) {
            Path candidate = outputDirectory.resolve(name + "_" + i + ext).toAbsolutePath().normalize();

            if (!Files.exists(candidate)) {
                return candidate;
            }
        }

        throw new IOException("Cannot create unique filename");
    }

    private void closePart() {
        if (partRaf == null) {
            return;
        }

        try {
            partRaf.close();
        } catch (IOException ignored) {
            // Beim Schließen gibt es nichts Sinnvolles mehr zu reparieren.
        }

        partRaf = null;
    }
}