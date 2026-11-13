package com.bridgecore.control;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Estimates slow disturbances for HOLD and RATE from world angular velocity after a control write. */
public final class AttitudeTrim {
    private static final double MAX_TRIM_ACCELERATION = 2.0;
    private static final double MAX_TRIM_FRACTION = 0.25;
    private static final double MAX_LEARNING_ANGULAR_SPEED_SQUARED = 0.25 * 0.25;
    private static final double MIN_TRIM_SQUARED = 0.01 * 0.01;
    private static final double LEARN_TAU = 0.75;
    private static final double RELEASE_TAU = 0.12;

    private final Vector3d correctionWorld = new Vector3d();
    private final Vector3d measuredCorrection = new Vector3d();
    private final Vector3d correctionBody = new Vector3d();
    private final Vector3d previousAfterWrite = new Vector3d();
    @Nullable
    private Quaterniondc target;
    private double mass;
    private double previousStep;

    public void reset() {
        correctionWorld.zero();
        previousStep = 0.0;
        target = null;
    }

    /** Adds the correction before the total acceleration limit. RATE supplies a null hold target. */
    public void addCorrection(@Nullable Quaterniondc holdTarget, Quaterniondc orientation, Vector3dc omegaWorld,
                              double currentMass, double maxAlpha, Vector3d accelerationBody) {
        // Keep the physical load estimate through stick press and release. A
        // direct change between two HOLD targets still clears the old estimate.
        boolean retargetedHold = target != null && holdTarget != null && target != holdTarget;
        if (retargetedHold || mass != currentMass) {
            reset();
            mass = currentMass;
        }
        target = holdTarget;

        double limit = Math.min(MAX_TRIM_ACCELERATION, maxAlpha * MAX_TRIM_FRACTION);
        if (previousStep > 0.0) {
            measuredCorrection.set(previousAfterWrite).sub(omegaWorld).div(previousStep);
            double stored = correctionWorld.lengthSquared();
            // Release faster than we learn. A vanished or reversed load must
            // shed the old correction before any opposite bias can build up.
            if (omegaWorld.lengthSquared() > MAX_LEARNING_ANGULAR_SPEED_SQUARED
                    || measuredCorrection.lengthSquared() < MIN_TRIM_SQUARED
                    || measuredCorrection.dot(correctionWorld) < 0.5 * stored) {
                correctionWorld.mul(Math.exp(-previousStep / RELEASE_TAU));
                if (correctionWorld.lengthSquared() < MIN_TRIM_SQUARED) correctionWorld.zero();
            } else {
                AttitudeController.limitAngularAcceleration(measuredCorrection, limit);
                correctionWorld.lerp(measuredCorrection, -Math.expm1(-previousStep / LEARN_TAU));
            }
        }

        AttitudeController.limitAngularAcceleration(correctionWorld, limit);
        accelerationBody.add(orientation.transformInverse(correctionWorld, correctionBody));
    }

    /** The caller supplies a sample only after a fully applied, unsaturated HOLD or RATE step. */
    public void recordAfterWrite(Vector3dc omegaWorld, double timeStep) {
        if (!omegaWorld.isFinite()) {
            reset();
            return;
        }
        previousAfterWrite.set(omegaWorld);
        previousStep = timeStep;
    }

    public Vector3dc correctionWorld() {
        return correctionWorld;
    }
}
