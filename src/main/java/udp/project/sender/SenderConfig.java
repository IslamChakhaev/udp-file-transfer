package udp.project.sender;

public record SenderConfig(
        int chunkSize,
        int controlBufferSize,
        int windowSize,
        int replyWaitTimeoutMs,
        int maxNoReplyRounds,
        int maxRetriesPerPacket
) {

    public static SenderConfig defaults() {
        return new SenderConfig(
                1400,   // Größe eines DATA-Chunks
                4096,   // Buffer für ACK/NAK/COMPLETE/ERROR
                128,    // feste Sliding-Window-Größe
                50,     // kurze Wartezeit auf Control-Antworten
                120,    // maximale Runden ohne Antwort
                20      // maximale Wiederholungen pro DATA-Paket
        );
    }
}