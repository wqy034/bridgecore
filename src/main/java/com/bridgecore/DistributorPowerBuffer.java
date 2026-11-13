package com.bridgecore;

/** The server fills the buffer and physics spends it. Each change is atomic with its rate budget. */
final class DistributorPowerBuffer {
    private int stored;
    private int capacity;
    private int inputRate;
    private int outputRate;
    private long tick = Long.MIN_VALUE;
    private long step = Long.MIN_VALUE;
    private int inputUsed;
    private int outputUsed;
    private int stepUsed;
    private double stepTicks;

    synchronized void configure(int capacity, int inputRate, int outputRate) {
        this.capacity = capacity;
        this.inputRate = inputRate;
        this.outputRate = outputRate;
    }

    synchronized int stored() { return stored; }
    synchronized int capacity() { return capacity; }
    synchronized int inputRate() { return inputRate; }
    synchronized int outputRate() { return outputRate; }

    synchronized void load(int energy) {
        stored = Math.max(0, energy);
    }

    private void advanceTick(long now) {
        if (tick == now) return;
        tick = now;
        inputUsed = 0;
        outputUsed = 0;
        stepUsed = 0;
        stepTicks = 0.0;
        step = Long.MIN_VALUE;
    }

    synchronized int receive(int requested, long now, boolean simulate) {
        advanceTick(now);
        int accepted = Math.max(0, Math.min(requested, Math.min(capacity - stored, inputRate - inputUsed)));
        if (!simulate) {
            stored += accepted;
            inputUsed += accepted;
        }
        return accepted;
    }

    synchronized int inputSpace(long now) {
        return receive(Integer.MAX_VALUE, now, true);
    }

    /** Restores saved charge outside the ordinary input path. */
    synchronized int restore(int requested) {
        int accepted = Math.max(0, Math.min(requested, capacity - stored));
        stored += accepted;
        return accepted;
    }

    synchronized int restoreLegacyOverflow(int requested) {
        int accepted = Math.max(0, Math.min(requested, Integer.MAX_VALUE - stored));
        stored += accepted;
        return accepted;
    }

    synchronized void beginStep(long now, long phase, double seconds) {
        advanceTick(now);
        if (phase == step) return;
        step = phase;
        stepUsed = 0;
        stepTicks = Math.min(1.0, Math.max(0.0, seconds * 20.0));
    }

    synchronized int available() {
        int stepLimit = (int) Math.ceil(outputRate * stepTicks);
        return Math.max(0, Math.min(stored, Math.min(outputRate - outputUsed, stepLimit - stepUsed)));
    }

    synchronized int spend(long requested) {
        int paid = (int) Math.max(0, Math.min(requested, available()));
        stored -= paid;
        outputUsed += paid;
        stepUsed += paid;
        return paid;
    }
}
