package udp.project.sender;

public class SlidingWindow {

    private final int maxSeq;
    private int baseSeq = 1;
    private int nextSeq = 1;

    public SlidingWindow(int maxSeq) {
        this.maxSeq = maxSeq;
    }

    public boolean canSend(int windowSize) {
        return windowSize > 0 && nextSeq <= maxSeq && nextSeq < baseSeq + windowSize;
    }

    public int nextToSend() {
        return nextSeq;
    }

    public void markSent() {
        nextSeq++;
    }

    public void onAck(int ackBase) {
        if (ackBase > baseSeq && ackBase <= maxSeq + 1) {
            baseSeq = ackBase;
            nextSeq = Math.max(nextSeq, baseSeq);
        }
    }

    public int getBaseSeq() {
        return baseSeq;
    }

    public boolean isFinished() {
        return baseSeq > maxSeq;
    }
}