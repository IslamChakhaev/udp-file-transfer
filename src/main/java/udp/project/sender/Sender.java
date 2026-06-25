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

    private static final int DUPLICATE_ACK_LIMIT = 3;

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
    //
    // 1. FIRST senden
    // 2. DATA mit Sliding Window senden
    // 3. Eine Control-Antwort pro Runde verarbeiten
    // 4. LAST senden, wenn alle DATA bestätigt wurden
    // 5. COMPLETE abwarten
    // ============================================================

    public void sendFile(String filePath) throws Exception {
        File file = new File(filePath);

        if (!file.isFile() || !file.canRead()) {
            throw new IllegalArgumentException("File not readable: " + filePath);
        }

        long fileSize = file.length();
        int maxSeq = maxSeq(fileSize);
        short txId = (short) (System.nanoTime() & 0xFFFF);

        Packet first = new Packet(PacketType.FIRST, txId, 0, maxSeq, null, file.getName(), null);
        Packet last = new Packet(PacketType.LAST, txId, maxSeq + 1, 0, null, null, Md5Util.calculateFile(file.toPath()));

        long started = System.currentTimeMillis();

        System.out.printf("TX start: file=%s, size=%d, txId=%d, chunks=%d%n", file.getName(), fileSize, Short.toUnsignedInt(txId), maxSeq);

        send(first);

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            sendDataWithSlidingWindow(txId, raf, fileSize, maxSeq, last);
        }

        System.out.printf("TX complete: file=%s, time=%d ms%n", file.getName(), System.currentTimeMillis() - started);
    }

    @Override
    public void close() {
        socket.close();
    }

    // ============================================================
    // Sliding Window
    //
    // Sender wartet nicht nach jedem DATA-Paket einzeln.
    // Er füllt zuerst das Fenster und wartet danach auf eine
    // Control-Antwort vom Receiver.
    // ============================================================

    private void sendDataWithSlidingWindow(short txId, RandomAccessFile raf, long fileSize, int maxSeq, Packet last) throws Exception {
        socket.setSoTimeout(config.replyWaitTimeoutMs());

        SlidingWindow window = new SlidingWindow(maxSeq);
        TransferState state = new TransferState();

        while (state.idleRounds < config.maxNoReplyRounds()) {
            // 1. Fenster mit neuen DATA-Paketen füllen.
            sendDataWhileWindowAllows(txId, raf, fileSize, window, state);

            // 2. Wenn alle DATA bestätigt wurden, LAST senden.
            sendLastWhenAllDataAcked(window, state, last);

            // 3. Eine Control-Antwort lesen: ACK, NAK, COMPLETE oder ERROR.
            ControlPacket reply = waitForReceiverReply(txId);

            if (reply == null) {
                onReceiverTimeout(txId, raf, fileSize, maxSeq, last, window, state);
                continue;
            }

            state.idleRounds = 0;

            if (onReceiverReply(txId, raf, fileSize, maxSeq, last, window, state, reply)) {
                return;
            }
        }

        throw new IllegalStateException("Transfer failed: COMPLETE not received");
    }

    private void sendDataWhileWindowAllows(short txId, RandomAccessFile raf, long fileSize, SlidingWindow window, TransferState state) throws Exception {
        while (window.canSend(config.windowSize())) {
            int seq = window.nextToSend();
            byte[] data = readChunk(raf, fileSize, seq);

            send(new Packet(PacketType.DATA, txId, seq, 0, data, null, null));

            state.sentAt.put(seq, System.currentTimeMillis());
            window.markSent();
        }
    }

    private void sendLastWhenAllDataAcked(SlidingWindow window, TransferState state, Packet last) throws Exception {
        if (window.isFinished() && !state.lastSent) {
            send(last);
            state.lastSent = true;
        }
    }

    // ============================================================
    // Control-Antwort lesen
    // ============================================================

    private ControlPacket waitForReceiverReply(short expectedTxId) throws Exception {
        byte[] buffer = new byte[config.controlBufferSize()];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);

        try {
            socket.receive(datagram);
        } catch (SocketTimeoutException e) {
            return null;
        }

        try {
            ControlPacket ctrl = serializer.deserializeControlPacket(Arrays.copyOf(datagram.getData(), datagram.getLength()));
            return ctrl.transmissionId() == expectedTxId ? ctrl : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ============================================================
    // ACK / NAK / COMPLETE verarbeiten
    //
    // ACK      -> Fenster verschieben oder Fast Retransmit auslösen
    // NAK      -> fehlende DATA-Pakete erneut senden
    // COMPLETE -> Transfer erfolgreich beenden
    // ERROR    -> Transfer abbrechen
    // ============================================================

    private boolean onReceiverReply(short txId, RandomAccessFile raf, long fileSize, int maxSeq, Packet last, SlidingWindow window, TransferState state, ControlPacket reply) throws Exception {
        switch (reply.type()) {
            case ACK -> onAckReceived(txId, raf, fileSize, maxSeq, window, state, reply.ackBase());

            case NAK -> retransmitMissingData(txId, raf, fileSize, maxSeq, state, reply);

            case COMPLETE -> {
                return true;
            }

            case ERROR -> throw new IllegalStateException("Receiver error: " + reply.errorMessage());
        }

        if (window.isFinished() && !state.lastSent) {
            send(last);
            state.lastSent = true;
        }

        return false;
    }

    private void onAckReceived(short txId, RandomAccessFile raf, long fileSize, int maxSeq, SlidingWindow window, TransferState state, int ackBase) throws Exception {
        int oldBase = window.getFirstUnackedSeq();

        if (ackBase > oldBase) {
            window.onAck(ackBase);
            clearAcked(state.sentAt, state.retries, oldBase, window.getFirstUnackedSeq());

            state.lastAckBase = ackBase;
            state.duplicateAckCount = 0;
            return;
        }

        if (ackBase == oldBase) {
            registerDuplicateAck(txId, raf, fileSize, maxSeq, window, state, ackBase);
        }
    }

    // ============================================================
    // Fast Retransmit
    //
    // Wenn derselbe ACK mehrfach kommt, fehlt wahrscheinlich genau
    // die erste unbestätigte Sequenz. Diese wird sofort erneut gesendet.
    // ============================================================

    private void registerDuplicateAck(short txId, RandomAccessFile raf, long fileSize, int maxSeq, SlidingWindow window, TransferState state, int ackBase) throws Exception {
        if (ackBase != state.lastAckBase) {
            state.lastAckBase = ackBase;
            state.duplicateAckCount = 1;
            return;
        }

        state.duplicateAckCount++;

        if (state.duplicateAckCount >= DUPLICATE_ACK_LIMIT) {
            int missingSeq = window.getFirstUnackedSeq();

            retransmitData(txId, raf, fileSize, maxSeq, missingSeq, state);
            state.sentAt.put(missingSeq, System.currentTimeMillis());
            state.duplicateAckCount = 0;
        }
    }

    private void retransmitMissingData(short txId, RandomAccessFile raf, long fileSize, int maxSeq, TransferState state, ControlPacket reply) throws Exception {
        for (int seq : reply.missingSequences()) {
            retransmitData(txId, raf, fileSize, maxSeq, seq, state);
            state.sentAt.put(seq, System.currentTimeMillis());
        }
    }

    // ============================================================
    // Timeout / Retransmission
    //
    // Wenn keine Antwort kommt:
    // - solange DATA offen sind, wird das erste unbestätigte DATA geprüft
    // - wenn alle DATA bestätigt sind, wird LAST gelegentlich wiederholt
    // ============================================================

    private void onReceiverTimeout(short txId, RandomAccessFile raf, long fileSize, int maxSeq, Packet last, SlidingWindow window, TransferState state) throws Exception {
        state.idleRounds++;

        if (!window.isFinished()) {
            int seq = window.getFirstUnackedSeq();

            if (timedOut(state.sentAt, seq)) {
                retransmitData(txId, raf, fileSize, maxSeq, seq, state);
                state.sentAt.put(seq, System.currentTimeMillis());
            }

            return;
        }

        if (state.idleRounds % 3 == 0) {
            send(last);
            state.lastSent = true;
        }
    }

    private boolean timedOut(Map<Integer, Long> sentAt, int seq) {
        Long time = sentAt.get(seq);
        return time != null && System.currentTimeMillis() - time >= config.replyWaitTimeoutMs();
    }

    private void retransmitData(short txId, RandomAccessFile raf, long fileSize, int maxSeq, int seq, TransferState state) throws Exception {
        if (seq < 1 || seq > maxSeq) {
            return;
        }

        int retries = state.retries.merge(seq, 1, Integer::sum);

        if (retries > config.maxRetriesPerPacket()) {
            throw new IllegalStateException("Retry limit exceeded for seq=" + seq);
        }

        byte[] data = readChunk(raf, fileSize, seq);
        send(new Packet(PacketType.DATA, txId, seq, 0, data, null, null));
    }

    private void clearAcked(Map<Integer, Long> sentAt, Map<Integer, Integer> retries, int from, int to) {
        for (int seq = from; seq < to; seq++) {
            sentAt.remove(seq);
            retries.remove(seq);
        }
    }

    // ============================================================
    // UDP send
    // ============================================================

    private void send(Packet packet) throws Exception {
        byte[] bytes = serializer.serialize(packet);
        socket.send(new DatagramPacket(bytes, bytes.length, address, port));

        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
    }

    // ============================================================
    // Datei lesen
    // ============================================================

    private byte[] readChunk(RandomAccessFile raf, long fileSize, int seq) throws IOException {
        long offset = (long) (seq - 1) * config.chunkSize();

        if (offset >= fileSize) {
            throw new IOException("Invalid chunk seq=" + seq);
        }

        int size = (int) Math.min(config.chunkSize(), fileSize - offset);
        byte[] chunk = new byte[size];

        raf.seek(offset);
        raf.readFully(chunk);

        return chunk;
    }

    private int maxSeq(long fileSize) {
        int chunkSize = config.chunkSize();
        return fileSize == 0 ? 0 : (int) ((fileSize + chunkSize - 1) / chunkSize);
    }

    // ============================================================
    // Zustand einer Dateiübertragung
    // ============================================================

    private static class TransferState {
        final Map<Integer, Long> sentAt = new HashMap<>();
        final Map<Integer, Integer> retries = new HashMap<>();

        int idleRounds;
        int lastAckBase = -1;
        int duplicateAckCount;
        boolean lastSent;
    }
}