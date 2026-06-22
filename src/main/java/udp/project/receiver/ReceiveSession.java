package udp.project.receiver;

import udp.project.protocol.ControlPacket;
import udp.project.protocol.Packet;
import udp.project.protocol.PacketType;
import udp.project.utils.Md5Util;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.SocketAddress;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

public class ReceiveSession {

    // Muss mit Sender.CHUNK_SIZE übereinstimmen – bestimmt den seek-Offset jedes Chunks in der .part-Datei.
    private static final int CHUNK_SIZE = 1400;

    // Zustand dieser Übertragung: feste Parameter (txId, Zielordner) und veränderliche Felder
    // für empfangene Chunks, Dateiinfos und Timing.
    private final short txId;
    private final Path outputDirectory;
    private final int maxNakSequences;

    private SocketAddress remoteAddress;
    private String fileName;
    private int maxSeq = -1;
    private BitSet received;      // O(1)-Duplikaterkennung pro seq
    private int receivedCount;
    private byte[] expectedMd5;
    private Path partFile;
    private RandomAccessFile partRaf;
    private boolean firstReceived;
    private boolean lastReceived;
    private boolean complete;
    private long lastSeenAt = System.currentTimeMillis();
    private long lastNakAt;

    // Öffentliche Schnittstelle: Pakete annehmen, NAKs senden, Zustand abfragen, aufräumen.
    public ReceiveSession(short txId, Path outputDirectory, int maxNakSequences) {
        this.txId = txId;
        this.outputDirectory = outputDirectory;
        this.maxNakSequences = maxNakSequences;
    }

    // Zentraler Einstiegspunkt für jedes eingehende Paket dieser Session.
    // Leitet an onFirst/onData/onLast weiter und gibt die passende Control-Antwort zurück.
    // Bei einer Ausnahme wird die .part-Datei bereinigt und ERROR gesendet.
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

    // NAK mit allen fehlenden Sequenznummern; max. maxNakSequences Einträge (MTU-Grenze).
    public ControlPacket nak() {
        lastNakAt = System.currentTimeMillis();
        return ControlPacket.nak(txId, missingSequences());
    }

    // Verhindert NAK-Floods: sendet nur, wenn kein NAK und kein Paket kürzlich gesehen wurde.
    public boolean shouldSendNak(long now, long intervalMs) {
        return firstReceived && !complete && remoteAddress != null
                && now - lastSeenAt >= intervalMs
                && now - lastNakAt >= intervalMs;
    }

    public void cleanPartFile() {
        closePart();
        if (partFile != null) {
            try {
                Files.deleteIfExists(partFile);
            } catch (IOException ignored) {
            }
        }
    }

    public short txId()              { return txId; }
    public int getMaxSeq()           { return maxSeq; }
    public boolean isComplete()      { return complete; }
    public long getLastSeenAt()      { return lastSeenAt; }
    public SocketAddress remoteAddress() { return remoteAddress; }

    // Verarbeitung eingehender Pakete: baut schrittweise die .part-Datei auf.
    // .part statt RAM: Chunks werden per seek+write direkt an die richtige Position geschrieben,
    // auch wenn sie in falscher Reihenfolge ankommen.
    private void onFirst(Packet packet) throws IOException {
        if (firstReceived) return;
        if (packet.maxSequenceNumber() < 0) throw new IOException("Invalid maxSeq");

        this.fileName = packet.fileName();
        this.maxSeq = packet.maxSequenceNumber();
        this.received = new BitSet(maxSeq + 1);
        this.partFile = outputDirectory.resolve(".udp-" + Short.toUnsignedInt(txId) + ".part");
        this.partRaf = new RandomAccessFile(partFile.toFile(), "rw");
        this.partRaf.setLength(0);
        this.firstReceived = true;

        System.out.printf("RX FIRST: file=%s, txId=%d, chunks=%d%n", fileName, Short.toUnsignedInt(txId), maxSeq);
    }

    // Duplikate per BitSet erkennen; Chunk direkt an Offset (seq-1)*CHUNK_SIZE schreiben.
    private void onData(Packet packet) throws IOException {
        if (!firstReceived || partRaf == null) return;
        int seq = packet.sequenceNumber();
        if (seq < 1 || seq > maxSeq || packet.data() == null) return;
        if (received.get(seq)) return;
        partRaf.seek((long) (seq - 1) * CHUNK_SIZE);
        partRaf.write(packet.data());
        received.set(seq);
        receivedCount++;
    }

    // seq=maxSeq+1 ist das Protokollmerkmal für LAST; schützt vor Verwechslung mit DATA.
    private void onLast(Packet packet) {
        if (!firstReceived || packet.sequenceNumber() != maxSeq + 1) return;
        if (packet.md5() == null || packet.md5().length != 16) return;
        expectedMd5 = packet.md5();
        lastReceived = true;
    }

    // Bestimmt die Kontrollantwort nach jedem Paket:
    //   Alle Chunks + LAST da → Datei fertigstellen, COMPLETE senden.
    //   LAST bekannt, Chunks fehlen noch → NAK mit fehlenden Sequenznummern.
    //   Sonst → kumulativer ACK (alles bis zur ersten Lücke bestätigen).
    private List<ControlPacket> response(PacketType latestType) throws Exception {
        if (!firstReceived) return List.of();

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

    // Kumulativer ACK-Punkt: erste noch fehlende seq.
    // Beispiel: empfangen {1,2,4} → ackBase = 3; der Sender kann alle seq < 3 freigeben.
    private int ackBase() {
        int seq = 1;
        while (seq <= maxSeq && received.get(seq)) seq++;
        return seq;
    }

    private List<Integer> missingSequences() {
        List<Integer> missing = new ArrayList<>();
        for (int seq = 1; seq <= maxSeq && missing.size() < maxNakSequences; seq++) {
            if (!received.get(seq)) missing.add(seq);
        }
        return missing;
    }

    // Abschluss der Übertragung: MD5 prüfen, dann .part atomar umbenennen.
    // Atomic Move verhindert, dass eine unvollständige Datei im Ausgabeordner sichtbar wird.
    // Bei MD5-Fehler: .part löschen und Exception werfen.
    private Path finishFile() throws Exception {
        closePart();
        if (!Arrays.equals(Md5Util.calculateFile(partFile), expectedMd5)) {
            Files.deleteIfExists(partFile);
            throw new IOException("MD5 mismatch");
        }

        Path finalPath = freePath(outputDirectory.resolve(fileName));
        try {
            Files.move(partFile, finalPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(partFile, finalPath);
        }
        return finalPath;
    }

    // Sicherer Ausgabepfad: Pfad außerhalb von outputDirectory wird abgelehnt (Directory-Traversal-Schutz).
    // Existiert der Dateiname bereits, wird _1, _2, ... angehängt.
    private Path freePath(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        if (!path.getParent().equals(outputDirectory)) throw new IOException("Unsafe output path");
        if (!Files.exists(path)) return path;

        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String name = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";

        for (int i = 1; i < 10_000; i++) {
            Path candidate = outputDirectory.resolve(name + "_" + i + ext).toAbsolutePath().normalize();
            if (!Files.exists(candidate)) return candidate;
        }
        throw new IOException("Cannot create unique filename");
    }

    private void closePart() {
        if (partRaf != null) {
            try {
                partRaf.close();
            } catch (IOException ignored) {
            }
            partRaf = null;
        }
    }
}
