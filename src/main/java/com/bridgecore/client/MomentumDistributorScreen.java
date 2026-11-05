package com.bridgecore.client;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.menu.MomentumDistributorMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * Momentum distributor screen (Mek induction-matrix style, trimmed): big
 * vertical energy bar on the left, five text readouts on the right (energy,
 * capacity, input rate, output rate, online devices), player inventory bottom.
 * ALL layout coordinates live in this constant block; the GUI texture
 * (textures/gui/momentum_distributor.png, 256x256 placeholder) is drawn to the
 * same numbers.
 */
public class MomentumDistributorScreen extends AbstractContainerScreen<MomentumDistributorMenu> {
    // ---- layout constants ----
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "textures/gui/momentum_distributor.png");
    private static final int WINDOW_W = 176;
    private static final int WINDOW_H = 166;
    /** Big vertical energy bar (frame is in the texture; fill drawn with fill()).
     *  Groove is (17,17,26x52) — short enough that the inventory label at
     *  (8,72) stays clear of it. */
    private static final int BAR_X = 18;
    private static final int BAR_Y = 18;
    private static final int BAR_W = 24;
    private static final int BAR_H = 50;
    private static final int BAR_COLOR = 0xFF35B5E8;
    /** Readout block: five lines, right of the bar (bar ends at x=43). Longest
     *  line "容量: 1,000,000 FE" is ~85px wide, so x=56 leaves 30px margin. */
    private static final int READOUT_X = 56;
    private static final int READOUT_Y = 18;
    private static final int READOUT_PITCH = 11;
    /** Inventory label: 2px above the player-inventory slot area (slots y=84). */
    private static final int INV_LABEL_X = 8;
    private static final int INV_LABEL_Y = 72;
    private static final int TEXT_COLOR = 0xFFC7D0DD;

    public MomentumDistributorScreen(MomentumDistributorMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        imageWidth = WINDOW_W;
        imageHeight = WINDOW_H;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, WINDOW_W, WINDOW_H, 256, 256);
        int cap = menu.capacityFe();
        int fe = menu.energyFe();
        if (cap > 0 && fe > 0) {
            int h = Math.min(BAR_H, (int) ((long) fe * BAR_H / cap));
            guiGraphics.fill(leftPos + BAR_X, topPos + BAR_Y + BAR_H - h,
                    leftPos + BAR_X + BAR_W, topPos + BAR_Y + BAR_H, BAR_COLOR);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(font, title, 8, 6, TEXT_COLOR, false);
        int y = READOUT_Y;
        guiGraphics.drawString(font,
                Component.translatable("gui.bridgecore.energy", String.format("%,d", menu.energyFe())),
                READOUT_X, y, TEXT_COLOR, false);
        y += READOUT_PITCH;
        guiGraphics.drawString(font,
                Component.translatable("gui.bridgecore.capacity", String.format("%,d", menu.capacityFe())),
                READOUT_X, y, TEXT_COLOR, false);
        y += READOUT_PITCH;
        guiGraphics.drawString(font,
                Component.translatable("gui.bridgecore.rate_in", String.format("%,d", menu.rateIn())),
                READOUT_X, y, TEXT_COLOR, false);
        y += READOUT_PITCH;
        guiGraphics.drawString(font,
                Component.translatable("gui.bridgecore.rate_out", String.format("%,d", menu.rateOut())),
                READOUT_X, y, TEXT_COLOR, false);
        y += READOUT_PITCH;
        guiGraphics.drawString(font,
                Component.translatable("gui.bridgecore.devices", menu.coreCount(), menu.extractorCount()),
                READOUT_X, y, TEXT_COLOR, false);
        guiGraphics.drawString(font, Component.translatable("container.inventory"),
                INV_LABEL_X, INV_LABEL_Y, TEXT_COLOR, false);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);
    }
}
