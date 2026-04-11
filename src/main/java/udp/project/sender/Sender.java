package udp.project.sender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import udp.project.protocol.Packet;
import udp.project.protocol.PacketSerializer;
import udp.project.utils.Md5Util;

import java.io.File;
import java.util.List;

public class Sender {

    private static final Logger log = LoggerFactory.getLogger(Sender.class);

    private final UdpSender udpSender;
    private final PacketSerializer serializer;
    private final FileChunker chunker;

    public Sender(String host, int port) throws Exception {
        this.udpSender = new UdpSender(host, port);
        this.serializer = new PacketSerializer();
        this.chunker = new FileChunker(1024);
    }

    public void sendFile(String filePath) throws Exception {

        File file = new File(filePath);
        long startTime = System.nanoTime();

        byte[] md5 = Md5Util.calculateFile(filePath);
        List<byte[]> chunks = chunker.splitFile(file);

        int maxSeq = chunks.size() + 1;
        short txId = 1;

        long totalBytesSent = 0;

        log.info("Start sending file: {}", file.getName());
        log.info("Total packets: {}", maxSeq);

        // FIRST
        Packet first = new Packet();
        first.setTransmissionId(txId);
        first.setSequenceNumber(0);
        first.setMaxSequenceNumber(maxSeq);
        first.setFileName(file.getName());

        totalBytesSent += sendPacket(first, "FIRST");

        // DATA
        for (int i = 0; i < chunks.size(); i++) {

            Packet dataPacket = new Packet();
            dataPacket.setTransmissionId(txId);
            dataPacket.setSequenceNumber(i + 1);
            dataPacket.setData(chunks.get(i));

            totalBytesSent += sendPacket(dataPacket, "DATA");
        }

        // LAST
        Packet last = new Packet();
        last.setTransmissionId(txId);
        last.setSequenceNumber(maxSeq);
        last.setMaxSequenceNumber(maxSeq);
        last.setMd5(md5);

        totalBytesSent += sendPacket(last, "LAST");

        udpSender.close();

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        log.info("File sent in {} ms", durationMs);
        log.info("Total bytes sent: {}", totalBytesSent);
    }

    private int sendPacket(Packet packet, String type) throws Exception {
        byte[] bytes = serializer.serialize(packet);

        log.info("-> {} seq={} size={} bytes",
                type,
                packet.getSequenceNumber(),
                bytes.length);

        udpSender.send(bytes);

        return bytes.length;
    }
}