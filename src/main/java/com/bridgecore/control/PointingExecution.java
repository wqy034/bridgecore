package com.bridgecore.control;

import com.bridgecore.firecontrol.ballistics.Quat;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Pointing execution helpers (design: docs/pointing-execution-design.md v1.1,
 * 2026-11-08). Pure math, no Minecraft state — unit-testable.
 *
 * Model: every physics frame the active pointing request rebuilds the target
 * attitude as the SHORTEST ARC from the current bow onto the requested world
 * direction. Roll about the bow is left free (no auto-level fight). The
 * existing attitude loops do the actual control work — nothing here computes
 * torques.
 */
public final class PointingExecution {

    /** SETTLED thresholds (implementation params, tunable at delivery):
     *  bow direction error < 1 deg and |omega| < 0.05 rad/s, sustained 5 frames. */
    public static final double SETTLED_ANGLE_RAD = Math.toRadians(1.0);
    public static final double SETTLED_OMEGA = 0.05;
    public static final int SETTLED_FRAMES = 5;

    private PointingExecution() {
    }

    /**
     * Shortest-arc target attitude: bow (body axis, plot frame) is carried onto
     * {@code dirWorld} by pre-multiplying the world-frame arc rotation onto the
     * current orientation. Roll about the bow is whatever the ship already has.
     * Returns null for degenerate input (non-finite / zero direction).
     */
    public static Quaterniond shortestArcTarget(Quaterniondc current, Vector3dc bowLocal,
                                                Vector3dc dirWorld) {
        if (!Double.isFinite(dirWorld.lengthSquared()) || dirWorld.lengthSquared() < 1e-12) {
            return null;
        }
        Vector3d dir = new Vector3d(dirWorld).normalize();
        Vector3d bowW = current.transform(new Vector3d(bowLocal));
        Quaterniond arc = new Quaterniond().rotationTo(bowW, dir); // world frame
        return arc.mul(current, new Quaterniond());
    }

    /** Bow direction error against the requested world direction, radians. */
    public static double bowErrorRad(Quaterniondc current, Vector3dc bowLocal,
                                     Vector3dc dirWorld) {
        Vector3d bowW = current.transform(new Vector3d(bowLocal));
        Vector3d dir = new Vector3d(dirWorld).normalize();
        double dot = Math.max(-1.0, Math.min(1.0, bowW.dot(dir)));
        return Math.acos(dot);
    }

    /**
     * RATE-form command (design §2): desired angular velocity = the body-frame
     * attitude error direction, magnitude = min(kp·|e|, maxAngularRate).
     * Drives the existing RATE loop; the rate cap is honored by construction.
     * dest = omegaTargetBody (rad/s). Returns dest.
     */
    public static Vector3d rateCommand(Quaterniondc current, Quaterniondc target,
                                       double kp, double maxAngularRate, Vector3d dest) {
        Vector3d e = AttitudeController.attitudeErrorVector(current, target, dest);
        double len = e.length();
        if (len < 1e-9) {
            return dest.set(0.0, 0.0, 0.0);
        }
        double mag = Math.min(kp * len, maxAngularRate);
        return e.mul(mag / len);
    }

    public static double attitudeErrorRad(Quaterniondc current, Quat target) {
        double dot = current.x() * target.x() + current.y() * target.y()
                + current.z() * target.z() + current.w() * target.w();
        return 2.0 * Math.acos(Math.clamp(Math.abs(dot), 0.0, 1.0));
    }

    public static boolean withinSettleWindow(double angle, double omega) {
        return angle < SETTLED_ANGLE_RAD && omega < SETTLED_OMEGA;
    }

    /** SETTLED sustain counter: +1 while within thresholds, reset otherwise. */
    public static int nextSettleFrames(int frames, double bowErrorRad, double omegaMag) {
        return withinSettleWindow(bowErrorRad, omegaMag) ? frames + 1 : 0;
    }
}
