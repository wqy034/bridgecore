package com.bridgecore.control;

import java.util.UUID;

/** One expiring, server-owned remote input stream per core. */
public final class RemoteManeuver {
    public static final int TURN_LEFT = 1 << 6;
    public static final int TURN_RIGHT = 1 << 7;
    public static final int MIN_SPEED_TENTHS = 10;
    public static final int MAX_SPEED_TENTHS = 40;
    public static final double YAW_RATE = 0.15;
    public static final double YAW_ACCELERATION = 0.45;
    static final long LEASE_NANOS = 400_000_000L;

    public record Owner(UUID player, UUID terminal, UUID session) {}
    public record Input(Owner owner, int mask, int speedTenths, long updatedAt) {}
    public record Motion(double x, double y, double z, double yaw) {}

    private Input input;
    private Owner revoked;

    public synchronized boolean update(Owner owner, int mask, int speedTenths, boolean changed, long now) {
        active(now);
        if (mask == 0) {
            if (input != null && input.owner().equals(owner)) input = null;
            if (owner.equals(revoked)) revoked = null;
            return true;
        }
        if (mask < 0 || mask > 0xFF || speedTenths < MIN_SPEED_TENTHS || speedTenths > MAX_SPEED_TENTHS
                || owner.equals(revoked) || (input == null ? !changed : !input.owner().equals(owner))) return false;
        input = new Input(owner, mask, speedTenths, now);
        return true;
    }

    public synchronized Input active(long now) {
        if (input != null && now - input.updatedAt() >= LEASE_NANOS) revoke();
        return input;
    }

    public synchronized void revoke() {
        if (input != null) {
            revoked = input.owner();
            input = null;
        }
    }

    public static Motion resolve(Input input, double bowX, double bowZ, double speedLimit) {
        int mask = input.mask();
        int forward = axis(mask, 1 << NudgeDirection.FORWARD.wireId(), 1 << NudgeDirection.BACK.wireId());
        int right = axis(mask, 1 << NudgeDirection.RIGHT.wireId(), 1 << NudgeDirection.LEFT.wireId());
        int up = axis(mask, 1 << NudgeDirection.UP.wireId(), 1 << NudgeDirection.DOWN.wireId());
        double yaw = YAW_RATE * axis(mask, TURN_LEFT, TURN_RIGHT);
        double length = Math.hypot(bowX, bowZ);
        if (length < 0.1) {
            forward = right = 0;
            length = 1.0;
        }
        double magnitude = Math.sqrt(forward * forward + right * right + up * up);
        if (magnitude == 0.0) return new Motion(0.0, 0.0, 0.0, yaw);
        double scale = Math.min(input.speedTenths() / 10.0, speedLimit) / magnitude;
        return new Motion((forward * bowX - right * bowZ) * scale / length, up * scale,
                (forward * bowZ + right * bowX) * scale / length, yaw);
    }

    /** Keep the requested yaw acceleration inside the rate envelope, including trim. */
    public static double limitYawAcceleration(double acceleration, double angularSpeed, double dt) {
        return Math.clamp(acceleration, Math.min(0.0, (-YAW_RATE - angularSpeed) / dt),
                Math.max(0.0, (YAW_RATE - angularSpeed) / dt));
    }

    private static int axis(int mask, int positive, int negative) {
        return ((mask & positive) == 0 ? 0 : 1) - ((mask & negative) == 0 ? 0 : 1);
    }
}
