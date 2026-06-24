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
    // Hauptszenario: FIRST, DATA oder LAST verarbeiten
    //
    // 1. Remote-Adresse und Aktivitätszeit merken
    // 2. FIRST initialisiert die Dateiübertragung
    // 3. DATA schreibt einen Chunk an die richtige Position
    // 4. LAST liefert den erwarteten MD5-Wert
    // 5. Danach entscheidet die Session: ACK, NAK, COMPLETE oder ERROR
    // ============================================================

    public List<ControlPacket> accept(Packet packet, SocketAddress remote) {
        // Phase 1: Sender-Adresse und letzte Aktivität aktualisieren
        this.remoteAddress = remote;
        this.lastSeenAt = System.currentTimeMillis();

        try {
            // Phase 2: Paket nach Typ verarbeiten
            switch (packet.type()) {
                case FIRST -> onFirst(packet);
                case DATA -> onData(packet);
                case LAST -> onLast(packet);
            }

            // Phase 3: passende Antwort für Sender erzeugen
            return response(packet.type());
        } catch (Exception e) {
            // Bei Fehler unfertige .part-Datei entfernen und ERROR senden
            cleanPartFile();
            return List.of(ControlPacket.error(txId, e.getMessage()));
        }
    }

    public ControlPacket nak() {
        // Zeitpunkt merken, damit NAK nicht zu oft wiederholt wird
        lastNakAt = System.currentTimeMillis();
        return ControlPacket.nak(txId, missingSequences());
    }

    public boolean shouldSendNak(long now, long intervalMs) {
        // NAK erneut senden, wenn die Session offen ist und länger nichts Neues kam
        return firstReceived
                && !complete
                && remoteAddress != null
                && now - lastSeenAt >= intervalMs
                && now - lastNakAt >= intervalMs;
    }

    public void cleanPartFile() {
        closePart();

        if (partFile != null) {
            try {
                Files.deleteIfExists(partFile);
            } catch (IOException ignored) {
                // Cleanup darf den Receiver nicht beenden.
            }
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
    // Paketverarbeitung
    //
    // FIRST: Dateiname und Anzahl der DATA-Pakete
    // DATA : eigentlicher Dateiausschnitt
    // LAST : MD5-Prüfsumme für die komplette Datei
    // ============================================================

    private void onFirst(Packet packet) throws IOException {
        // FIRST kann durch Wiederholung erneut kommen. Dann nichts neu initialisieren.
        if (firstReceived) {
            return;
        }

        if (packet.maxSequenceNumber() < 0) {
            throw new IOException("Invalid maxSeq");
        }

        // Metadaten der Übertragung übernehmen
        this.fileName = packet.fileName();
        this.maxSeq = packet.maxSequenceNumber();

        // Empfangsstatus für DATA-Pakete vorbereiten
        this.received = new BitSet(maxSeq + 1);

        // Temporäre Datei anlegen, bis MD5 erfolgreich geprüft wurde
        this.partFile = outputDirectory.resolve(".udp-" + Short.toUnsignedInt(txId) + ".part");
        this.partRaf = new RandomAccessFile(partFile.toFile(), "rw");
        this.partRaf.setLength(0);

        this.firstReceived = true;

        System.out.printf(
                "RX FIRST: file=%s, txId=%d, chunks=%d%n",
                fileName,
                Short.toUnsignedInt(txId),
                maxSeq
        );
    }

    private void onData(Packet packet) throws IOException {
        // DATA darf erst nach FIRST verarbeitet werden
        if (!firstReceived || partRaf == null) {
            return;
        }

        int seq = packet.sequenceNumber();

        // Ungültige Sequenzen oder leere Daten ignorieren
        if (seq < 1 || seq > maxSeq || packet.data() == null) {
            return;
        }

        // Doppelte DATA-Pakete nicht noch einmal schreiben
        if (received.get(seq)) {
            return;
        }

        // Chunk an die richtige Position schreiben. Reihenfolge der UDP-Pakete ist egal.
        partRaf.seek((long) (seq - 1) * CHUNK_SIZE);
        partRaf.write(packet.data());

        // Sequenz als empfangen markieren
        received.set(seq);
        receivedCount++;
    }

    private void onLast(Packet packet) {
        // LAST muss nach FIRST kommen und direkt nach der letzten DATA-Sequenz liegen
        if (!firstReceived || packet.sequenceNumber() != maxSeq + 1) {
            return;
        }

        // MD5 muss genau 16 Bytes haben
        if (packet.md5() == null || packet.md5().length != MD5_SIZE) {
            return;
        }

        expectedMd5 = packet.md5();
        lastReceived = true;
    }

    // ============================================================
    // ACK / NAK / COMPLETE
    //
    // ACK      -> Receiver bestätigt fortlaufend empfangene DATA
    // NAK      -> Receiver nennt konkrete fehlende DATA-Sequenzen
    // COMPLETE -> Datei ist vollständig und MD5 stimmt
    // ============================================================

    private List<ControlPacket> response(PacketType latestType) throws Exception {
        if (!firstReceived) {
            return List.of();
        }

        // Wenn DATA vollständig sind und LAST da ist, Datei final prüfen und abschließen
        if (readyToFinish()) {
            Path finalPath = finishFile();
            complete = true;
            System.out.printf("RX complete: saved=%s%n", finalPath.toAbsolutePath());
            return List.of(ControlPacket.complete(txId));
        }

        // Nach LAST oder wenn LAST schon da war, fehlen noch DATA -> NAK senden
        if (latestType == PacketType.LAST || lastReceived) {
            return List.of(nak());
        }

        // Normalfall während DATA: kumulativen ACK senden
        return List.of(ControlPacket.ack(txId, ackBase()));
    }

    private boolean readyToFinish() {
        // Fertig erst, wenn FIRST, alle DATA und LAST vorhanden sind
        return firstReceived && lastReceived && receivedCount == maxSeq && !complete;
    }

    private int ackBase() {
        int seq = 1;

        // Erste fehlende DATA-Sequenz suchen
        while (seq <= maxSeq && received.get(seq)) {
            seq++;
        }

        return seq;
    }

    private List<Integer> missingSequences() {
        List<Integer> missing = new ArrayList<>();

        // Fehlende DATA-Sequenzen sammeln, damit Sender sie erneut senden kann
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
    // 1. .part-Datei schließen
    // 2. MD5 prüfen
    // 3. freien Zielpfad bestimmen
    // 4. .part-Datei in endgültige Datei umbenennen
    // ============================================================

    private Path finishFile() throws Exception {
        closePart();

        // MD5 schützt davor, eine beschädigte Datei als fertig zu speichern
        if (!Arrays.equals(Md5Util.calculateFile(partFile), expectedMd5)) {
            Files.deleteIfExists(partFile);
            throw new IOException("MD5 mismatch");
        }

        // Zielpfad bestimmen und .part-Datei final speichern
        Path finalPath = freePath(outputDirectory.resolve(fileName));
        Files.move(partFile, finalPath);
        return finalPath;
    }

    private Path freePath(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();

        // Schutz: Datei darf nur direkt im outputDirectory gespeichert werden
        if (!path.getParent().equals(outputDirectory)) {
            throw new IOException("Unsafe output path");
        }

        // Wenn der Name frei ist, kann er direkt verwendet werden
        if (!Files.exists(path)) {
            return path;
        }

        String currentName = path.getFileName().toString();
        int dot = currentName.lastIndexOf('.');
        String name = dot > 0 ? currentName.substring(0, dot) : currentName;
        String ext = dot > 0 ? currentName.substring(dot) : "";

        // Wenn Datei existiert, Namen mit _1, _2, ... erzeugen
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