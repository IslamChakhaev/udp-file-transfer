package udp.project.sender;

public class SlidingWindow {

    private int baseSeq = 1;
    private int nextSeq = 1;
    private final int maxSeq;
    private final int windowSize;

    public SlidingWindow(int maxSeq, int windowSize) {
        if (maxSeq < 0) {
            throw new IllegalArgumentException("maxSeq must not be negative");
        }
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be positive");
        }
        this.maxSeq = maxSeq;
        this.windowSize = windowSize;
    }

    public boolean canSend() {
        return nextSeq <= maxSeq && nextSeq < baseSeq + windowSize;
    }

    public int nextToSend() {
        return nextSeq;
    }

    public void markSent() {
        nextSeq++;
    }

    public void onAck(int ackBase) {
        if (ackBase > baseSeq) {
            baseSeq = Math.min(ackBase, maxSeq + 1);
        }
        if (nextSeq < baseSeq) {
            nextSeq = baseSeq;
        }
    }

    public int getBaseSeq() {
        return baseSeq;
    }

    public int getNextSeq() {
        return nextSeq;
    }

    public boolean isFinished() {
        return baseSeq > maxSeq;
    }
}
