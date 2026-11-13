package com.bridgecore.control;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.bridgecore.control.NavigationController.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

class NavigationControllerTest {
    private static final double DT = 0.025;

    @Test
    void brakesAndSettlesWithAWeakerLoadedResponse() {
        for (double loadResponse : new double[]{1.0, 0.55}) {
            NavigationController controller = new NavigationController();
            NavigationController.Task task = task();
            assertTrue(controller.start(task));
            double x = 0.0;
            double speed = 0.0;
            double furthest = 0.0;
            for (int frame = 0; frame < 1000 && controller.status().phase() == ACTIVE; frame++) {
                var guidance = controller.guide(task, DT, x, 70.0, 0.0, speed, -0.13, 0.0,
                        2.0 * loadResponse, 4.0, false);
                assertTrue(Math.abs(guidance.vx()) <= 4.0);
                double acceleration = Math.clamp(3.0 * (guidance.vx() - speed), -2.0, 2.0);
                controller.recordBrake(task, speed, 0.0, acceleration * DT, 0.0, DT);
                speed += acceleration * loadResponse * DT;
                x += speed * DT;
                furthest = Math.max(furthest, x);
            }
            assertEquals(ARRIVED, controller.status().phase());
            assertEquals(task.x(), x, 0.5);
            assertTrue(Math.abs(speed) < 0.3);
            assertTrue(furthest < task.x() + 0.5);
        }
    }

    @Test
    void movingThroughTheTargetDoesNotCountAsArrival() {
        var controller = new NavigationController();
        var task = task();
        controller.start(task);
        for (int i = 0; i < 40; i++) {
            var guidance = controller.guide(task, DT, task.x(), task.y(), task.z(), 2.0, 0.0, 0.0,
                    2.0, 4.0, false);
            assertEquals(0.0, guidance.vx());
        }
        assertEquals(ACTIVE, controller.status().phase());
    }

    @Test
    void aBriefPowerDipCanRecoverButSustainedLossAborts() {
        var controller = new NavigationController();
        var task = task();
        controller.start(task);
        controller.guide(task, 0.4, 0.0, 70.0, 0.0, 0.0, 0.0, 0.0, 0.0, 4.0, true);
        assertEquals(ACTIVE, controller.status().phase());
        controller.guide(task, DT, 0.0, 70.0, 0.0, 0.0, 0.0, 0.0, 2.0, 4.0, false);
        controller.guide(task, 0.4, 0.0, 70.0, 0.0, 0.0, 0.0, 0.0, 0.0, 4.0, true);
        assertEquals(ACTIVE, controller.status().phase());
        controller.guide(task, 0.4, 0.0, 70.0, 0.0, 0.0, 0.0, 0.0, 0.0, 4.0, true);
        assertEquals(POWER_LIMITED, controller.status().phase());
    }

    @Test
    void cancellationAndExpiryCannotReviveTheSameTask() {
        var controller = new NavigationController();
        var task = task();
        assertTrue(controller.start(task));
        assertFalse(controller.cancel(UUID.randomUUID()));
        controller.finish(PILOT_OVERRIDE);
        assertNull(controller.active(0));
        assertFalse(controller.start(task));

        var next = task();
        assertTrue(controller.start(next));
        assertEquals(next, controller.active(next.expiresAt() - 1));
        assertNull(controller.active(next.expiresAt()));
        assertEquals(TIMED_OUT, controller.status().phase());
    }

    @Test
    void anExternalAltitudeDoesNotReplaceThePilotHoldCapture() {
        var hover = new HoverController();
        hover.captureHeight(70.0);
        double climb = hover.thrustAcceleration(70.0, 0.0, 0.0, false,
                11.0, 20.0, DT, 1.0, 3.0, 3.0, 1.0, 74.0);
        assertTrue(climb > 11.0);
        assertEquals(70.0, hover.targetHeight());
    }

    @Test
    void nudgeTakesAHalfBlockStepAndRejectsASecondTaskWhileActive() {
        var controller = new NavigationController();
        var task = new NavigationController.Task(UUID.randomUUID(), 0.5, 70.0, 0.0, 0.6, 160,
                NavigationController.Profile.NUDGE);
        assertTrue(controller.start(task));
        assertFalse(controller.start(task()));
        double x = 0.0;
        double vx = 0.0;
        for (int i = 0; i < 300 && controller.status().phase() == ACTIVE; i++) {
            var guide = controller.guide(task, DT, x, 70.0, 0.0, vx, 0.0, 0.0, 2.0, 4.0, false);
            vx += 3.0 * (guide.vx() - vx) * DT;
            x += vx * DT;
        }
        assertEquals(ARRIVED, controller.status().phase());
        assertEquals(0.5, x, 0.075);
        assertTrue(Math.abs(vx) <= 0.1);
    }

    @Test
    void verticalNudgeSettlesDespiteThePreStepVelocityBias() {
        for (double target : new double[]{69.5, 70.5}) {
            var controller = new NavigationController();
            var hover = new HoverController();
            var task = new NavigationController.Task(UUID.randomUUID(), 0.0, target, 0.0, 0.6, 160,
                    NavigationController.Profile.NUDGE);
            controller.start(task);
            double y = 70.0;
            double vy = 0.0;
            for (int i = 0; i < 300 && controller.status().phase() == ACTIVE; i++) {
                double measured = hover.sampleHeightSpeed(y, vy - 0.13, DT);
                double acceleration = hover.thrustAcceleration(y, measured, 0.0, false,
                        11.0, 20.0, DT, 1.0, 3.0, task.speed(), 1.0, target);
                controller.guide(task, DT, 0.0, y, 0.0, 0.0, measured, 0.0, 2.0, 4.0, false);
                vy += (acceleration - 11.0) * DT;
                y += vy * DT;
            }
            assertEquals(ARRIVED, controller.status().phase());
            assertEquals(target, y, 0.09);
        }
    }

    @Test
    void nudgeDirectionsUseLevelHeadingAndExplicitWireIds() {
        assertEquals(NudgeDirection.RIGHT, NudgeDirection.fromWireId(3));
        assertNull(NudgeDirection.fromWireId(6));
        assertEquals(new NudgeDirection.Offset(0.0, 0.0, -0.5), NudgeDirection.FORWARD.offset(0.0, -0.3));
        assertEquals(new NudgeDirection.Offset(0.5, 0.0, 0.0), NudgeDirection.RIGHT.offset(0.0, -0.3));
        assertEquals(new NudgeDirection.Offset(0.0, -0.5, 0.0), NudgeDirection.DOWN.offset(0.0, 0.0));
        assertNull(NudgeDirection.LEFT.offset(0.0, 0.01));
    }

    private static NavigationController.Task task() {
        return new NavigationController.Task(UUID.randomUUID(), 35.0, 70.0, 0.0, 4.0, 2400);
    }
}
