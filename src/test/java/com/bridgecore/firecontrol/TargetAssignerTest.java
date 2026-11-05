package com.bridgecore.firecontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.bridgecore.firecontrol.TargetAssigner.Candidate;

/** Distributed-lock selection contract (火力指挥层④): nearest in-arc wins,
 *  out-of-arc never picked, hysteresis keeps the current target until a rival
 *  is clearly closer. */
class TargetAssignerTest {

    private static Candidate c(String id, double dist, boolean inArc) {
        return new Candidate(id, dist, inArc);
    }

    @Test
    void nearestInArcWins() {
        String pick = TargetAssigner.select(null,
                List.of(c("far", 100, true), c("near", 30, true), c("mid", 60, true)));
        assertEquals("near", pick);
    }

    @Test
    void outOfArcNeverPicked() {
        String pick = TargetAssigner.select(null,
                List.of(c("nearButBlind", 10, false), c("far", 90, true)));
        assertEquals("far", pick);
    }

    @Test
    void nothingReachableHolds() {
        assertNull(TargetAssigner.select(null, List.of(c("a", 10, false))));
        assertNull(TargetAssigner.select(null, List.of()));
    }

    @Test
    void hysteresisKeepsCurrent() {
        // rival closer but within 15% + 2 blocks -> keep current
        String pick = TargetAssigner.select("cur",
                List.of(c("cur", 100, true), c("rival", 90, true)));
        assertEquals("cur", pick);
    }

    @Test
    void clearlyCloserRivalTakesOver() {
        String pick = TargetAssigner.select("cur",
                List.of(c("cur", 100, true), c("rival", 40, true)));
        assertEquals("rival", pick);
    }

    @Test
    void currentOutOfArcDropsImmediately() {
        // hysteresis does NOT protect a target the turret can no longer bear on
        String pick = TargetAssigner.select("cur",
                List.of(c("cur", 20, false), c("rival", 90, true)));
        assertEquals("rival", pick);
    }

    @Test
    void currentGoneFallsBackToBest() {
        String pick = TargetAssigner.select("ghost",
                List.of(c("a", 50, true), c("b", 80, true)));
        assertEquals("a", pick);
    }
}
