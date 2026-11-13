package com.bridgecore;

import com.bridgecore.menu.MomentumDistributorMenu;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
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
 * The buffer synchronizes energy and rate limits between fills and physics spends.
 * The physics thread never calls setChanged() — the server ticker marks
 * the block dirty when the stored value moved.
 *
 * Directory membership is maintained lazily from the server ticker (the sub-level
 * object can change identity across reassembly, same as the core's re-registration)
 * and dropped in setRemoved.
 */
public class MomentumDistributorBlockEntity extends BlockEntity implements MenuProvider {

    /** CC 通道外设缓存（CCIntegration 管理；Object 类型防 CC 缺席加载）。 */
    private Object ccChannelPeripheral;

    public Object ccChannelPeripheral() {
        return ccChannelPeripheral;
    }

    public void setCcChannelPeripheral(Object peripheral) {
        ccChannelPeripheral = peripheral;
    }

    public static final int CAPACITY_SOCKET = 0;
    public static final int THROUGHPUT_SOCKET = 1;
    public static final int SOCKET_COUNT = 2;
    public static final int CARDS_PER_SOCKET = 5;
    private static final int LEGACY_SOCKET_COUNT = 4;
    private final DistributorPowerBuffer buffer = new DistributorPowerBuffer();
    private final List<ItemStack> recoveryCards = new ArrayList<>();
    private final ItemStackHandler cards = new ItemStackHandler(SOCKET_COUNT) {
        @Override
        public int getSlotLimit(int slot) {
            return CARDS_PER_SOCKET;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return stack.getItem() instanceof DistributorUpgradeItem card
                    && card.capacityCard() == (slot == CAPACITY_SOCKET);
        }

        @Override
        protected void onContentsChanged(int slot) {
            upgradeCardsChanged();
        }
    };
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

    public ItemStackHandler cards() { return cards; }
    public int capacity() { return buffer.capacity(); }
    public int maxReceive() { return buffer.inputRate(); }
    public int maxOutput() { return buffer.outputRate(); }

    public void upgradeCardsChanged() {
        if (level != null && !level.isClientSide) {
            refreshLimits();
            setChanged();
        }
    }

    private void refreshLimits() {
        int capacity = BCConfig.POOL_CAPACITY.get();
        int input = BCConfig.POOL_MAX_RECEIVE.get();
        int output = BCConfig.POOL_MAX_OUTPUT.get();
        for (int slot = 0; slot < SOCKET_COUNT; slot++) {
            ItemStack stack = cards.getStackInSlot(slot);
            if (cards.isItemValid(slot, stack) && stack.getItem() instanceof DistributorUpgradeItem card) {
                int count = Math.min(CARDS_PER_SOCKET, stack.getCount());
                capacity += card.capacityBonus() * count;
                input += card.inputBonus() * count;
                output += card.outputBonus() * count;
            }
        }
        buffer.configure(capacity, input, output);
    }

    int restoreCharge(int amount) {
        int accepted = buffer.restore(amount);
        inAccum += accepted;
        return accepted;
    }

    int restoreLegacyOverflow(int amount) {
        return buffer.restoreLegacyOverflow(amount);
    }

    /** Internal fill shares the input budget, while finite fuel stays out of the grid meter. */
    int fillInternal(int amount) {
        return receive(amount, false);
    }

    int fillCountedExternal(int amount) {
        int accepted = receive(amount, false);
        inflowAccum += accepted;
        return accepted;
    }

    private int receive(int amount, boolean simulate) {
        if (level == null || isRemoved()) return 0;
        int accepted = buffer.receive(amount, level.getGameTime(), simulate);
        if (!simulate) inAccum += accepted;
        return accepted;
    }

    void beginPhysicsStep(long tick, long phase, double seconds) {
        buffer.beginStep(tick, phase, seconds);
    }

    int availableOutput() {
        return isRemoved() ? 0 : buffer.available();
    }

    /** Physics-thread spend by same-structure cores (via StructureEnergyAccount);
     *  the capability view stays receive-only. */
    long tryExtractAtomic(long maxAmount) {
        int paid = isRemoved() ? 0 : buffer.spend(maxAmount);
        if (paid > 0) outAccum.addAndGet(paid);
        return paid;
    }

    int space() {
        return Math.max(0, capacity() - energy());
    }

    int inputSpace() {
        return level == null || isRemoved() ? 0 : buffer.inputSpace(level.getGameTime());
    }

    public int energy() {
        return buffer.stored();
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
        if (!player.level().isClientSide && !recoveryCards.isEmpty()) {
            for (ItemStack card : recoveryCards) playerInventory.placeItemBackInInventory(card);
            recoveryCards.clear();
            setChanged();
            player.sendSystemMessage(Component.translatable("gui.bridgecore.distributor.recovered_cards"));
        }
        return new MomentumDistributorMenu(containerId, playerInventory, this);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  MomentumDistributorBlockEntity be) {
        be.syncDirectory(level, pos);
        be.refreshLimits();
        be.inflowFePerTick += (be.inflowAccum - be.inflowFePerTick) * INFLOW_SMOOTH_ALPHA;
        be.inflowAccum = 0;
        if (++be.windowTick >= RATE_WINDOW) {
            be.rateIn = (int) Math.round(be.inAccum / (double) RATE_WINDOW);
            be.rateOut = (int) Math.round(be.outAccum.getAndSet(0) / (double) RATE_WINDOW);
            be.inAccum = 0;
            be.windowTick = 0;
        }
        long stored = be.energy();
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
            refreshLimits();
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
        tag.put("upgradeCards", cards.serializeNBT(registries));
        if (!recoveryCards.isEmpty()) {
            ListTag saved = new ListTag();
            for (ItemStack card : recoveryCards) saved.add(card.save(registries));
            tag.put("recoveryCards", saved);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // Older saves and removed capacity cards can leave an overfull buffer.
        // Keep its balance and block new charge until it falls below capacity.
        buffer.load(tag.getInt("fe"));
        recoveryCards.clear();
        for (Tag saved : tag.getList("recoveryCards", Tag.TAG_COMPOUND)) {
            ItemStack.parse(registries, saved).ifPresent(this::recoverCard);
        }
        CompoundTag cardData = tag.getCompound("upgradeCards").copy();
        if (cardData.getInt("Size") == LEGACY_SOCKET_COUNT) {
            migrateLegacyCards(registries, cardData);
            return;
        }
        cardData.putInt("Size", SOCKET_COUNT);
        cards.deserializeNBT(registries, cardData);
    }

    private void migrateLegacyCards(HolderLookup.Provider registries, CompoundTag cardData) {
        // R7-4：卡槽迁移留日志。
        BridgeCoreMod.LOGGER.warn("[bridgecore] distributor legacy cards migrated at {}",
                getBlockPos().toShortString());
        ItemStackHandler legacy = new ItemStackHandler(LEGACY_SOCKET_COUNT);
        legacy.deserializeNBT(registries, cardData);
        for (int slot = 0; slot < SOCKET_COUNT; slot++) {
            ItemStack first = legacy.getStackInSlot(slot * 2);
            ItemStack second = legacy.getStackInSlot(slot * 2 + 1);
            if (upgradeStrength(second) > upgradeStrength(first)) {
                ItemStack swap = first;
                first = second;
                second = swap;
            }
            cards.setStackInSlot(slot, ItemStack.EMPTY);
            recoverCard(cards.insertItem(slot, first, false));
            recoverCard(cards.insertItem(slot, second, false));
        }
    }

    private static int upgradeStrength(ItemStack stack) {
        return stack.getItem() instanceof DistributorUpgradeItem card ? card.capacityBonus() + card.outputBonus() : 0;
    }

    private void recoverCard(ItemStack card) {
        if (!card.isEmpty()) recoveryCards.add(card);
    }

    void addCardDrops(List<ItemStack> drops) {
        for (int slot = 0; slot < SOCKET_COUNT; slot++) {
            ItemStack card = cards.getStackInSlot(slot);
            if (!card.isEmpty()) drops.add(card.copy());
        }
        for (ItemStack card : recoveryCards) drops.add(card.copy());
    }

    /** External capability view: charge-only, rate-capped (server thread). */
    private final class StorageView implements IEnergyStorage {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            if (simulate) {
                return receive(maxReceive, true);
            }
            return fillCountedExternal(maxReceive);
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
            // R6-5：拆卡/legacy 超容时对外 max 跟随存量，守住 stored<=max 的
            // IEnergyStorage 契约；存量本身不吞（load 只夹非负），接收侧按
            // capacity-stored 算空间，超容时自然停止接收。
            return Math.max(capacity(), energy());
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
