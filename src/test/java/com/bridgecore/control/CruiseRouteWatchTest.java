package com.bridgecore.control;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CruiseRouteWatchTest {
    @Test
    void checksIntermediateChunksAndStopsAtTheFirstMissingOne() {
        var checked = new HashSet<String>();
        assertFalse(CruiseRouteWatch.loadedAhead(15, 7, 80, 7, 8, (x, z) -> {
            checked.add(x + "," + z);
            return x != 1;
        }));
        assertEquals(java.util.Set.of("0,0", "1,0"), checked);
    }

    @Test
    void lookaheadRemainsBoundedWithHighSpeedAndDiagonalRoutes() {
        for (int x : new int[]{-65, -63, 0, 15}) {
            for (int z : new int[]{-65, -63, 0, 15}) {
                for (int sign : new int[]{-1, 1}) {
                    assertBoundedLookahead(x, z, sign, 8.0, CruiseRouteWatch.CHUNK_QUERY_LIMIT);
                    assertBoundedLookahead(x, z, sign, NavigationController.MAX_SPEED,
                            CruiseRouteWatch.FAST_CHUNK_QUERY_LIMIT);
                }
            }
        }
    }

    private static void assertBoundedLookahead(int x, int z, int sign, double speed, int budget) {
        var count = new AtomicInteger();
        assertTrue(CruiseRouteWatch.loadedAhead(x + 0.9, z + 0.1, x + sign * 10_000, z + 10_000, speed,
                (cx, cz) -> count.incrementAndGet() <= budget));
        assertTrue(count.get() <= budget);
    }

    @Test
    void fastCruiseChecksFartherAndFitsDefaultG4WithEnoughBraking() {
        var loaded = (CruiseRouteWatch.LoadedChunk) (x, z) -> x != 4;
        assertTrue(CruiseRouteWatch.loadedAhead(0, 0, 200, 0, 8, loaded));
        assertFalse(CruiseRouteWatch.loadedAhead(0, 0, 200, 0, NavigationController.MAX_SPEED, loaded));
        assertEquals(48.0, NavigationController.MAX_SPEED);
        assertTrue(CruiseRouteWatch.fastSpeedLimit(24) >= NavigationController.MAX_SPEED);
        assertTrue(CruiseRouteWatch.fastSpeedLimit(4) < NavigationController.MAX_SPEED);
        assertEquals(0.0, CruiseRouteWatch.fastSpeedLimit(0));
    }

    @Test
    void aSlowTurnCanProgressWithoutHorizontalTravel() {
        var watch = new CruiseRouteWatch();
        watch.start(50, 0);
        assertFalse(watch.stalled(50, Math.toRadians(120), 20));
        assertFalse(watch.stalled(50, Math.toRadians(100), 400));
        assertFalse(watch.stalled(50, Math.toRadians(80), 780));
        assertTrue(watch.stalled(50, Math.toRadians(79), 1180));
    }

    @Test
    void crewGraceRecoversAfterABriefDisembarkAndExpiresIfNobodyReturns() {
        var watch = new CruiseRouteWatch();
        watch.start(512, 100);
        assertFalse(watch.lostSupervision(false, 140));
        assertFalse(watch.lostSupervision(true, 155));
        assertFalse(watch.lostSupervision(false, 214));
        assertTrue(watch.lostSupervision(false, 215));
    }

    @Test
    void progressTimeoutUsesAccumulatedDistanceAndLongRoutesGetTimeToFinish() {
        var watch = new CruiseRouteWatch();
        watch.start(512, 0);
        assertEquals(2400, CruiseRouteWatch.cruiseLifetime(20, 4, 2400));
        assertTrue(CruiseRouteWatch.cruiseLifetime(512, 4, 2400) > 512 / 4 * 20);
        assertFalse(watch.stalled(511.9, 399));
        assertFalse(watch.stalled(511.4, 400));
        assertFalse(watch.stalled(511.3, 799));
        assertTrue(watch.stalled(511.3, 800));
    }
}
