package com.bridgecore.firecontrol.ballistics;

import org.jetbrains.annotations.Nullable;

/** Bounded, stationary-pose estimate using the fire-control solver's drag and gravity inputs. */
public final class StrikeTrajectory {
    private static final int MAX_TICKS = 200;

    private StrikeTrajectory() {}

    public static @Nullable Vec3 direction(Vec3 displacement, SolveChain.Params params, int lifetime) {
        double speed = params.shellVelocity() / 20.0;
        double drag = params.dragPT();
        double gravity = params.gravPT();
        double barrel = params.barrelLength();
        double distance = displacement.length();
        if (!Double.isFinite(speed) || speed <= 0 || !(drag > 0 && drag <= 1)
                || !Double.isFinite(gravity) || gravity < 0 || !Double.isFinite(barrel) || barrel < 0
                || !Double.isFinite(distance) || distance <= barrel || lifetime <= 0) {
            return null;
        }

        double travel = barrel;
        double drop = 0;
        double fallSpeed = 0;
        double margin = distance - travel;
        double horizontalSquared = displacement.x() * displacement.x() + displacement.z() * displacement.z();
        for (int tick = 0; tick < Math.min(lifetime, MAX_TICKS); tick++) {
            double nextDrop = drop + fallSpeed;
            travel += speed;
            double elevation = displacement.y() + nextDrop;
            double nextMargin = Math.sqrt(horizontalSquared + elevation * elevation) - travel;
            if (nextMargin <= 0) {
                double fraction = margin / (margin - nextMargin);
                return displacement.add(new Vec3(0, drop + fallSpeed * fraction, 0)).normalized();
            }
            margin = nextMargin;
            drop = nextDrop;
            fallSpeed = fallSpeed * drag + gravity;
            speed *= drag;
        }
        return null;
    }
}
