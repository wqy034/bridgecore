package com.bridgecore.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicsWatchdogTest {
    @Test
    void invalidCallbacksDoNotExtendTheLastValidSample() {
        PhysicsWatchdog watchdog = new PhysicsWatchdog();
        watchdog.recordValid(40);
        for (int tick = 41; tick <= 50; tick++) {
            watchdog.recordInvalid(tick);
            assertEquals(-1, watchdog.pollTimeout(tick));
        }

        watchdog.recordInvalid(51);
        assertEquals(11, watchdog.pollTimeout(51));
        assertTrue(watchdog.timedOut());
        watchdog.recordInvalid(52);
        assertEquals(-1, watchdog.pollTimeout(52));
        assertFalse(watchdog.recordValid(53));
        assertFalse(watchdog.timedOut());
        assertTrue(watchdog.recordValid(53));
    }

    @Test
    void callbackSilenceTimesOutWithoutAnInvalidCallback() {
        PhysicsWatchdog watchdog = new PhysicsWatchdog();
        watchdog.recordValid(90);
        assertEquals(-1, watchdog.pollTimeout(100));
        assertEquals(11, watchdog.pollTimeout(101));
        assertEquals(-1, watchdog.pollTimeout(102));
        assertFalse(watchdog.recordValid(110));
    }

    @Test
    void shortInterruptionsBreakAccelerationContinuity() {
        PhysicsWatchdog watchdog = new PhysicsWatchdog();
        assertFalse(watchdog.recordValid(40));
        assertTrue(watchdog.recordValid(40));
        assertTrue(watchdog.recordValid(41));
        watchdog.recordInvalid(41);
        assertFalse(watchdog.recordValid(41));
        assertFalse(watchdog.recordValid(43));
        assertTrue(watchdog.recordValid(44));
        assertFalse(watchdog.timedOut());
    }

    @Test
    void previouslyPhysicalizedCoreGetsAGracePeriodAfterReload() {
        PhysicsWatchdog watchdog = new PhysicsWatchdog();
        assertEquals(-1, watchdog.pollTimeout(80));
        assertFalse(watchdog.wasPhysicalized());

        watchdog.restorePhysicalized(true);
        assertEquals(-1, watchdog.pollTimeout(90));
        assertEquals(-1, watchdog.pollTimeout(100));
        assertEquals(11, watchdog.pollTimeout(101));
    }

    @Test
    void firstInvalidCallbackArmsAWatchdogThatNeverHadAValidSample() {
        PhysicsWatchdog watchdog = new PhysicsWatchdog();
        watchdog.recordInvalid(90);
        watchdog.recordInvalid(100);
        assertTrue(watchdog.wasPhysicalized());
        assertEquals(-1, watchdog.pollTimeout(100));
        assertEquals(11, watchdog.pollTimeout(101));
    }
}
