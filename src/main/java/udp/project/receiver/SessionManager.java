package udp.project.receiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import udp.project.protocol.ControlPacket;
import udp.project.protocol.Packet;
import udp.project.protocol.PacketType;

import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private static final int MAX_NAK_SEQUENCES = 350;
    private static final long NAK_INTERVAL_MS = 700;

    private final Map<Integer, TransferSession> sessions = new HashMap<>();
    private final Map<Integer, List<Packet>> pending = new HashMap<>();
    private final FileAssembler assembler = new FileAssembler();
    private final Path outputDirectory;
    private final long idleTimeoutMs;

    public SessionManager() {
        this(Path.of("."), 10_000);
    }

    public SessionManager(Path outputDirectory, long idleTimeoutMs) {
        this.outputDirectory = outputDirectory;
        this.idleTimeoutMs = idleTimeoutMs;
    }

    public List<ControlPacket> handle(Packet packet, SocketAddress remoteAddress) {
        int transmissionId = packet.getUnsignedTransmissionId();

        if (packet.getType() == PacketType.FIRST) {
            return handleFirst(packet, remoteAddress, transmissionId);
        }

        TransferSession session = sessions.get(transmissionId);
        if (session == null) {
            pending.computeIfAbsent(transmissionId, key -> new ArrayList<>()).add(packet);
            return List.of();
        }

        session.accept(packet, remoteAddress);
        return createResponses(session, packet);
    }

    public Map<SocketAddress, List<ControlPacket>> findTimedOutSessions() {
        Map<SocketAddress, List<ControlPacket>> responses = new HashMap<>();
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, TransferSession>> iterator = sessions.entrySet().iterator();

        while (iterator.hasNext()) {
            TransferSession session = iterator.next().getValue();

            if (!session.isFirstReceived() || session.getRemoteAddress() == null || session.isCompleted()) {
                continue;
            }

            long silence = now - session.getLastSeenAt();

            if (silence > idleTimeoutMs) {
                log.error("RX timeout: txId={}, received={}/{}",
                        session.getTransmissionId(), session.getChunks().size(), session.getMaxSeq());
                addResponse(responses, session.getRemoteAddress(),
                        ControlPacket.error(session.getTransmissionIdAsShort(), "Transfer timeout"));
                iterator.remove();
                continue;
            }

            if (silence >= NAK_INTERVAL_MS && now - session.getLastNakSentAt() >= NAK_INTERVAL_MS) {
                addResponse(responses, session.getRemoteAddress(), ControlPacket.nak(
                        session.getTransmissionIdAsShort(),
                        session.getMissingPackets(MAX_NAK_SEQUENCES)
                ));
                session.markNakSent();
            }
        }

        return responses;
    }

    private void addResponse(Map<SocketAddress, List<ControlPacket>> responses,
                             SocketAddress address,
                             ControlPacket packet) {
        responses.computeIfAbsent(address, key -> new ArrayList<>()).add(packet);
    }

    private List<ControlPacket> handleFirst(Packet packet, SocketAddress remoteAddress, int transmissionId) {
        TransferSession session = sessions.computeIfAbsent(transmissionId, TransferSession::new);
        session.accept(packet, remoteAddress);

        List<Packet> buffered = pending.remove(transmissionId);
        if (buffered != null) {
            for (Packet bufferedPacket : buffered) {
                session.accept(bufferedPacket, remoteAddress);
            }
        }

        return createResponses(session, packet);
    }

    private List<ControlPacket> createResponses(TransferSession session, Packet latestPacket) {
        List<ControlPacket> responses = new ArrayList<>();

        if (!session.isFirstReceived()) {
            return responses;
        }

        if (session.isReadyForAssembly()) {
            try {
                assembler.assembleAndVerify(session, outputDirectory);
                session.markCompleted();
                responses.add(ControlPacket.complete(session.getTransmissionIdAsShort()));
                sessions.remove(session.getTransmissionId());
                return responses;
            } catch (Exception e) {
                log.error("Assembly failed: txId={}, error={}", session.getTransmissionId(), e.getMessage());
                responses.add(ControlPacket.error(session.getTransmissionIdAsShort(), e.getMessage()));
                sessions.remove(session.getTransmissionId());
                return responses;
            }
        }

        if (latestPacket.getType() == PacketType.LAST || session.isLastReceived()) {
            List<Integer> missing = session.getMissingPackets(MAX_NAK_SEQUENCES);
            responses.add(ControlPacket.nak(session.getTransmissionIdAsShort(), missing));
            session.markNakSent();
            return responses;
        }

        responses.add(ControlPacket.ack(session.getTransmissionIdAsShort(), session.getAckBase()));
        return responses;
    }
}
