package com.bridgecore.control;

/** Tracks valid physics samples on the server thread, independently of callback arrival. */
public final class PhysicsWatchdog {
    private static final long UNSET = Long.MIN_VALUE;
    private static final int TIMEOUT_TICKS = 10;
    private long lastValidTick = UNSET;
    private long waitingSince = UNSET;
    private boolean physicalized;
    private boolean interrupted = true;
    private boolean timedOut;

    /** Returns whether the new sample can be differenced against the previous one. */
    public boolean recordValid(long tick) {
        boolean continuous = !interrupted && tick >= lastValidTick && tick - lastValidTick <= 1;
        physicalized = true;
        interrupted = false;
        timedOut = false;
        lastValidTick = tick;
        waitingSince = UNSET;
        return continuous;
    }

    public void recordInvalid(long tick) {
        physicalized = true;
        interrupted = true;
        if (lastValidTick == UNSET && waitingSince == UNSET) {
            waitingSince = tick;
        }
    }

    /** Returns the age once per outage, or -1 while no new timeout has occurred. */
    public long pollTimeout(long tick) {
        if (!physicalized || timedOut) return -1;
        if (lastValidTick == UNSET && waitingSince == UNSET) {
            waitingSince = tick;
        }
        long gap = tick - (lastValidTick == UNSET ? waitingSince : lastValidTick);
        if (gap <= TIMEOUT_TICKS) return -1;
        interrupted = true;
        timedOut = true;
        return gap;
    }

    public boolean timedOut() {
        return timedOut;
    }

    public boolean wasPhysicalized() {
        return physicalized;
    }

    public void restorePhysicalized(boolean value) {
        physicalized = value;
    }
}
