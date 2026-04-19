package udp.project.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class PacketSerializer {

    private static final int TRANSMISSION_ID_SIZE = 2;
    private static final int SEQUENCE_NUMBER_SIZE = 4;
    private static final int MAX_SEQUENCE_NUMBER_SIZE = 4;
    private static final int MD5_SIZE = 16;

    public byte[] serialize(Packet packet) {

        if (packet.getSequenceNumber() == 0) return serializeFirst(packet);
        else if (packet.getMd5() != null) return serializeLast(packet);
        else return serializeData(packet);

    }

    public Packet deserialize(byte[] bytes) {

        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.BIG_ENDIAN);

        Packet p = new Packet();

        short transId = buffer.getShort();
        int seqNr = buffer.getInt();

        p.setTransmissionId(transId);
        p.setSequenceNumber(seqNr);

        if (seqNr == 0) deserializeFirst(buffer, p);
        else if (buffer.remaining() == MD5_SIZE) deserializeLast(buffer, p);
        else deserializeData(buffer, p);

        return p;
    }

    //  FIRST

    private byte[] serializeFirst(Packet p) {

        byte[] fileNameBytes = p.getFileName().getBytes(StandardCharsets.UTF_8);

        int size = TRANSMISSION_ID_SIZE + SEQUENCE_NUMBER_SIZE + MAX_SEQUENCE_NUMBER_SIZE + fileNameBytes.length;

        ByteBuffer buffer = createBuffer(size);

        buffer.putShort(p.getTransmissionId());
        buffer.putInt(p.getSequenceNumber());
        buffer.putInt(p.getMaxSequenceNumber());
        buffer.put(fileNameBytes);

        return buffer.array();
    }

    private void deserializeFirst(ByteBuffer buffer, Packet p) {

        int maxSeq = buffer.getInt();

        byte[] nameBytes = new byte[buffer.remaining()];
        buffer.get(nameBytes);

        p.setMaxSequenceNumber(maxSeq);
        p.setFileName(new String(nameBytes, StandardCharsets.UTF_8));
    }

    //  DATA

    private byte[] serializeData(Packet p) {

        byte[] data = p.getData();

        int size = TRANSMISSION_ID_SIZE + SEQUENCE_NUMBER_SIZE + data.length;

        ByteBuffer buffer = createBuffer(size);

        buffer.putShort(p.getTransmissionId());
        buffer.putInt(p.getSequenceNumber());
        buffer.put(data);

        return buffer.array();
    }

    private void deserializeData(ByteBuffer buffer, Packet p) {

        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);

        p.setData(data);
    }

    //  LAST

    private byte[] serializeLast(Packet p) {

        byte[] md5 = p.getMd5();

        int size = TRANSMISSION_ID_SIZE + SEQUENCE_NUMBER_SIZE + MD5_SIZE;

        ByteBuffer buffer = createBuffer(size);

        buffer.putShort(p.getTransmissionId());
        buffer.putInt(p.getSequenceNumber());
        buffer.put(md5);

        return buffer.array();
    }

    private void deserializeLast(ByteBuffer buffer, Packet p) {

        byte[] md5 = new byte[MD5_SIZE];
        buffer.get(md5);

        p.setMd5(md5);
    }

    //  BUFFER

    private ByteBuffer createBuffer(int size) {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return buffer;
    }
}