package com.bridgecore;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public final class DistributorUpgradeItem extends Item {
    private final boolean capacityCard;
    private final int capacity;
    private final int input;
    private final int output;

    DistributorUpgradeItem(int capacity, int input, int output) {
        super(new Properties());
        this.capacityCard = capacity > 0;
        this.capacity = capacity;
        this.input = input;
        this.output = output;
    }

    boolean capacityCard() { return capacityCard; }
    int capacityBonus() { return capacity; }
    int inputBonus() { return input; }
    int outputBonus() { return output; }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (capacityCard) {
            tooltip.add(Component.translatable("item.bridgecore.upgrade.capacity", capacity));
        } else {
            tooltip.add(Component.translatable("item.bridgecore.upgrade.input", input));
            tooltip.add(Component.translatable("item.bridgecore.upgrade.output", output));
        }
    }
}
