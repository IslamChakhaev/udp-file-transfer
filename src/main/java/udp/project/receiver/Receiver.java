package udp.project.receiver;

import udp.project.protocol.ControlPacket;
import udp.project.protocol.Packet;
import udp.project.protocol.PacketSerializer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Receiver implements AutoCloseable {

    private static final int BUFFER_SIZE = 32_384;
    private static final int SOCKET_TIMEOUT_MS = 500;
    private static final int BATCH_DRAIN_TIMEOUT_MS = 1;
    private static final int MAX_BATCH_SIZE = 256;
    private static final int MAX_PENDING_RAW = 512;
    private static final long NAK_INTERVAL_MS = 700;

    private final DatagramSocket socket;
    private final Path outputDirectory;
    private final long idleTimeoutMs;
    private final PacketSerializer serializer = new PacketSerializer();

    private final Map<Integer, ReceiveSession> sessions = new HashMap<>();
    private final Map<Integer, List<byte[]>> pending = new HashMap<>();

    public Receiver(int port) throws Exception {
        this(port, Path.of("."), 10_000);
    }

    public Receiver(int port, Path outputDirectory, long idleTimeoutMs) throws Exception {
        this.outputDirectory = outputDirectory.toAbsolutePath().normalize();
        this.idleTimeoutMs = idleTimeoutMs;

        Files.createDirectories(this.outputDirectory);

        this.socket = new DatagramSocket(port);
        this.socket.setSoTimeout(SOCKET_TIMEOUT_MS);
    }

    // ============================================================
    // Hauptablauf
    //
    // 1. UDP-Pakete empfangen
    // 2. Pakete einer Session zuordnen
    // 3. Control-Antworten erzeugen
    // 4. ACKs zusammenfassen und Antworten senden
    // ============================================================

    public void start() throws Exception {
        System.out.printf("RX start: port=%d, output=%s%n", socket.getLocalPort(), outputDirectory);

        byte[] buffer = new byte[BUFFER_SIZE];

        while (!socket.isClosed()) {
            try {
                for (OutgoingControl outgoing : handleBatch(receiveBatch(buffer))) {
                    sendControl(outgoing.packet(), outgoing.remote());
                }
            } catch (SocketTimeoutException ignored) {
                onTimeout();
            } catch (Exception e) {
                System.err.println("RX error: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        for (ReceiveSession session : sessions.values()) {
            if (!session.isComplete()) {
                session.cleanPartFile();
            }
        }

        sessions.clear();
        pending.clear();
        socket.close();
    }

    // ============================================================
    // UDP-Pakete im kleinen Batch lesen
    //
    // Das erste Paket wird normal empfangen.
    // Danach wird sehr kurz weitergelesen, damit bereits gepufferte
    // Pakete gemeinsam verarbeitet werden können.
    // ============================================================

    private List<IncomingPacket> receiveBatch(byte[] buffer) throws Exception {
        List<IncomingPacket> batch = new ArrayList<>();
        batch.add(toIncomingPacket(receive(buffer)));

        try {
            socket.setSoTimeout(BATCH_DRAIN_TIMEOUT_MS);

            while (batch.size() < MAX_BATCH_SIZE) {
                try {
                    batch.add(toIncomingPacket(receive(buffer)));
                } catch (SocketTimeoutException ignored) {
                    break;
                }
            }
        } finally {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        }

        return batch;
    }

    private IncomingPacket toIncomingPacket(DatagramPacket datagram) {
        byte[] raw = Arrays.copyOf(datagram.getData(), datagram.getLength());
        return new IncomingPacket(raw, datagram.getSocketAddress());
    }

    // ============================================================
    // Control-Antworten aus einem Batch erzeugen
    //
    // ACK wird gesammelt:
    // ACK 2, ACK 3, ACK 65 -> ACK 65
    //
    // NAK, COMPLETE und ERROR werden sofort übernommen.
    // Für diese Fälle wird kein zusätzlicher ACK mehr gesendet.
    // ============================================================

    private List<OutgoingControl> handleBatch(List<IncomingPacket> batch) {
        List<OutgoingControl> outgoing = new ArrayList<>();
        Map<AckKey, Integer> bestAckByTransfer = new HashMap<>();
        Set<AckKey> blockedAckKeys = new HashSet<>();

        for (IncomingPacket incoming : batch) {
            for (ControlPacket response : handle(incoming.raw(), incoming.remote())) {
                AckKey key = new AckKey(incoming.remote(), response.transmissionId());

                switch (response.type()) {
                    case ACK -> bestAckByTransfer.merge(key, response.ackBase(), Math::max);

                    case NAK, COMPLETE, ERROR -> {
                        blockedAckKeys.add(key);
                        outgoing.add(new OutgoingControl(response, incoming.remote()));
                    }
                }
            }
        }

        for (Map.Entry<AckKey, Integer> entry : bestAckByTransfer.entrySet()) {
            AckKey key = entry.getKey();

            if (!blockedAckKeys.contains(key)) {
                outgoing.add(new OutgoingControl(ControlPacket.ack(key.txId(), entry.getValue()), key.remote()));
            }
        }

        return outgoing;
    }

    // ============================================================
    // Paket-Routing
    //
    // FIRST startet oder findet eine Session.
    // DATA/LAST werden an die bestehende Session weitergegeben.
    // Wenn DATA/LAST vor FIRST kommen, werden sie kurz gepuffert.
    // ============================================================

    private List<ControlPacket> handle(byte[] raw, SocketAddress remote) {
        if (raw.length < PacketSerializer.DATA_HEADER_SIZE) {
            return List.of();
        }

        Header header = peekHeader(raw);

        if (header.isFirst()) {
            return handleFirst(raw, remote, header.txId());
        }

        return handleDataOrLast(raw, remote, header);
    }

    private List<ControlPacket> handleFirst(byte[] raw, SocketAddress remote, short txId) {
        try {
            Packet first = serializer.deserialize(raw);
            ReceiveSession session = sessions.computeIfAbsent(Short.toUnsignedInt(txId), id -> new ReceiveSession(txId, outputDirectory));

            List<ControlPacket> responses = new ArrayList<>(session.accept(first, remote));
            responses.addAll(processPending(session, remote));

            return responses;
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    private List<ControlPacket> handleDataOrLast(byte[] raw, SocketAddress remote, Header header) {
        ReceiveSession session = sessions.get(header.unsignedTxId());

        if (session == null) {
            storePending(header.unsignedTxId(), raw);
            return List.of();
        }

        if (session.isComplete()) {
            return List.of(ControlPacket.complete(header.txId()));
        }

        try {
            Packet packet = serializer.deserialize(raw, session.getMaxSeq());
            return session.accept(packet, remote);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    // ============================================================
    // Pending-Pakete
    //
    // UDP kann Pakete in anderer Reihenfolge liefern.
    // DATA/LAST vor FIRST werden deshalb kurz zwischengespeichert.
    // ============================================================

    private void storePending(int txId, byte[] raw) {
        List<byte[]> packets = pending.computeIfAbsent(txId, id -> new ArrayList<>());

        if (packets.size() < MAX_PENDING_RAW) {
            packets.add(raw);
        }
    }

    private List<ControlPacket> processPending(ReceiveSession session, SocketAddress remote) {
        List<byte[]> packets = pending.remove(Short.toUnsignedInt(session.txId()));

        if (packets == null || packets.isEmpty()) {
            return List.of();
        }

        List<ControlPacket> responses = new ArrayList<>();

        for (byte[] raw : packets) {
            try {
                Packet packet = serializer.deserialize(raw, session.getMaxSeq());
                responses.addAll(session.accept(packet, remote));
            } catch (IllegalArgumentException ignored) {
                // Kaputte oder unpassende Pakete werden ignoriert.
            }
        }

        return responses;
    }

    // ============================================================
    // Timeout
    //
    // Socket-Timeout heißt nicht automatisch Fehler.
    // Er wird als regelmäßiger Takt für NAK und Cleanup genutzt.
    // ============================================================

    private void onTimeout() throws Exception {
        long now = System.currentTimeMillis();
        Iterator<ReceiveSession> iterator = sessions.values().iterator();

        while (iterator.hasNext()) {
            ReceiveSession session = iterator.next();

            if (session.isComplete() && now - session.getLastSeenAt() > idleTimeoutMs) {
                iterator.remove();
                continue;
            }

            if (now - session.getLastSeenAt() > idleTimeoutMs) {
                sendControl(ControlPacket.error(session.txId(), "Transfer timeout"), session.remoteAddress());
                session.cleanPartFile();
                iterator.remove();
                continue;
            }

            if (session.shouldSendNak(now, NAK_INTERVAL_MS)) {
                sendControl(session.nak(), session.remoteAddress());
            }
        }
    }

    // ============================================================
    // Netzwerk-I/O
    // ============================================================

    private DatagramPacket receive(byte[] buffer) throws Exception {
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        socket.receive(datagram);
        return datagram;
    }

    private void sendControl(ControlPacket packet, SocketAddress remote) throws Exception {
        if (remote == null) {
            return;
        }

        byte[] bytes = serializer.serialize(packet);
        DatagramPacket datagram = new DatagramPacket(bytes, bytes.length);

        datagram.setSocketAddress(remote);
        socket.send(datagram);
    }

    private Header peekHeader(byte[] raw) {
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        return new Header(buffer.getShort(), buffer.getInt());
    }

    // ============================================================
    // Kleine interne Datenstrukturen
    // ============================================================

    private record IncomingPacket(byte[] raw, SocketAddress remote) {
    }

    private record OutgoingControl(ControlPacket packet, SocketAddress remote) {
    }

    private record AckKey(SocketAddress remote, short txId) {
    }

    private record Header(short txId, int seq) {
        boolean isFirst() {
            return seq == 0;
        }

        int unsignedTxId() {
            return Short.toUnsignedInt(txId);
        }
    }
}