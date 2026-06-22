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

    // CHUNK_SIZE = 1400 Byte: knapp unter MTU, damit Router keine IP-Fragmentierung auslösen.
    // Muss mit der kompatiblen Implementierung übereinstimmen.
    // WINDOW_SIZE, RTO-Werte und RETRY_LIMIT steuern zusammen Durchsatz und Fehlertoleranz.
    private static final int CHUNK_SIZE = 1400;
    private static final int BUFFER_SIZE = 4096;
    private static final int SOCKET_BUFFER_BYTES = 4 * 1024 * 1024;

    private static final int WINDOW_SIZE = 64;       // max. Pakete gleichzeitig in flight
    private static final int INITIAL_CWND = 8;       // Startwert; wächst pro ACK, halbiert sich bei NAK/Timeout
    private static final int MIN_CWND = 4;
    private static final int INITIAL_RTO_MS = 300;   // Retransmission Timeout; verdoppelt sich bei Timeout (Backoff)
    private static final int MAX_RTO_MS = 3_000;
    private static final int CONTROL_TIMEOUT_MS = 100; // Wartezeit pro Runde auf ein Control-Paket
    private static final int IDLE_LIMIT = 80;          // Runden ohne Antwort bis Abbruch
    private static final int RETRY_LIMIT = 10;         // max. Wiederholungen pro seq
    private static final int DUP_ACK_THRESHOLD = 3;   // Duplikat-ACKs bis Fast Retransmit

    // Socket und Zieladresse werden für alle sendFile()-Aufrufe dieser Instanz wiederverwendet.
    private final DatagramSocket socket;
    private final InetAddress address;
    private final int port;
    private final int delayMs;
    private final PacketSerializer serializer = new PacketSerializer();

    public Sender(String host, int port, int delayMs) throws Exception {
        this.socket = new DatagramSocket();
        this.socket.setSendBufferSize(SOCKET_BUFFER_BYTES);
        this.socket.setReceiveBufferSize(SOCKET_BUFFER_BYTES);
        this.address = InetAddress.getByName(host);
        this.port = port;
        this.delayMs = Math.max(0, delayMs);
    }

    public void sendFile(String filePath) throws Exception {
        sendFile(filePath, null);
    }

    // Protokollablauf: FIRST → DATA (Sliding Window) → LAST → warten auf COMPLETE.
    // MD5 wird vor dem Senden aus der vollständigen Datei berechnet und im LAST-Paket mitgeschickt.
    public void sendFile(String filePath, Short fixedTxId) throws Exception {
        File file = new File(filePath);
        if (!file.isFile() || !file.canRead()) throw new IllegalArgumentException("File not readable: " + filePath);

        long fileSize = file.length();
        int maxSeq = maxSeq(fileSize);
        short txId = fixedTxId != null ? fixedTxId : (short) (System.nanoTime() & 0xFFFF);
        Packet first = firstPacket(txId, maxSeq, file.getName());
        Packet last = lastPacket(txId, maxSeq, Md5Util.calculateFile(file.toPath()));

        long started = System.currentTimeMillis();
        System.out.printf("TX start: file=%s, size=%d, txId=%d, chunks=%d%n",
                file.getName(), fileSize, Short.toUnsignedInt(txId), maxSeq);

        send(first);

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            sendWithWindow(txId, raf, fileSize, maxSeq, last);
        }

        System.out.printf("TX complete: file=%s, time=%d ms%n", file.getName(), System.currentTimeMillis() - started);
    }

    @Override
    public void close() {
        socket.close();
    }

    // Kern der Übertragung: alle Chunks per Sliding Window senden und auf Control-Pakete reagieren.
    // Ohne das Window würde der Sender auf jedes ACK warten (Stop-and-Wait) – viel zu langsam.
    //   ACK      → Fenster vorrücken; 3× gleicher ACK → Fast Retransmit.
    //   NAK      → fehlende Chunks sofort erneut senden + LAST schicken.
    //   COMPLETE → fertig.
    //   Timeout  → RTO-Backoff; nach IDLE_LIMIT Runden ohne Antwort abbrechen.
    private void sendWithWindow(short txId, RandomAccessFile raf, long fileSize,
                                int maxSeq, Packet last) throws Exception {
        socket.setSoTimeout(CONTROL_TIMEOUT_MS);
        SlidingWindow window = new SlidingWindow(maxSeq);
        Map<Integer, Long> sentAt = new HashMap<>();
        Map<Integer, Integer> retries = new HashMap<>();

        int cwnd = Math.min(INITIAL_CWND, WINDOW_SIZE);
        int rtoMs = INITIAL_RTO_MS;
        int idleRounds = 0;
        int lastAckBase = -1;
        int dupAckCount = 0;
        int fastRetransmittedAt = -1;
        boolean lastSent = false;

        while (idleRounds < IDLE_LIMIT) {
            sendAvailable(txId, raf, fileSize, window, sentAt, cwnd);

            if (window.isFinished() && !lastSent) {
                send(last);
                lastSent = true;
            }

            ControlPacket ctrl = readControl(txId);
            if (ctrl == null) {
                idleRounds++;
                if (!window.isFinished() && timedOut(sentAt, window.getBaseSeq(), rtoMs)) {
                    // RTO abgelaufen: ältestes Paket erneut senden, RTO verdoppeln.
                    retransmit(txId, raf, fileSize, maxSeq, window.getBaseSeq(), retries, true);
                    sentAt.put(window.getBaseSeq(), System.currentTimeMillis());
                    cwnd = Math.max(MIN_CWND, cwnd / 2);
                    rtoMs = Math.min(MAX_RTO_MS, rtoMs * 2);
                    dupAckCount = 0;
                    fastRetransmittedAt = -1;
                } else if (window.isFinished() && idleRounds % 3 == 0) {
                    // Alle Daten bestätigt, aber kein COMPLETE: LAST periodisch wiederholen.
                    send(last);
                    lastSent = true;
                }
                continue;
            }

            idleRounds = 0;
            switch (ctrl.type()) {
                case ACK -> {
                    int ackBase = ctrl.ackBase();
                    int oldBase = window.getBaseSeq();

                    if (ackBase > oldBase) {
                        // Fenster vorrücken: Receiver hat alles bis ackBase-1 empfangen.
                        window.onAck(ackBase);
                        clearAcked(sentAt, retries, oldBase, window.getBaseSeq());
                        cwnd = Math.min(WINDOW_SIZE, cwnd + 1);
                        rtoMs = INITIAL_RTO_MS;
                        dupAckCount = 0;
                        fastRetransmittedAt = -1;
                    } else if (ackBase == oldBase && ackBase >= 1 && ackBase <= maxSeq) {
                        // Duplikat-ACK: gleiche ackBase → nach DUP_ACK_THRESHOLD einmalig Fast Retransmit.
                        dupAckCount = ackBase == lastAckBase ? dupAckCount + 1 : 1;
                        if (dupAckCount >= DUP_ACK_THRESHOLD && fastRetransmittedAt != ackBase) {
                            retransmit(txId, raf, fileSize, maxSeq, ackBase, retries, false);
                            sentAt.put(ackBase, System.currentTimeMillis());
                            fastRetransmittedAt = ackBase;
                        }
                    }
                    lastAckBase = ackBase;
                }
                case NAK -> {
                    // Alle fehlenden Sequenzen erneut senden; LAST danach schicken,
                    // damit der Receiver nach der Reparatur sofort die MD5 prüfen kann.
                    for (int seq : ctrl.missingSequences()) {
                        retransmit(txId, raf, fileSize, maxSeq, seq, retries, true);
                        sentAt.put(seq, System.currentTimeMillis());
                    }
                    cwnd = Math.max(MIN_CWND, cwnd / 2);
                    rtoMs = Math.min(MAX_RTO_MS, rtoMs * 2);
                    send(last);
                    lastSent = true;
                }
                case COMPLETE -> { return; }
                case ERROR -> throw new IllegalStateException("Receiver error: " + ctrl.errorMessage());
            }
        }

        throw new IllegalStateException("Transfer failed: COMPLETE not received");
    }

    // Füllt das Sliding Window: schickt mehrere DATA-Pakete raus, ohne auf ACKs zu warten.
    // Das ermöglicht hohen Durchsatz, weil die Netzwerk-Latenz überbrückt wird.
    private void sendAvailable(short txId, RandomAccessFile raf, long fileSize,
                               SlidingWindow window, Map<Integer, Long> sentAt, int cwnd) throws Exception {
        while (window.canSend(cwnd)) {
            int seq = window.nextToSend();
            send(dataPacket(txId, seq, readChunk(raf, fileSize, seq)));
            sentAt.put(seq, System.currentTimeMillis());
            window.markSent();
        }
    }

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

    // countRetry=false bei Fast Retransmit: kein Zähler, da kein dauerhafter Paketverlust angenommen wird.
    private void retransmit(short txId, RandomAccessFile raf, long fileSize, int maxSeq,
                            int seq, Map<Integer, Integer> retries, boolean countRetry) throws Exception {
        if (seq < 1 || seq > maxSeq) return;
        if (countRetry && retries.merge(seq, 1, Integer::sum) > RETRY_LIMIT) {
            throw new IllegalStateException("Retry limit exceeded for seq=" + seq);
        }
        send(dataPacket(txId, seq, readChunk(raf, fileSize, seq)));
    }

    // Wartet auf ein Control-Paket (ACK/NAK/COMPLETE/ERROR) vom Receiver.
    // Gibt null zurück bei Timeout oder Paketen mit falscher txId.
    private ControlPacket readControl(short expectedTxId) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);

        try {
            socket.receive(datagram);
        } catch (SocketTimeoutException e) {
            return null;
        }

        try {
            ControlPacket ctrl = serializer.deserializeControlPacket(
                    Arrays.copyOf(datagram.getData(), datagram.getLength()));
            return ctrl.transmissionId() == expectedTxId ? ctrl : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // delayMs > 0: künstliche Verzögerung zwischen Sends (Pacing für Tests).
    private void send(Packet packet) throws Exception {
        byte[] bytes = serializer.serialize(packet);
        socket.send(new DatagramPacket(bytes, bytes.length, address, port));
        if (delayMs > 0) Thread.sleep(delayMs);
    }

    // Protokollpakete erzeugen: FIRST (seq=0), DATA und LAST (seq=maxSeq+1 mit MD5).
    // Die besonderen seq-Werte für FIRST und LAST verhindern Verwechslungen mit DATA.
    private Packet firstPacket(short txId, int maxSeq, String fileName) {
        return new Packet(PacketType.FIRST, txId, 0, maxSeq, null, fileName, null);
    }

    private Packet dataPacket(short txId, int seq, byte[] data) {
        return new Packet(PacketType.DATA, txId, seq, 0, data, null, null);
    }

    // LAST: seq=maxSeq+1 ist das Protokollsignal für "alle Daten gesendet"; enthält den MD5-Hash.
    private Packet lastPacket(short txId, int maxSeq, byte[] md5) {
        return new Packet(PacketType.LAST, txId, maxSeq + 1, 0, null, null, md5);
    }

    // Offset = (seq - 1) * CHUNK_SIZE, da Sequenznummern bei 1 beginnen.
    private static byte[] readChunk(RandomAccessFile raf, long fileSize, int seq) throws IOException {
        long offset = (long) (seq - 1) * CHUNK_SIZE;
        if (offset >= fileSize) throw new IOException("Invalid chunk seq=" + seq);
        int size = (int) Math.min(CHUNK_SIZE, fileSize - offset);
        byte[] chunk = new byte[size];
        raf.seek(offset);
        raf.readFully(chunk);
        return chunk;
    }

    // Ceiling-Division: z. B. 2801 Bytes / 1400 = 2 Chunks.
    private static int maxSeq(long fileSize) {
        return fileSize == 0 ? 0 : (int) ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
    }
}
