package udp.project.receiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Arrays;

public class UdpReceiver {

    private static final Logger log = LoggerFactory.getLogger(UdpReceiver.class);

    private final DatagramSocket socket;
    private static final int BUFFER_SIZE = 2048;

    public UdpReceiver(int port) throws Exception {
        this.socket = new DatagramSocket(port);
    }

    public byte[] receive() throws Exception {

        byte[] buffer = new byte[BUFFER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, BUFFER_SIZE);

        socket.receive(packet);

        int length = packet.getLength();

        log.info("[UDP] Packet received ({} bytes)", length);

        return Arrays.copyOf(buffer, length);
    }

    public void close() {
        socket.close();
    }
}