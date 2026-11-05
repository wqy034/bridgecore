package com.bridgecore.firecontrol.ballistics;

/**
 * Aim-chain layer, ported line-by-line from OnCannon.lua aimStep()
 * (lines 621-758) plus its helpers (omega2Q, SOLVE_YAW/FACE_TRIM tables,
 * faceTrimOf, axisServoAngle).
 *
 * <p>External hooks are stubbed as specified for the golden-vector harness:
 * <ul>
 *   <li>ac.getSelfSableShip() -> the input ship data (null in ground cases)</li>
 *   <li>ac.getCoord() -> only used in the ground branch as shipPos fallback;
 *       cannonBasePos() -> the input cannon position</li>
 *   <li>yawServo perfect tracking: getAngle() reads back the last setAngle();
 *       pitchCur()/aimPitch() likewise. A fresh solver has issued no command,
 *       so yawCmd/pitchCmd are null when the locked branch never runs.</li>
 *   <li>mount == null is treated as assembled (Lua: assembled stays true when
 *       the isAssembled check is skipped); mountFire defaults to true, so
 *       fired counts a mount.fire() call only when fire and hasMount.</li>
 * </ul>
 */
public final class SolveChain {

    private SolveChain() {
    }

    /** Effective properties for one aim step (Lua P() values, already numeric). */
    public record Params(
            double dragPT, double gravPT,
            double shellVelocity, double barrelLength,
            double minPitchAngle, double maxPitchAngle,
            double lockYawRange, String lockZoneDir, double lockZoneRange,
            double forecastMov, double forecastRot, double tgVelScale,
            boolean invertYaw, boolean invertPitch,
            double yawTrim, String face) {
    }

    /**
     * One aim-step input. Ship fields are SI units (m, m/s, rad/s) exactly as
     * astrocompass reports them; conversions to per-tick happen inside.
     *
     * @param ground   true -> ground fallback (identity pose, static); the
     *                 ship fields are then ignored
     * @param shipQuat ship pose quaternion (required when !ground)
     * @param shipOmega rad/s
     * @param shipVel   m/s
     */
    public record ChainInput(
            Vec3 targetPos, Vec3 targetVel,
            int mode, boolean ccFire,
            boolean ground,
            Quat shipQuat, Vec3 shipOmega, Vec3 shipVel, Vec3 shipPos,
            Vec3 cannonPos,
            boolean hasMount, boolean assembled) {
    }

    /**
     * @param yawT/pitchT status.yawT/status.pitchT (deg; yaw is the
     *                    zone-clamped value when the zone branch clamped it)
     * @param yawCmd/pitchCmd last servo commands, null when none issued
     * @param fired number of mount.fire() calls (0 or 1)
     * @param desWorld desired muzzle direction in the WORLD frame (unit vector),
     *                 inverse of the chain's own world->ship extraction:
     *                 des_ship = (-cos p*cos y, sin p, -cos p*sin y) from the
     *                 pre-trim yaw and the (possibly ballistic) pitch, mapped
     *                 back through nextQ. Input for the swivel-bearing gimbal
     *                 layer (phase 3); the golden vectors predate this field.
     */
    public record SolveOutcome(
            double yawT, double pitchT,
            boolean locked, boolean fire,
            String src,
            Double yawCmd, Double pitchCmd,
            int fired,
            Vec3 desWorld) {
    }

    // OnCannon.lua: SOLVE_YAW — solved yaw per shipyard axis
    // (yaw = -deg(atan2(rot.z, -rot.x)))
    private static double solveYawOf(String axis) {
        return switch (axis) {
            case "-X" -> 0;
            case "-Z" -> 90;
            case "+X" -> 180;
            case "+Z" -> -90;
            default -> 0;
        };
    }

    // OnCannon.lua: FACE_TRIM = -SOLVE_YAW remapped (2026-08-08 fix: ±Z swapped)
    private static double faceTrimOf(String face) {
        return switch (face) {
            case "-X" -> 0;
            case "-Z" -> -90;
            case "+X" -> 180;
            case "+Z" -> 90;
            default -> 0; // FACE_TRIM[face] or 0
        };
    }

    /** OnCannon.lua axisServoAngle. */
    private static double axisServoAngle(String axis, String face) {
        return BallisticsSolver.wrap180(solveYawOf(axis) + faceTrimOf(face));
    }

    /** OnCannon.lua omega2Q: rotation delta after `ticks` ticks; null when theta == 0. */
    private static Quat omega2Q(Vec3 omega, double ticks) {
        double ox = omega.x() * ticks, oy = omega.y() * ticks, oz = omega.z() * ticks;
        double theta = Math.sqrt(ox * ox + oy * oy + oz * oz);
        if (theta > Math.PI) theta = Math.PI;
        if (theta == 0) return null;
        double half = theta / 2, s = Math.sin(theta / 2) / theta;
        return new Quat(Math.cos(half), ox * s, oy * s, oz * s);
    }

    /** OnCannon.lua aimStep() body from the ship-data branch onwards. */
    public static SolveOutcome solveCore(Params p, ChainInput in) {
        Quat quat;
        Vec3 omega; // rad/tick
        Vec3 vel;   // blocks/tick
        boolean ground;
        if (!in.ground()) {
            // ship present: SI -> per-tick
            quat = in.shipQuat();
            omega = new Vec3(in.shipOmega().x() / 20, in.shipOmega().y() / 20, in.shipOmega().z() / 20);
            // omega spike clamp (synax version): pi rad/tick
            double omLen = Math.sqrt(omega.x() * omega.x() + omega.y() * omega.y() + omega.z() * omega.z());
            if (omLen > Math.PI) {
                double s = Math.PI / omLen;
                omega = new Vec3(omega.x() * s, omega.y() * s, omega.z() * s);
            }
            vel = new Vec3(in.shipVel().x() / 20, in.shipVel().y() / 20, in.shipVel().z() / 20);
            ground = false;
        } else {
            quat = Quat.IDENTITY;
            omega = Vec3.ZERO;
            vel = Vec3.ZERO;
            ground = true;
        }

        // forecast: ship pose at shell exit (ship only; ground skips it)
        boolean isShip = !ground;
        Quat nextQ = quat;
        if (isShip) {
            double fRot = p.forecastRot() * 20; // seconds -> ticks
            Quat q2 = omega2Q(omega, fRot);
            if (q2 != null) nextQ = nextQ.multiply(q2);
        }

        double fMov = isShip ? p.forecastMov() : 0; // seconds
        Vec3 base = in.cannonPos(); // cannonBasePos() stub
        Vec3 cannonPos = new Vec3(
                base.x() + vel.x() * 20 * fMov, // vel is blocks/tick; *20 -> m/s, *seconds
                base.y() + vel.y() * 20 * fMov,
                base.z() + vel.z() * 20 * fMov);

        // target solve
        double tx = in.targetPos().x();
        double ty = in.targetPos().y() + 0.5;
        double tz = in.targetPos().z();
        Vec3 tgVel = in.targetVel();
        double vs = p.tgVelScale();
        if (vs == 0) vs = 0.05;

        BallisticsSolver solver = new BallisticsSolver(
                p.dragPT(), p.gravPT(), p.shellVelocity(), p.barrelLength());

        // lead: mode > 2 iterates once against ballistic flight time
        double pitch = 0, cTime = 0;
        if (in.mode() > 2) {
            double vx = tx - cannonPos.x(), vy = ty - cannonPos.y(), vz = tz - cannonPos.z();
            double xDis = Math.sqrt(vx * vx + vz * vz);
            double[] r = solver.solvePitch(xDis, vy);
            pitch = r[0];
            cTime = r[1];
            if (cTime > 5) {
                tx = tx + tgVel.x() * vs * cTime;
                ty = ty + tgVel.y() * vs * cTime;
                tz = tz + tgVel.z() * vs * cTime;
                vx = tx - cannonPos.x();
                vy = ty - cannonPos.y();
                vz = tz - cannonPos.z();
                xDis = Math.sqrt(vx * vx + vz * vz);
                r = solver.solvePitch(xDis, vy);
                pitch = r[0];
                cTime = r[1];
            }
        }

        // world-frame gun direction -> ship frame
        Vec3 v = new Vec3(tx - cannonPos.x(), ty - cannonPos.y(), tz - cannonPos.z());
        Vec3 rot = nextQ.negate().rotate(v);

        double yaw = -Math.toDegrees(Math.atan2(rot.z(), -rot.x()));
        double len = Math.sqrt(rot.x() * rot.x() + rot.y() * rot.y() + rot.z() * rot.z());
        double pitchSolved = Math.toDegrees(Math.asin(rot.y() / len));

        // ballistic mode: bisected elevation replaces the geometric one
        if (in.mode() > 2 && cTime > 5) {
            pitchSolved = Math.toDegrees(pitch);
        }

        // desired world direction for the swivel gimbal (phase 3), built in the
        // WORLD frame (2026-09-25 fix): the previous desShip/nextQ round-trip
        // injected the WORLD-frame ballistic pitch into the SHIP-frame
        // reconstruction, so any ship tilt turned the sub-degree elevation
        // correction into a 40-76° slew error whenever the ballistic branch
        // armed (cTime>5, ~40m+ out) — air-to-ground locks slewed opposite the
        // target while AIM read 0 (the actuator faithfully reached the wrong
        // vector). Close locks never showed it: the geometric branch is an
        // exact identity (nextQ * nextQ^-1 * v == v). Now the ballistic
        // correction rotates the world-frame direction about the
        // world-horizontal axis perpendicular to the shot; nextQ never touches
        // the aim vector. face/invert/yawTrim are servo-convention adjustments
        // and do not belong in the aim vector either.
        Vec3 desWorld = new Vec3(v.x() / len, v.y() / len, v.z() / len);
        if (in.mode() > 2 && cTime > 5) {
            double delta = pitch - Math.asin(v.y() / len); // ballistic - geometric, rad
            double hor = Math.sqrt(v.x() * v.x() + v.z() * v.z());
            if (hor > 1e-9 && delta != 0) {
                // axis = normalize(des x up) = (-dz, 0, dx)/hor; Rodrigues with a·r=0
                double ax = -v.z() / hor, az = v.x() / hor;
                double cosD = Math.cos(delta), sinD = Math.sin(delta);
                // a x r = (-dx*dy, dx^2+dz^2, -dz*dy) / (len*hor) ... expanded per component
                double crx = -v.x() * v.y() / (len * hor);
                double cry = hor / len;
                double crz = -v.z() * v.y() / (len * hor);
                desWorld = new Vec3(
                        desWorld.x() * cosD + crx * sinD,
                        desWorld.y() * cosD + cry * sinD,
                        desWorld.z() * cosD + crz * sinD);
            }
        }

        // mounting-direction compensation
        if (p.invertYaw()) yaw = -yaw;
        if (p.invertPitch()) pitchSolved = -pitchSolved;
        // bow-axis convention: solved zero is shipyard -X (upstream), folded by face + yawTrim
        yaw = BallisticsSolver.wrap180(yaw + faceTrimOf(p.face()) + p.yawTrim());

        // field-of-fire
        boolean inYaw = Math.abs(yaw) <= p.lockYawRange();
        boolean inPitch = pitchSolved >= p.minPitchAngle() && pitchSolved <= p.maxPitchAngle();

        // lock zone (upstream lock_yaw_range): lockZoneDir axis ±lockZoneRange is forbidden
        boolean inZone = true;
        if (!"none".equals(p.lockZoneDir())) {
            double zc = axisServoAngle(p.lockZoneDir(), p.face());
            double zr = p.lockZoneRange();
            double dz = BallisticsSolver.wrap180(yaw - zc);
            if (Math.abs(dz) < zr) {
                yaw = BallisticsSolver.wrap180(zc + (dz >= 0 ? zr : -zr));
                inZone = false;
            }
        }

        if (inYaw && inPitch && inZone) {
            // yawServo.setAngle(yaw); aimPitch(pitchSolved) — perfect-tracking stubs,
            // so the closed-loop readbacks below return exactly the commanded values.
            Double yawCmd = yaw;
            Double pitchCmd = pitchSolved;
            double yErr = Math.abs(BallisticsSolver.wrap180(yawCmd - yaw));
            double pErr = Math.abs(pitchCmd - pitchSolved);
            boolean assembled = true;
            if (in.hasMount()) {
                assembled = in.assembled();
            }
            boolean fire = in.ccFire() && yErr < 1.5 && pErr < 1.5 && assembled;
            // mountFire defaults to true: software fire pulses the mount
            int fired = (fire && in.hasMount()) ? 1 : 0;
            return new SolveOutcome(yaw, pitchSolved, true, fire, "track", yawCmd, pitchCmd, fired, desWorld);
        } else {
            String src = inZone ? "out-of-range" : "lock-zone";
            // out-of-range still records the (possibly zone-clamped) target
            return new SolveOutcome(yaw, pitchSolved, false, false, src, null, null, 0, desWorld);
        }
    }
}
