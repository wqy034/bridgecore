package com.bridgecore.menu;

import com.bridgecore.BCRegistry;
import com.bridgecore.CoreDirectory;
import com.bridgecore.EnergyExtractorBlockEntity;
import com.bridgecore.MomentumDistributorBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * Energy extractor menu (Mek heat-generator style): one fuel slot + player
 * inventory. Data sync is a ContainerData int array broadcast every tick while
 * the GUI is open:
 *
 *   0 = internal buffer, FE (raw int — capped at 50,000, no compression needed)
 *   1 = buffer capacity, FE (raw int)
 *   2 = heat pool stored, FE-heat (raw int — capped at 5,000,000, fits int)
 *   3 = heat pool capacity, FE-heat (raw int)
 *   4 = same-structure distributor pool total, kFE (FE/1000 — the pool sum is
 *       N * 1M FE and would run out of int headroom past ~2000 pools; kFE keeps
 *       the transmitted value small. Screen multiplies back by 1000.)
 *   5 = pool capacity, kFE (bar scale reference; 0 = no pool, screen falls back
 *       to showing the internal buffer + a "no distributor" hint)
 *   6 = TOTAL tax (base * acceleration multiplier), hundredths of a percent
 *       (12.5% -> 1250)
 *   7 = rate slider position, 0..100 percent
 *   8 = real-time output, FE/t (raw int — actual payout on the latest tick;
 *       peaks at 20,000 * (1 - tax), fits int easily)
 *
 * stillValid does NOT use a distance check: on a Sable ship the BE sits at
 * far-out shipyard coordinates while the player stands at the logical position,
 * so a world-coordinate distance check would always fail. Removal is the only
 * invalidation.
 */
public class EnergyExtractorMenu extends AbstractContainerMenu {
    private static final int DATA_BUFFER = 0;
    private static final int DATA_BUFFER_CAP = 1;
    private static final int DATA_HEAT = 2;
    private static final int DATA_HEAT_CAP = 3;
    private static final int DATA_POOL_KFE = 4;
    private static final int DATA_POOL_CAP_KFE = 5;
    private static final int DATA_TAX_BP = 6;
    private static final int DATA_RATE = 7;
    private static final int DATA_OUTPUT = 8;
    private static final int DATA_COUNT = 9;

    /** Fuel slot position (window-relative; kept in sync with the screen's
     *  layout constants and the GUI texture's slot frame). */
    public static final int FUEL_SLOT_X = 17;
    public static final int FUEL_SLOT_Y = 17;

    private final EnergyExtractorBlockEntity be;
    private final SyncData data = new SyncData();

    /** Server-side constructor. */
    public EnergyExtractorMenu(int windowId, Inventory playerInv, EnergyExtractorBlockEntity be) {
        super(BCRegistry.ENERGY_EXTRACTOR_MENU.get(), windowId);
        this.be = be;
        addSlot(new SlotItemHandler(be.itemHandler(), 0, FUEL_SLOT_X, FUEL_SLOT_Y));
        addPlayerSlots(playerInv);
        addDataSlots(data);
    }

    /** Client-side constructor (IMenuTypeExtension factory): the extra data is
     *  the BE's BlockPos, written by Player.openMenu(provider, pos). */
    public EnergyExtractorMenu(int windowId, Inventory playerInv, FriendlyByteBuf buf) {
        this(windowId, playerInv,
                (EnergyExtractorBlockEntity) playerInv.player.level().getBlockEntity(buf.readBlockPos()));
    }

    private void addPlayerSlots(Inventory playerInv) {
        // Window is 240x210: the 162px-wide slot-frame block is horizontally
        // centered (frames x = (240-162)/2 = 39, so slots sit at x=40);
        // slots start at y=116, hotbar y=182.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, 9 + row * 9 + col, 40 + col * 18, 116 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 40 + col * 18, 182));
        }
    }

    @Override
    public void broadcastChanges() {
        // Server thread: snapshot the BE and the live CoreDirectory pool total.
        data.set(DATA_BUFFER, be.buffer());
        data.set(DATA_BUFFER_CAP, EnergyExtractorBlockEntity.BUFFER_CAPACITY);
        data.set(DATA_HEAT, be.heat());
        data.set(DATA_HEAT_CAP, EnergyExtractorBlockEntity.HEAT_CAPACITY);
        long poolTotal = 0;
        long poolCap = 0;
        if (be.getLevel() != null
                && Sable.HELPER.getContaining(be.getLevel(), be.getBlockPos()) instanceof ServerSubLevel sub) {
            for (MomentumDistributorBlockEntity pool : CoreDirectory.powerPoolsOf(sub)) {
                poolTotal += pool.energy();
                poolCap += MomentumDistributorBlockEntity.CAPACITY;
            }
        }
        data.set(DATA_POOL_KFE, (int) Math.min(Integer.MAX_VALUE, poolTotal / 1000));
        data.set(DATA_POOL_CAP_KFE, (int) Math.min(Integer.MAX_VALUE, poolCap / 1000));
        data.set(DATA_TAX_BP, be.currentTotalTaxBasisPoints());
        data.set(DATA_RATE, be.ratePercent());
        data.set(DATA_OUTPUT, be.lastOutputFe());
        super.broadcastChanges();
    }

    // ---- client-side accessors for the screen ----

    public int bufferFe() {
        return data.get(DATA_BUFFER);
    }

    public int bufferCapFe() {
        return data.get(DATA_BUFFER_CAP);
    }

    public int heatStored() {
        return data.get(DATA_HEAT);
    }

    public int heatCapacity() {
        return data.get(DATA_HEAT_CAP);
    }

    public int poolTotalFe() {
        return data.get(DATA_POOL_KFE) * 1000;
    }

    public int poolCapFe() {
        return data.get(DATA_POOL_CAP_KFE) * 1000;
    }

    public int totalTaxBasisPoints() {
        return data.get(DATA_TAX_BP);
    }

    public int ratePercent() {
        return data.get(DATA_RATE);
    }

    public int outputFePerTick() {
        return data.get(DATA_OUTPUT);
    }

    /** BE position — the screen needs it for the rate-slider payload. */
    public net.minecraft.core.BlockPos extractorPos() {
        return be.getBlockPos();
    }

    @Override
    public boolean stillValid(Player player) {
        // No distance check — shipyard vs logical coordinates (see class javadoc).
        return !be.isRemoved();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack copy = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();
            copy = stack.copy();
            if (index == 0) {
                if (!moveItemStackTo(stack, 1, 37, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (EnergyExtractorBlockEntity.isFuel(stack)) {
                if (!moveItemStackTo(stack, 0, 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index < 28) {
                if (!moveItemStackTo(stack, 28, 37, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 1, 28, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return copy;
    }

    private static final class SyncData implements ContainerData {
        private final int[] values = new int[DATA_COUNT];

        @Override
        public int get(int index) {
            return values[index];
        }

        @Override
        public void set(int index, int value) {
            values[index] = value;
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    }
}
