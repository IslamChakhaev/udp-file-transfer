package udp.project.protocol;

public enum ControlType {
    NAK(0),
    COMPLETE(1),
    ACK(2),
    ERROR(3);

    private final int code;

    ControlType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static ControlType fromCode(int code) {
        for (ControlType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown control type: " + code);
    }
}
