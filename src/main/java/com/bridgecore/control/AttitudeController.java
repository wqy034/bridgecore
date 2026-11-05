package com.bridgecore.control;

import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Attitude PD core — the "pd_rot_control" atom from the legacy flight control
 * (handoff §5.4), upgraded to use the real 3x3 inertia tensor instead of the
 * legacy scalar shortcut (§5.7: do not inherit that).
 *
 * All math happens in the body (plot-local) frame:
 *   - omega read from the rigid-body handle is world-frame -> rotated by q^-1
 *   - attitude error: local error quaternion q_err = q_cur^-1 * q_target,
 *     converted to axis-angle vector e (|e| = error angle in radians)
 *   - desired angular acceleration alpha = kp*e - kd*omega_local, magnitude-clamped
 *   - torque = I * alpha; the caller turns it into an impulse (tau * dt)
 *
 * Units are SI throughout (handoff §6 pitfall 1): rad, rad/s, rad/s^2, N*m.
 */
public final class AttitudeController {
    public enum Mode {OFF, DAMPING, HOLD, RATE}

    // --- ground-test calibration knobs (§6 pitfall 4: concentrated, one place) ---
    /** Attitude stiffness / angular damping DEFAULTS, s^-2 / s^-1 (kd ≈ 2*sqrt(kp) ≈
     *  critical damping; KD 6.0 post-ground-test: 4.0 felt sluggish under sustained
     *  typewriter input). Per-core values come from the core's Tunings snapshot
     *  (config GUI tab 4) and arrive as computeTorque parameters — no global mutable
     *  state remains for these two. */
    public static final double DEFAULT_KP = 4.0;
    public static final double DEFAULT_KD = 6.0;
    /** Rate-command loop gain, s^-1 (M1): alpha = RATE_KR * (omegaTarget - omega).
     *  Same order as KD; closed-loop gain is dimensionless and hull-independent
     *  (torque = I * alpha adapts via the inertia tensor). */
    public static double RATE_KR = 6.0;
    /** Angular-acceleration clamp DEFAULT, rad/s^2 (protects tiny test hulls from
     *  flipping). The live value is the per-core tuning attMaxAlpha (config GUI
     *  tab 1), passed as a computeTorque parameter — this constant only seeds
     *  Tunings.DEFAULT. */
    public static double MAX_ALPHA = 8.0;
    /** Garbage-omega spike clamp at the read exit, rad/s (§6 pitfall 2). */
    public static double OMEGA_CLAMP = 20.0;
    /** Axis sign convention; flip components here if ground test shows reversed response. */
    private static final Vector3d AXIS_SIGN = new Vector3d(1.0, 1.0, 1.0);

    private AttitudeController() {
    }

    /**
     * Compute the body-frame control torque for one physics frame.
     *
     * @param qCurrent  current pose orientation (local->world)
     * @param qTarget   hold setpoint (only used in HOLD mode)
     * @param omegaTargetBody target angular velocity, body frame, rad/s (RATE mode only)
     * @param omegaWorld angular velocity from the handle (world frame, rad/s)
     * @param inertia   3x3 inertia tensor from the mass tracker (body frame)
     * @param dest      receives the body-frame torque (N*m); zeroed in OFF mode
     * @param kp        per-core attitude stiffness, s^-2 (HOLD mode)
     * @param kd        per-core angular damping, s^-1 (HOLD and DAMPING modes)
     * @param maxAlpha  per-core angular-acceleration clamp, rad/s^2
     * @return dest
     */
    public static Vector3d computeTorque(Mode mode, Quaterniondc qCurrent, Quaterniondc qTarget,
                                         Vector3dc omegaTargetBody,
                                         Vector3dc omegaWorld, Matrix3dc inertia, Vector3d dest,
                                         double kp, double kd, double maxAlpha) {
        dest.set(0.0, 0.0, 0.0);
        if (mode == Mode.OFF) {
            return dest;
        }

        // omega: world -> body frame, with spike clamp applied at this read exit
        Vector3d omegaLocal = qCurrent.transformInverse(omegaWorld, new Vector3d());
        double omegaLen = omegaLocal.length();
        if (omegaLen > OMEGA_CLAMP) {
            omegaLocal.normalize(OMEGA_CLAMP);
        }

        // desired angular acceleration (rad/s^2), per-axis sign calibration applied
        Vector3d alpha = new Vector3d();
        if (mode == Mode.HOLD && qTarget != null) {
            attitudeErrorVector(qCurrent, qTarget, alpha); // alpha := e (rad), reused as scratch
            alpha.mul(kp);
            alpha.sub(omegaLocal.mul(kd)); // alpha = kp*e - kd*omega
        } else if (mode == Mode.RATE && omegaTargetBody != null) {
            // Rate command (M1): stick = target angular velocity, loop drives omega to it.
            alpha.set(omegaTargetBody).sub(omegaLocal).mul(RATE_KR);
        } else {
            alpha.set(omegaLocal).mul(-kd); // pure D damping
        }
        alpha.mul(AXIS_SIGN);

        double alphaLen = alpha.length();
        if (alphaLen > maxAlpha) {
            alpha.normalize(maxAlpha);
        }

        inertia.transform(alpha, dest); // torque = I * alpha
        return dest;
    }

    /**
     * Body-frame attitude error as an axis-angle vector (|result| = radians).
     * q_err = q_cur^-1 * q_target (the local-frame error rotation), shortest path.
     */
    public static Vector3d attitudeErrorVector(Quaterniondc qCurrent, Quaterniondc qTarget, Vector3d dest) {
        Quaterniond qErr = new Quaterniond(qCurrent).invert().mul(qTarget);
        if (qErr.w < 0.0) { // shortest path: q and -q are the same rotation
            qErr.set(-qErr.x, -qErr.y, -qErr.z, -qErr.w);
        }
        double w = Math.max(-1.0, Math.min(1.0, qErr.w));
        double angle = 2.0 * Math.acos(w);
        double s = Math.sqrt(Math.max(0.0, 1.0 - w * w));
        if (s < 1.0e-6 || angle < 1.0e-9) {
            return dest.set(0.0, 0.0, 0.0);
        }
        return dest.set(qErr.x / s, qErr.y / s, qErr.z / s).mul(angle);
    }

    /** Attitude error magnitude in radians, for observability logging. */
    public static double attitudeErrorRad(Quaterniondc qCurrent, Quaterniondc qTarget) {
        return attitudeErrorVector(qCurrent, qTarget, new Vector3d()).length();
    }

    /**
     * Auto-level target (M3): keep the hull's current heading, zero pitch and
     * roll — the bow is projected onto the horizontal plane and the wings are
     * levelled. Built from basis images: a proper rotation preserves cross
     * products, so with t = b x u in both frames, M = Bw * Bl^T maps the local
     * orthonormal frame {bow, up, t} onto the levelled world frame.
     *
     * @return false when the bow is near-vertical (heading undefined) — the
     *         caller should fall back to a full-attitude capture.
     */
    public static boolean levelTarget(Quaterniondc qCurrent, Vector3dc bowLocal, Quaterniond dest) {
        Vector3d bowW = qCurrent.transform(bowLocal, new Vector3d());
        bowW.y = 0.0;
        double len = bowW.length();
        if (len < 0.1) {
            return false; // bow vertical: heading undefined, no sensible level target
        }
        bowW.div(len);
        Vector3d upW = new Vector3d(0.0, 1.0, 0.0);
        Vector3d thirdW = new Vector3d();
        bowW.cross(upW, thirdW);

        Vector3d upL = new Vector3d(0.0, 1.0, 0.0);
        Vector3d thirdL = new Vector3d();
        bowLocal.cross(upL, thirdL);

        Matrix3d bl = new Matrix3d();
        bl.setColumn(0, bowLocal).setColumn(1, upL).setColumn(2, thirdL);
        Matrix3d bw = new Matrix3d();
        bw.setColumn(0, bowW).setColumn(1, upW).setColumn(2, thirdW);
        dest.setFromNormalized(bw.mul(bl.transpose()));
        return dest.isFinite();
    }
}
