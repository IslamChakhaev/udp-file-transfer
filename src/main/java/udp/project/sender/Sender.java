package udp.project.sender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import udp.project.protocol.ControlPacket;
import udp.project.protocol.ControlType;
import udp.project.protocol.Packet;
import udp.project.protocol.PacketSerializer;
import udp.project.protocol.PacketType;
import udp.project.utils.Md5Util;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Sender {

    private static final Logger log = LoggerFactory.getLogger(Sender.class);

    private static final int CHUNK_SIZE = 1400;
    private static final int BUFFER_SIZE = 4096;
    private static final int WINDOW_SIZE = 64;
    private static final int FIRST_ACK_TIMEOUT_MS = 200;
    private static final int CONTROL_TIMEOUT_MS = 100;
    private static final int RTO_MS = 300;
    private static final int PROBE_TRIES = 5;
    private static final int SIMPLE_REPAIR_ROUNDS = 50;
    private static final int WINDOW_IDLE_LIMIT = 80;
    private static final int RETRY_LIMIT = 10;

    private final DatagramSocket socket;
    private final InetAddress address;
    private final int port;
    private final int delayMs;
    private final PacketSerializer serializer = new PacketSerializer();
    private final FileChunker chunker = new FileChunker(CHUNK_SIZE);

    public Sender(String host, int port, int delayMs) throws Exception {
        this.socket = new DatagramSocket();
        this.address = InetAddress.getByName(host);
        this.port = port;
        this.delayMs = Math.max(0, delayMs);
    }

    public void sendFile(String filePath) throws Exception {
        sendFile(filePath, null);
    }

    public void sendFile(String filePath, Short fixedTransmissionId) throws Exception {
        File file = Path.of(filePath).toFile();
        if (!file.isFile()) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }

        byte[] md5 = Md5Util.calculateFile(file.toPath());
        List<byte[]> chunks = chunker.splitFile(file);
        int maxSeq = chunks.size();
        short transmissionId = fixedTransmissionId != null
                ? fixedTransmissionId
                : (short) (System.nanoTime() & 0xFFFF);

        Packet first = createFirst(transmissionId, maxSeq, file.getName());
        Packet last = createLast(transmissionId, maxSeq + 1, md5);

        log.info("TX started: file='{}', target={}:{}, txId={}, chunks={}",
                file.getName(), address.getHostAddress(), port, Short.toUnsignedInt(transmissionId), maxSeq);
        log.info("MD5: {}", Md5Util.toHex(md5));

        boolean controlEnabled = probeControlSupport(transmissionId, first);

        if (controlEnabled) {
            log.info("Mode: sliding window, window={}", WINDOW_SIZE);
            sendWithWindow(transmissionId, chunks, last);
        } else {
            log.info("Mode: simple send + repair fallback");
            sendSimple(transmissionId, chunks, last);
        }

        log.info("TX success: file='{}'", file.getName());
    }

    private boolean probeControlSupport(short transmissionId, Packet first) throws Exception {
        for (int attempt = 1; attempt <= PROBE_TRIES; attempt++) {
            send(first);
            ControlPacket control = readControl(transmissionId, FIRST_ACK_TIMEOUT_MS);
            if (control != null && control.getType() == ControlType.ACK) {
                return true;
            }
        }
        return false;
    }

    private void sendSimple(short transmissionId, List<byte[]> chunks, Packet last) throws Exception {
        for (int i = 0; i < chunks.size(); i++) {
            send(createData(transmissionId, i + 1, chunks.get(i)));
        }

        send(last);
        listenForRepair(transmissionId, chunks, last, SIMPLE_REPAIR_ROUNDS);
    }

    private void sendWithWindow(short transmissionId, List<byte[]> chunks, Packet last) throws Exception {
        SlidingWindow window = new SlidingWindow(chunks.size(), WINDOW_SIZE);
        Map<Integer, Long> sentAt = new HashMap<>();
        Map<Integer, Integer> retries = new HashMap<>();
        boolean lastSent = false;
        int idleRounds = 0;

        while (idleRounds < WINDOW_IDLE_LIMIT) {
            while (window.canSend()) {
                int seq = window.nextToSend();
                send(createData(transmissionId, seq, chunks.get(seq - 1)));
                sentAt.put(seq, System.currentTimeMillis());
                window.markSent();
            }

            if (window.isFinished() && !lastSent) {
                send(last);
                lastSent = true;
            }

            ControlPacket control = readControl(transmissionId, CONTROL_TIMEOUT_MS);
            if (control == null) {
                idleRounds++;
                handleWindowTimeout(transmissionId, chunks, last, window, sentAt, retries, lastSent, idleRounds);
                continue;
            }

            idleRounds = 0;

            if (control.getType() == ControlType.ACK) {
                window.onAck(control.getAckBase());
            } else if (control.getType() == ControlType.NAK) {
                retransmitMissing(transmissionId, chunks, control.getMissingSequences(), retries);
                send(last);
                lastSent = true;
            } else if (control.getType() == ControlType.COMPLETE) {
                return;
            } else if (control.getType() == ControlType.ERROR) {
                throw new IllegalStateException("Receiver error: " + control.getErrorMessage());
            }
        }

        throw new IllegalStateException("Transfer failed: retry limit reached");
    }

    private void handleWindowTimeout(short transmissionId,
                                     List<byte[]> chunks,
                                     Packet last,
                                     SlidingWindow window,
                                     Map<Integer, Long> sentAt,
                                     Map<Integer, Integer> retries,
                                     boolean lastSent,
                                     int idleRounds) throws Exception {
        long now = System.currentTimeMillis();

        if (!window.isFinished()) {
            int seq = window.getBaseSeq();
            Long lastSendTime = sentAt.get(seq);
            if (lastSendTime != null && now - lastSendTime >= RTO_MS) {
                retransmitData(transmissionId, chunks, seq, retries);
                sentAt.put(seq, now);
            }
            return;
        }

        if (!lastSent || idleRounds % 3 == 0) {
            send(last);
        }
    }

    private void listenForRepair(short transmissionId,
                                 List<byte[]> chunks,
                                 Packet last,
                                 int maxRounds) throws Exception {
        Map<Integer, Integer> retries = new HashMap<>();
        boolean sawControl = false;

        for (int round = 0; round < maxRounds; round++) {
            ControlPacket control = readControl(transmissionId, CONTROL_TIMEOUT_MS);
            if (control == null) {
                if (round > 0 && round % 5 == 0) {
                    send(last);
                }
                continue;
            }

            sawControl = true;

            if (control.getType() == ControlType.NAK) {
                log.info("NAK received: missing={}", control.getMissingSequences().size());
                retransmitMissing(transmissionId, chunks, control.getMissingSequences(), retries);
                send(last);
                round = 0;
            } else if (control.getType() == ControlType.ACK) {
                log.debug("ACK received: base={}", control.getAckBase());
            } else if (control.getType() == ControlType.COMPLETE) {
                return;
            } else if (control.getType() == ControlType.ERROR) {
                throw new IllegalStateException("Receiver error: " + control.getErrorMessage());
            }
        }

        if (sawControl) {
            throw new IllegalStateException("Transfer failed: COMPLETE not received");
        }

        log.warn("No control response received; legacy receiver assumed");
    }

    private void retransmitMissing(short transmissionId,
                                   List<byte[]> chunks,
                                   List<Integer> missingSequences,
                                   Map<Integer, Integer> retries) throws Exception {
        if (missingSequences == null || missingSequences.isEmpty()) {
            return;
        }

        for (int sequence : missingSequences) {
            retransmitData(transmissionId, chunks, sequence, retries);
        }
    }

    private void retransmitData(short transmissionId,
                                List<byte[]> chunks,
                                int sequence,
                                Map<Integer, Integer> retries) throws Exception {
        if (sequence <= 0 || sequence > chunks.size()) {
            return;
        }

        int retryCount = retries.merge(sequence, 1, Integer::sum);
        if (retryCount > RETRY_LIMIT) {
            throw new IllegalStateException("Retry limit exceeded for seq=" + sequence);
        }

        log.info("Retransmit DATA seq={} retry={}", sequence, retryCount);
        send(createData(transmissionId, sequence, chunks.get(sequence - 1)));
    }

    private ControlPacket readControl(short expectedTransmissionId, int timeoutMs) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        socket.setSoTimeout(timeoutMs);

        try {
            socket.receive(packet);
        } catch (SocketTimeoutException e) {
            return null;
        }

        byte[] raw = Arrays.copyOf(packet.getData(), packet.getLength());

        try {
            ControlPacket control = serializer.deserializeControlPacket(raw);
            if (control.getTransmissionId() != expectedTransmissionId) {
                return null;
            }
            return control;
        } catch (IllegalArgumentException e) {
            log.debug("Ignored invalid control packet: {}", e.getMessage());
            return null;
        }
    }

    private void send(Packet packet) throws Exception {
        byte[] bytes = serializer.serialize(packet);
        DatagramPacket datagram = new DatagramPacket(bytes, bytes.length, address, port);
        socket.send(datagram);

        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
    }

    private Packet createFirst(short id, int maxSeq, String fileName) {
        Packet packet = new Packet();
        packet.setType(PacketType.FIRST);
        packet.setTransmissionId(id);
        packet.setSequenceNumber(0);
        packet.setMaxSequenceNumber(maxSeq);
        packet.setFileName(fileName);
        return packet;
    }

    private Packet createData(short id, int sequence, byte[] data) {
        Packet packet = new Packet();
        packet.setType(PacketType.DATA);
        packet.setTransmissionId(id);
        packet.setSequenceNumber(sequence);
        packet.setData(data);
        return packet;
    }

    private Packet createLast(short id, int sequence, byte[] md5) {
        Packet packet = new Packet();
        packet.setType(PacketType.LAST);
        packet.setTransmissionId(id);
        packet.setSequenceNumber(sequence);
        packet.setMd5(md5);
        return packet;
    }

    public void close() {
        socket.close();
    }
}
