package udp.project.sender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import udp.project.protocol.*;
import udp.project.utils.Md5Util;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;

public class Sender {

    private static final Logger log = LoggerFactory.getLogger(Sender.class);

    private final DatagramSocket socket;
    private final InetAddress address;
    private final int port;

    private final PacketSerializer serializer = new PacketSerializer();
    private final FileChunker chunker = new FileChunker(1400);

    private final int delayMs;

    public Sender(String host, int port, int delayMs) throws Exception {
        this.socket = new DatagramSocket();
        this.address = InetAddress.getByName(host);
        this.port = port;
        this.delayMs = delayMs;
    }

    public void sendFile(String filePath) throws Exception {

        File file = new File(filePath);

        byte[] md5 = Md5Util.calculateFile(filePath);
        List<byte[]> chunks = chunker.splitFile(file);

        int maxSeq = chunks.size();
        short txId = (short) (System.currentTimeMillis() & 0xFFFF);

        log.info("Start file transfer: {}", file.getName());
        log.info("File size: {} bytes", file.length());
        log.info("Chunk size: {} bytes", chunker.getChunkSize()); // ✔ FIX
        log.info("Total DATA packets: {}", maxSeq);
        log.info("Transmission ID: {}", txId);

        // FIRST
        log.info("Sending FIRST packet (metadata)");
        send(createFirst(txId, maxSeq, file.getName()));

        // DATA
        for (int i = 0; i < chunks.size(); i++) {

            byte[] chunk = chunks.get(i);

            log.debug("Sending DATA packet {}/{} (size={} bytes)",
                    i + 1,
                    maxSeq,
                    chunk.length
            );

            send(createData(txId, i + 1, chunk));

            if ((i + 1) % 10 == 0 || i == chunks.size() - 1) {
                log.info("Progress: {}/{} packets sent", i + 1, maxSeq);
            }
        }

        // LAST
        log.info("Sending LAST packet (MD5 checksum, size={} bytes)", md5.length);
        send(createLast(txId, maxSeq + 1, md5));

        log.info("File transfer finished: {}", file.getName());
    }

    private void send(Packet packet) throws Exception {

        byte[] bytes = serializer.serialize(packet);

        DatagramPacket dp = new DatagramPacket(bytes, bytes.length, address, port);
        socket.send(dp);

        log.debug("UDP packet sent: type={}, totalSize={} bytes",
                packet.getType(),
                bytes.length
        );

        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
    }

    // ===== CREATE PACKETS =====

    private Packet createFirst(short id, int maxSeq, String fileName) {
        Packet p = new Packet();
        p.setType(PacketType.FIRST);
        p.setTransmissionId(id);
        p.setSequenceNumber(0);
        p.setMaxSequenceNumber(maxSeq);
        p.setFileName(fileName);
        return p;
    }

    private Packet createData(short id, int seq, byte[] data) {
        Packet p = new Packet();
        p.setType(PacketType.DATA);
        p.setTransmissionId(id);
        p.setSequenceNumber(seq);
        p.setData(data);
        return p;
    }

    private Packet createLast(short id, int seq, byte[] md5) {
        Packet p = new Packet();
        p.setType(PacketType.LAST);
        p.setTransmissionId(id);
        p.setSequenceNumber(seq);
        p.setMd5(md5);
        return p;
    }

    public void close() {
        socket.close();
    }
}