package com.bridgecore.firecontrol;

import com.bridgecore.firecontrol.ballistics.BallisticsSolver;
import com.bridgecore.firecontrol.ballistics.Quat;
import com.bridgecore.firecontrol.ballistics.Vec3;

/**
 * Gimbal math for the swivel-bearing turret, ported from the proven Lua
 * prototypes (tests/swivel_angle_test.lua, tests/swivel_closed_loop_test.lua —
 * "先进火控同款" axis-projection formulas). Pure functions, no MC types.
 */
public final class GimbalMath {

    private GimbalMath() {}

    /** wrapDeg: (-180, 180] — Lua (d + 180) % 360 - 180 with floored mod. */
    public static double wrapDeg(double d) {
        return BallisticsSolver.wrap180(d);
    }

    /** norm360: [0, 360) — Lua d % 360 with floored mod. */
    public static double norm360(double d) {
        return BallisticsSolver.luaMod(d, 360);
    }

    /**
     * Twist of q around unit axis a, degrees (swing-twist decomposition;
     * swivel_angle_test.lua twistDeg — equivalent to the bearing's own
     * setTargetAngleFromCurrentOrientation math).
     */
    public static double twistDeg(Quat q, Vec3 unitAxis) {
        double d = q.x() * unitAxis.x() + q.y() * unitAxis.y() + q.z() * unitAxis.z();
        return Math.toDegrees(2 * Math.atan2(d, q.w()));
    }

    /**
     * Axis-projection task-space error (swivel_closed_loop_test.lua main loop):
     * project the current and desired directions perpendicular to the hinge
     * axis, then errDeg = atan2(axis·(curP×desP), curP·desP).
     *
     * @return signed error in degrees, or null when singular (direction
     *         parallel to the axis) — caller skips the tick, same as the Lua
     */
    public static Double axisErrorDeg(Vec3 unitAxisWorld, Vec3 curWorld, Vec3 desWorld) {
        Vec3 curP = projectOut(curWorld, unitAxisWorld);
        Vec3 desP = projectOut(desWorld, unitAxisWorld);
        if (curP == null || desP == null) return null;
        return Math.toDegrees(Math.atan2(unitAxisWorld.dot(curP.cross(desP)), curP.dot(desP)));
    }

    /** v - axis*(v·axis), normalised; null when the projection is degenerate. */
    public static Vec3 projectOut(Vec3 v, Vec3 unitAxis) {
        return v.sub(unitAxis.scale(v.dot(unitAxis))).normalized();
    }

    /** Convert hinge right-hand twist into elevation (up positive) using the
     * muzzle at the stored arc zero, all in the pitch parent's local frame.
     * Undo the current angle first: the sign must not flip past vertical.
     * Zero means degenerate geometry; caller keeps its last known convention. */
    public static int pitchUpSense(Vec3 axis, Vec3 currentForward, double rawRelativeDeg) {
        Vec3 a = axis.normalized(), v = currentForward.normalized();
        if (a == null || v == null || !Double.isFinite(rawRelativeDeg)) return 0;
        double theta = Math.toRadians(-rawRelativeDeg), c = Math.cos(theta), sn = Math.sin(theta);
        Vec3 zeroForward = v.scale(c).add(a.cross(v).scale(sn)).add(a.scale(a.dot(v) * (1 - c)));
        double up = a.cross(zeroForward).y();
        return !Double.isFinite(up) || Math.abs(up) < 1e-6 ? 0 : up > 0 ? 1 : -1;
    }

    /** Limits are operator angles; result stays in raw hinge coordinates for
     * the existing calibrated servo and AxisLock. Sense is strictly +/-1. */
    public static double clampJointDelta(double rawRelative, double rawDelta,
                                         double min, double max, int sense) {
        if (sense != 1 && sense != -1) throw new IllegalArgumentException("Invalid joint sense");
        double operatorTarget = sense * (rawRelative + rawDelta);
        return sense * Math.max(min, Math.min(max, operatorTarget)) - rawRelative;
    }

    /** Total angle between two directions, degrees (the fire-gate input). */
    public static double angleBetweenDeg(Vec3 a, Vec3 b) {
        double c = Math.max(-1, Math.min(1, a.dot(b)));
        return Math.toDegrees(Math.acos(c));
    }
}
