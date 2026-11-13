package com.bridgecore.control;

/** Turns physical key samples into short steps and sustained input. */
public final class RemoteKeyTiming {
    public static final long HOLD_NANOS = 500_000_000L;
    private final long[] pressedSince = new long[6];
    private int previous;
    private int suppressed;

    public record Update(int held, NudgeDirection step) {}

    public void reset(int pressed) {
        previous = 0;
        suppressed = pressed;
    }

    public Update sample(int pressed, long now) {
        suppressed &= pressed;
        pressed &= ~suppressed;
        int held = pressed & (RemoteManeuver.TURN_LEFT | RemoteManeuver.TURN_RIGHT);
        int taps = 0;
        for (int id = 0; id < pressedSince.length; id++) {
            int bit = 1 << id;
            boolean wasDown = (previous & bit) != 0;
            if ((pressed & bit) != 0) {
                if (!wasDown) pressedSince[id] = now;
                if (now - pressedSince[id] >= HOLD_NANOS) held |= bit;
            } else if (wasDown && now - pressedSince[id] < HOLD_NANOS) {
                taps |= bit;
            }
        }
        previous = pressed;
        NudgeDirection step = pressed == 0 && Integer.bitCount(taps) == 1
                ? NudgeDirection.fromWireId(Integer.numberOfTrailingZeros(taps)) : null;
        return new Update(held, step);
    }
}
