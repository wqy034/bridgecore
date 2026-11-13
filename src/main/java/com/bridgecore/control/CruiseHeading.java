package com.bridgecore.control;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Keeps a level bow on the horizontal course and preserves the approach heading near arrival. */
public final class CruiseHeading {
    public static final double MIN_RATE_DEGREES = 3.0;
    public static final double DEFAULT_RATE_DEGREES = 10.0;
    public static final double MAX_RATE_DEGREES = 14.0;
    public static final double MIN_RATE = Math.toRadians(MIN_RATE_DEGREES);
    public static final double DEFAULT_RATE = Math.toRadians(DEFAULT_RATE_DEGREES);
    public static final double MAX_RATE = Math.toRadians(MAX_RATE_DEGREES);
    public static final double MAX_ACCELERATION = 0.35;
    private static final double TURN_FIRST_DOT = Math.cos(Math.toRadians(45.0));
    private static final double FULL_SPEED_DOT = Math.cos(Math.toRadians(10.0));
    private static final double COURSE_REFRESH_DOT = Math.cos(Math.toRadians(0.5));

    private NavigationController.Task task;
    private final Quaterniond target = new Quaterniond();
    private final Vector3d currentBow = new Vector3d();
    private double directionX;
    private double directionZ;
    private boolean hasHeading;
    private int bowX;
    private int bowZ;

    public void update(NavigationController.Task next, double x, double z, int bowX, int bowZ) {
        if (task != next) {
            task = next;
            hasHeading = false;
        }
        if (next == null || next.profile() != NavigationController.Profile.CRUISE) return;
        double dx = next.x() - x;
        double dz = next.z() - z;
        double distance = Math.hypot(dx, dz);
        if (distance <= (hasHeading ? 1.0 : 0.5)) return;
        double nextX = dx / distance;
        double nextZ = dz / distance;
        if (hasHeading && bowX == this.bowX && bowZ == this.bowZ
                && nextX * directionX + nextZ * directionZ >= COURSE_REFRESH_DOT) return;
        directionX = nextX;
        directionZ = nextZ;
        this.bowX = bowX;
        this.bowZ = bowZ;
        target.rotationY(Math.atan2(-dz, dx) - Math.atan2(-bowZ, bowX));
        hasHeading = true;
    }

    public void clear() {
        task = null;
        hasHeading = false;
    }

    public Quaterniondc target() { return hasHeading ? target : null; }

    public double speedFactor(Quaterniondc orientation) {
        if (!hasHeading) return 1.0;
        orientation.transform(currentBow.set(bowX, 0.0, bowZ));
        double alignment = currentBow.x * directionX + currentBow.z * directionZ;
        return Math.clamp((alignment - TURN_FIRST_DOT) / (FULL_SPEED_DOT - TURN_FIRST_DOT), 0.0, 1.0);
    }

    public Vector3dc course(Vector3d dest) { return dest.set(directionX, 0.0, directionZ); }

    /** A turn counts as route progress while the bow is too far off course to advance. */
    public static double turningError(Quaterniondc orientation, int bowX, int bowZ, double dx, double dz) {
        double distance = Math.hypot(dx, dz);
        if (distance <= 1.0) return Double.NaN;
        Vector3d bow = orientation.transform(new Vector3d(bowX, 0.0, bowZ));
        double alignment = (bow.x * dx + bow.z * dz) / distance;
        return alignment < TURN_FIRST_DOT ? Math.acos(Math.clamp(alignment, -1.0, 1.0)) : Double.NaN;
    }
}
