package com.bridgecore.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoverControllerTest {
    private static final double DT = 0.025;
    private static final double EPSILON = 1.0e-9;

    @Test
    void brakesImmediatelyAndCapturesTheStoppedHeight() {
        HoverController hover = new HoverController();
        thrust(hover, 90, -4, -1, false, 20, DT);

        for (int step = 0; step < 5; step++) {
            assertEquals(18, thrust(hover, 90, -4, 0, false, 20, DT), EPSILON);
            assertTrue(Double.isNaN(hover.targetHeight()));
        }

        assertEquals(11.52, thrust(hover, 88.5, -0.13, 0, false, 20, DT), EPSILON);
        assertEquals(88.5, hover.targetHeight(), EPSILON);
        assertEquals(12, thrust(hover, 88.25, 0, 0, false, 20, DT), EPSILON);
    }

    @Test
    void confirmsReleaseByElapsedPhysicsTime() {
        HoverController hover = new HoverController();
        for (int step = 0; step < 9; step++) {
            thrust(hover, 90, 0, 0, false, 20, DT / 2);
            assertTrue(Double.isNaN(hover.targetHeight()));
        }

        thrust(hover, 90, 0, 0, false, 20, DT / 2);
        assertEquals(90, hover.targetHeight(), EPSILON);
    }

    @Test
    void limitsDescentAndBrakingWhenTheSameBudgetMovesMoreMass() {
        HoverController hover = new HoverController();
        // For the same FE budget per step, 1.6 times the mass cuts available
        // thrust per kilogram from 20 to 12.5 m/s².
        double fullPowerDescent = thrust(hover, 90, 0, -1, false, 20, DT);
        assertTrue(fullPowerDescent > -2.3 && fullPowerDescent < -2.2);
        double limitedDescent = thrust(hover, 90, 0, -1, false, 12.5, DT);
        assertTrue(limitedDescent > 4 && limitedDescent < 5);
        assertEquals(12.2, thrust(hover, 90, -4, 0, false, 12.5, DT), EPSILON);
        assertEquals(4, thrust(hover, 90, 4, 0, false, 12.5, DT), EPSILON);
        assertEquals(11, thrust(hover, 90, 12, 1, false, 20, DT), EPSILON);
    }

    @Test
    void capturesANewHeightAfterBrownoutAndAfterPitchFollow() {
        HoverController hover = new HoverController();
        thrust(hover, 90, 0, 0, false, 20, 5 * DT);
        assertEquals(90, hover.targetHeight(), EPSILON);

        thrust(hover, 85, -1, 0, false, 10, DT);
        assertTrue(Double.isNaN(hover.targetHeight()));
        thrust(hover, 84, -1, 0, false, 20, 5 * DT);
        assertTrue(Double.isNaN(hover.targetHeight()));
        thrust(hover, 83, 0, 0, false, 20, DT);
        assertEquals(83, hover.targetHeight(), EPSILON);

        assertEquals(11, thrust(hover, 83, -1, 0, true, 20, DT), EPSILON);
        assertTrue(Double.isNaN(hover.targetHeight()));
        assertEquals(15, thrust(hover, 82, -1, 0, false, 20, DT), EPSILON);
    }

    @Test
    void fullStickDescentStopsWithinOneBlockAtTheMaximumRateSetting() {
        HoverController hover = new HoverController();
        double height = 90.0;
        double velocity = 0.0;

        // Isolated vertical plant with matched mass, full FE, and no contact.
        for (int step = 0; step < 160; step++) {
            double acceleration = thrust(hover, height, velocity, -1, false, 20, DT);
            velocity += (Math.clamp(acceleration, -20.0, 20.0) - 11.0) * DT;
            height += velocity * DT;
        }
        double releaseHeight = height;
        double lowestHeight = height;
        assertTrue(velocity < -3.2 && velocity > -3.4);

        for (int step = 0; step < 160; step++) {
            double acceleration = thrust(hover, height, velocity, 0, false, 20, DT);
            velocity += (Math.clamp(acceleration, -20.0, 20.0) - 11.0) * DT;
            height += velocity * DT;
            lowestHeight = Math.min(lowestHeight, height);
        }

        assertTrue(Double.isFinite(hover.targetHeight()));
        assertTrue(releaseHeight - lowestHeight < 1.0);
        assertTrue(hover.targetHeight() - lowestHeight < 0.05);
        assertEquals(hover.targetHeight(), height, 0.01);
    }

    @Test
    void includesTheLongerBrakingTailAtLowVelocityGain() {
        HoverController hover = new HoverController();
        assertEquals(10.75, hover.thrustAcceleration(90, 0, -1, false,
                11, 20, DT, 1, 0.5, 12), EPSILON);
    }

    private static double thrust(HoverController hover, double height, double velocity,
                                 double stick, boolean followPitch, double availableThrust, double timeStep) {
        return hover.thrustAcceleration(height, velocity, stick, followPitch,
                11.0, availableThrust, timeStep, 1.0, 4.0, 12.0);
    }
}
