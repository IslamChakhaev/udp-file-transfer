package udp.project.receiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import udp.project.protocol.ControlPacket;
import udp.project.protocol.Packet;
import udp.project.protocol.PacketSerializer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class Receiver {

    private static final Logger log = LoggerFactory.getLogger(Receiver.class);

    private static final int BUFFER_SIZE = 32_384;
    private static final int SOCKET_TIMEOUT_MS = 500;

    private final DatagramSocket socket;
    private final PacketSerializer serializer;
    private final SessionManager sessionManager;

    public Receiver(int port) throws Exception {
        this(port, Path.of("."), 10_000);
    }

    public Receiver(int port, Path outputDirectory, long idleTimeoutMs) throws Exception {
        this.socket = new DatagramSocket(port);
        this.socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        this.serializer = new PacketSerializer();
        this.sessionManager = new SessionManager(outputDirectory, idleTimeoutMs);
    }

    public void start() throws Exception {
        log.info("RX started: port={}", socket.getLocalPort());

        byte[] buffer = new byte[BUFFER_SIZE];

        while (!socket.isClosed()) {
            try {
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                socket.receive(datagram);

                byte[] raw = Arrays.copyOf(datagram.getData(), datagram.getLength());
                Packet packet = serializer.deserialize(raw);

                List<ControlPacket> responses = sessionManager.handle(packet, datagram.getSocketAddress());
                sendResponses(responses, datagram.getSocketAddress());
            } catch (SocketTimeoutException e) {
                sendTimeoutResponses();
            } catch (Exception e) {
                log.error("RX error: {}", e.getMessage());
            }
        }
    }

    private void sendTimeoutResponses() throws Exception {
        Map<SocketAddress, List<ControlPacket>> responsesByAddress = sessionManager.findTimedOutSessions();
        for (Map.Entry<SocketAddress, List<ControlPacket>> entry : responsesByAddress.entrySet()) {
            sendResponses(entry.getValue(), entry.getKey());
        }
    }

    private void sendResponses(List<ControlPacket> responses, SocketAddress remoteAddress) throws Exception {
        if (responses == null || responses.isEmpty() || remoteAddress == null) {
            return;
        }

        for (ControlPacket response : responses) {
            sendControl(response, remoteAddress);
        }
    }

    private void sendControl(ControlPacket controlPacket, SocketAddress remoteAddress) throws Exception {
        byte[] bytes = serializer.serialize(controlPacket);
        DatagramPacket datagram = new DatagramPacket(bytes, bytes.length);
        datagram.setSocketAddress(remoteAddress);
        socket.send(datagram);
        logControl(controlPacket);
    }

    private void logControl(ControlPacket packet) {
        switch (packet.getType()) {
            case ACK -> log.debug("ACK sent: txId={}, base={}", packet.getUnsignedTransmissionId(), packet.getAckBase());
            case NAK -> log.info("NAK sent: txId={}, missing={}", packet.getUnsignedTransmissionId(), packet.getMissingSequences().size());
            case COMPLETE -> log.info("COMPLETE sent: txId={}", packet.getUnsignedTransmissionId());
            case ERROR -> log.error("ERROR sent: txId={}, message={}", packet.getUnsignedTransmissionId(), packet.getErrorMessage());
        }
    }

    public void close() {
        socket.close();
    }
}
