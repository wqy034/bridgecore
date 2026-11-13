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
    private final EnduranceEstimator totalEndurance = new EnduranceEstimator();
    private final EnduranceEstimator bufferEndurance = new EnduranceEstimator();
    private ServerSubLevel structure;
    private volatile EnergyStatus status = new EnergyStatus(false, 0.0);

    public record EnergyStatus(boolean hasEnergy, double enduranceSeconds) {}
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
                long space = 0;
                for (MomentumDistributorBlockEntity pool : pools) space += pool.inputSpace();
                return (int) Math.min(Math.max(0, maxReceive), space);
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
            // R6-5 残留：与分配器同款——超容时 max 跟随存量，契约不被破坏。
            return (int) Math.min(Integer.MAX_VALUE, Math.max(totalCapacity(), totalStored()));
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
        if (sub != structure) {
            structure = sub;
            resetEndurance();
        }
        if (sub == null) {
            pools = EMPTY;
            return;
        }
        List<MomentumDistributorBlockEntity> list = CoreDirectory.powerPoolsOf(sub);
        // R6-6：池集合增减也复位 END 窗口——否则拆/装分配器会把存量阶跃
        // 误当消耗/补充（短时 0 秒或短时 ∞）。
        boolean changed = list.size() != pools.length;
        if (!changed) {
            for (int i = 0; i < pools.length; i++) {
                if (pools[i] != list.get(i)) { changed = true; break; }
            }
        }
        if (changed) resetEndurance();
        pools = list.toArray(EMPTY);
    }

    /** Server-thread status shared by the flight core and its displays. */
    public void updateStatus(double draw, long tick) {
        long stored = totalStored();
        double stock = stored;
        if (structure != null) {
            for (EnergyExtractorBlockEntity extractor : CoreDirectory.extractorsOf(structure)) {
                stock += extractor.buffer() + extractor.potentialFe();
            }
        }
        // R6-4：池空但提取器有燃料/heat 时也算有能量——否则 END 闪"无能量"
        // 且窗口被重预热。
        boolean hasEnergy = stored > 0 || stock > 0;
        if (hasEnergy) {
            // Fuel in an extractor can outlast the buffer when the input limit is binding.
            double seconds = Math.min(totalEndurance.update(stock, draw, tick),
                    bufferEndurance.update(stored, draw, tick));
            status = new EnergyStatus(true, seconds);
        } else {
            resetEndurance();
            status = new EnergyStatus(false, 0.0);
        }
    }

    private void resetEndurance() {
        totalEndurance.reset();
        bufferEndurance.reset();
    }

    public EnergyStatus status() { return status; }

    public void beginStep(long tick, long phase, double seconds) {
        for (MomentumDistributorBlockEntity pool : pools) pool.beginPhysicsStep(tick, phase, seconds);
    }

    /** FE that the attached distributors can still deliver in this physics substep. */
    public long availableForStep() {
        long total = 0;
        for (MomentumDistributorBlockEntity pool : pools) total += pool.availableOutput();
        return total;
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
        if (!Double.isFinite(fe) || fe <= 0.0) {
            return 0.0;
        }
        double carry = fractionCarry; // R6-3：brownout 分支要先扣旧账再算比例
        double total = fe + fractionCarry;
        long whole = (long) total;
        if (whole <= 0) {
            if (availableForStep() == 0) return 0.0;
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
        // R6-3：paid 里含上一帧已授信未收费的旧账 carry，正确比例先扣 carry。
        fractionCarry = 0.0;
        return fe * Math.max(0.0, Math.min(1.0, (paid - carry) / fe));
    }

    /** Server thread: FE spent since the last call (resets the counter). Feeds the
     *  low-passed draw readout with per-tick resolution. */
    public long drainSpent() {
        return spentSinceLastDrain.getAndSet(0);
    }

    /** Server thread: pour externally received FE into the pools, in snapshot order.
     *  @return FE actually accepted (the rest has nowhere to go). */
    public long fillExternal(long amount) {
        long remaining = amount;
        for (MomentumDistributorBlockEntity pool : pools) {
            if (remaining <= 0) {
                break;
            }
            remaining -= pool.fillCountedExternal((int) Math.min(Integer.MAX_VALUE, remaining));
        }
        return amount - remaining;
    }

    public long restoreLegacy(long amount) {
        long remaining = amount;
        for (MomentumDistributorBlockEntity pool : pools) {
            if (remaining <= 0) break;
            remaining -= pool.restoreCharge((int) Math.min(Integer.MAX_VALUE, remaining));
        }
        for (MomentumDistributorBlockEntity pool : pools) {
            if (remaining <= 0) break;
            remaining -= pool.restoreLegacyOverflow((int) Math.min(Integer.MAX_VALUE, remaining));
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
        long total = 0;
        for (MomentumDistributorBlockEntity pool : pools) total += pool.capacity();
        return total;
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
