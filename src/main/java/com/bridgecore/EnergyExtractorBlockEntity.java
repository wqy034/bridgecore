package com.bridgecore;

import com.bridgecore.menu.EnergyExtractorMenu;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Energy extractor, two-stage model (Mek heat-generator style):
 *
 * Stage 1, fuel -> heat: the fuel slot converts into the internal HEAT POOL at
 * a fixed fast rate — ONE item per tick, any fuel. The pool gains
 * totalFe * (1 - baseTax) per item; conversion pauses when the pool cannot fit
 * the next item's full yield (no items eaten, no heat voided).
 *
 * Stage 2, heat -> FE: each tick draws draw = min(rate(p), heatStored) heat and
 * pays out draw * (1 - baseTax * (mult(p) - 1)) FE, pushed to same-structure
 * distributors (round-robin), falling back to the internal 50k buffer; if
 * neither can absorb the tick's output, extraction pauses and the heat STAYS in
 * the pool. p = ratePercent (0-100) from the GUI slider.
 *
 * Tax simplification: the heat pool does not track fuel provenance. The
 * acceleration tax uses the base tax of the item CURRENTLY in the fuel slot;
 * if the slot is empty, the base tax of the last converted item (lastTax);
 * before any conversion ever happened, the 10% fallback. This makes the tax a
 * live knob readable from the GUI instead of a historical property of the pool.
 *
 * Server thread only; nothing here runs on the physics thread.
 */
public class EnergyExtractorBlockEntity extends BlockEntity implements MenuProvider {
    /** Heat pool capacity (FE-heat). */
    public static final int HEAT_CAPACITY = 5_000_000;
    /** Internal overflow buffer, used when no same-structure distributor exists. */
    public static final int BUFFER_CAPACITY = 50_000;
    /** Vanilla-burn-time -> FE anchor for ALL fuels: 1 burn tick = 2 FE.
     *  Balance basis: Mek's heat generator works out to ~0.22 FE/tick for coal
     *  (measured); as a "high-conversion" furnace we take 2 FE/tick, ~9x Mek. */
    public static final int FE_PER_BURN_TICK = 2;

    // ---- stage-2 rate/tax curves (user-tunable; p = ratePercent 0..100) ----
    /** p in [0,50]: rate = RATE_LOW_SLOPE * p. */
    private static final int RATE_LOW_SLOPE = 100;        // 5% -> 500 FE/t, 50% -> 5,000
    /** p in (50,100]: rate = RATE_MID_CAP + RATE_HIGH_SLOPE * (p - 50). */
    private static final int RATE_MID_CAP = 5_000;
    private static final int RATE_HIGH_SLOPE = 300;       // 100% -> 20,000 FE/t
    /** p in [0,50]: tax multiplier = 1 + MULT_LOW_SLOPE * p (1.0 -> 1.5). */
    private static final double MULT_LOW_SLOPE = 0.01;
    /** p in (50,100]: tax multiplier = MULT_MID_CAP + MULT_HIGH_SLOPE * (p - 50) (-> 3.0). */
    private static final double MULT_MID_CAP = 1.5;
    private static final double MULT_HIGH_SLOPE = 0.03;
    /** Stage-1 conversion: one item per tick. */
    private static final int ITEMS_PER_TICK = 1;
    /** Output-blocking hysteresis: once blocked, extraction resumes only when
     *  free space reaches max(this fraction of total sink capacity, this many
     *  ticks' worth of output). Stops the per-tick block/unblock flapping (and
     *  LIT flicker) when the distributor pool is full. */
    private static final double HYSTERESIS_CAPACITY_FRACTION = 0.05;
    private static final int HYSTERESIS_TICKS_OF_OUTPUT = 20;
    /** Ticks of inactivity before the LIT blockstate turns off. */
    private static final int LIT_OFF_DELAY_TICKS = 10;
    /** Default slider position. */
    public static final int RATE_DEFAULT = 50;
    /** Base tax assumed before any fuel has ever been converted (slot empty AND
     *  no lastTax): the vanilla-fallback rate. */
    private static final double FALLBACK_TAX = 0.10;

    /** FE/t the slider asks for at position p. */
    public static int rateFor(int p) {
        return p <= 50 ? RATE_LOW_SLOPE * p : RATE_MID_CAP + RATE_HIGH_SLOPE * (p - 50);
    }

    /** Acceleration tax multiplier at slider position p. */
    public static double taxMultFor(int p) {
        return p <= 50 ? 1.0 + MULT_LOW_SLOPE * p : MULT_MID_CAP + MULT_HIGH_SLOPE * (p - 50);
    }

    /** Fuel definition: gross heat value (FE) and its base conversion tax. */
    private record FuelSpec(int totalFe, double baseTax) {
    }

    /** Hardcoded fuel table for the test build; production moves this to a
     *  datapack-driven registry. Lazily built so the deferred item lookups happen
     *  after registration. All totals = vanilla burn time * FE_PER_BURN_TICK. */
    private static Map<Item, FuelSpec> fuelTable;

    private static Map<Item, FuelSpec> fuelTable() {
        if (fuelTable == null) {
            Map<Item, FuelSpec> m = new HashMap<>();
            // End essence tier, 1% base tax. Custom fuel: deliberate premium over
            // the 2 FE/tick anchor (this is the endgame fuel).
            m.put(BCRegistry.END_ESSENCE.get(), new FuelSpec(100_000, 0.01));
            // Lava tier, 2% base tax (lava bucket = vanilla 20,000 ticks * 2;
            // magma block is not a vanilla fuel — explicit entry, 2,000 * 2).
            m.put(Items.LAVA_BUCKET, new FuelSpec(40_000, 0.02));
            m.put(Items.MAGMA_BLOCK, new FuelSpec(4_000, 0.02));
            // Coal tier, 5% base tax (1,600 / 16,000 vanilla ticks * 2).
            m.put(Items.COAL, new FuelSpec(3_200, 0.05));
            m.put(Items.CHARCOAL, new FuelSpec(3_200, 0.05));
            m.put(Items.COAL_BLOCK, new FuelSpec(32_000, 0.05));
            fuelTable = m;
        }
        return fuelTable;
    }

    /** Table lookup first; any other burnable vanilla fuel (wood tier etc.) falls
     *  back to burnTime * FE_PER_BURN_TICK gross at the 10% base tax. */
    private static FuelSpec specFor(ItemStack stack) {
        FuelSpec spec = fuelTable().get(stack.getItem());
        if (spec != null) {
            return spec;
        }
        int burn = stack.getBurnTime(RecipeType.SMELTING);
        if (burn <= 0) {
            return null;
        }
        return new FuelSpec(burn * FE_PER_BURN_TICK, FALLBACK_TAX);
    }

    public static boolean isFuel(ItemStack stack) {
        return !stack.isEmpty() && specFor(stack) != null;
    }

    private final ItemStackHandler fuelSlot = new ItemStackHandler(1) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return isFuel(stack);
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    /** Heat pool, FE-heat (stage-1 output, stage-2 input). */
    private int heat;
    /** Base tax of the last converted fuel item (stage-2 tax source when the
     *  slot is empty — see class javadoc for the simplification). */
    private double lastTax = FALLBACK_TAX;
    /** Stage-2 throttle, 0..100 percent (GUI slider). */
    private int ratePercent = RATE_DEFAULT;
    /** FE actually paid out on the latest tick (GUI "live output" readout). */
    private int lastOutputFe;
    /** Fractional-FE carry so sub-1-FE ticks are not lost. */
    private double accumulator;
    /** Overflow buffer (no same-structure distributor / distributors full). */
    private int buffer;
    /** Output-blocked latch (hysteresis): set when the sinks cannot absorb one
     *  tick's payout, cleared when free space reaches the resume threshold. */
    private boolean outputBlocked;
    /** LIT blockstate delay: counts down from LIT_OFF_DELAY_TICKS after the last
     *  active tick so the lit state does not flicker with block/unblock cycles. */
    private int litCooldown;
    /** Round-robin cursor for balanced pool filling. */
    private int pushCursor;
    /** Sub-level this extractor is registered under in CoreDirectory (server thread). */
    private ServerSubLevel registeredSubLevel;

    public EnergyExtractorBlockEntity(BlockPos pos, BlockState state) {
        super(BCRegistry.ENERGY_EXTRACTOR_BE.get(), pos, state);
    }

    public IItemHandler itemHandler() {
        return fuelSlot;
    }

    // ---- GUI accessors (menu reads these on the server thread) ----

    public int buffer() {
        return buffer;
    }

    public int heat() {
        return heat;
    }

    public int ratePercent() {
        return ratePercent;
    }

    public int lastOutputFe() {
        return lastOutputFe;
    }

    /** Slider update from the GUI payload; range-validated 0..100. */
    public void setRatePercent(int percent) {
        if (percent < 0 || percent > 100 || percent == ratePercent) {
            return;
        }
        ratePercent = percent;
        setChanged();
    }

    /** Stage-2 base tax source: slot fuel's base tax, else lastTax (see class
     *  javadoc for the provenance simplification). */
    private double currentBaseTax() {
        FuelSpec spec = specFor(fuelSlot.getStackInSlot(0));
        return spec != null ? spec.baseTax() : lastTax;
    }

    /** TOTAL tax right now (base * acceleration multiplier), in hundredths of a
     *  percent (12.5% -> 1250). Always computable — falls back to lastTax/10%. */
    public int currentTotalTaxBasisPoints() {
        return (int) Math.round(Math.min(1.0, currentBaseTax() * taxMultFor(ratePercent)) * 10000);
    }

    /** Estimate of the FE this extractor can still realize — the HUD endurance
     *  numerator. Heat pool plus fuel slot contents, both discounted by the
     *  stage-2 tax factor at the CURRENT slider position (same formula as
     *  extractHeat). The heat pool capacity cap is deliberately not modeled:
     *  this is an estimate for the pilot, not a promise. */
    public double potentialFe() {
        double stage2Factor = 1.0 - Math.min(1.0, currentBaseTax() * (taxMultFor(ratePercent) - 1.0));
        double fe = heat * stage2Factor;
        ItemStack stack = fuelSlot.getStackInSlot(0);
        FuelSpec spec = specFor(stack);
        if (spec != null) {
            fe += stack.getCount() * spec.totalFe() * (1.0 - spec.baseTax()) * stage2Factor;
        }
        return fe;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.bridgecore.energy_extractor");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new EnergyExtractorMenu(containerId, playerInventory, this);
    }

    /** Player fuel insertion (right-click with a burnable item): one unit into an
     *  EMPTY slot. Returns null on success, otherwise the refusal reason. */
    public String insertFuel(ItemStack stack) {
        if (!fuelSlot.getStackInSlot(0).isEmpty()) {
            return "[bridgecore] extractor fuel slot occupied";
        }
        ItemStack one = stack.copyWithCount(1);
        if (fuelSlot.insertItem(0, one, false).isEmpty()) {
            return null;
        }
        return "[bridgecore] extractor refused " + stack.getHoverName().getString();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, EnergyExtractorBlockEntity be) {
        var containing = Sable.HELPER.getContaining(level, pos);
        ServerSubLevel sub = containing instanceof ServerSubLevel s ? s : null;
        if (sub != be.registeredSubLevel) {
            CoreDirectory.deregisterExtractor(be.registeredSubLevel, be);
            CoreDirectory.registerExtractor(sub, be);
            be.registeredSubLevel = sub;
        }
        List<MomentumDistributorBlockEntity> pools =
                sub != null ? CoreDirectory.powerPoolsOf(sub) : List.of();

        boolean active = be.convertFuel();
        active |= be.extractHeat(pools);
        be.flush(pools);

        // LIT follows activity with a 10-tick off delay (no flicker when the
        // output latch bounces around a full pool).
        if (active) {
            be.litCooldown = LIT_OFF_DELAY_TICKS;
        } else if (be.litCooldown > 0) {
            be.litCooldown--;
        }
        boolean lit = be.litCooldown > 0;
        if (state.getValue(EnergyExtractorBlock.LIT) != lit) {
            level.setBlock(pos, state.setValue(EnergyExtractorBlock.LIT, lit), 3);
        }
    }

    /** Stage 1: convert ONE fuel item per tick into heat. Pauses when the pool
     *  cannot fit the item's full net yield. @return true if an item was consumed. */
    private boolean convertFuel() {
        ItemStack stack = fuelSlot.getStackInSlot(0);
        FuelSpec spec = specFor(stack);
        if (spec == null) {
            return false;
        }
        int gain = (int) Math.round(spec.totalFe() * (1.0 - spec.baseTax()));
        if (heat + gain > HEAT_CAPACITY) {
            return false; // pool full — wait instead of voiding heat or eating the item
        }
        heat += gain;
        lastTax = spec.baseTax();
        ItemStack remainder = stack.getCraftingRemainingItem();
        fuelSlot.extractItem(0, ITEMS_PER_TICK, false);
        if (fuelSlot.getStackInSlot(0).isEmpty() && !remainder.isEmpty()) {
            fuelSlot.setStackInSlot(0, remainder);
        }
        setChanged();
        return true;
    }

    /** Stage 2: draw heat at the slider rate, pay out taxed FE. Pauses when the
     *  payout has nowhere to go — the heat stays in the pool. Hysteresis: once
     *  blocked, resumes only when free space reaches the resume threshold (see
     *  HYSTERESIS_* constants). @return true if heat was drawn. */
    private boolean extractHeat(List<MomentumDistributorBlockEntity> pools) {
        lastOutputFe = 0;
        int draw = Math.min(rateFor(ratePercent), heat);
        if (draw <= 0) {
            return false;
        }
        double fe = draw * (1.0 - Math.min(1.0, currentBaseTax() * (taxMultFor(ratePercent) - 1.0)));
        int need = Math.max(1, (int) Math.ceil(fe));
        int space = availableSpace(pools);
        if (outputBlocked) {
            // Resume threshold: 5% of total sink capacity, or 20 ticks of output,
            // whichever is larger (the smoother of the two for this rate).
            long totalCap = pools.isEmpty() ? BUFFER_CAPACITY
                    : (long) pools.size() * MomentumDistributorBlockEntity.CAPACITY;
            long resumeAt = Math.max((long) (totalCap * HYSTERESIS_CAPACITY_FRACTION),
                    (long) need * HYSTERESIS_TICKS_OF_OUTPUT);
            if (space < resumeAt) {
                return false; // still blocked — heat stays in the pool
            }
            outputBlocked = false;
        }
        if (space < need) {
            outputBlocked = true;
            return false; // output blocked — heat stays in the pool
        }
        heat -= draw;
        accumulator += fe;
        lastOutputFe = (int) Math.round(fe);
        setChanged();
        return true;
    }

    private int availableSpace(List<MomentumDistributorBlockEntity> pools) {
        int space = BUFFER_CAPACITY - buffer;
        for (MomentumDistributorBlockEntity pool : pools) {
            space += pool.space();
        }
        return space;
    }

    /** Move ready FE: distributors first (round-robin), overflow into the buffer. */
    private void flush(List<MomentumDistributorBlockEntity> pools) {
        int ready = (int) accumulator + buffer;
        if (ready <= 0) {
            return;
        }
        int moved = 0;
        if (!pools.isEmpty()) {
            int n = pools.size();
            int start = Math.floorMod(pushCursor++, n);
            int remaining = ready;
            for (int i = 0; i < n && remaining > 0; i++) {
                remaining -= pools.get(Math.floorMod(start + i, n)).fillInternal(remaining);
            }
            moved = ready - remaining;
        }
        // Deduct moved FE from the accumulator first, then the buffer.
        int fromAcc = Math.min(moved, (int) accumulator);
        accumulator -= fromAcc;
        buffer -= moved - fromAcc;
        // What the pools did not take overflows into the buffer.
        int extra = Math.min((int) accumulator, BUFFER_CAPACITY - buffer);
        if (extra > 0) {
            buffer += extra;
            accumulator -= extra;
        }
        setChanged();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            var containing = Sable.HELPER.getContaining(level, getBlockPos());
            if (containing instanceof ServerSubLevel sub && sub != registeredSubLevel) {
                CoreDirectory.deregisterExtractor(registeredSubLevel, this);
                CoreDirectory.registerExtractor(sub, this);
                registeredSubLevel = sub;
            }
        }
    }

    @Override
    public void setRemoved() {
        CoreDirectory.deregisterExtractor(registeredSubLevel, this);
        registeredSubLevel = null;
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("fuel", fuelSlot.serializeNBT(registries));
        tag.putInt("heat", heat);
        tag.putInt("rate", ratePercent);
        tag.putDouble("lastTax", lastTax);
        tag.putDouble("acc", accumulator);
        tag.putInt("fe", buffer);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        fuelSlot.deserializeNBT(registries, tag.getCompound("fuel"));
        heat = Math.max(0, Math.min(HEAT_CAPACITY, tag.getInt("heat")));
        ratePercent = Math.max(0, Math.min(100, tag.getInt("rate")));
        lastTax = tag.contains("lastTax") ? tag.getDouble("lastTax") : FALLBACK_TAX;
        accumulator = tag.getDouble("acc");
        buffer = Math.max(0, Math.min(BUFFER_CAPACITY, tag.getInt("fe")));
    }
}
