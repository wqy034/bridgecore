package com.bridgecore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnduranceEstimatorTest {
    @Test
    void countsDownEvenAtSmallShipDraw() {
        EnduranceEstimator estimator = new EnduranceEstimator();
        for (long tick = 0; tick < 60; tick++) {
            assertEquals(Double.POSITIVE_INFINITY, estimator.update(3_000 - 25 * tick, 25, tick));
        }
        assertEquals(3.0, estimator.update(1_500, 25, 60));
        estimator.reset();
        assertEquals(Double.POSITIVE_INFINITY, estimator.update(1_000, 25, 80));
    }

    @Test
    void steadyGridSupplyDoesNotCountDownFromOneTickOfJitter() {
        EnduranceEstimator estimator = new EnduranceEstimator();
        for (long tick = 0; tick < 200; tick++) {
            double stock = tick % 40 == 0 ? 1_000_000 : 950_000;
            assertEquals(Double.POSITIVE_INFINITY, estimator.update(stock, 50_000, tick));
        }
    }
}
