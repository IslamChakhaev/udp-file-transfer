package udp.project.receiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import udp.project.protocol.Packet;
import udp.project.protocol.PacketSerializer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.util.Arrays;

public class Receiver {

    private static final Logger log = LoggerFactory.getLogger(Receiver.class);

    private static final int BUFFER_SIZE = 4096;
    private static final int SOCKET_TIMEOUT_MS = 2000;

    private final DatagramSocket socket;
    private final PacketSerializer serializer;
    private final SessionManager sessionManager;

    public Receiver(int port) throws Exception {
        this.socket = new DatagramSocket(port);
        this.socket.setSoTimeout(SOCKET_TIMEOUT_MS);

        this.serializer = new PacketSerializer();
        this.sessionManager = new SessionManager();
    }

    public void start() throws Exception {

        log.info("Receiver started on port {}", socket.getLocalPort());

        byte[] buffer = new byte[BUFFER_SIZE];

        while (true) {
            try {
                DatagramPacket dp = new DatagramPacket(buffer, buffer.length);

                socket.receive(dp);

                byte[] raw = Arrays.copyOf(dp.getData(), dp.getLength());

                log.debug("UDP packet received (size={} bytes)", raw.length);

                Packet packet = serializer.deserialize(raw);

                log.debug("Packet: type={}, seq={}, txId={}",
                        packet.getType(),
                        packet.getSequenceNumber(),
                        packet.getTransmissionId()
                );

                sessionManager.handle(packet);

            } catch (SocketTimeoutException e) {
                log.warn("Timeout waiting for packets");
            }
        }
    }

    public void close() {
        socket.close();
    }
}