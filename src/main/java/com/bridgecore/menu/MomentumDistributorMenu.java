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
import net.neoforged.neoforge.items.SlotItemHandler;

public class MomentumDistributorMenu extends AbstractContainerMenu {
    public static final int PLAYER_X = 38;
    public static final int PLAYER_Y = 123;
    public static final int HOTBAR_Y = 181;
    private static final int SOCKET_COUNT = MomentumDistributorBlockEntity.SOCKET_COUNT;
    private static final int MAIN_END = SOCKET_COUNT + 27;
    private static final int PLAYER_END = SOCKET_COUNT + 36;
    private static final int DATA_ENERGY = 0;
    private static final int DATA_CAPACITY = 1;
    private static final int DATA_RATE_IN = 2;
    private static final int DATA_RATE_OUT = 3;
    private static final int DATA_CORES = 4;
    private static final int DATA_EXTRACTORS = 5;
    private static final int DATA_MAX_IN = 6;
    private static final int DATA_MAX_OUT = 7;
    private static final int DATA_COUNT = 8;

    private final MomentumDistributorBlockEntity be;
    private final SyncData data = new SyncData();

    /** Server-side constructor. */
    public MomentumDistributorMenu(int windowId, Inventory playerInv, MomentumDistributorBlockEntity be) {
        super(BCRegistry.MOMENTUM_DISTRIBUTOR_MENU.get(), windowId);
        this.be = be;
        for (int index = 0; index < SOCKET_COUNT; index++) {
            addSlot(new SlotItemHandler(be.cards(), index, 185, 36 + index * 24) {
                @Override
                public void setChanged() {
                    // Vanilla can grow the existing stack in place when shift-clicking.
                    be.upgradeCardsChanged();
                }
            });
        }
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
                addSlot(new Slot(playerInv, 9 + row * 9 + col, PLAYER_X + col * 18, PLAYER_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, PLAYER_X + col * 18, HOTBAR_Y));
        }
    }

    @Override
    public void broadcastChanges() {
        if (be == null) return; // 审查 P1-7
        data.setInt(DATA_ENERGY, be.energy());
        data.setInt(DATA_CAPACITY, be.capacity());
        data.setInt(DATA_RATE_IN, be.rateIn());
        data.setInt(DATA_RATE_OUT, be.rateOut());
        data.setInt(DATA_MAX_IN, be.maxReceive());
        data.setInt(DATA_MAX_OUT, be.maxOutput());
        int cores = 0;
        int extractors = 0;
        if (be.getLevel() != null
                && Sable.HELPER.getContaining(be.getLevel(), be.getBlockPos()) instanceof ServerSubLevel sub) {
            cores = CoreDirectory.coresOf(sub).size();
            extractors = CoreDirectory.extractorsOf(sub).size();
        }
        data.setInt(DATA_CORES, cores);
        data.setInt(DATA_EXTRACTORS, extractors);
        super.broadcastChanges();
    }

    // ---- client-side accessors for the screen ----

    public int energyFe() {
        return data.getInt(DATA_ENERGY);
    }

    public int capacityFe() {
        return data.getInt(DATA_CAPACITY);
    }

    public int rateIn() {
        return data.getInt(DATA_RATE_IN);
    }

    public int rateOut() {
        return data.getInt(DATA_RATE_OUT);
    }

    public int inputLimit() {
        return data.getInt(DATA_MAX_IN);
    }

    public int outputLimit() {
        return data.getInt(DATA_MAX_OUT);
    }

    public int coreCount() {
        return data.getInt(DATA_CORES);
    }

    public int extractorCount() {
        return data.getInt(DATA_EXTRACTORS);
    }

    @Override
    public boolean stillValid(Player player) {
        // Shipyard and logical coordinates are in different spaces.
        // 2026-11-09 审查 P1-7：BE 缺失判空。
        return be != null && !be.isRemoved();
    }

    /** 菜单归属位置（PayloadGuards 用）。 */
    public net.minecraft.core.BlockPos poolPos() {
        return be != null ? be.getBlockPos() : net.minecraft.core.BlockPos.ZERO;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index < SOCKET_COUNT) {
            if (!moveItemStackTo(stack, SOCKET_COUNT, PLAYER_END, true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 0, SOCKET_COUNT, false)) {
            boolean moved = index < MAIN_END
                    ? moveItemStackTo(stack, MAIN_END, PLAYER_END, false)
                    : moveItemStackTo(stack, SOCKET_COUNT, MAIN_END, false);
            if (!moved) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        slot.onTake(player, stack);
        return copy;
    }

    private static final class SyncData implements ContainerData {
        // Vanilla container-data packets carry signed 16-bit fields.
        private final int[] values = new int[DATA_COUNT * 2];

        void setInt(int index, int value) {
            values[index * 2] = (short) value;
            values[index * 2 + 1] = (short) (value >>> 16);
        }

        int getInt(int index) {
            return (values[index * 2] & 0xFFFF) | (values[index * 2 + 1] << 16);
        }

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
            return values.length;
        }
    }
}
