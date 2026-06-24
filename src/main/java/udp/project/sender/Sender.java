package udp.project.sender;

import udp.project.protocol.ControlPacket;
import udp.project.protocol.Packet;
import udp.project.protocol.PacketSerializer;
import udp.project.protocol.PacketType;
import udp.project.utils.Md5Util;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class Sender implements AutoCloseable {

    // ============================================================
    // Grunddaten des Senders
    // ============================================================

    private final DatagramSocket socket;
    private final InetAddress address;
    private final int port;
    private final int delayMs;
    private final SenderConfig config;
    private final PacketSerializer serializer = new PacketSerializer();

    public Sender(String host, int port, int delayMs) throws Exception {
        this.config = SenderConfig.defaults();
        this.socket = new DatagramSocket();
        this.address = InetAddress.getByName(host);
        this.port = port;
        this.delayMs = Math.max(0, delayMs);
    }

    // ============================================================
    // Hauptszenario: Datei senden
    // 1. Datei prüfen
    // 2. FIRST-Paket senden
    // 3. DATA-Pakete mit Sliding Window senden
    // 4. LAST-Paket senden, wenn alle DATA bestätigt wurden
    // 5. Auf COMPLETE vom Receiver warten
    // ============================================================

    public void sendFile(String filePath) throws Exception {
        // Phase 1: Datei prüfen und Metadaten vorbereiten
        File file = new File(filePath);
        if (!file.isFile() || !file.canRead()) throw new IllegalArgumentException("File not readable: " + filePath);

        long fileSize = file.length();
        int maxSeq = maxSeq(fileSize);
        short txId = (short) (System.nanoTime() & 0xFFFF);

        // Phase 2: Start- und Endpaket vorbereiten
        Packet first = new Packet(PacketType.FIRST, txId, 0, maxSeq, null, file.getName(), null);
        Packet last = new Packet(PacketType.LAST, txId, maxSeq + 1, 0, null, null, Md5Util.calculateFile(file.toPath()));

        long started = System.currentTimeMillis();
        System.out.printf("TX start: file=%s, size=%d, txId=%d, chunks=%d%n", file.getName(), fileSize, Short.toUnsignedInt(txId), maxSeq);

        // Phase 3: FIRST senden, damit der Receiver die Übertragung kennt
        send(first);

        // Phase 4: DATA mit Sliding Window senden
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            sendDataWithSlidingWindow(txId, raf, fileSize, maxSeq, last);
        }

        // Phase 5: Übertragung wurde durch COMPLETE beendet
        System.out.printf("TX complete: file=%s, time=%d ms%n", file.getName(), System.currentTimeMillis() - started);
    }

    @Override
    public void close() {
        socket.close();
    }

    // ============================================================
    // DATA-Szenario mit Sliding Window
    // Hier passiert:
    // - neue DATA senden, solange das Fenster Platz hat
    // - Receiver-Antwort lesen
    // - ACK verarbeitet bestätigte Pakete
    // - NAK fordert fehlende Pakete erneut an
    // - Timeout löst Retransmission aus
    // - COMPLETE beendet die Übertragung
    // ============================================================

    private void sendDataWithSlidingWindow(short txId, RandomAccessFile raf,
                                           long fileSize, int maxSeq, Packet last) throws Exception {
        // Phase 1: Timeout für Receiver-Antworten setzen
        socket.setSoTimeout(config.replyWaitTimeoutMs());

        // Phase 2: Fenster und Laufzeitstatus initialisieren
        SlidingWindow window = new SlidingWindow(maxSeq);
        TransferState state = new TransferState(config);

        // Phase 3: Hauptschleife der DATA-Übertragung
        while (state.idleRounds < config.maxNoReplyRounds()) {
            // Neue DATA senden, wenn das Fenster Platz hat
            sendDataWhileWindowAllows(txId, raf, fileSize, window, state);

            // LAST senden, sobald alle DATA bestätigt sind
            sendLastWhenAllDataAcked(window, state, last);

            // Auf ACK / NAK / COMPLETE / ERROR warten
            ControlPacket reply = waitForReceiverReply(txId);

            // Keine Antwort: Timeout-Logik ausführen
            if (reply == null) {
                onReceiverTimeout(txId, raf, fileSize, maxSeq, last, window, state);
                continue;
            }

            // Antwort erhalten: Idle-Zähler zurücksetzen
            state.idleRounds = 0;

            // Receiver-Antwort verarbeiten; COMPLETE beendet die Übertragung
            if (onReceiverReply(txId, raf, fileSize, maxSeq, last, window, state, reply)) {
                return;
            }
        }

        throw new IllegalStateException("Transfer failed: COMPLETE not received");
    }

    // ============================================================
    // Sliding-Window-Senden
    // ============================================================

    // Sendet neue DATA-Pakete, solange das Sliding Window Platz hat.
    // Die Pakete sind danach "in flight": gesendet, aber noch nicht bestätigt.
    private void sendDataWhileWindowAllows(short txId, RandomAccessFile raf, long fileSize,
                                           SlidingWindow window, TransferState state) throws Exception {
        while (window.canSend(state.cwnd)) {
            // Nächste freie Sequenznummer im Fenster nehmen
            int seq = window.nextToSend();

            // Passenden Dateiausschnitt für diese Sequenz lesen
            byte[] data = readChunk(raf, fileSize, seq);

            // DATA-Paket senden
            send(new Packet(PacketType.DATA, txId, seq, 0, data, null, null));

            // Sendezeit merken, damit Timeout geprüft werden kann
            state.sentAt.put(seq, System.currentTimeMillis());

            // Fenster intern auf das nächste DATA vorbereiten
            window.markSent();
        }
    }

    // Sendet LAST erst dann, wenn alle DATA-Pakete bestätigt wurden.
    // LAST bedeutet: "Alle Daten wurden gesendet, jetzt kann der Receiver prüfen."
    private void sendLastWhenAllDataAcked(SlidingWindow window, TransferState state, Packet last) throws Exception {
        // LAST nur einmal automatisch senden
        if (window.isFinished() && !state.lastSent) {
            send(last);
            state.lastSent = true;
        }
    }

    // ============================================================
    // Keine Antwort vom Receiver
    // ============================================================

    // Wird aufgerufen, wenn innerhalb des Socket-Timeouts kein ACK/NAK/COMPLETE kam.
    // Wenn noch DATA offen sind, wird bei Timeout das älteste unbestätigte Paket erneut gesendet.
    // Wenn alle DATA bestätigt sind, aber COMPLETE fehlt, wird LAST regelmäßig erneut gesendet.
    private void onReceiverTimeout(short txId, RandomAccessFile raf, long fileSize,
                                   int maxSeq, Packet last,
                                   SlidingWindow window, TransferState state) throws Exception {
        // Eine Runde ohne Receiver-Antwort zählen
        state.idleRounds++;

        if (!window.isFinished() && timedOut(state.sentAt, window.getFirstUnackedSeq(), state.rtoMs)) {
            // Ältestes unbestätigtes DATA erneut senden
            retransmitData(txId, raf, fileSize, maxSeq, window.getFirstUnackedSeq(), state, true);
            state.sentAt.put(window.getFirstUnackedSeq(), System.currentTimeMillis());

            // Bei Timeout langsamer senden und RTO erhöhen
            state.cwnd = Math.max(config.minWindowSize(), state.cwnd / 2);
            state.rtoMs = Math.min(config.maxReplyTimeoutMs(), state.rtoMs * 2);

            // Duplicate-ACK-Zustand zurücksetzen
            state.dupAckCount = 0;
            state.fastRetransmittedAt = -1;
        } else if (window.isFinished() && state.idleRounds % 3 == 0) {
            // Alle DATA bestätigt, aber COMPLETE fehlt: LAST erneut senden
            send(last);
            state.lastSent = true;
        }
    }

    // ============================================================
    // Antwort vom Receiver verarbeiten
    // ============================================================

    // Verteilt die Antwort des Receivers:
    // ACK      -> Fenster verschieben oder Duplicate ACK behandeln
    // NAK      -> fehlende DATA erneut senden
    // COMPLETE -> Übertragung ist fertig
    // ERROR    -> Receiver meldet Fehler
    private boolean onReceiverReply(short txId, RandomAccessFile raf, long fileSize,
                                    int maxSeq, Packet last,
                                    SlidingWindow window, TransferState state,
                                    ControlPacket reply) throws Exception {
        // Antworttyp prüfen und an die passende Methode weitergeben
        switch (reply.type()) {
            case ACK -> onAckReceived(txId, raf, fileSize, maxSeq, window, state, reply.ackBase());
            case NAK -> onNakReceived(txId, raf, fileSize, maxSeq, last, state, reply);
            case COMPLETE -> { return true; }
            case ERROR -> throw new IllegalStateException("Receiver error: " + reply.errorMessage());
        }

        return false;
    }

    // ACK bedeutet:
    // "Ich habe alles bis ackBase - 1 erhalten und warte jetzt auf ackBase."
    //
    // Wenn ackBase größer wird, kann das Fenster weitergeschoben werden.
    // Wenn derselbe ACK mehrfach kommt, kann ein Paket verloren sein.
    private void onAckReceived(short txId, RandomAccessFile raf, long fileSize,
                               int maxSeq, SlidingWindow window,
                               TransferState state, int ackBase) throws Exception {
        int oldBase = window.getFirstUnackedSeq();

        if (ackBase > oldBase) {
            // Neuer ACK-Fortschritt: Fenster nach vorne schieben
            window.onAck(ackBase);

            // Bestätigte DATA aus Timeout-/Retry-Tracking entfernen
            clearAcked(state.sentAt, state.retries, oldBase, window.getFirstUnackedSeq());

            // Erfolgreicher Fortschritt: Fenster langsam vergrößern
            state.cwnd = Math.min(config.windowSize(), state.cwnd + 1);
            state.rtoMs = config.startReplyTimeoutMs();

            // Duplicate-ACK-Zustand zurücksetzen
            state.dupAckCount = 0;
            state.fastRetransmittedAt = -1;
        } else if (ackBase == oldBase && ackBase >= 1 && ackBase <= maxSeq) {
            // Gleicher ACK wie vorher: Receiver wartet noch auf dieselbe Sequenz
            state.dupAckCount = ackBase == state.lastAckBase ? state.dupAckCount + 1 : 1;

            // Mehrere gleiche ACKs: vermutlich fehlt genau dieses DATA
            if (state.dupAckCount >= config.sameAckLimit()
                    && state.fastRetransmittedAt != ackBase) {
                retransmitData(txId, raf, fileSize, maxSeq, ackBase, state, false);
                state.sentAt.put(ackBase, System.currentTimeMillis());
                state.fastRetransmittedAt = ackBase;
            }
        }

        // Letzten ACK merken, damit Duplicate ACKs erkannt werden
        state.lastAckBase = ackBase;
    }

    // NAK enthält konkrete fehlende Sequenznummern.
    // Diese DATA-Pakete werden sofort erneut gesendet.
    private void onNakReceived(short txId, RandomAccessFile raf, long fileSize,
                               int maxSeq, Packet last,
                               TransferState state, ControlPacket reply) throws Exception {
        // Alle vom Receiver genannten Lücken reparieren
        for (int seq : reply.missingSequences()) {
            retransmitData(txId, raf, fileSize, maxSeq, seq, state, true);
            state.sentAt.put(seq, System.currentTimeMillis());
        }

        // Bei NAK vorsichtiger senden
        state.cwnd = Math.max(config.minWindowSize(), state.cwnd / 2);
        state.rtoMs = Math.min(config.maxReplyTimeoutMs(), state.rtoMs * 2);

        // LAST erneut senden, damit Receiver nach der Reparatur prüfen kann
        send(last);
        state.lastSent = true;
    }

    // ============================================================
    // Retransmission und Bestätigungen
    // ============================================================

    private boolean timedOut(Map<Integer, Long> sentAt, int seq, int rtoMs) {
        Long time = sentAt.get(seq);
        return time != null && System.currentTimeMillis() - time >= rtoMs;
    }

    private void clearAcked(Map<Integer, Long> sentAt, Map<Integer, Integer> retries, int from, int to) {
        for (int seq = from; seq < to; seq++) {
            sentAt.remove(seq);
            retries.remove(seq);
        }
    }

    // Sendet ein bestimmtes DATA-Paket erneut.
    // Bei Timeout/NAK wird der Retry-Zähler erhöht.
    // Bei Fast Retransmit durch Duplicate ACK wird er nicht erhöht.
    private void retransmitData(short txId, RandomAccessFile raf, long fileSize,
                                int maxSeq, int seq,
                                TransferState state, boolean countRetry) throws Exception {
        // Ungültige Sequenzen ignorieren
        if (seq < 1 || seq > maxSeq) return;

        // Retry-Limit nur bei Timeout/NAK zählen
        if (countRetry && state.retries.merge(seq, 1, Integer::sum) > config.maxRetriesPerPacket()) {
            throw new IllegalStateException("Retry limit exceeded for seq=" + seq);
        }

        // DATA erneut aus der Datei lesen und senden
        byte[] data = readChunk(raf, fileSize, seq);
        send(new Packet(PacketType.DATA, txId, seq, 0, data, null, null));
    }

    // ============================================================
    // Netzwerk-I/O
    // ============================================================

    // Wartet auf eine Antwort des Receivers.
    // Ergebnis ist ACK, NAK, COMPLETE, ERROR oder null bei Timeout.
    private ControlPacket waitForReceiverReply(short expectedTxId) throws Exception {
        byte[] buffer = new byte[config.controlBufferSize()];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);

        try {
            // Auf UDP-Antwort warten
            socket.receive(datagram);
        } catch (SocketTimeoutException e) {
            // Keine Antwort innerhalb des Socket-Timeouts
            return null;
        }

        try {
            // UDP-Daten in ControlPacket umwandeln
            ControlPacket ctrl = serializer.deserializeControlPacket(
                    Arrays.copyOf(datagram.getData(), datagram.getLength()));

            // Nur Pakete derselben Übertragung akzeptieren
            return ctrl.transmissionId() == expectedTxId ? ctrl : null;
        } catch (IllegalArgumentException e) {
            // Ungültige oder fremde Pakete ignorieren
            return null;
        }
    }

    private void send(Packet packet) throws Exception {
        // Protokollpaket in Bytes serialisieren und per UDP senden
        byte[] bytes = serializer.serialize(packet);
        socket.send(new DatagramPacket(bytes, bytes.length, address, port));

        // Optionaler Test-Delay zwischen Sendungen
        if (delayMs > 0) Thread.sleep(delayMs);
    }

    // ============================================================
    // Datei lesen und Sequenzen berechnen
    // ============================================================

    // Liest genau den Dateiausschnitt, der zu einer DATA-Sequenznummer gehört.
    private byte[] readChunk(RandomAccessFile raf, long fileSize, int seq) throws IOException {
        // Position des Chunks in der Datei berechnen
        long offset = (long) (seq - 1) * config.chunkSize();
        if (offset >= fileSize) throw new IOException("Invalid chunk seq=" + seq);

        // Letzter Chunk kann kleiner als chunkSize sein
        int size = (int) Math.min(config.chunkSize(), fileSize - offset);
        byte[] chunk = new byte[size];

        // Chunk aus der Datei lesen
        raf.seek(offset);
        raf.readFully(chunk);

        return chunk;
    }

    // Berechnet, wie viele DATA-Pakete für die Datei nötig sind.
    private int maxSeq(long fileSize) {
        int chunkSize = config.chunkSize();

        // Aufrunden: auch ein kleiner Rest braucht ein eigenes DATA-Paket
        return fileSize == 0 ? 0 : (int) ((fileSize + chunkSize - 1) / chunkSize);
    }

    // ============================================================
    // Zustand einer einzelnen Dateiübertragung
    // ============================================================

    // Speichert nur Laufzeitdaten der aktuellen Übertragung.
    // Interner Zustand des Senders.
    private static class TransferState {
        final Map<Integer, Long> sentAt = new HashMap<>();
        final Map<Integer, Integer> retries = new HashMap<>();
        int cwnd;
        int rtoMs;
        int idleRounds;
        int lastAckBase = -1;
        int dupAckCount;
        int fastRetransmittedAt = -1;
        boolean lastSent;

        TransferState(SenderConfig config) {
            this.cwnd = Math.min(config.startWindowSize(), config.windowSize());
            this.rtoMs = config.startReplyTimeoutMs();
        }
    }
}