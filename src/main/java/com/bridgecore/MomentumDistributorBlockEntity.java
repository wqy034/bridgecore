package com.bridgecore;

import com.bridgecore.menu.MomentumDistributorMenu;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Momentum distributor: the structure-scale FE pool. v0.13 makes it the ONLY energy
 * store in the chain — the shipboard capacitor (storage + bandwidth): cores spend
 * straight out of it (no core-local buffer, no trickle), extractors fill it.
 *
 * Capability view is receive-only so external generators and cables can charge it;
 * same-structure consumers never go through the capability — cores drain it via
 * {@link #tryExtractAtomic} and extractors fill it via {@link #fillInternal}, both
 * over CoreDirectory membership, not world adjacency (ship blocks sit at far-out
 * shipyard coordinates).
 *
 * Threading: the store is an AtomicLong. {@link #tryExtractAtomic} runs on the
 * physics thread (CAS loop); fills, NBT and the capability view run on the server
 * thread. The physics thread never calls setChanged() — the server ticker marks
 * the block dirty when the stored value moved.
 *
 * Directory membership is maintained lazily from the server ticker (the sub-level
 * object can change identity across reassembly, same as the core's re-registration)
 * and dropped in setRemoved.
 */
public class MomentumDistributorBlockEntity extends BlockEntity implements MenuProvider {
    public static final int CAPACITY = 4_000_000;
    /** Capability receive cap, FE per transfer. */
    public static final int MAX_RECEIVE = 1_000_000;

    private final AtomicLong energy = new AtomicLong();
    /** GUI rate readouts: fills/extracts accumulate into inAccum/outAccum as they
     *  happen; every RATE_WINDOW ticks the serverTicker settles the window average
     *  into rateIn/rateOut (FE/t) and resets the accumulators. The menu only reads
     *  the settled values. inAccum is server-thread only; outAccum is also written
     *  by physics-thread spends, hence atomic. */
    private long inAccum;
    private final AtomicLong outAccum = new AtomicLong();
    private int windowTick;
    private int rateIn;
    private int rateOut;
    /** Rate averaging window, ticks. */
    private static final int RATE_WINDOW = 20;
    /** External-supply inflow (capability receives only — extractor fills do NOT
     *  count; the HUD endurance estimate treats grid supply as sustainable, fuel
     *  burn as finite). inflowAccum is this tick's total; inflowFePerTick is its
     *  low-pass (tau ≈ 0.5 s, same shape as the core's draw readout). Server thread only. */
    private double inflowAccum;
    private double inflowFePerTick;
    private static final double INFLOW_SMOOTH_ALPHA = 1.0 - Math.exp(-0.05 / 0.5);
    /** Last stored value seen by the server ticker; a change marks the block dirty
     *  (physics-thread spends cannot call setChanged()). */
    private long lastSeenEnergy = -1;
    /** Sub-level this pool is registered under in CoreDirectory (server thread). */
    private ServerSubLevel registeredSubLevel;
    private final IEnergyStorage storage = new StorageView();

    public MomentumDistributorBlockEntity(BlockPos pos, BlockState state) {
        super(BCRegistry.MOMENTUM_DISTRIBUTOR_BE.get(), pos, state);
    }

    public IEnergyStorage energyStorage() {
        return storage;
    }

    /** CAS fill helper (server thread). @return FE actually accepted. */
    private long fill(long amount) {
        if (amount <= 0) {
            return 0;
        }
        for (;;) {
            long cur = energy.get();
            long space = CAPACITY - cur;
            if (space <= 0) {
                return 0;
            }
            long add = Math.min(amount, space);
            if (energy.compareAndSet(cur, cur + add)) {
                inAccum += add;
                return add;
            }
        }
    }

    /** Internal fill from same-structure extractors (server thread); bypasses the
     *  capability rate cap. NOT counted in the inflow meter — fuel burn is finite,
     *  the endurance estimate treats it as consumption, not supply. */
    int fillInternal(int amount) {
        return (int) fill(amount);
    }

    /** External-supply fill (capability receives, incl. those routed through the
     *  core's StructureEnergyAccount): like {@link #fillInternal} but counted in
     *  the inflow meter. */
    int fillCountedExternal(int amount) {
        int accepted = (int) fill(amount);
        inflowAccum += accepted;
        return accepted;
    }

    /** Physics-thread spend by same-structure cores (via StructureEnergyAccount);
     *  the capability view stays receive-only. */
    long tryExtractAtomic(long maxAmount) {
        if (maxAmount <= 0) {
            return 0;
        }
        for (;;) {
            long cur = energy.get();
            if (cur <= 0) {
                return 0;
            }
            long take = Math.min(maxAmount, cur);
            if (energy.compareAndSet(cur, cur - take)) {
                outAccum.addAndGet(take);
                return take;
            }
        }
    }

    int space() {
        return (int) Math.max(0, CAPACITY - energy.get());
    }

    public int energy() {
        return (int) Math.min(Integer.MAX_VALUE, energy.get());
    }

    /** Settled GUI readouts: average FE/t over the last 20-tick window. */
    public int rateIn() {
        return rateIn;
    }

    public int rateOut() {
        return rateOut;
    }

    /** Low-passed external-supply inflow, FE/t (capability receives only). The HUD
     *  no longer consumes this (the windowed stock-decline END replaced the
     *  stream-difference estimate, 2026-09-09) — retained for the planned
     *  distributor GUI flow indicator. */
    public double inflowFePerTick() {
        return inflowFePerTick;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.bridgecore.momentum_distributor");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new MomentumDistributorMenu(containerId, playerInventory, this);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  MomentumDistributorBlockEntity be) {
        be.syncDirectory(level, pos);
        be.inflowFePerTick += (be.inflowAccum - be.inflowFePerTick) * INFLOW_SMOOTH_ALPHA;
        be.inflowAccum = 0;
        if (++be.windowTick >= RATE_WINDOW) {
            be.rateIn = (int) Math.round(be.inAccum / (double) RATE_WINDOW);
            be.rateOut = (int) Math.round(be.outAccum.getAndSet(0) / (double) RATE_WINDOW);
            be.inAccum = 0;
            be.windowTick = 0;
        }
        long stored = be.energy.get();
        if (stored != be.lastSeenEnergy) {
            be.lastSeenEnergy = stored;
            be.setChanged();
        }
    }

    private void syncDirectory(Level level, BlockPos pos) {
        var containing = Sable.HELPER.getContaining(level, pos);
        ServerSubLevel sub = containing instanceof ServerSubLevel s ? s : null;
        if (sub != registeredSubLevel) {
            CoreDirectory.deregisterPool(registeredSubLevel, this);
            CoreDirectory.registerPool(sub, this);
            registeredSubLevel = sub;
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            syncDirectory(level, getBlockPos());
        }
    }

    @Override
    public void setRemoved() {
        CoreDirectory.deregisterPool(registeredSubLevel, this);
        registeredSubLevel = null;
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("fe", energy());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy.set(Math.max(0, Math.min(CAPACITY, tag.getInt("fe"))));
    }

    /** External capability view: charge-only, rate-capped (server thread). */
    private final class StorageView implements IEnergyStorage {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int capped = Math.min(maxReceive, MAX_RECEIVE);
            if (simulate) {
                return Math.min(capped, space());
            }
            int accepted = fillCountedExternal(capped);
            return accepted;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return energy();
        }

        @Override
        public int getMaxEnergyStored() {
            return CAPACITY;
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    }
}
