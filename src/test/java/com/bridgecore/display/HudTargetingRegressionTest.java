package com.bridgecore.display;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class HudTargetingRegressionTest {
    @Test void offAxisCalibrationStillProjectsCenterRayToCenter() {
        var p = HudProjectionMath.project(-1.2, .8, 10, .24, -.16, 2);
        assertNotNull(p); assertEquals(0, p.x(), 1e-9); assertEquals(0, p.y(), 1e-9);
        assertEquals(0, p.offAxis(), 1e-9);
    }
    @Test void screenAndConeUseTheSameProjectedOffsets() {
        var p = HudProjectionMath.project(2, 1, 10, 0, 0, 2);
        assertTrue(p.onPanel(1.25)); assertEquals(Math.hypot(.4,.2)/2, p.offAxis(), 1e-9);
        assertFalse(HudProjectionMath.project(20,0,10,0,0,2).onPanel(1.25));
    }
    @Test void behindEyeAndInvalidGeometryCannotBecomeCandidates() {
        assertNull(HudProjectionMath.project(0,0,-1,0,0,2));
        assertNull(HudProjectionMath.project(0,0,10,0,0,0));
        assertNull(HudProjectionMath.project(Double.NaN,0,10,0,0,2));
    }
    @Test void lookingAwayFromAnchorCanStillCycleToOneVisibleReplacement() {
        assertEquals("b", HudTargetCycle.next(List.of("b"), "a"));
        assertNull(HudTargetCycle.next(List.of("a"), "a"));
        assertNull(HudTargetCycle.next(List.of(), "a"));
    }
    @Test void cyclingWrapsWithoutReacquiringTheSameAnchor() {
        assertEquals("b", HudTargetCycle.next(List.of("a","b"), "a"));
        assertEquals("a", HudTargetCycle.next(List.of("a","b"), "b"));
    }
}
