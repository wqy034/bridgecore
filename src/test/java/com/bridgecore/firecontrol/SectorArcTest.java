package com.bridgecore.firecontrol;

import com.bridgecore.firecontrol.SectorArc.Endpoint;
import com.bridgecore.firecontrol.SectorArc.Limits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SectorArcTest {
    private static final Limits YAW = new Limits(-180, 180);
    private static final Limits PITCH = new Limits(-90, 90);
    private static final SectorArc DECK = new SectorArc(List.of(
            new Endpoint(-135, -18, 35), new Endpoint(-45, -4, 35),
            new Endpoint(45, -18, 35), new Endpoint(135, -6, 35)));

    @Test void endpointsSelectTheCorrectLimitsAcrossTheSeam() {
        assertEquals(new Limits(-4, 35), DECK.limitsAt(0, PITCH));
        assertEquals(new Limits(-18, 35), DECK.limitsAt(46, PITCH));
        assertEquals(new Limits(-4, 35), DECK.limitsAt(45, PITCH));
        assertEquals(new Limits(-6, 35), DECK.limitsAt(180, PITCH));
        assertEquals(DECK.limitsAt(180, PITCH), DECK.limitsAt(-180, PITCH));
        assertEquals(new Limits(-9, 30), DECK.limitsAt(46, new Limits(-9, 30)));
        assertFalse(DECK.contains(45, -8, YAW, PITCH));
        assertTrue(DECK.contains(46, -8, YAW, PITCH));
    }

    @Test void raisingTheGunPrecedesYawAndItStaysRaisedThroughTheBoundary() {
        var raise = DECK.step(60, -18, 10, -3, YAW, PITCH);
        assertTrue(raise.targetAllowed());
        assertTrue(raise.crossing());
        assertEquals(0, raise.yawDelta());
        assertEquals(-3, raise.pitch());

        var cross = DECK.step(60, -3, 10, -3, YAW, PITCH);
        assertEquals(-50, cross.yawDelta());
        assertTrue(cross.crossing());

        var leavingBoundary = DECK.step(45, -3, 60, -18, YAW, PITCH);
        assertEquals(15, leavingBoundary.yawDelta());
        assertEquals(-4, leavingBoundary.pitch());
        assertTrue(leavingBoundary.crossing());
        assertEquals(-18, DECK.step(46, -3, 60, -18, YAW, PITCH).pitch());
    }

    @Test void disconnectedBoundaryUsesAnOpenAlternativeOnlyWhenYawAllowsIt() {
        var arc = new SectorArc(List.of(new Endpoint(-180, -20, -10),
                new Endpoint(-60, -15, 15), new Endpoint(60, 10, 20)));
        var alternate = arc.step(170, 12, -170, -12, YAW, PITCH);
        assertTrue(alternate.targetAllowed());
        assertTrue(alternate.yawDelta() < 0);
        var blocked = arc.step(-175, -12, 175, 12, new Limits(-175, 175), new Limits(-90, 8));
        assertFalse(blocked.targetAllowed());
        assertEquals(0, blocked.yawDelta());
        assertFalse(arc.step(170, 12, -170, -12, new Limits(60, 180), PITCH).targetAllowed());
    }

    @Test void aMoveStopsBeforeTheSecondBoundaryUntilItsPitchIsChecked() {
        var first = DECK.step(65, -3, -60, -10, YAW, PITCH);
        assertEquals(-65, first.yawDelta()); // stop midway between +45 and -45
        assertTrue(first.targetAllowed());
        assertTrue(first.crossing());
        assertEquals(-3.9, first.pitch(), 1e-8);
    }

    @Test void splittingRetainsLimitsAndStopsAtFourEndpoints() {
        var initial = SectorArc.start(-30, 35);
        var three = initial.split(0);
        assertEquals(new Endpoint(0, -30, 35), three.endpoints().get(1));
        var four = three.split(2);
        assertEquals(new Endpoint(-180, -30, 35), four.endpoints().getFirst());
        assertSame(four, four.split(0));
        assertEquals(3, four.remove(0).endpoints().size());
        assertSame(initial, initial.remove(0));
        assertThrows(IllegalArgumentException.class, () -> four.replace(1, four.endpoints().getFirst()));
    }

    @Test void aSideTurretUsesOnlyItsEnabledHalfCircle() {
        var arc = new SectorArc(List.of(new Endpoint(-90, -15, 30), new Endpoint(90, -45, 45, false)));
        assertTrue(arc.contains(-90, -15, YAW, PITCH));
        assertTrue(arc.contains(90, 30, YAW, PITCH));
        assertFalse(arc.contains(90, -20, YAW, PITCH));
        assertFalse(arc.contains(91, 0, YAW, PITCH));
        assertFalse(arc.contains(-180, 0, YAW, PITCH));

        var sweep = arc.step(-90, 0, 90, 0, YAW, PITCH);
        assertTrue(sweep.targetAllowed());
        assertEquals(90, sweep.yawDelta());
        assertEquals(90, arc.step(0, 0, 90, 0, YAW, PITCH).yawDelta());

        var intoClosed = arc.step(90, 0, 91, 0, YAW, PITCH);
        assertFalse(intoClosed.targetAllowed());
        assertEquals(0, intoClosed.yawDelta());

        var disabledWhileInside = arc.step(100, 0, 0, 0, YAW, PITCH);
        assertFalse(disabledWhileInside.targetAllowed());
        assertEquals(0, disabledWhileInside.yawDelta());
    }

    @Test void routingUsesTheOpenDirectionAndCannotBridgeTwoDisabledGaps() {
        var arc = new SectorArc(List.of(new Endpoint(-165, -20, 30), new Endpoint(165, -20, 30, false)));
        var alternate = arc.step(150, 0, -150, 0, YAW, PITCH);
        assertTrue(alternate.targetAllowed());
        assertEquals(-300, alternate.yawDelta());

        var split = new SectorArc(List.of(new Endpoint(-135, -20, 30), new Endpoint(-45, -20, 30, false),
                new Endpoint(45, -20, 30), new Endpoint(135, -20, 30, false)));
        var blocked = split.step(-90, 0, 90, 0, YAW, PITCH);
        assertFalse(blocked.targetAllowed());
        assertEquals(0, blocked.yawDelta());

        var closed = new SectorArc(List.of(new Endpoint(-90, -20, 30, false), new Endpoint(90, -20, 30, false)));
        assertFalse(closed.contains(90, 0, YAW, PITCH));
        assertFalse(closed.step(0, 0, 0, 0, YAW, PITCH).targetAllowed());
        assertEquals(new Endpoint(-180, -20, 30, false), closed.split(1).endpoints().getFirst());
    }

    @Test void aSideTurretRecoversFromMeasuredDriftJustAcrossItsClosedEdge() {
        var left = new SectorArc(List.of(new Endpoint(-180, -40, 60), new Endpoint(0, -60, 60, false)));
        var recovery = left.step(0.253, 0, -30, -10, YAW, PITCH);
        assertTrue(recovery.targetAllowed());
        assertTrue(recovery.crossing());
        assertEquals(-0.553, recovery.yawDelta(), 1e-8);
        assertEquals(-0.3, left.restYaw(PITCH), 1e-8);
        assertEquals(0, DECK.restYaw(PITCH));
        assertFalse(left.contains(0.253, 0, YAW, PITCH));
        assertFalse(left.step(0.253, 0, 20, 0, YAW, PITCH).targetAllowed());
        assertEquals(0, left.step(2, 0, -30, 0, YAW, PITCH).yawDelta());
        var alignPitch = left.step(0.253, -50, -30, 0, YAW, PITCH);
        assertEquals(0, alignPitch.yawDelta());
        assertEquals(-40, alignPitch.pitch());
    }

    @Test void poseMarginCountsClosedEdgesAndLowClearanceTransitions() {
        var half = new SectorArc(List.of(new Endpoint(-90, -15, 30), new Endpoint(90, -15, 30, false)));
        assertEquals(1, half.marginAt(89, 0, YAW, PITCH));
        assertEquals(0, half.marginAt(90, 0, YAW, PITCH));
        assertEquals(15, half.marginAt(0, 0, YAW, PITCH));
        assertEquals(1, DECK.marginAt(46, -9, YAW, PITCH));
        assertEquals(18, DECK.marginAt(46, 0, YAW, PITCH));
    }

    @Test void editsTheSharedEndAcrossTheSeamWithoutChangingTheNeighbourSettings() {
        var original = new SectorArc(List.of(new Endpoint(-150, -12, 30, false),
                new Endpoint(-30, -20, 25), new Endpoint(75, -9, 24), new Endpoint(150, -8, 28)));
        var updated = original.editSector(3, new Endpoint(160, -10, 32), -165);
        assertEquals(new Endpoint(-165, -12, 30, false), updated.endpoints().getFirst());
        assertEquals(new Endpoint(160, -10, 32), updated.endpoints().getLast());
        assertThrows(IllegalArgumentException.class, () -> original.editSector(3, new Endpoint(160, -10, 32), -25));
        assertThrows(IllegalArgumentException.class, () -> original.editSector(3, new Endpoint(160, -10, 32), 160));
    }

    @Test void bothBoundariesCanMoveInOneEdit() {
        var original = SectorArc.start(-30, 35);
        var updated = original.editSector(0, new Endpoint(90, -5, 20), 120);
        assertEquals(List.of(new Endpoint(90, -5, 20), new Endpoint(120, -30, 35)), updated.endpoints());
    }

    @Test void aUniformProfileUsesTheMechanicalLimits() {
        assertEquals(new Limits(-21, 35), SectorArc.UNIFORM.limitsAt(-178, new Limits(-21, 35)));
    }
}
