package com.bridgecore.control;

import com.bridgecore.firecontrol.ballistics.Quat;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointingExecutionTest {
    private static final double EPS = 1.0e-9;
    private static final Vector3d BOW = new Vector3d(1, 0, 0); // +X bow

    @Test
    void shortestArcCarriesBowOntoDirection() {
        Quaterniond current = new Quaterniond(); // identity: bow = world +X
        Vector3d dir = new Vector3d(0, 1, 0);    // straight up
        Quaterniond target = PointingExecution.shortestArcTarget(current, BOW, dir);
        Vector3d bowAfter = target.transform(new Vector3d(BOW));
        assertEquals(0.0, bowAfter.distance(dir), EPS);
        // error metric agrees: exactly on target
        assertEquals(0.0, PointingExecution.bowErrorRad(target, BOW, dir), 1.0e-6);
    }

    @Test
    void shortestArcWorksFromRolledAndPitchedAttitude() {
        Quaterniond current = new Quaterniond().rotateY(0.7).rotateZ(0.4).rotateX(-0.3);
        Vector3d dir = new Vector3d(-0.4, 0.5, -0.8);
        Quaterniond target = PointingExecution.shortestArcTarget(current, BOW, dir);
        Vector3d dirN = new Vector3d(dir).normalize();
        Vector3d bowAfter = target.transform(new Vector3d(BOW));
        assertEquals(0.0, bowAfter.distance(dirN), 1.0e-6);
        // shortest arc: the world-frame rotation angle equals the bow error
        Quaterniond arc = new Quaterniond(target).mul(new Quaterniond(current).conjugate());
        double arcAngle = 2.0 * Math.acos(Math.min(1.0, Math.abs(arc.w())));
        assertEquals(PointingExecution.bowErrorRad(current, BOW, dirN), arcAngle, 1.0e-6);
    }

    @Test
    void degenerateDirectionRejected() {
        assertNull(PointingExecution.shortestArcTarget(new Quaterniond(), BOW,
                new Vector3d(0, 0, 0)));
        assertNull(PointingExecution.shortestArcTarget(new Quaterniond(), BOW,
                new Vector3d(Double.NaN, 0, 0)));
    }

    @Test
    void rateCommandHonorsRateCapAndErrorDirection() {
        Quaterniond current = new Quaterniond();
        Vector3d dir = new Vector3d(0, 1, 0);
        Quaterniond target = PointingExecution.shortestArcTarget(current, BOW, dir);
        // 90 deg error, kp=4 -> uncapped magnitude 2π, capped at 0.6
        Vector3d cmd = PointingExecution.rateCommand(current, target, 4.0, 0.6, new Vector3d());
        assertEquals(0.6, cmd.length(), EPS);
        // cap not reached: small error -> kp·|e|
        Quaterniond smallTarget = PointingExecution.shortestArcTarget(current, BOW,
                new Vector3d(1, 0.02, 0));
        Vector3d small = PointingExecution.rateCommand(current, smallTarget, 4.0, 0.6,
                new Vector3d());
        double e = PointingExecution.bowErrorRad(current, BOW, new Vector3d(1, 0.02, 0));
        assertEquals(4.0 * e, small.length(), 1.0e-6);
        // zero error -> zero command
        Quaterniond same = PointingExecution.shortestArcTarget(current, BOW, new Vector3d(1, 0, 0));
        Vector3d zero = PointingExecution.rateCommand(current, same, 4.0, 0.6, new Vector3d());
        assertEquals(0.0, zero.length(), EPS);
    }

    @Test
    void settledCounterSustainsAndResets() {
        int frames = 0;
        for (int i = 0; i < PointingExecution.SETTLED_FRAMES; i++) {
            frames = PointingExecution.nextSettleFrames(frames, 0.001, 0.01);
        }
        assertEquals(PointingExecution.SETTLED_FRAMES, frames);
        assertEquals(0, PointingExecution.nextSettleFrames(frames, 0.1, 0.01));
        assertEquals(0, PointingExecution.nextSettleFrames(frames, 0.001, 0.5));
    }

    @Test
    void attitudeSettleDetectsRollWithTheBowAlreadyAligned() {
        double half = Math.toRadians(6);
        var rolled = new Quaterniond(Math.sin(half), 0, 0, Math.cos(half));
        var target = new Quat(rolled.w, rolled.x, rolled.y, rolled.z);
        assertEquals(0.0, PointingExecution.bowErrorRad(rolled, BOW, BOW), 1e-7);
        double error = PointingExecution.attitudeErrorRad(new Quaterniond(), target);
        assertEquals(Math.toRadians(12), error, EPS);
        assertFalse(PointingExecution.withinSettleWindow(error, 0.0));
        assertTrue(PointingExecution.withinSettleWindow(PointingExecution.attitudeErrorRad(rolled, target), 0.0));
        var equivalent = new Quat(-rolled.w, -rolled.x, -rolled.y, -rolled.z);
        assertEquals(0.0, PointingExecution.attitudeErrorRad(rolled, equivalent), 1e-7);
    }

    @Test
    void oppositeDirectionIsFinite() {
        // 180 deg flip: rotationTo must not produce NaN at the singularity
        Quaterniond target = PointingExecution.shortestArcTarget(new Quaterniond(), BOW,
                new Vector3d(-1, 0, 0));
        assertTrue(target.isFinite());
        Vector3d bowAfter = target.transform(new Vector3d(BOW));
        assertEquals(0.0, bowAfter.distance(new Vector3d(-1, 0, 0)), 1.0e-6);
    }
}
