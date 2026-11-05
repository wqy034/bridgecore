package com.bridgecore;

/**
 * Endurance (END) estimate from the MEASURED stock decline (2026-09-09 redesign):
 * the power-stream difference (draw - inflow) had structural phase noise — the
 * physics thread spends in real time while supply refills in per-tick pulses — so
 * END is derived from the total-energy stock itself. S = stored + burnable is
 * sampled at 1 Hz into a 4-slot ring (3 s window); declineRate = (S[3 s ago] -
 * S[now]) / 60 (FE/t). "In decline" needs declineRate > max(50, draw/10): on
 * unlimited external supply the stock jitters within one tick's consumption
 * around full (sampling noise ~ draw/window), and the threshold swallows that; a
 * real deficit is on the order of draw itself, far above it. The three covered
 * cases: external unlimited supply = stock constant = INF; pure fuel burn =
 * stock truly declines = exact countdown; extractor output >= draw but burning
 * fuel = pools full, burnable declines, S declines = countdown (a power-stream
 * method would wrongly say INF — the case only the window method gets right).
 *
 * Shared by the HUD screen (DisplayContext.endSeconds) and the core's CC channel
 * so both readouts agree by construction. Server thread only.
 */
public final class EnduranceEstimator {
    private static final int SAMPLES = 4;
    private static final int WINDOW_TICKS = 60; // 3 s between oldest and newest sample

    private final double[] ring = new double[SAMPLES];
    private int cursor;
    private int samples;
    private double lastEndSeconds = Double.POSITIVE_INFINITY;

    /** Call every server tick; sampling runs at 1 Hz internally.
     *  @return seconds of endurance, POSITIVE_INFINITY = not in decline. */
    public double update(double stockFe, double drawFePerTick, long gameTime) {
        if (gameTime % 20 == 0) {
            if (samples >= SAMPLES) {
                double declineRate = (ring[cursor] - stockFe) / (double) WINDOW_TICKS;
                lastEndSeconds = declineRate > Math.max(50.0, drawFePerTick / 10.0)
                        ? stockFe / (declineRate * 20.0)
                        : Double.POSITIVE_INFINITY;
            } // else: ring warm-up — stay at INF
            ring[cursor] = stockFe;
            cursor = (cursor + 1) % SAMPLES;
            samples++;
        }
        return lastEndSeconds;
    }

    /** Drop the window (signal lost / structure change); next updates warm up to INF. */
    public void reset() {
        samples = 0;
        lastEndSeconds = Double.POSITIVE_INFINITY;
    }
}
