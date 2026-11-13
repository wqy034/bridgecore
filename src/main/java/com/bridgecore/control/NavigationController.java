package com.bridgecore.control;

import com.bridgecore.BridgeCoreBlockEntity;

import java.util.UUID;

/** One supervised waypoint. Commands and results cross the server/physics boundary by task ID. */
public final class NavigationController {
    public static final double MIN_TERMINAL_SPEED = 1.0;
    public static final double DEFAULT_CRUISE_SPEED = 4.0;
    public static final double MAX_SPEED = BridgeCoreBlockEntity.TOP_SPEED_BASE * BridgeCoreBlockEntity.DEFAULT_G4_MULTIPLIER;
    private static final double SETTLE_SECONDS = 0.35;
    private static final double POWER_LIMIT_SECONDS = 0.75;
    private static final double NOMINAL_BRAKE = 1.5;
    private static final double BRAKE_RESPONSE_SECONDS = 0.75;

    public enum Profile {
        CRUISE(0.5, 0.45, 0.3, 0.25, 2400),
        NUDGE(0.075, 0.09, 0.10, 0.10, 160);

        final double horizontalTolerance;
        final double verticalTolerance;
        final double horizontalSpeed;
        final double verticalSpeed;
        private final int lifetimeTicks;

        Profile(double horizontalTolerance, double verticalTolerance, double horizontalSpeed,
                double verticalSpeed, int lifetimeTicks) {
            this.horizontalTolerance = horizontalTolerance;
            this.verticalTolerance = verticalTolerance;
            this.horizontalSpeed = horizontalSpeed;
            this.verticalSpeed = verticalSpeed;
            this.lifetimeTicks = lifetimeTicks;
        }

        public double verticalTolerance() { return verticalTolerance; }
        public int lifetimeTicks() { return lifetimeTicks; }
    }

    public enum Phase {
        ACTIVE, ARRIVED, CANCELLED, PILOT_OVERRIDE, MODE_CHANGED, PHYSICS_LOST, POWER_LIMITED,
        TIMED_OUT, ROUTE_UNLOADED, SUPERVISION_LOST, NO_PROGRESS
    }

    public enum Admission { ACCEPTED, UNAVAILABLE, BUSY, INVALID_TARGET, NEEDS_CREW, ROUTE_UNLOADED }

    public record Task(UUID id, double x, double y, double z, double speed, long expiresAt, Profile profile,
                       double turnRate) {
        public Task(UUID id, double x, double y, double z, double speed, long expiresAt, Profile profile) {
            this(id, x, y, z, speed, expiresAt, profile, CruiseHeading.DEFAULT_RATE);
        }

        public Task(UUID id, double x, double y, double z, double speed, long expiresAt) {
            this(id, x, y, z, speed, expiresAt, Profile.CRUISE);
        }
    }
    public record Status(UUID id, Phase phase) {}
    public record Guidance(double vx, double vz) {
        private static final Guidance STOP = new Guidance(0.0, 0.0);
    }

    private Task task;
    private Status status;
    private double settledFor;
    private double powerLimitedFor;
    private double brakeX;
    private double brakeZ;
    private double brakeSpeed;
    private double brakeStep;
    private double measuredBrake;
    private double measurementAge;

    public synchronized boolean start(Task request) {
        if (task != null) return task.id().equals(request.id()) && task.x() == request.x() && task.y() == request.y()
                && task.z() == request.z() && task.speed() == request.speed() && task.profile() == request.profile()
                && task.turnRate() == request.turnRate();
        if (status != null && status.id().equals(request.id())) return false;
        task = request;
        status = new Status(request.id(), Phase.ACTIVE);
        settledFor = powerLimitedFor = brakeStep = 0.0;
        measuredBrake = NOMINAL_BRAKE;
        measurementAge = Double.POSITIVE_INFINITY;
        return true;
    }

    public synchronized Status status() { return status; }

    public synchronized Task active(long tick) {
        if (task != null && tick >= task.expiresAt()) finish(Phase.TIMED_OUT);
        return task;
    }

    public synchronized boolean cancel(UUID id) {
        if (task == null || !task.id().equals(id)) return false;
        finish(Phase.CANCELLED);
        return true;
    }

    public synchronized void finish(Phase phase) {
        if (task == null) return;
        status = new Status(task.id(), phase);
        task = null;
    }

    public synchronized void finish(Task expected, Phase phase) {
        if (task == expected) finish(phase);
    }

    public synchronized Guidance guide(Task expected, double dt, double x, double y, double z,
                                       double vx, double vy, double vz, double brakeBudget, double speedLimit,
                                       boolean liftLimited) {
        if (task != expected) return Guidance.STOP;
        double dx = task.x() - x;
        double dz = task.z() - z;
        double distance = Math.hypot(dx, dz);
        double speed = Math.hypot(vx, vz);
        Profile profile = task.profile();
        boolean nearHeight = Math.abs(task.y() - y) <= profile.verticalTolerance;
        boolean needsHorizontal = distance > profile.horizontalTolerance || speed > profile.horizontalSpeed;
        boolean limited = liftLimited || (nearHeight && needsHorizontal && brakeBudget < 0.05);
        powerLimitedFor = limited ? powerLimitedFor + dt : 0.0;
        if (powerLimitedFor >= POWER_LIMIT_SECONDS) {
            finish(Phase.POWER_LIMITED);
            return Guidance.STOP;
        }
        boolean arrived = !limited && !needsHorizontal && nearHeight && Math.abs(vy) <= profile.verticalSpeed;
        settledFor = arrived ? settledFor + dt : 0.0;
        if (settledFor >= SETTLE_SECONDS) finish(Phase.ARRIVED);
        updateBrake(vx, vz, dt);
        double stopRadius = profile.horizontalTolerance * 0.5;
        if (task == null || liftLimited || distance <= stopRadius) return Guidance.STOP;

        double deceleration = Math.min(brakeBudget, measurementAge < 2.0 ? measuredBrake : NOMINAL_BRAKE);
        // Reserve travel while the velocity loop builds braking force.
        double response = BRAKE_RESPONSE_SECONDS * deceleration;
        double stoppingSpeed = Math.sqrt(response * response
                + 2.0 * deceleration * (distance - stopRadius)) - response;
        double targetSpeed = Math.min(Math.min(task.speed(), speedLimit), Math.min(distance, stoppingSpeed));
        return new Guidance(dx * targetSpeed / distance, dz * targetSpeed / distance);
    }

    /** Pair an actual braking write with the next solver sample in the same direction. */
    public synchronized void recordBrake(Task expected, double vx, double vz, double ix, double iz, double dt) {
        if (task != expected) return;
        double speed = Math.hypot(vx, vz);
        if (speed < (task.profile() == Profile.NUDGE ? 0.1 : 0.4) || vx * ix + vz * iz >= -1.0e-6) return;
        brakeX = vx / speed;
        brakeZ = vz / speed;
        brakeSpeed = speed;
        brakeStep = dt;
    }

    private void updateBrake(double vx, double vz, double dt) {
        measurementAge += dt;
        if (brakeStep == 0.0) return;
        double deceleration = (brakeSpeed - (brakeX * vx + brakeZ * vz)) / brakeStep;
        measuredBrake += 0.2 * (Math.clamp(deceleration, 0.05, NOMINAL_BRAKE) - measuredBrake);
        measurementAge = 0.0;
        brakeStep = 0.0;
    }
}
