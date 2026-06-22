package udp.project.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class PacketSerializer {

    public static final int TRANSMISSION_ID_SIZE = 2;
    public static final int SEQUENCE_NUMBER_SIZE = 4;
    public static final int MAX_SEQUENCE_NUMBER_SIZE = 4;
    public static final int MD5_SIZE = 16;
    public static final int DATA_HEADER_SIZE = TRANSMISSION_ID_SIZE + SEQUENCE_NUMBER_SIZE;
    public static final int FIRST_MIN_SIZE = DATA_HEADER_SIZE + MAX_SEQUENCE_NUMBER_SIZE;
    public static final int LAST_SIZE = DATA_HEADER_SIZE + MD5_SIZE;
    public static final int CONTROL_HEADER_SIZE = 5;
    public static final int ACK_SIZE = CONTROL_HEADER_SIZE + 4;

    public byte[] serialize(Packet p) {
        return switch (p.type()) {
            case FIRST -> {
                byte[] name = p.fileName().getBytes(StandardCharsets.UTF_8);
                ByteBuffer b = buf(FIRST_MIN_SIZE + name.length);
                b.putShort(p.transmissionId()).putInt(0).putInt(p.maxSequenceNumber()).put(name);
                yield b.array();
            }
            case DATA -> {
                ByteBuffer b = buf(DATA_HEADER_SIZE + p.data().length);
                b.putShort(p.transmissionId()).putInt(p.sequenceNumber()).put(p.data());
                yield b.array();
            }
            case LAST -> {
                ByteBuffer b = buf(LAST_SIZE);
                b.putShort(p.transmissionId()).putInt(p.sequenceNumber()).put(p.md5());
                yield b.array();
            }
        };
    }

    public byte[] serialize(ControlPacket p) {
        return switch (p.type()) {
            case ACK -> {
                ByteBuffer b = buf(ACK_SIZE);
                b.putShort(p.transmissionId()).put((byte) ControlType.ACK.getCode())
                 .putShort((short) 1).putInt(p.ackBase());
                yield b.array();
            }
            case NAK -> {
                List<Integer> missing = p.missingSequences();
                if (missing.size() > 350) missing = missing.subList(0, 350);
                ByteBuffer b = buf(CONTROL_HEADER_SIZE + missing.size() * SEQUENCE_NUMBER_SIZE);
                b.putShort(p.transmissionId()).put((byte) ControlType.NAK.getCode())
                 .putShort((short) missing.size());
                for (int seq : missing) b.putInt(seq);
                yield b.array();
            }
            case COMPLETE -> {
                ByteBuffer b = buf(CONTROL_HEADER_SIZE);
                b.putShort(p.transmissionId()).put((byte) ControlType.COMPLETE.getCode())
                 .putShort((short) 0);
                yield b.array();
            }
            case ERROR -> {
                byte[] msg = p.errorMessage() == null ? new byte[0]
                        : p.errorMessage().getBytes(StandardCharsets.UTF_8);
                int n = Math.min(msg.length, 65535);
                ByteBuffer b = buf(CONTROL_HEADER_SIZE + n);
                b.putShort(p.transmissionId()).put((byte) ControlType.ERROR.getCode())
                 .putShort((short) n).put(msg, 0, n);
                yield b.array();
            }
        };
    }

    // seq==0 → FIRST; remaining==16 AND (no context OR seq==maxSeq+1) → LAST; else → DATA
    public Packet deserialize(byte[] bytes, int maxSeq) {
        if (bytes == null || bytes.length < DATA_HEADER_SIZE)
            throw new IllegalArgumentException("Packet too small");

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        short txId = buf.getShort();
        int seq = buf.getInt();

        if (seq == 0) {
            if (buf.remaining() < MAX_SEQUENCE_NUMBER_SIZE)
                throw new IllegalArgumentException("Invalid FIRST packet");
            int maxSeqNum = buf.getInt();
            byte[] nameBytes = new byte[buf.remaining()];
            buf.get(nameBytes);
            if (nameBytes.length == 0)
                throw new IllegalArgumentException("FIRST packet filename is empty");
            return new Packet(PacketType.FIRST, txId, 0, maxSeqNum, null,
                    new String(nameBytes, StandardCharsets.UTF_8), null);
        }

        if (buf.remaining() == MD5_SIZE && (maxSeq < 0 || seq == maxSeq + 1)) {
            byte[] md5 = new byte[MD5_SIZE];
            buf.get(md5);
            return new Packet(PacketType.LAST, txId, seq, 0, null, null, md5);
        }

        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        if (data.length == 0) throw new IllegalArgumentException("Empty DATA packet");
        return new Packet(PacketType.DATA, txId, seq, 0, data, null, null);
    }

    public Packet deserialize(byte[] bytes) {
        return deserialize(bytes, -1);
    }

    public ControlPacket deserializeControlPacket(byte[] bytes) {
        if (bytes == null || bytes.length < CONTROL_HEADER_SIZE)
            throw new IllegalArgumentException("Control packet too small");

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        short txId = buf.getShort();
        ControlType type = ControlType.fromCode(Byte.toUnsignedInt(buf.get()));
        int count = Short.toUnsignedInt(buf.getShort());

        return switch (type) {
            case ACK -> {
                if (buf.remaining() < SEQUENCE_NUMBER_SIZE)
                    throw new IllegalArgumentException("Invalid ACK packet");
                yield ControlPacket.ack(txId, buf.getInt());
            }
            case NAK -> {
                if (buf.remaining() < count * SEQUENCE_NUMBER_SIZE)
                    throw new IllegalArgumentException("Invalid NAK packet");
                List<Integer> missing = new ArrayList<>(count);
                for (int i = 0; i < count; i++) missing.add(buf.getInt());
                yield ControlPacket.nak(txId, missing);
            }
            case COMPLETE -> {
                if (count != 0) throw new IllegalArgumentException("Invalid COMPLETE packet");
                yield ControlPacket.complete(txId);
            }
            case ERROR -> {
                byte[] msg = new byte[buf.remaining()];
                buf.get(msg);
                yield ControlPacket.error(txId, new String(msg, StandardCharsets.UTF_8));
            }
        };
    }

    private ByteBuffer buf(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
    }
}
