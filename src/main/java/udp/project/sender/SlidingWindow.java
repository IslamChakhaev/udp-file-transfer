package udp.project.sender;

public class SlidingWindow {

    private final int maxSeq;
    private int firstUnackedSeq = 1;
    private int nextSeqToSend = 1;

    public SlidingWindow(int maxSeq) {
        this.maxSeq = maxSeq;
    }

    // Prüft, ob noch ein neues DATA-Paket ins aktuelle Fenster passt.
    public boolean canSend(int windowSize) {
        return windowSize > 0
                && nextSeqToSend <= maxSeq
                && nextSeqToSend < firstUnackedSeq + windowSize;
    }

    // Gibt die nächste DATA-Sequenz zurück, die gesendet werden soll.
    public int nextToSend() {
        return nextSeqToSend;
    }

    // Markiert die aktuelle Sequenz als gesendet und geht zur nächsten weiter.
    public void markSent() {
        nextSeqToSend++;
    }

    // ACK bedeutet: Alle DATA-Pakete vor ackBase sind angekommen.
    public void onAck(int ackBase) {
        if (ackBase <= firstUnackedSeq || ackBase > maxSeq + 1) {
            return;
        }

        firstUnackedSeq = ackBase;
        nextSeqToSend = Math.max(nextSeqToSend, firstUnackedSeq);
    }

    // Erstes DATA-Paket, das noch nicht bestätigt wurde.
    public int getFirstUnackedSeq() {
        return firstUnackedSeq;
    }

    // Fertig, wenn alle DATA-Pakete bestätigt wurden.
    public boolean isFinished() {
        return firstUnackedSeq > maxSeq;
    }
}