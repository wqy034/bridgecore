package com.bridgecore.control;

/** Fixed wire IDs and a half-block offset along the hull's level reference frame. */
public enum NudgeDirection {
    FORWARD(0, 1, 0, 0), BACK(1, -1, 0, 0), LEFT(2, 0, -1, 0),
    RIGHT(3, 0, 1, 0), UP(4, 0, 0, 1), DOWN(5, 0, 0, -1);

    public record Offset(double x, double y, double z) {}

    public static final double DISTANCE = 0.5;
    private final int wireId;
    private final int forward;
    private final int right;
    private final int vertical;

    NudgeDirection(int wireId, int forward, int right, int vertical) {
        this.wireId = wireId;
        this.forward = forward;
        this.right = right;
        this.vertical = vertical;
    }

    public int wireId() { return wireId; }

    public static NudgeDirection fromWireId(int id) {
        return switch (id) {
            case 0 -> FORWARD;
            case 1 -> BACK;
            case 2 -> LEFT;
            case 3 -> RIGHT;
            case 4 -> UP;
            case 5 -> DOWN;
            default -> null;
        };
    }

    public Offset offset(double bowX, double bowZ) {
        if (vertical != 0) return new Offset(0.0, vertical * DISTANCE, 0.0);
        double length = Math.hypot(bowX, bowZ);
        if (!(length >= 0.1) || !Double.isFinite(length)) return null;
        double scale = DISTANCE / length;
        return new Offset((forward * bowX - right * bowZ) * scale, 0.0,
                (forward * bowZ + right * bowX) * scale);
    }
}
