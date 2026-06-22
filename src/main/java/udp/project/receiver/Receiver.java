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
    // SOCKET_TIMEOUT_MS ist kein echter Fehler – er triggert alle 500 ms NAK-Wiederholungen und Session-Bereinigung.
    private static final int SOCKET_TIMEOUT_MS = 500;
    private static final int SOCKET_BUFFER_BYTES = 4 * 1024 * 1024;
    // Puffer für DATA/LAST, die vor FIRST ankommen – passiert, wenn Pakete im Netz die Reihenfolge tauschen.
    private static final int MAX_PENDING_RAW = 512;
    // 350 seq × 4 Byte = 1400 Byte Nutzlast im NAK – passt in ein MTU-sicheres UDP-Paket.
    private static final int MAX_NAK_SEQUENCES = 350;
    private static final long NAK_INTERVAL_MS = 700; // Mindestabstand zwischen zwei NAKs derselben Session

    // Laufzeitdaten dieser Receiver-Instanz: Socket, Zielordner und aktive Transfers.
    private final DatagramSocket socket;
    private final Path outputDirectory;
    private final long idleTimeoutMs;
    private final PacketSerializer serializer = new PacketSerializer();
    // sessions: alle laufenden Übertragungen, key = txId (unsigned). pending: Pakete, die vor FIRST ankamen.
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
        this.socket.setReceiveBufferSize(SOCKET_BUFFER_BYTES);
        this.socket.setSendBufferSize(SOCKET_BUFFER_BYTES);
        this.socket.setSoTimeout(SOCKET_TIMEOUT_MS);
    }

    // Empfangsschleife: jedes Paket → handle() → Control-Antworten sofort zurückschicken.
    // SocketTimeoutException = periodischer Trigger für NAK-Wiederholungen und Session-Bereinigung.
    public void start() throws Exception {
        System.out.printf("RX start: port=%d, output=%s%n", socket.getLocalPort(), outputDirectory);
        byte[] buffer = new byte[BUFFER_SIZE];

        while (!socket.isClosed()) {
            try {
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                socket.receive(datagram);

                byte[] raw = Arrays.copyOf(datagram.getData(), datagram.getLength());

                for (ControlPacket response : handle(raw, datagram.getSocketAddress())) {
                    sendControl(response, datagram.getSocketAddress());
                }
            } catch (SocketTimeoutException ignored) {
                sendTimeoutResponses();
            } catch (Exception e) {
                System.err.println("RX error: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        for (ReceiveSession session : sessions.values()) {
            if (!session.isComplete()) session.cleanPartFile();
        }
        sessions.clear();
        pending.clear();
        socket.close();
    }

    // Grobe Paketverteilung: FIRST startet eine Session, DATA/LAST werden an die passende Session weitergegeben.
    // Kam DATA vor FIRST an (Netzwerk-Reorder), landet es im pending-Puffer und wird nachgeliefert.
    // Ist eine Session schon fertig, wiederholt der Receiver das COMPLETE – der Sender könnte es verpasst haben.
    private List<ControlPacket> handle(byte[] raw, SocketAddress remote) {
        if (raw.length < PacketSerializer.DATA_HEADER_SIZE) return List.of();

        Header header = peekHeader(raw);
        if (header.seq == 0) return handleFirst(raw, remote, header.txId);

        ReceiveSession session = sessions.get(header.unsignedTxId());
        if (session == null) {
            List<byte[]> list = pending.computeIfAbsent(header.unsignedTxId(), k -> new ArrayList<>());
            if (list.size() < MAX_PENDING_RAW) list.add(raw);
            return List.of();
        }

        if (session.isComplete()) return List.of(ControlPacket.complete(header.txId));

        try {
            Packet packet = serializer.deserialize(raw, session.getMaxSeq());
            return session.accept(packet, remote);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    // Session anlegen und dann alle gepufferten Pakete (pending) sofort nachverarbeiten,
    // damit DATA/LAST-Pakete, die vor FIRST ankamen, nicht verloren gehen.
    private List<ControlPacket> handleFirst(byte[] raw, SocketAddress remote, short txId) {
        try {
            Packet first = serializer.deserialize(raw);
            ReceiveSession session = sessions.computeIfAbsent(Short.toUnsignedInt(txId),
                    id -> new ReceiveSession(txId, outputDirectory, MAX_NAK_SEQUENCES));

            List<ControlPacket> responses = new ArrayList<>(session.accept(first, remote));
            List<byte[]> buffered = pending.remove(Short.toUnsignedInt(txId));
            if (buffered != null) {
                for (byte[] packetRaw : buffered) {
                    try {
                        responses.addAll(session.accept(serializer.deserialize(packetRaw, session.getMaxSeq()), remote));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            return responses;
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    // Wird alle 500 ms durch den Socket-Timeout aufgerufen (kein Fehler – das ist der Takt des Receivers).
    // Bereinigt fertige Sessions, bricht abgelaufene mit ERROR ab, sendet NAKs für aktive Sessions.
    private void sendTimeoutResponses() throws Exception {
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

    // Sendet ACK, NAK, COMPLETE oder ERROR zurück an den Sender.
    // Diese Control-Pakete sind das, was UDP-Übertragung hier erst zuverlässig macht.
    private void sendControl(ControlPacket packet, SocketAddress remote) throws Exception {
        if (remote == null) return;
        byte[] bytes = serializer.serialize(packet);
        DatagramPacket datagram = new DatagramPacket(bytes, bytes.length);
        datagram.setSocketAddress(remote);
        socket.send(datagram);
    }

    // txId + seq schnell lesen, um das Paket zuzuordnen – ohne den Rest zu parsen.
    private Header peekHeader(byte[] raw) {
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        return new Header(buffer.getShort(), buffer.getInt());
    }

    private record Header(short txId, int seq) {
        int unsignedTxId() {
            return Short.toUnsignedInt(txId);
        }
    }
}
