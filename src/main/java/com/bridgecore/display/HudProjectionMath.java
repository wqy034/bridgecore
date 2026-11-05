package com.bridgecore.display;

/** Shared off-axis panel projection, independent of Minecraft/world lookup. */
public final class HudProjectionMath {
    private HudProjectionMath() {}
    public record Point(double x, double y, double offAxis) {
        public boolean onPanel(double half) {
            return Math.abs(x) <= half && Math.abs(y) <= half;
        }
    }
    /** Coordinates are in the calibrated panel basis; forward depth is positive. */
    public static Point project(double right, double up, double depth,
                                double eyeRight, double eyeUp, double eyeDepth) {
        if (!Double.isFinite(depth) || depth < 0.1 || !Double.isFinite(eyeDepth) || eyeDepth <= 0) return null;
        double x = eyeRight + eyeDepth * right / depth;
        double y = eyeUp + eyeDepth * up / depth;
        return Double.isFinite(x) && Double.isFinite(y)
                ? new Point(x, y, Math.hypot(x, y) / eyeDepth) : null;
    }
}
