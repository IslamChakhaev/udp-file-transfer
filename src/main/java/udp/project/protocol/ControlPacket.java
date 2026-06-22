package udp.project.protocol;

import java.util.List;

public record ControlPacket(short transmissionId, ControlType type, int ackBase,
                             List<Integer> missingSequences, String errorMessage) {

    public static ControlPacket ack(short txId, int ackBase) {
        return new ControlPacket(txId, ControlType.ACK, ackBase, List.of(), null);
    }

    public static ControlPacket nak(short txId, List<Integer> missing) {
        return new ControlPacket(txId, ControlType.NAK, 0,
                missing == null ? List.of() : List.copyOf(missing), null);
    }

    public static ControlPacket complete(short txId) {
        return new ControlPacket(txId, ControlType.COMPLETE, 0, List.of(), null);
    }

    public static ControlPacket error(short txId, String message) {
        return new ControlPacket(txId, ControlType.ERROR, 0, List.of(), message);
    }
}
