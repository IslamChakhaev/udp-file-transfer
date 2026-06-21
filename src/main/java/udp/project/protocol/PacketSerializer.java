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

    public byte[] serialize(Packet packet) {
        if (packet == null || packet.getType() == null) {
            throw new IllegalArgumentException("Packet type is required");
        }

        return switch (packet.getType()) {
            case FIRST -> serializeFirst(packet);
            case DATA -> serializeData(packet);
            case LAST -> serializeLast(packet);
        };
    }

    public Packet deserialize(byte[] bytes) {
        if (bytes == null || bytes.length < DATA_HEADER_SIZE) {
            throw new IllegalArgumentException("Packet too small");
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        Packet packet = new Packet();

        short transmissionId = buffer.getShort();
        int sequenceNumber = buffer.getInt();

        packet.setTransmissionId(transmissionId);
        packet.setSequenceNumber(sequenceNumber);

        if (sequenceNumber == 0) {
            packet.setType(PacketType.FIRST);
            deserializeFirst(buffer, packet);
        } else if (buffer.remaining() == MD5_SIZE) {
            packet.setType(PacketType.LAST);
            deserializeLast(buffer, packet);
        } else {
            packet.setType(PacketType.DATA);
            deserializeData(buffer, packet);
        }

        return packet;
    }

    public byte[] serializeFirst(Packet packet) {
        if (packet.getFileName() == null || packet.getFileName().isBlank()) {
            throw new IllegalArgumentException("FIRST packet requires filename");
        }
        if (packet.getSequenceNumber() != 0) {
            throw new IllegalArgumentException("FIRST packet sequence must be 0");
        }

        byte[] fileNameBytes = packet.getFileName().getBytes(StandardCharsets.UTF_8);
        int size = FIRST_MIN_SIZE + fileNameBytes.length;

        ByteBuffer buffer = createBuffer(size);
        buffer.putShort(packet.getTransmissionId());
        buffer.putInt(0);
        buffer.putInt(packet.getMaxSequenceNumber());
        buffer.put(fileNameBytes);
        return buffer.array();
    }

    public byte[] serializeData(Packet packet) {
        if (packet.getSequenceNumber() <= 0) {
            throw new IllegalArgumentException("DATA packet sequence must be positive");
        }
        if (packet.getData() == null || packet.getData().length == 0) {
            throw new IllegalArgumentException("DATA packet requires payload");
        }

        ByteBuffer buffer = createBuffer(DATA_HEADER_SIZE + packet.getData().length);
        buffer.putShort(packet.getTransmissionId());
        buffer.putInt(packet.getSequenceNumber());
        buffer.put(packet.getData());
        return buffer.array();
    }

    public byte[] serializeLast(Packet packet) {
        if (packet.getSequenceNumber() <= 0) {
            throw new IllegalArgumentException("LAST packet sequence must be positive");
        }
        if (packet.getMd5() == null || packet.getMd5().length != MD5_SIZE) {
            throw new IllegalArgumentException("LAST packet requires 16-byte MD5");
        }

        ByteBuffer buffer = createBuffer(LAST_SIZE);
        buffer.putShort(packet.getTransmissionId());
        buffer.putInt(packet.getSequenceNumber());
        buffer.put(packet.getMd5());
        return buffer.array();
    }

    public byte[] serialize(ControlPacket packet) {
        if (packet == null || packet.getType() == null) {
            throw new IllegalArgumentException("Control packet type is required");
        }

        return switch (packet.getType()) {
            case ACK -> serializeAck(packet.getTransmissionId(), packet.getAckBase());
            case NAK -> serializeNak(packet.getTransmissionId(), packet.getMissingSequences());
            case COMPLETE -> serializeComplete(packet.getTransmissionId());
            case ERROR -> serializeError(packet.getTransmissionId(), packet.getErrorMessage());
        };
    }

    public byte[] serializeAck(short transmissionId, int ackBase) {
        ByteBuffer buffer = createBuffer(ACK_SIZE);
        buffer.putShort(transmissionId);
        buffer.put((byte) ControlType.ACK.getCode());
        buffer.putShort((short) 1);
        buffer.putInt(ackBase);
        return buffer.array();
    }

    public byte[] serializeNak(short transmissionId, List<Integer> missingSequences) {
        List<Integer> missing = missingSequences == null ? List.of() : missingSequences;
        if (missing.size() > 350) {
            missing = missing.subList(0, 350);
        }

        ByteBuffer buffer = createBuffer(CONTROL_HEADER_SIZE + missing.size() * SEQUENCE_NUMBER_SIZE);
        buffer.putShort(transmissionId);
        buffer.put((byte) ControlType.NAK.getCode());
        buffer.putShort((short) missing.size());
        for (int sequence : missing) {
            buffer.putInt(sequence);
        }
        return buffer.array();
    }

    public byte[] serializeComplete(short transmissionId) {
        ByteBuffer buffer = createBuffer(CONTROL_HEADER_SIZE);
        buffer.putShort(transmissionId);
        buffer.put((byte) ControlType.COMPLETE.getCode());
        buffer.putShort((short) 0);
        return buffer.array();
    }

    public byte[] serializeError(short transmissionId, String message) {
        byte[] messageBytes = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
        int count = Math.min(messageBytes.length, 65535);

        ByteBuffer buffer = createBuffer(CONTROL_HEADER_SIZE + count);
        buffer.putShort(transmissionId);
        buffer.put((byte) ControlType.ERROR.getCode());
        buffer.putShort((short) count);
        buffer.put(messageBytes, 0, count);
        return buffer.array();
    }

    public ControlPacket deserializeControlPacket(byte[] bytes) {
        if (bytes == null || bytes.length < CONTROL_HEADER_SIZE) {
            throw new IllegalArgumentException("Control packet too small");
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        short transmissionId = buffer.getShort();
        ControlType type = ControlType.fromCode(Byte.toUnsignedInt(buffer.get()));
        int count = Short.toUnsignedInt(buffer.getShort());

        ControlPacket packet = new ControlPacket();
        packet.setTransmissionId(transmissionId);
        packet.setType(type);

        switch (type) {
            case ACK -> {
                if (buffer.remaining() < SEQUENCE_NUMBER_SIZE) {
                    throw new IllegalArgumentException("Invalid ACK packet");
                }
                packet.setAckBase(buffer.getInt());
            }
            case NAK -> {
                if (buffer.remaining() < count * SEQUENCE_NUMBER_SIZE) {
                    throw new IllegalArgumentException("Invalid NAK packet");
                }
                List<Integer> missing = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    missing.add(buffer.getInt());
                }
                packet.setMissingSequences(missing);
            }
            case COMPLETE -> {
                if (count != 0) {
                    throw new IllegalArgumentException("Invalid COMPLETE packet");
                }
            }
            case ERROR -> {
                byte[] messageBytes = new byte[buffer.remaining()];
                buffer.get(messageBytes);
                packet.setErrorMessage(new String(messageBytes, StandardCharsets.UTF_8));
            }
        }

        return packet;
    }

    private void deserializeFirst(ByteBuffer buffer, Packet packet) {
        if (buffer.remaining() < MAX_SEQUENCE_NUMBER_SIZE) {
            throw new IllegalArgumentException("Invalid FIRST packet");
        }

        int maxSeq = buffer.getInt();
        byte[] fileNameBytes = new byte[buffer.remaining()];
        buffer.get(fileNameBytes);

        if (fileNameBytes.length == 0) {
            throw new IllegalArgumentException("FIRST packet filename is empty");
        }

        packet.setMaxSequenceNumber(maxSeq);
        packet.setFileName(new String(fileNameBytes, StandardCharsets.UTF_8));
    }

    private void deserializeData(ByteBuffer buffer, Packet packet) {
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        if (data.length == 0) {
            throw new IllegalArgumentException("Empty DATA packet");
        }
        packet.setData(data);
    }

    private void deserializeLast(ByteBuffer buffer, Packet packet) {
        byte[] md5 = new byte[MD5_SIZE];
        buffer.get(md5);
        packet.setMd5(md5);
    }

    private ByteBuffer createBuffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
    }
}
