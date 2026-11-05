package com.bridgecore.display;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DisplayResolutionTest {
    @Test void legacyLowDefaultsUpgradeButExplicitEconomyRemainsAvailable() {
        assertEquals(256, DisplayResolution.effective(0, 64));
        assertEquals(256, DisplayResolution.effective(0, 128));
        assertEquals(512, DisplayResolution.effective(0, 512));
        assertEquals(128, DisplayResolution.effective(128, 512));
        assertEquals(256, DisplayResolution.effective(-1, 64));
        assertFalse(DisplayResolution.validChoice(Integer.MAX_VALUE));
    }
    @Test void standardWallFitsAtBothDensitiesAndOversizedWallsRejectBeforeAllocation() {
        assertTrue(DisplayResolution.fits(3, 2, 256, 64L * 1024 * 1024));
        assertTrue(DisplayResolution.fits(3, 2, 512, 64L * 1024 * 1024));
        assertTrue(DisplayResolution.fits(8, 8, 512, 64L * 1024 * 1024));
        assertFalse(DisplayResolution.fits(9, 8, 512, 64L * 1024 * 1024));
        assertFalse(DisplayResolution.fits(Integer.MAX_VALUE, Integer.MAX_VALUE, 512, Long.MAX_VALUE));
        assertFalse(DisplayResolution.fits(1, 1, 513, Long.MAX_VALUE));
        assertTrue(DisplayResolution.fits(0, 0, 512, 1024));
    }
}
