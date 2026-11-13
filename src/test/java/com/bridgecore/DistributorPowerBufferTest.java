package com.bridgecore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DistributorPowerBufferTest {
    @Test
    void inputBudgetIsSharedAndSimulationDoesNotConsumeIt() {
        DistributorPowerBuffer pool = new DistributorPowerBuffer();
        pool.configure(250_000, 2_500, 12_000);

        assertEquals(2_500, pool.receive(10_000, 5, true));
        assertEquals(1_000, pool.receive(1_000, 5, false));
        assertEquals(1_500, pool.inputSpace(5));
        assertEquals(1_500, pool.receive(10_000, 5, false));
        assertEquals(0, pool.receive(10_000, 5, false));
        assertEquals(2_500, pool.receive(10_000, 6, false));
        assertEquals(5_000, pool.stored());
    }

    @Test
    void allCoresShareEachSubstepAndTheTickLimit() {
        DistributorPowerBuffer pool = new DistributorPowerBuffer();
        pool.configure(250_000, 2_500, 12_000);
        pool.load(100_000);
        pool.beginStep(5, 1, 0.025);

        assertEquals(5_000, pool.spend(5_000));
        pool.beginStep(5, 1, 0.025);
        assertEquals(1_000, pool.available());
        assertEquals(1_000, pool.spend(10_000));
        pool.beginStep(5, 2, 0.025);
        assertEquals(6_000, pool.spend(10_000));
        pool.beginStep(5, 3, 0.025);
        assertEquals(0, pool.spend(10_000));
        pool.beginStep(6, 1, 0.025);
        assertEquals(6_000, pool.available());
        assertEquals(88_000, pool.stored());
    }

    @Test
    void upgradesChangeLimitsWithoutDeletingAnOverfullBalance() {
        DistributorPowerBuffer pool = new DistributorPowerBuffer();
        pool.configure(3_750_000, 72_500, 252_000);
        pool.load(3_000_000);
        assertEquals(72_500, pool.receive(Integer.MAX_VALUE, 4, false));
        pool.beginStep(4, 1, 0.025);
        assertEquals(126_000, pool.spend(Integer.MAX_VALUE));

        pool.configure(250_000, 2_500, 12_000);
        assertEquals(2_946_500, pool.stored());
        assertEquals(0, pool.receive(Integer.MAX_VALUE, 5, false));
        pool.beginStep(5, 1, 0.025);
        assertEquals(6_000, pool.spend(Integer.MAX_VALUE));
        assertEquals(2_940_500, pool.stored());
    }

    @Test
    void emptyOrInactiveBufferCannotFundAnImpulse() {
        DistributorPowerBuffer pool = new DistributorPowerBuffer();
        pool.configure(250_000, 2_500, 12_000);
        assertEquals(0, pool.spend(100));
        pool.load(8);
        assertEquals(0, pool.spend(100));
        pool.beginStep(5, 1, 0.025);
        assertEquals(8, pool.spend(100));
        assertEquals(0, pool.spend(100));
    }

    @Test
    void legacyCoreBalanceCanBeTransferredAboveTheNewCapacity() {
        DistributorPowerBuffer pool = new DistributorPowerBuffer();
        pool.configure(250_000, 2_500, 12_000);
        assertEquals(250_000, pool.restore(1_000_000));
        assertEquals(750_000, pool.restoreLegacyOverflow(750_000));
        assertEquals(1_000_000, pool.stored());
        assertEquals(0, pool.inputSpace(1));
    }
}
