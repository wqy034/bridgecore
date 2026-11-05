package com.bridgecore.menu;

import com.bridgecore.BCRegistry;
import com.bridgecore.CoreDirectory;
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

/**
 * Momentum distributor menu (Mek induction-matrix style, trimmed): no machine
 * slots, player inventory only. ContainerData layout (broadcast every tick):
 *
 *   0 = stored energy, FE (raw int — pool is capped at 1,000,000 so it always
 *       fits; no compression needed)
 *   1 = capacity, FE (raw int)
 *   2 = input rate, FE/t (settled on the BE: per-tick accumulation, averaged
 *       over a 20-tick window — the menu only reads it)
 *   3 = output rate, FE/t (same)
 *   4 = online bridge cores on the same structure (CoreDirectory live count)
 *   5 = online energy extractors on the same structure (CoreDirectory live count)
 *
 * stillValid does NOT use a distance check (Sable shipyard vs logical
 * coordinates would always fail); removal is the only invalidation.
 */
public class MomentumDistributorMenu extends AbstractContainerMenu {
    private static final int DATA_ENERGY = 0;
    private static final int DATA_CAPACITY = 1;
    private static final int DATA_RATE_IN = 2;
    private static final int DATA_RATE_OUT = 3;
    private static final int DATA_CORES = 4;
    private static final int DATA_EXTRACTORS = 5;
    private static final int DATA_COUNT = 6;

    private final MomentumDistributorBlockEntity be;
    private final SyncData data = new SyncData();

    /** Server-side constructor. */
    public MomentumDistributorMenu(int windowId, Inventory playerInv, MomentumDistributorBlockEntity be) {
        super(BCRegistry.MOMENTUM_DISTRIBUTOR_MENU.get(), windowId);
        this.be = be;
        addPlayerSlots(playerInv);
        addDataSlots(data);
    }

    /** Client-side constructor (IMenuTypeExtension factory): extra data = BlockPos. */
    public MomentumDistributorMenu(int windowId, Inventory playerInv, FriendlyByteBuf buf) {
        this(windowId, playerInv,
                (MomentumDistributorBlockEntity) playerInv.player.level().getBlockEntity(buf.readBlockPos()));
    }

    private void addPlayerSlots(Inventory playerInv) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, 9 + row * 9 + col, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 142));
        }
    }

    @Override
    public void broadcastChanges() {
        data.set(DATA_ENERGY, be.energy());
        data.set(DATA_CAPACITY, MomentumDistributorBlockEntity.CAPACITY);
        data.set(DATA_RATE_IN, be.rateIn());
        data.set(DATA_RATE_OUT, be.rateOut());
        int cores = 0;
        int extractors = 0;
        if (be.getLevel() != null
                && Sable.HELPER.getContaining(be.getLevel(), be.getBlockPos()) instanceof ServerSubLevel sub) {
            cores = CoreDirectory.coresOf(sub).size();
            extractors = CoreDirectory.extractorsOf(sub).size();
        }
        data.set(DATA_CORES, cores);
        data.set(DATA_EXTRACTORS, extractors);
        super.broadcastChanges();
    }

    // ---- client-side accessors for the screen ----

    public int energyFe() {
        return data.get(DATA_ENERGY);
    }

    public int capacityFe() {
        return data.get(DATA_CAPACITY);
    }

    public int rateIn() {
        return data.get(DATA_RATE_IN);
    }

    public int rateOut() {
        return data.get(DATA_RATE_OUT);
    }

    public int coreCount() {
        return data.get(DATA_CORES);
    }

    public int extractorCount() {
        return data.get(DATA_EXTRACTORS);
    }

    @Override
    public boolean stillValid(Player player) {
        // No distance check — shipyard vs logical coordinates (see class javadoc).
        return !be.isRemoved();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // No machine slots: shift-click just shuffles between main inventory and hotbar.
        ItemStack copy = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();
            copy = stack.copy();
            if (index < 27) {
                if (!moveItemStackTo(stack, 27, 36, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, 27, false)) {
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
