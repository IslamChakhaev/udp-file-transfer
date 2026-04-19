package udp.project.receiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import udp.project.protocol.Packet;
import udp.project.protocol.PacketSerializer;
import udp.project.utils.Md5Util;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class Receiver {

    private static final Logger log = LoggerFactory.getLogger(Receiver.class);

    private final UdpReceiver udpReceiver;
    private final PacketSerializer serializer;
    private final FileAssembler assembler;

    private String fileName;
    private int maxSeq;
    private byte[] expectedMd5;

    private final Map<Integer, Packet> pending = new HashMap<>();
    private boolean firstReceived = false;

    public Receiver(int port) throws Exception {
        this.udpReceiver = new UdpReceiver(port);
        this.serializer = new PacketSerializer();
        this.assembler = new FileAssembler();
    }

    public void start() throws Exception {

        log.info("[RX] Receiver started. Waiting for packets...");

        while (true) {

            byte[] rawData = udpReceiver.receive();
            Packet packet = serializer.deserialize(rawData);

            int seqNr = packet.getSequenceNumber();

            log.info("[RX] Packet received → seq={} (maxSeq={}, firstReceived={})",
                    seqNr, maxSeq, firstReceived);

            if (seqNr == 0) {
                handleFirst(packet);
                firstReceived = true;
                processPending();
                continue;
            }

            if (!firstReceived) {
                log.info("[RX] Metadata not received yet → buffering packet seq={}", seqNr);
                pending.put(seqNr, packet);
                continue;
            }

            if (seqNr == maxSeq + 1) {
                handleLast(packet);
                break;
            }

            handleData(packet);
        }

        log.info("[RX] All packets received. Building file...");

        try {
            File file = new File(fileName);
            log.info("[RX] Saving file to: {}", file.getAbsolutePath());

            assembler.buildFile(fileName, maxSeq);

        } catch (Exception e) {
            log.error("[RX] Error while saving file", e);
            return;
        }

        byte[] actualMd5 = Md5Util.calculateFile(fileName);

        if (Arrays.equals(expectedMd5, actualMd5)) {
            log.info("[RX] File successfully received and verified");
        } else {
            log.error("[RX] File corrupted (MD5 mismatch)");
        }

        udpReceiver.close();
    }

    private void processPending() {

        log.info("[RX] Processing buffered packets: {}", pending.size());

        for (Packet p : pending.values()) {

            int seq = p.getSequenceNumber();

            if (seq == maxSeq + 1) {
                handleLast(p);
                continue;
            }

            handleData(p);
        }

        pending.clear();
    }

    private void handleFirst(Packet packet) {
        this.fileName = "received_" + packet.getFileName();
        this.maxSeq = packet.getMaxSequenceNumber();

        log.info("[RX] FIRST packet received (metadata)");
        log.info("[RX] File: {}", fileName);
        log.info("[RX] Expected DATA packets: {}", maxSeq);
    }

    private void handleData(Packet packet) {
        log.info("[RX] DATA packet → seq={} ({} bytes)",
                packet.getSequenceNumber(),
                packet.getData().length);

        assembler.addChunk(packet.getSequenceNumber(), packet.getData());
    }

    private void handleLast(Packet packet) {
        this.expectedMd5 = packet.getMd5();

        log.info("[RX] LAST packet received (checksum)");
        log.info("[RX] Verifying file integrity...");
    }
}