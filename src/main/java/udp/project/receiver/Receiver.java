package udp.project.receiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import udp.project.protocol.Packet;
import udp.project.protocol.PacketSerializer;
import udp.project.utils.Md5Util;

import java.util.Arrays;

public class Receiver {

    private static final Logger log = LoggerFactory.getLogger(Receiver.class);

    private final UdpReceiver udpReceiver;
    private final PacketSerializer serializer;
    private final FileAssembler assembler;

    private String fileName;
    private int maxSeq;
    private byte[] expectedMd5;

    public Receiver(int port) throws Exception {
        this.udpReceiver = new UdpReceiver(port);
        this.serializer = new PacketSerializer();
        this.assembler = new FileAssembler();
    }

    public void start() throws Exception {

        long startTime = System.nanoTime();

        log.info("Receiver started...");

        while (true) {

            byte[] rawData = udpReceiver.receive();

            log.info("<- packet received size={} bytes", rawData.length);

            Packet packet = serializer.deserialize(rawData);

            int seqNr = packet.getSequenceNumber();

            log.info("<- Packet seq={}", seqNr);

            if (seqNr == 0) {
                handleFirst(packet);
            }
            else if (seqNr == packet.getMaxSequenceNumber()) {
                handleLast(packet);
                break;
            }
            else {
                handleData(packet);
            }
        }

        log.info("Building file...");
        assembler.buildFile(fileName);

        byte[] actualMd5 = Md5Util.calculateFile(fileName);

        if (Arrays.equals(expectedMd5, actualMd5)) {
            log.info("SUCCESS: File received correctly");
        } else {
            log.error("ERROR: MD5 mismatch");
        }

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        log.info("Total time: {} ms", durationMs);

        udpReceiver.close();
    }

    private void handleFirst(Packet packet) {
        this.fileName = "received_" + packet.getFileName();
        this.maxSeq = packet.getMaxSequenceNumber();

        log.info("FIRST packet");
        log.info("File: {}", fileName);
        log.info("Expected packets: {}", maxSeq);
    }

    private void handleData(Packet packet) {
        log.debug("DATA seq={}", packet.getSequenceNumber());
        assembler.addChunk(packet.getSequenceNumber(), packet.getData());
    }

    private void handleLast(Packet packet) {
        this.expectedMd5 = packet.getMd5();
        log.info("LAST packet");
    }
}