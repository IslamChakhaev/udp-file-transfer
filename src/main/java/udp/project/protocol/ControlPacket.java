package udp.project.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ControlPacket {

    private short transmissionId;
    private ControlType type;
    private int ackBase;
    private List<Integer> missingSequences = new ArrayList<>();
    private String errorMessage;

    public static ControlPacket ack(short transmissionId, int ackBase) {
        ControlPacket packet = new ControlPacket();
        packet.setTransmissionId(transmissionId);
        packet.setType(ControlType.ACK);
        packet.setAckBase(ackBase);
        return packet;
    }

    public static ControlPacket nak(short transmissionId, List<Integer> missingSequences) {
        ControlPacket packet = new ControlPacket();
        packet.setTransmissionId(transmissionId);
        packet.setType(ControlType.NAK);
        packet.setMissingSequences(missingSequences);
        return packet;
    }

    public static ControlPacket complete(short transmissionId) {
        ControlPacket packet = new ControlPacket();
        packet.setTransmissionId(transmissionId);
        packet.setType(ControlType.COMPLETE);
        return packet;
    }

    public static ControlPacket error(short transmissionId, String message) {
        ControlPacket packet = new ControlPacket();
        packet.setTransmissionId(transmissionId);
        packet.setType(ControlType.ERROR);
        packet.setErrorMessage(message);
        return packet;
    }

    public short getTransmissionId() {
        return transmissionId;
    }

    public int getUnsignedTransmissionId() {
        return Short.toUnsignedInt(transmissionId);
    }

    public void setTransmissionId(short transmissionId) {
        this.transmissionId = transmissionId;
    }

    public ControlType getType() {
        return type;
    }

    public void setType(ControlType type) {
        this.type = type;
    }

    public int getAckBase() {
        return ackBase;
    }

    public void setAckBase(int ackBase) {
        this.ackBase = ackBase;
    }

    public List<Integer> getMissingSequences() {
        return Collections.unmodifiableList(missingSequences);
    }

    public void setMissingSequences(List<Integer> missingSequences) {
        this.missingSequences = missingSequences == null
                ? new ArrayList<>()
                : new ArrayList<>(missingSequences);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
