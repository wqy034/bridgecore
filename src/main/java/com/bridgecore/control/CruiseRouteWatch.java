package com.bridgecore.control;

/** Bounded server-tick supervision for an active cruise. */
public final class CruiseRouteWatch {
    public static final int CHECK_INTERVAL = 20;
    public static final int CHUNK_QUERY_LIMIT = 7;
    public static final int FAST_CHUNK_QUERY_LIMIT = 12;
    public static final double FAST_SPEED_THRESHOLD = 8.0;
    private static final int CREW_GRACE_TICKS = 60;
    private static final int STALL_TICKS = 400;
    private static final double MAX_LOOKAHEAD = 48.0;
    private static final double FAST_LOOKAHEAD = 112.0;
    private static final double CHECK_TRAVEL_SECONDS = 1.25;
    private static final double HEADING_PROGRESS = Math.toRadians(2.0);

    @FunctionalInterface
    public interface LoadedChunk {
        boolean test(int x, int z);
    }

    private double bestRemaining;
    private long lastProgress;
    private long lastCrew;
    private double bestHeadingError;

    public void start(double distance, long tick) {
        bestRemaining = distance;
        bestHeadingError = Double.POSITIVE_INFINITY;
        lastProgress = lastCrew = tick;
    }

    public boolean stalled(double remaining, long tick) {
        return stalled(remaining, Double.NaN, tick);
    }

    public boolean stalled(double remaining, double headingError, long tick) {
        if (remaining <= bestRemaining - 0.5) {
            bestRemaining = remaining;
            lastProgress = tick;
            bestHeadingError = Double.POSITIVE_INFINITY;
        }
        if (Double.isFinite(headingError) && headingError <= bestHeadingError - HEADING_PROGRESS) {
            bestHeadingError = headingError;
            lastProgress = tick;
        }
        return tick - lastProgress >= STALL_TICKS;
    }

    public boolean lostSupervision(boolean crewAboard, long tick) {
        if (crewAboard) lastCrew = tick;
        return tick - lastCrew >= CREW_GRACE_TICKS;
    }

    public static long cruiseLifetime(double distance, double speed, long minimumTicks) {
        return Math.max(minimumTicks, (long) Math.ceil(40.0 * (distance / speed) + 600.0));
    }

    public static boolean loadedAhead(double x, double z, double targetX, double targetZ, double speed,
                                      LoadedChunk loaded) {
        double dx = targetX - x;
        double dz = targetZ - z;
        double distance = Math.hypot(dx, dz);
        if (distance == 0.0) return loaded.test(chunk(x), chunk(z));
        // Reserve travel until the next sample plus a conservative brake allowance.
        boolean fast = speed > FAST_SPEED_THRESHOLD;
        double ahead = fast ? FAST_LOOKAHEAD : Math.min(MAX_LOOKAHEAD,
                2.0 + speed * CHECK_TRAVEL_SECONDS + speed * speed * 0.5);
        double fraction = Math.min(1.0, ahead / distance);
        return loadedBetween(x, z, dx * fraction, dz * fraction, fast ? FAST_CHUNK_QUERY_LIMIT : CHUNK_QUERY_LIMIT, loaded);
    }

    /** At higher speeds, cap cruise by the distance the bounded check can cover before braking. */
    public static double fastSpeedLimit(double brakingAcceleration) {
        double reserve = brakingAcceleration * CHECK_TRAVEL_SECONDS;
        return Math.sqrt(reserve * reserve + 2.0 * brakingAcceleration * (FAST_LOOKAHEAD - 2.0)) - reserve;
    }

    private static boolean loadedBetween(double x, double z, double dx, double dz, int budget, LoadedChunk loaded) {
        int cx = chunk(x), cz = chunk(z);
        int endX = chunk(x + dx), endZ = chunk(z + dz);
        int stepX = Integer.compare(endX, cx), stepZ = Integer.compare(endZ, cz);
        double nextX = boundaryTime(x, dx, cx, stepX), nextZ = boundaryTime(z, dz, cz, stepZ);
        double intervalX = stepX == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(dx);
        double intervalZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(dz);
        for (int checked = 0; checked < budget; checked++) {
            if (!loaded.test(cx, cz)) return false;
            if (cx == endX && cz == endZ) return true;
            boolean crossX = cx != endX && (cz == endZ || nextX <= nextZ);
            boolean crossZ = cz != endZ && (cx == endX || nextZ <= nextX);
            if (crossX) { cx += stepX; nextX += intervalX; }
            if (crossZ) { cz += stepZ; nextZ += intervalZ; }
        }
        return false;
    }

    private static double boundaryTime(double start, double delta, int chunk, int direction) {
        if (direction == 0) return Double.POSITIVE_INFINITY;
        return (((direction > 0 ? chunk + 1 : chunk) * 16.0) - start) / delta;
    }

    private static int chunk(double coordinate) {
        return (int) Math.floor(coordinate / 16.0);
    }
}
