package com.bridgecore.client;

import com.bridgecore.MomentumDistributorBlockEntity;
import com.bridgecore.menu.MomentumDistributorMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.Locale;

public class MomentumDistributorScreen extends AbstractContainerScreen<MomentumDistributorMenu> {
    private static final int FRAME = 0xFF465A6B;
    private static final int BACKGROUND = 0xFF222E3C;
    private static final int PANEL = 0xFF16222E;
    private static final int ACCENT = 0xFF55C8EE;
    private static final int TEXT = 0xFFE1E9F3;
    private static final int MUTED = 0xFFABBCCD;

    public MomentumDistributorScreen(MomentumDistributorMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        imageWidth = 236;
        imageHeight = 215;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        fill(graphics, 0, 0, imageWidth, imageHeight, FRAME);
        fill(graphics, 1, 1, imageWidth - 1, imageHeight - 1, BACKGROUND);
        fill(graphics, 8, 20, 111, 81, PANEL);
        fill(graphics, 115, 20, 228, 81, PANEL);
        fill(graphics, 8, 84, 228, 110, PANEL);

        fill(graphics, 14, 64, 105, 72, FRAME);
        int capacity = menu.capacityFe();
        if (capacity > 0 && menu.energyFe() > 0) {
            int width = Math.min(89, Math.max(1, (int) ((long) menu.energyFe() * 89 / capacity)));
            fill(graphics, 15, 65, 15 + width, 71, ACCENT);
        }

        for (var slot : menu.slots) {
            fill(graphics, slot.x - 1, slot.y - 1, slot.x + 17, slot.y + 17, FRAME);
            fill(graphics, slot.x, slot.y, slot.x + 16, slot.y + 16, 0xFF0E1925);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 7, TEXT, false);
        label(graphics, "gui.bridgecore.distributor.buffer", 14, 27, MUTED);
        label(graphics, "gui.bridgecore.distributor.upgrades", 121, 26, MUTED);
        label(graphics, "gui.bridgecore.distributor.capacity_cards", 121, 40, TEXT);
        label(graphics, "gui.bridgecore.distributor.throughput_cards", 121, 64, TEXT);
        cardCount(graphics, MomentumDistributorBlockEntity.CAPACITY_SOCKET, 40);
        cardCount(graphics, MomentumDistributorBlockEntity.THROUGHPUT_SOCKET, 64);

        graphics.drawString(font, integer(menu.energyFe()) + " FE", 14, 39, ACCENT, false);
        graphics.drawString(font, integer(menu.capacityFe()) + " FE", 14, 51, TEXT, false);
        label(graphics, "gui.bridgecore.distributor.input", 14, 87, MUTED);
        label(graphics, "gui.bridgecore.distributor.output", 124, 87, MUTED);
        rate(graphics, 14, menu.rateIn(), menu.inputLimit());
        rate(graphics, 124, menu.rateOut(), menu.outputLimit());

        label(graphics, "container.inventory", MomentumDistributorMenu.PLAYER_X, 113, MUTED);
        graphics.drawString(font,
                Component.translatable("gui.bridgecore.devices", menu.coreCount(), menu.extractorCount()),
                8, 202, MUTED, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        int x = mouseX - leftPos;
        int y = mouseY - topPos;
        if (y >= 84 && y < 110 && x >= 8 && x < 228) {
            int measured = x < 118 ? menu.rateIn() : menu.rateOut();
            int limit = x < 118 ? menu.inputLimit() : menu.outputLimit();
            graphics.renderTooltip(font, Component.translatable("gui.bridgecore.distributor.rate_tooltip",
                    integer(measured), integer(limit)), mouseX, mouseY);
        }
    }

    private void fill(GuiGraphics graphics, int x, int y, int right, int bottom, int color) {
        graphics.fill(leftPos + x, topPos + y, leftPos + right, topPos + bottom, color);
    }

    private void label(GuiGraphics graphics, String key, int x, int y, int color) {
        graphics.drawString(font, Component.translatable(key), x, y, color, false);
    }

    private void rate(GuiGraphics graphics, int x, int measured, int limit) {
        graphics.drawString(font, compact(measured) + " / " + compact(limit) + " FE/t", x, 99, TEXT, false);
    }

    private void cardCount(GuiGraphics graphics, int slot, int y) {
        String count = menu.getSlot(slot).getItem().getCount() + "/" + MomentumDistributorBlockEntity.CARDS_PER_SOCKET;
        graphics.drawString(font, count, 224 - font.width(count), y, MUTED, false);
    }

    private static String integer(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String compact(int value) {
        if (value >= 1_000_000) return String.format(Locale.ROOT, "%.2fM", value / 1_000_000.0);
        if (value >= 1_000) return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        return Integer.toString(value);
    }
}
