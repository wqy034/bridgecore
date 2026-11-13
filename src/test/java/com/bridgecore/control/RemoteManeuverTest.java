package com.bridgecore.control;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RemoteManeuverTest {
    private static final int FORWARD = 1 << NudgeDirection.FORWARD.wireId();
    private static final int RIGHT = 1 << NudgeDirection.RIGHT.wireId();
    private static final int UP = 1 << NudgeDirection.UP.wireId();
    private static final long HOLD = RemoteKeyTiming.HOLD_NANOS;

    @Test
    void aTapProducesOneStepAndAHoldProducesOnlySustainedInput() {
        var keys = new RemoteKeyTiming();
        assertNull(keys.sample(FORWARD, 0).step());
        assertEquals(NudgeDirection.FORWARD, keys.sample(0, HOLD - 1).step());
        assertNull(keys.sample(0, HOLD).step());

        assertEquals(0, keys.sample(FORWARD, HOLD).held());
        assertEquals(0, keys.sample(FORWARD, HOLD * 2 - 1).held());
        assertEquals(FORWARD, keys.sample(FORWARD, HOLD * 2).held());
        var release = keys.sample(0, HOLD * 3);
        assertEquals(0, release.held());
        assertNull(release.step());
    }

    @Test
    void enteringWithKeysDownRequiresAReleaseAndYawIsImmediate() {
        var keys = new RemoteKeyTiming();
        keys.reset(UP | RemoteManeuver.TURN_RIGHT);
        assertEquals(0, keys.sample(UP | RemoteManeuver.TURN_RIGHT, 0).held());
        assertNull(keys.sample(0, HOLD).step());
        assertEquals(RemoteManeuver.TURN_RIGHT, keys.sample(RemoteManeuver.TURN_RIGHT, HOLD + 1).held());
    }

    @Test
    void heartbeatCannotStartOrReviveControlAfterExpiryOrTakeover() {
        var remote = new RemoteManeuver();
        var owner = owner();
        assertFalse(remote.update(owner, UP, 20, false, 0));
        assertTrue(remote.update(owner, UP, 20, true, 1));
        assertFalse(remote.update(owner(), RIGHT, 40, true, 2));
        assertTrue(remote.update(owner, UP, 20, false, 100_000_000));
        assertNotNull(remote.active(499_999_999));
        assertNull(remote.active(500_000_000));
        assertFalse(remote.update(owner, UP, 20, true, 500_000_001));
        assertTrue(remote.update(owner, 0, 20, true, 500_000_002));
        assertTrue(remote.update(owner, UP, 20, true, 500_000_003));
        remote.revoke();
        assertFalse(remote.update(owner, UP, 20, false, 500_000_004));
        assertTrue(remote.update(owner, 0, 20, true, 500_000_005));
        assertNull(remote.active(500_000_005));
    }

    @Test
    void movementNormalizesCombinedAxesAndYawKeepsItsOwnRateLimit() {
        var input = new RemoteManeuver.Input(owner(), FORWARD | RIGHT | UP | RemoteManeuver.TURN_LEFT, 40, 0);
        var motion = RemoteManeuver.resolve(input, 0.0, -0.3, 8.0);
        assertEquals(4.0, Math.sqrt(motion.x() * motion.x() + motion.y() * motion.y() + motion.z() * motion.z()), 1e-9);
        assertTrue(motion.x() > 0 && motion.y() > 0 && motion.z() < 0);
        assertEquals(0.15, motion.yaw());
        assertEquals(0.0, RemoteManeuver.limitYawAcceleration(1.0, 0.15, 0.025));
        assertEquals(0.0, RemoteManeuver.limitYawAcceleration(-1.0, -0.15, 0.025));
        assertEquals(0.4, RemoteManeuver.limitYawAcceleration(1.0, 0.14, 0.025), 1e-9);
    }

    private static RemoteManeuver.Owner owner() {
        return new RemoteManeuver.Owner(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
}
