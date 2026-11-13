package com.bridgecore.control;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CruiseHeadingTest {
    @Test
    void bowFacesTheCourseAndHorizontalSpeedWaitsForTheTurn() {
        var heading = new CruiseHeading();
        heading.update(cruise(20, 77, 0), 0, 0, 0, -1);
        var target = heading.target();
        assertNotNull(target);
        var bow = target.transform(new Vector3d(0, 0, -1));
        assertEquals(1.0, bow.x, 1e-9);
        assertEquals(0.0, bow.y, 1e-9);
        assertEquals(0.0, bow.z, 1e-9);
        assertEquals(0.0, heading.speedFactor(new Quaterniond()));
        double partial = heading.speedFactor(new Quaterniond().rotationY(Math.toRadians(-60)));
        assertTrue(partial > 0.0 && partial < 1.0);
        assertEquals(1.0, heading.speedFactor(target));
    }

    @Test
    void turningProgressOnlyCountsWhileTheBowIsStillOffCourse() {
        var start = new Quaterniond();
        assertEquals(Math.PI, CruiseHeading.turningError(start, 0, -1, 0, 20), 1e-9);
        assertTrue(Double.isNaN(CruiseHeading.turningError(start, 0, -1, 0, -20)));
        assertTrue(Double.isNaN(CruiseHeading.turningError(start, 0, -1, 0, 0.5)));
    }

    @Test
    void keepsTheApproachHeadingAcrossSmallArrivalCorrections() {
        var heading = new CruiseHeading();
        var route = cruise(20, 70, 0);
        heading.update(route, 0, 0, 1, 0);
        heading.update(route, 20.25, 0, 1, 0);
        assertEquals(1.0, heading.course(new Vector3d()).x(), 1e-9);
        assertEquals(1.0, heading.speedFactor(new Quaterniond()));
        heading.clear();
        assertNull(heading.target());
    }

    @Test
    void aVerticalCruiseOrAnIndependentNudgeKeepsExistingHeading() {
        var heading = new CruiseHeading();
        heading.update(cruise(0, 100, 0), 0, 0, 0, -1);
        assertNull(heading.target());
        assertEquals(1.0, heading.speedFactor(new Quaterniond()));
        var nudge = new NavigationController.Task(UUID.randomUUID(), 0.5, 70, 0, 0.6, 160,
                NavigationController.Profile.NUDGE);
        heading.update(nudge, 0, 0, 0, -1);
        assertNull(heading.target());
    }

    private static NavigationController.Task cruise(double x, double y, double z) {
        return new NavigationController.Task(UUID.randomUUID(), x, y, z, 4.0, 2400);
    }
}
