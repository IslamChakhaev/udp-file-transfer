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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class Receiver implements AutoCloseable {

    private static final int BUFFER_SIZE = 32_384;
    private static final int SOCKET_TIMEOUT_MS = 500;
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
    // Hauptszenario: UDP-Pakete empfangen und Control-Antworten senden
    //
    // 1. UDP-Paket empfangen
    // 2. Header lesen und txId/seq bestimmen
    // 3. FIRST startet oder bestätigt eine ReceiveSession
    // 4. DATA/LAST werden an die passende Session weitergegeben
    // 5. Receiver antwortet mit ACK, NAK, COMPLETE oder ERROR
    // 6. Timeout-Takt wiederholt NAKs oder entfernt alte Sessions
    // ============================================================

    public void start() throws Exception {
        System.out.printf("RX start: port=%d, output=%s%n", socket.getLocalPort(), outputDirectory);

        byte[] buffer = new byte[BUFFER_SIZE];

        while (!socket.isClosed()) {
            try {
                // Phase 1: UDP-Paket empfangen und auf echte Paketlänge kürzen
                DatagramPacket datagram = receive(buffer);
                byte[] raw = Arrays.copyOf(datagram.getData(), datagram.getLength());

                // Phase 2: Paket verarbeiten und alle Control-Antworten zurücksenden
                for (ControlPacket response : handle(raw, datagram.getSocketAddress())) {
                    sendControl(response, datagram.getSocketAddress());
                }
            } catch (SocketTimeoutException ignored) {
                // Phase 3: Kein Paket angekommen -> Timeout-Takt für NAK/Cleanup nutzen
                onTimeout();
            } catch (Exception e) {
                System.err.println("RX error: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        // Offene .part-Dateien entfernen, wenn der Receiver beendet wird
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
    // Paket-Routing
    //
    // Hier wird noch nicht die Datei geschrieben.
    // Der Receiver entscheidet nur:
    // - Ist es FIRST?
    // - Gehört es zu einer bestehenden Session?
    // - Muss es kurz gepuffert werden?
    // ============================================================

    private List<ControlPacket> handle(byte[] raw, SocketAddress remote) {
        // Zu kleine Pakete können kein gültiger DATA/FIRST/LAST-Header sein
        if (raw.length < PacketSerializer.DATA_HEADER_SIZE) {
            return List.of();
        }

        // Phase 1: Nur txId und seq lesen, ohne das ganze Paket zu deserialisieren
        Header header = peekHeader(raw);

        // Phase 2: FIRST startet die Übertragung
        if (header.isFirst()) {
            return handleFirst(raw, remote, header.txId());
        }

        // Phase 3: DATA oder LAST an bestehende Session weitergeben
        return handleDataOrLast(raw, remote, header);
    }

    private List<ControlPacket> handleFirst(byte[] raw, SocketAddress remote, short txId) {
        try {
            // FIRST vollständig lesen: Dateiname und maxSeq werden dort gebraucht
            Packet first = serializer.deserialize(raw);

            // Session für diese Übertragung anlegen oder vorhandene wiederverwenden
            ReceiveSession session = sessions.computeIfAbsent(
                    Short.toUnsignedInt(txId),
                    id -> new ReceiveSession(txId, outputDirectory)
            );

            // FIRST verarbeiten und danach eventuell vorher gepufferte DATA/LAST nachholen
            List<ControlPacket> responses = new ArrayList<>(session.accept(first, remote));
            responses.addAll(processPending(session, remote));

            return responses;
        } catch (IllegalArgumentException e) {
            // Ungültiges FIRST ignorieren
            return List.of();
        }
    }

    private List<ControlPacket> handleDataOrLast(byte[] raw, SocketAddress remote, Header header) {
        ReceiveSession session = sessions.get(header.unsignedTxId());

        // DATA/LAST kam vor FIRST: kurz puffern, damit UDP-Reordering nicht direkt stört
        if (session == null) {
            storePending(header.unsignedTxId(), raw);
            return List.of();
        }

        // Falls COMPLETE verloren ging, kann Receiver COMPLETE erneut senden
        if (session.isComplete()) {
            return List.of(ControlPacket.complete(header.txId()));
        }

        try {
            // DATA/LAST mit bekanntem maxSeq deserialisieren und an Session geben
            Packet packet = serializer.deserialize(raw, session.getMaxSeq());
            return session.accept(packet, remote);
        } catch (IllegalArgumentException e) {
            // Kaputte Pakete ignorieren
            return List.of();
        }
    }

    // DATA/LAST können bei UDP kurz vor FIRST ankommen.
    // Das ist keine Optimierung, sondern Reordering-Schutz.
    private void storePending(int txId, byte[] raw) {
        List<byte[]> packets = pending.computeIfAbsent(txId, id -> new ArrayList<>());

        // Limit verhindert, dass unbekannte txIds endlos Speicher belegen
        if (packets.size() < MAX_PENDING_RAW) {
            packets.add(raw);
        }
    }

    private List<ControlPacket> processPending(ReceiveSession session, SocketAddress remote) {
        // Gepufferte Pakete derselben Übertragung holen
        List<byte[]> packets = pending.remove(Short.toUnsignedInt(session.txId()));

        if (packets == null || packets.isEmpty()) {
            return List.of();
        }

        List<ControlPacket> responses = new ArrayList<>();

        for (byte[] raw : packets) {
            try {
                // Nach FIRST ist maxSeq bekannt, deshalb können DATA/LAST jetzt gelesen werden
                Packet packet = serializer.deserialize(raw, session.getMaxSeq());
                responses.addAll(session.accept(packet, remote));
            } catch (IllegalArgumentException ignored) {
                // Kaputte Pakete werden ignoriert.
            }
        }

        return responses;
    }

    // ============================================================
    // Timeout-Takt: NAK wiederholen oder alte Sessions löschen
    //
    // Socket-Timeout bedeutet hier nicht: Übertragung kaputt.
    // Er gibt dem Receiver nur regelmäßig Zeit für:
    // - NAK erneut senden
    // - abgebrochene Sessions entfernen
    // - fertige Sessions später aus der Map löschen
    // ============================================================

    private void onTimeout() throws Exception {
        long now = System.currentTimeMillis();
        Iterator<ReceiveSession> iterator = sessions.values().iterator();

        while (iterator.hasNext()) {
            ReceiveSession session = iterator.next();

            // Fertige Session nach etwas Wartezeit entfernen
            if (session.isComplete() && now - session.getLastSeenAt() > idleTimeoutMs) {
                iterator.remove();
                continue;
            }

            // Unfertige Session ohne neue Pakete abbrechen
            if (now - session.getLastSeenAt() > idleTimeoutMs) {
                sendControl(ControlPacket.error(session.txId(), "Transfer timeout"), session.remoteAddress());
                session.cleanPartFile();
                iterator.remove();
                continue;
            }

            // Wenn Lücken bestehen und lange nichts Neues kam, NAK erneut senden
            if (session.shouldSendNak(now, NAK_INTERVAL_MS)) {
                sendControl(session.nak(), session.remoteAddress());
            }
        }
    }

    // ============================================================
    // Netzwerk-I/O
    // ============================================================

    private DatagramPacket receive(byte[] buffer) throws Exception {
        // Wartet auf ein UDP-Paket
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        socket.receive(datagram);
        return datagram;
    }

    private void sendControl(ControlPacket packet, SocketAddress remote) throws Exception {
        if (remote == null) {
            return;
        }

        // ControlPacket in Bytes serialisieren und an Sender zurückschicken
        byte[] bytes = serializer.serialize(packet);
        DatagramPacket datagram = new DatagramPacket(bytes, bytes.length);
        datagram.setSocketAddress(remote);
        socket.send(datagram);
    }

    private Header peekHeader(byte[] raw) {
        // Header schnell lesen: txId + seq reichen für Routing im Receiver
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        return new Header(buffer.getShort(), buffer.getInt());
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