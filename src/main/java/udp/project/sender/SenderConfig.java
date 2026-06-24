package udp.project.sender;

public record SenderConfig(
        int chunkSize,
        int controlBufferSize,
        int windowSize,
        int startWindowSize,
        int minWindowSize,
        int startReplyTimeoutMs,
        int maxReplyTimeoutMs,
        int replyWaitTimeoutMs,
        int maxNoReplyRounds,
        int maxRetriesPerPacket,
        int sameAckLimit
) {

    public static SenderConfig defaults() {
        return new SenderConfig(
                1400,   // Größe eines DATA-Chunks
                4096,   // Buffer für ACK/NAK/COMPLETE/ERROR
                64,     // maximales Sliding-Window
                8,      // Startgröße des aktuellen Fensters
                4,      // kleinste erlaubte Fenstergröße
                300,    // Start-Timeout für erneutes Senden
                3_000,  // maximaler Timeout für erneutes Senden
                100,    // Wartezeit auf Antwort vom Receiver
                80,     // maximale Runden ohne Antwort
                10,     // maximale Wiederholungen pro DATA-Paket
                3       // gleiche ACKs bis Fast-Retransmit
        );
    }
}