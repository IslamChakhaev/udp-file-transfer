package udp.project.protocol;

public record Packet(PacketType type, short transmissionId, int sequenceNumber, int maxSequenceNumber, byte[] data, String fileName, byte[] md5) {

}
