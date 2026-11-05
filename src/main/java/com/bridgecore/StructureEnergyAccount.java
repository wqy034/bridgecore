package com.bridgecore;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Physics-thread energy account of one bridge core (v0.13): holds a snapshot of the
 * structure's momentum-distributor pools and lets the physics thread spend FE
 * straight out of them. The distributor IS the shipboard capacitor (storage +
 * bandwidth), the extractor is the generator — there is no core-local buffer and
 * no trickle settlement anymore.
 *
 * Threading contract: {@link #rebind}, {@link #drainSpent}, {@link #fillExternal}
 * and {@link #externalView()} are server-thread only (rebind touches CoreDirectory);
 * {@link #trySpend} is physics-thread only and never touches CoreDirectory, the
 * world, or any block-entity mutating method beyond the pools' atomic spend. The
 * pool snapshot is swapped wholesale (volatile reference), so a spend sees either
 * the old set or the new one, never a half-updated list.
 */
public final class StructureEnergyAccount {
    private static final MomentumDistributorBlockEntity[] EMPTY = new MomentumDistributorBlockEntity[0];

    private volatile MomentumDistributorBlockEntity[] pools = EMPTY;
    /** Whole-FE spent since the last drainSpent() (physics writes, server drains). */
    private final AtomicLong spentSinceLastDrain = new AtomicLong();
    /** Fractional-FE carry: physics-frame costs below 1 FE accumulate here and are
     *  charged as whole FE (physics thread only, no synchronization needed). */
    private double fractionCarry;
    /** Round-robin spend cursor (physics thread only). */
    private int cursor;
    /** Capability view: external FE pushed into the core is poured into the pools. */
    private final IEnergyStorage externalView = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            if (simulate) {
                long space = totalCapacity() - totalStored();
                return (int) Math.min(maxReceive, Math.max(0, Math.min(Integer.MAX_VALUE, space)));
            }
            return (int) Math.min(Integer.MAX_VALUE, fillExternal(maxReceive));
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return (int) Math.min(Integer.MAX_VALUE, totalStored());
        }

        @Override
        public int getMaxEnergyStored() {
            return (int) Math.min(Integer.MAX_VALUE, totalCapacity());
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    };

    /** Server thread: rebind the pool snapshot to the structure's current
     *  distributors (null = no structure / bench core -> account holds nothing,
     *  every spend browns out fully). */
    public void rebind(ServerSubLevel sub) {
        if (sub == null) {
            pools = EMPTY;
            return;
        }
        List<MomentumDistributorBlockEntity> list = CoreDirectory.powerPoolsOf(sub);
        pools = list.toArray(EMPTY);
    }

    /**
     * Physics thread: spend up to {@code fe} FE from the pools. Sub-FE costs are
     * granted on credit and accumulate in a local carry; the matured whole-FE bill
     * is charged to the pools round-robin (cursor start, then in order) so wear
     * spreads across the set. No pools at bill time, or pools unable to cover the
     * bill = brownout (return shortfall); an accumulating sub-FE frame is NOT a
     * brownout and must not be reported as one.
     *
     * @return FE affordable for this frame (less than requested = brownout).
     */
    public double trySpend(double fe) {
        if (fe <= 0.0) {
            return 0.0;
        }
        double total = fe + fractionCarry;
        long whole = (long) total;
        if (whole <= 0) {
            // Sub-FE frame: accumulate the debt and grant the write on credit.
            // Returning 0 here made priceWrite both drop the write and scream
            // "pools empty" — small ships (fractional-FE trim frames) ran the
            // attitude loop with most writes dropped (2026-09-17 live-log finding).
            fractionCarry = total;
            return fe;
        }
        fractionCarry = total - whole;
        MomentumDistributorBlockEntity[] ps = pools;
        int n = ps.length;
        if (n == 0) {
            fractionCarry = 0.0;
            return 0.0;
        }
        long paid = 0;
        for (int i = 0; i < n && paid < whole; i++) {
            paid += ps[Math.floorMod(cursor + i, n)].tryExtractAtomic(whole - paid);
        }
        cursor = Math.floorMod(cursor + 1, n);
        if (paid > 0) {
            spentSinceLastDrain.addAndGet(paid);
        }
        if (paid >= whole) {
            return fe;
        }
        // Genuine brownout: the pools cannot cover the matured whole-FE bill.
        // Do not accumulate unpayable debt; scale this frame by what was paid.
        fractionCarry = 0.0;
        return fe * ((double) paid / whole);
    }

    /** Server thread: FE spent since the last call (resets the counter). Feeds the
     *  low-passed draw readout with per-tick resolution. */
    public long drainSpent() {
        return spentSinceLastDrain.getAndSet(0);
    }

    /** Server thread: pour externally received FE (core capability receive, legacy
     *  save migration) into the pools, in snapshot order.
     *  @return FE actually accepted (the rest has nowhere to go). */
    public long fillExternal(long amount) {
        long remaining = amount;
        for (MomentumDistributorBlockEntity pool : pools) {
            while (remaining > 0) {
                int chunk = (int) Math.min(remaining, MomentumDistributorBlockEntity.CAPACITY);
                int got = pool.fillCountedExternal(chunk);
                remaining -= got;
                if (got < chunk) {
                    break; // this pool is full
                }
            }
            if (remaining <= 0) {
                break;
            }
        }
        return amount - remaining;
    }

    /** Structure-wide totals for status displays; safe on any thread (atomic reads). */
    public long totalStored() {
        long total = 0;
        for (MomentumDistributorBlockEntity pool : pools) {
            total += pool.energy();
        }
        return total;
    }

    public long totalCapacity() {
        return (long) pools.length * MomentumDistributorBlockEntity.CAPACITY;
    }

    public boolean hasPools() {
        return pools.length > 0;
    }

    /** Server thread: the core's IEnergyStorage capability — receive-only, pours
     *  straight into the pools. */
    public IEnergyStorage externalView() {
        return externalView;
    }
}
