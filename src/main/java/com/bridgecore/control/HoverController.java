package com.bridgecore.control;

/** World-Y climb, braking, and altitude hold. All state belongs to the physics callback. */
public final class HoverController {
    private static final double BRAKE_ACCELERATION = 7.0;
    private static final double BRAKE_RESERVE = 0.8;
    private static final double DESCENT_STOP_DISTANCE = 1.0;
    private static final double RELEASE_CONFIRM_SECONDS = 0.125;
    // The pre-step velocity can read about -0.13 m/s at a stationary hover.
    private static final double CAPTURE_SPEED = 0.2;
    private static final double SETTLE_DISTANCE = 1.0;
    private static final double DRIFT_DISTANCE = 8.0;
    private static final double DRIFT_CONFIRM_SECONDS = 1.0;
    private static final double TIME_EPSILON = 1.0e-9;

    private double targetHeight = Double.NaN;
    private double quietSeconds;
    private double driftSeconds;
    private boolean settled;
    private double previousHeight;
    private double previousHeightStep;

    public void reset() {
        targetHeight = Double.NaN;
        quietSeconds = 0.0;
        driftSeconds = 0.0;
        settled = false;
    }

    /** NaN while the pilot owns the height channel or the ship is still braking. */
    public double targetHeight() {
        return targetHeight;
    }

    /** Gravity compensation is signed, positive when the dimension pulls downward. */
    public double thrustAcceleration(double height, double velocity, double stick, boolean followPitch,
                                     double gravityCompensation, double availableThrust, double timeStep,
                                     double positionGain, double velocityGain, double climbRate) {
        return thrustAcceleration(height, velocity, stick, followPitch, gravityCompensation, availableThrust,
                timeStep, positionGain, velocityGain, climbRate, 1.0);
    }

    /** Support and thrust use force per controlled mass; response accounts for a flexible load's inertia. */
    public double thrustAcceleration(double height, double velocity, double stick, boolean followPitch,
                                     double supportAcceleration, double availableThrust, double timeStep,
                                     double positionGain, double velocityGain, double climbRate, double response) {
        return thrustAcceleration(height, velocity, stick, followPitch, supportAcceleration, availableThrust,
                timeStep, positionGain, velocityGain, climbRate, response, Double.NaN);
    }

    public void captureHeight(double height) {
        reset();
        targetHeight = height;
    }

    /** Use world displacement for precision hold, where the pre-step velocity bias is significant. */
    public double sampleHeightSpeed(double height, double fallback, double dt) {
        double speed = previousHeightStep > 0.0 ? (height - previousHeight) / previousHeightStep : fallback;
        previousHeight = height;
        previousHeightStep = dt;
        return Math.abs(speed - fallback) <= 0.5 ? speed : fallback;
    }

    public void resetHeightSampling() {
        previousHeightStep = 0.0;
    }

    public double thrustAcceleration(double height, double velocity, double stick, boolean followPitch,
                                     double supportAcceleration, double availableThrust, double timeStep,
                                     double positionGain, double velocityGain, double climbRate, double response,
                                     double externalHeight) {
        if (followPitch) {
            reset();
            return supportAcceleration;
        }

        double upwardBrake = brakeLimit(availableThrust - supportAcceleration);
        double downwardBrake = brakeLimit(availableThrust + supportAcceleration);
        // Preserve the nominal stopping distance as available braking authority falls.
        double climbLimit = climbRate * Math.sqrt(downwardBrake / BRAKE_ACCELERATION);
        double descentLimit = Math.min(climbRate * Math.sqrt(upwardBrake / BRAKE_ACCELERATION),
                descentSpeedForStoppingDistance(upwardBrake * response, velocityGain * response));

        if (Double.isFinite(externalHeight)) {
            double speed = Math.clamp(positionGain * (externalHeight - height), -descentLimit, climbLimit);
            return supportAcceleration + Math.clamp(velocityGain * (speed - velocity), -downwardBrake, upwardBrake);
        }

        if (stick != 0.0) {
            reset();
            double speed = stick * (stick > 0.0 ? climbLimit : descentLimit);
            double correction = velocityGain * (speed - velocity);
            if (stick < 0.0 && response < 1.0) {
                correction = Math.clamp(correction, -upwardBrake, upwardBrake);
            }
            return supportAcceleration + correction;
        }

        if (availableThrust <= Math.abs(supportAcceleration)) {
            // Discard the old height while power cannot support hold. On refuel,
            // brake and capture at the new height.
            reset();
        } else if (Double.isNaN(targetHeight)) {
            quietSeconds += timeStep;
            if (quietSeconds + TIME_EPSILON >= RELEASE_CONFIRM_SECONDS && Math.abs(velocity) <= CAPTURE_SPEED) {
                targetHeight = height;
            }
        } else {
            double distance = Math.abs(targetHeight - height);
            if (distance < SETTLE_DISTANCE && Math.abs(velocity) <= CAPTURE_SPEED) {
                settled = true;
            }
            driftSeconds = settled && distance > DRIFT_DISTANCE ? driftSeconds + timeStep : 0.0;
            if (driftSeconds + TIME_EPSILON >= DRIFT_CONFIRM_SECONDS) {
                reset();
            }
        }

        if (Double.isNaN(targetHeight)) {
            double braking = Math.clamp(-velocityGain * velocity, -downwardBrake, upwardBrake);
            return supportAcceleration + braking;
        }

        double speed = Math.clamp(positionGain * (targetHeight - height), -descentLimit, climbLimit);
        return supportAcceleration + velocityGain * (speed - velocity);
    }

    private static double brakeLimit(double authority) {
        return Math.clamp(authority * BRAKE_RESERVE, 0.0, BRAKE_ACCELERATION);
    }

    private static double descentSpeedForStoppingDistance(double upwardBrake, double velocityGain) {
        // Release braking is min(upwardBrake, velocityGain * descentSpeed).
        // Include the proportional slowdown near zero in the distance budget.
        double transitionSpeed = upwardBrake / velocityGain;
        double proportionalSpeed = velocityGain * DESCENT_STOP_DISTANCE;
        if (proportionalSpeed <= transitionSpeed) {
            return proportionalSpeed;
        }
        return Math.sqrt(2.0 * upwardBrake * DESCENT_STOP_DISTANCE - transitionSpeed * transitionSpeed);
    }
}
