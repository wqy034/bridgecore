package com.bridgecore.firecontrol.ballistics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StrikeTrajectoryTest {
    @Test
    void directFlightRespectsMuzzleSpeedAndLifetime() {
        var displacement = new Vec3(12, 0, 0);
        assertEquals(new Vec3(1, 0, 0), StrikeTrajectory.direction(displacement, params(100, 0, 1), 3));
        assertNull(StrikeTrajectory.direction(displacement, params(100, 0, 1), 2));
        assertNull(StrikeTrajectory.direction(displacement, params(50, 0, 1), 3));
    }

    @Test
    void gravityAddsElevationAndDragCanShortenReach() {
        var displacement = new Vec3(50, 0, 0);
        var direction = StrikeTrajectory.direction(displacement, params(100, 0.05, 1), 20);
        assertNotNull(direction);
        assertTrue(direction.y() > 0);
        assertEquals(1, direction.length(), 1e-9);
        assertNull(StrikeTrajectory.direction(displacement, params(100, 0.05, 0.8), 20));
    }

    @Test
    void invalidInputsAndUnreachableDistancesReturnNoSolution() {
        assertNull(StrikeTrajectory.direction(new Vec3(0, 0, 0), params(100, 0.05, 1), 20));
        assertNull(StrikeTrajectory.direction(new Vec3(Double.NaN, 0, 0), params(100, 0.05, 1), 20));
        assertNull(StrikeTrajectory.direction(new Vec3(5000, 0, 0), params(100, 0, 1), Integer.MAX_VALUE));
    }

    private static SolveChain.Params params(double speed, double gravity, double drag) {
        return new SolveChain.Params(drag, gravity, speed, 0, -90, 90, 180, "none", 0, 0, 0, 0.05,
                false, false, 0, "-X");
    }
}
