package com.bridgecore.control;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Uses delivered impulse and the next physics sample to estimate a flexible external load. */
final class TetherLoad {
    private static final double LEARN_SECONDS = 0.45;
    private static final double RELEASE_SECONDS = 0.12;
    private static final double MAX_LEARN_VERTICAL_SPEED = 0.30;
    private static final double MAX_HORIZONTAL_MASS_RATIO = 0.35;
    private static final double MIN_HORIZONTAL_DELTA_SQUARED = 0.03 * 0.03;
    private static final double SLACK_SLEW = 5.0;
    private static final double TAUT_SLEW = 12.0;

    private final Vector3d previousVelocity = new Vector3d();
    private final Vector3d previousDelta = new Vector3d();
    private final Vector3d previousForce = new Vector3d();
    private double previousStep;
    private double support;
    private double horizontalRatio;

    void reset() {
        previousStep = 0.0;
        previousForce.zero();
        support = 0.0;
        horizontalRatio = 0.0;
    }

    void observe(Vector3dc velocity, double gravity, double maxThrust, double tautness, double massRatio,
                 boolean canLearnSupport) {
        if (previousStep <= 0.0) return;
        double learn = -Math.expm1(-previousStep / LEARN_SECONDS);
        double release = Math.exp(-previousStep / RELEASE_SECONDS);
        double maxSupport = Math.max(0.0, maxThrust - Math.abs(gravity));
        double gravitySign = Math.signum(gravity);
        double solvedDelta = velocity.y() - previousVelocity.y;
        double observedSupport = gravitySign * ((previousDelta.y - solvedDelta) / previousStep - gravity);

        // Descent changes the force carried by the rope. Keep the last steady
        // support estimate through a maneuver, unless the load has clearly let go.
        if (observedSupport < Math.max(0.25, 0.25 * support)) {
            support *= release;
            if (support < 0.01) support = 0.0;
        } else if (canLearnSupport) {
            // Remove the estimated acceleration shared with the payload when
            // learning during neutral-stick recovery from a downward drift.
            double steadySupport = observedSupport - gravitySign * solvedDelta * massRatio * tautness / previousStep;
            double estimate = Math.clamp(steadySupport, 0.0, maxSupport);
            if (Math.abs(velocity.y()) < MAX_LEARN_VERTICAL_SPEED
                    || (gravitySign * velocity.y() < 0.0 && estimate > support)) {
                support += (estimate - support) * learn;
            }
        }
        support = Math.min(support, maxSupport);

        double horizontalDeltaSquared = previousDelta.x * previousDelta.x + previousDelta.z * previousDelta.z;
        double maxRatio = Math.min(massRatio, MAX_HORIZONTAL_MASS_RATIO) * tautness;
        if (horizontalDeltaSquared >= MIN_HORIZONTAL_DELTA_SQUARED) {
            double response = ((velocity.x() - previousVelocity.x) * previousDelta.x
                    + (velocity.z() - previousVelocity.z) * previousDelta.z) / horizontalDeltaSquared;
            // A stopped or recoiling load is not an inertial mass estimate.
            double observedRatio = response >= 0.15 ? Math.clamp(1.0 / response - 1.0, 0.0, maxRatio) : 0.0;
            horizontalRatio += (observedRatio - horizontalRatio) * learn;
        }
        horizontalRatio = Math.min(horizontalRatio, maxRatio);
    }

    void slewHorizontalForce(Vector3d force, double mass, double tautness, double timeStep) {
        // A released or reversed command must stop driving in the old direction.
        boolean reversing = force.x * previousForce.x + force.z * previousForce.z <= 0.0;
        double startX = reversing ? 0.0 : previousForce.x;
        double startZ = reversing ? 0.0 : previousForce.z;
        double dx = force.x - startX;
        double dz = force.z - startZ;
        double limit = (SLACK_SLEW + (TAUT_SLEW - SLACK_SLEW) * tautness) * mass * timeStep;
        double distance = Math.hypot(dx, dz);
        if (distance > limit) {
            double fraction = limit / distance;
            force.x = startX + dx * fraction;
            force.z = startZ + dz * fraction;
        }
    }

    void record(Vector3dc velocity, Vector3dc impulse, double mass, double timeStep) {
        previousVelocity.set(velocity);
        previousDelta.set(impulse).div(mass);
        previousForce.set(impulse).div(timeStep);
        previousStep = timeStep;
    }

    double support() { return support; }
    double horizontalRatio() { return horizontalRatio; }
}
