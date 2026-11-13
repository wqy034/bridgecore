package com.bridgecore.control;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttitudeTrimTest {
    private static final double STEP = 0.025;
    private static final double MASS = 215.5;
    private static final double MAX_ALPHA = 8.0;

    @Test
    void reducesSteadyTiltAndLimitsReboundAfterUnload() {
        AttitudeTrim trim = new AttitudeTrim();
        Quaterniond target = new Quaterniond();
        Quaterniond orientation = new Quaterniond();
        Vector3d omega = new Vector3d();
        Vector3d acceleration = new Vector3d();
        double angle = 0.0;
        double maxOppositeTilt = 0.0;

        for (int step = 0; step < 520; step++) {
            boolean loaded = step < 320;
            orientation.setAngleAxis(angle, 1.0, 0.0, 0.0);
            AttitudeController.computeAngularAcceleration(AttitudeController.Mode.HOLD, orientation, target,
                    null, omega, acceleration, AttitudeController.DEFAULT_KP, AttitudeController.DEFAULT_KD);
            trim.addCorrection(target, orientation, omega, MASS, MAX_ALPHA, acceleration);
            AttitudeController.limitAngularAcceleration(acceleration, MAX_ALPHA);
            omega.x += acceleration.x * STEP;
            trim.recordAfterWrite(omega, STEP);
            if (loaded) omega.x -= 0.6 * STEP;
            angle += omega.x * STEP;

            if (step == 319) assertTrue(Math.abs(Math.toDegrees(angle)) < 0.25);
            if (!loaded) maxOppositeTilt = Math.max(maxOppositeTilt, angle);
        }

        assertTrue(maxOppositeTilt > 0.0);
        assertTrue(Math.toDegrees(maxOppositeTilt) < 1.5);
        assertTrue(Math.abs(Math.toDegrees(angle)) < 0.1);
    }

    @Test
    void keepsLoadedPitchMovingThroughRateAndHoldRecapture() {
        AttitudeTrim trim = new AttitudeTrim();
        Quaterniond target = new Quaterniond();
        Quaterniond orientation = new Quaterniond();
        Vector3d omega = new Vector3d();
        Vector3d acceleration = new Vector3d();
        Vector3d rateTarget = new Vector3d(0.6, 0.0, 0.0);
        double load = 4.2;
        double angle = -Math.toRadians(31.55);
        orientation.setAngleAxis(angle, 1.0, 0.0, 0.0);

        // Establish the settled bias observed before pressing the stick.
        Vector3d afterHoldWrite = new Vector3d(load * STEP, 0.0, 0.0);
        trim.addCorrection(target, orientation, omega, MASS, MAX_ALPHA, acceleration);
        for (int step = 0; step < 320; step++) {
            trim.recordAfterWrite(afterHoldWrite, STEP);
            trim.addCorrection(target, orientation, omega, MASS, MAX_ALPHA, acceleration.zero());
        }
        trim.recordAfterWrite(afterHoldWrite, STEP);

        for (int step = 0; step < 120; step++) {
            orientation.setAngleAxis(angle, 1.0, 0.0, 0.0);
            AttitudeController.computeAngularAcceleration(AttitudeController.Mode.RATE, orientation, null,
                    rateTarget, omega, acceleration, AttitudeController.DEFAULT_KP, AttitudeController.DEFAULT_KD);
            if (step == 0) assertTrue(acceleration.x < load);
            trim.addCorrection(null, orientation, omega, MASS, MAX_ALPHA, acceleration);
            if (step == 0) assertEquals(5.6, acceleration.x, 0.001);
            AttitudeController.limitAngularAcceleration(acceleration, MAX_ALPHA);
            omega.x += acceleration.x * STEP;
            trim.recordAfterWrite(omega, STEP);
            omega.x -= load * STEP;
            angle += omega.x * STEP;
            assertTrue(omega.x > 0.0);
        }
        assertTrue(omega.x > 0.2);

        orientation.setAngleAxis(angle, 1.0, 0.0, 0.0);
        Quaterniond recaptured = new Quaterniond(orientation);
        trim.addCorrection(recaptured, orientation, omega, MASS, MAX_ALPHA, acceleration.zero());
        assertEquals(2.0, acceleration.x, 0.001);

        for (int step = 0; step < 28; step++) {
            trim.recordAfterWrite(omega, STEP);
            trim.addCorrection(recaptured, orientation, omega, MASS, MAX_ALPHA, acceleration.zero());
        }
        assertEquals(0.0, acceleration.length());
    }

    @Test
    void limitsLearnedCorrectionAndLetsAnIsolatedImpactFade() {
        AttitudeTrim trim = new AttitudeTrim();
        Quaterniond target = new Quaterniond();
        Vector3d atRest = new Vector3d();
        Vector3d afterWrite = new Vector3d(100.0 * STEP, 0.0, 0.0);
        Vector3d acceleration = new Vector3d();
        trim.addCorrection(target, target, atRest, MASS, MAX_ALPHA, acceleration);
        trim.recordAfterWrite(afterWrite, STEP);
        trim.addCorrection(target, target, atRest, MASS, MAX_ALPHA, acceleration);
        assertTrue(trim.correctionWorld().length() < 0.07);

        for (int step = 0; step < 20; step++) {
            trim.recordAfterWrite(atRest, STEP);
            trim.addCorrection(target, target, atRest, MASS, MAX_ALPHA, acceleration.zero());
        }
        assertEquals(0.0, trim.correctionWorld().length());

        for (int step = 0; step < 320; step++) {
            trim.recordAfterWrite(afterWrite, STEP);
            trim.addCorrection(target, target, atRest, MASS, MAX_ALPHA, acceleration.zero());
        }
        assertEquals(2.0, trim.correctionWorld().length(), 0.001);
        trim.addCorrection(target, target, atRest, MASS, 2.0, acceleration.zero());
        assertEquals(0.5, trim.correctionWorld().length(), 0.001);
    }

    @Test
    void clearsStaleCorrectionAndVelocityWhenTargetOrMassChanges() {
        AttitudeTrim trim = new AttitudeTrim();
        Quaterniond target = new Quaterniond();
        Vector3d atRest = new Vector3d();
        Vector3d afterWrite = new Vector3d(0.6 * STEP, 0.0, 0.0);
        Vector3d acceleration = new Vector3d();
        trim.addCorrection(target, target, atRest, MASS, MAX_ALPHA, acceleration);
        trim.recordAfterWrite(afterWrite, STEP);
        trim.addCorrection(target, target, atRest, MASS, MAX_ALPHA, acceleration.zero());
        assertTrue(acceleration.x > 0.0);

        Quaterniond recaptured = new Quaterniond(target);
        trim.recordAfterWrite(afterWrite, STEP);
        trim.addCorrection(recaptured, target, atRest, MASS, MAX_ALPHA, acceleration.zero());
        assertEquals(0.0, acceleration.length());

        trim.recordAfterWrite(afterWrite, STEP);
        trim.addCorrection(recaptured, target, atRest, MASS, MAX_ALPHA, acceleration.zero());
        assertTrue(acceleration.x > 0.0);

        trim.recordAfterWrite(afterWrite, STEP);
        trim.addCorrection(recaptured, target, atRest, MASS + 24.5, MAX_ALPHA, acceleration.zero());
        assertEquals(0.0, acceleration.length());
    }
}
