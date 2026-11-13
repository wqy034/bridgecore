package com.bridgecore.control;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CargoFlightTest {
    private static final double STEP = 0.025;
    private static final double GRAVITY = 11.0;
    private static final double MAX_THRUST = 20.0;

    @Test
    void addsRotatedDockedInertiaAboutTheSharedCenter() {
        DockedMass mass = new DockedMass();
        mass.reset(100, new Vector3d(), new Matrix3d().scaling(10, 20, 30));
        mass.add(30, new Vector3d(4, 2, 0), new Matrix3d().scaling(1, 2, 3),
                new Quaterniond().setAngleAxis(Math.PI / 2.0, 0, 1, 0));
        double reduced = 100.0 * 30 / 130;

        assertEquals(130, mass.mass(), 1.0e-9);
        assertEquals(4.0 * 30 / 130, mass.center().x(), 1.0e-9);
        assertEquals(2.0 * 30 / 130, mass.center().y(), 1.0e-9);
        assertEquals(13 + 4 * reduced, mass.inertia().m00(), 1.0e-9);
        assertEquals(22 + 16 * reduced, mass.inertia().m11(), 1.0e-9);
        assertEquals(31 + 20 * reduced, mass.inertia().m22(), 1.0e-9);
        assertEquals(-8 * reduced, mass.inertia().m01(), 1.0e-9);
        assertEquals(mass.inertia().m01(), mass.inertia().m10(), 1.0e-9);
    }

    @Test
    void descendsAndStopsWithThirtyPercentExternalLoad() {
        verifyTetherDescent(0.30);
    }

    @Test
    void brakesAgainstTheSupportMeasuredInTheSixBlockTrace() {
        verifyTetherDescent((18.82 - GRAVITY) / GRAVITY);
    }

    @Test
    void limitsTheChangeInHorizontalForceAndShedsObsoleteThrustOnReversal() {
        TetherLoad tether = new TetherLoad();
        Vector3d initial = new Vector3d(1, 0, 0);
        Vector3d impulse = new Vector3d(0.1, 0, 0);
        Vector3d solved = new Vector3d(initial).fma(1.0 / 1.3, impulse);
        for (int step = 0; step < 140; step++) {
            tether.record(initial, impulse, 1.0, STEP);
            tether.observe(solved, GRAVITY, MAX_THRUST, 1.0, 0.3, false);
        }
        assertEquals(0.3, tether.horizontalRatio(), 0.001);

        Vector3d reversal = new Vector3d(-20, 0, 0);
        tether.slewHorizontalForce(reversal, 1.0, 1.0, STEP);
        assertEquals(-0.3, reversal.x, 1.0e-9);
        tether.observe(solved, GRAVITY, MAX_THRUST, 0.0, 0.3, false);
        assertEquals(0.0, tether.horizontalRatio());
    }

    private static void verifyTetherDescent(double ratio) {
        HoverController hover = new HoverController();
        TetherLoad tether = new TetherLoad();
        Vector3d velocity = new Vector3d();
        Vector3d impulse = new Vector3d();
        double loadAcceleration = GRAVITY * (1.0 + ratio);
        double height = 90.0;
        double releaseHeight = 0.0;
        double lowestAfterRelease = Double.POSITIVE_INFINITY;

        for (int step = 0; step < 520; step++) {
            double stick = step >= 200 && step < 320 ? -1.0 : 0.0;
            tether.observe(velocity, GRAVITY, MAX_THRUST, 1.0, ratio, stick == 0.0);
            double support = GRAVITY + tether.support();
            double response = 1.0 / (1.0 + Math.max(ratio, tether.support() / GRAVITY));
            double thrust = hover.thrustAcceleration(height, velocity.y, stick, false, support,
                    MAX_THRUST, STEP, 1.0, 4.0, 12.0, response);
            thrust = Math.clamp(thrust, -MAX_THRUST, MAX_THRUST);
            if (step == 200) assertEquals(loadAcceleration, support, 0.08);
            if (step == 320) {
                releaseHeight = height;
                assertTrue(velocity.y < -0.5);
                assertTrue(thrust > loadAcceleration);
            }
            tether.record(velocity, impulse.set(0, thrust * STEP, 0), 1.0, STEP);
            velocity.y += (thrust - loadAcceleration) * STEP / (1.0 + ratio);
            height += velocity.y * STEP;
            if (step >= 320) lowestAfterRelease = Math.min(lowestAfterRelease, height);
        }

        assertTrue(releaseHeight - lowestAfterRelease < 1.0);
        assertTrue(Double.isFinite(hover.targetHeight()));
        assertTrue(Math.abs(velocity.y) < 0.04);

        // The rope can remain attached but unload when it goes slack.
        velocity.zero();
        for (int step = 0; step < 36; step++) {
            tether.record(velocity, impulse.set(0, GRAVITY * STEP, 0), 1.0, STEP);
            tether.observe(velocity, GRAVITY, MAX_THRUST, 0.0, ratio, true);
        }
        assertEquals(0.0, tether.support());
    }
}
