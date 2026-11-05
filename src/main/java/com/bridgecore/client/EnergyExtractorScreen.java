package com.bridgecore.client;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.EnergyExtractorBlockEntity;
import com.bridgecore.menu.EnergyExtractorMenu;
import com.bridgecore.network.ExtractorRatePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Energy extractor screen (Mek heat-generator style, two-stage model). ALL
 * layout coordinates live in this constant block — tweak here when the real art
 * lands; the GUI texture (textures/gui/energy_extractor.png, 256x256
 * placeholder) is drawn to the same numbers.
 *
 * Layout (window 240x210):
 *   title (8,6) | fuel slot (17,17) + flame (52,18) | readouts x=8, y=38..68
 *   (heat / loss / output / stored, full width) | dual bars right: heat
 *   (195,18,10x60) + FE (213,18,10x60), "H"/"FE" tags below | slider label
 *   (8,80) + rate slider (8,92,180x8) with 5/50/100% ticks | inventory label
 *   (40,106) | player slots x=40 (frames x=39, centered), y=116/182.
 * Overlap audit (worst case): longest readout line "热量池: 5,000,000 /
 * 5,000,000 FE" ≈ 166px -> ends x=174, 20px clear of the heat groove at x=194.
 * Readout line 4 bottom = 76 < 80 (slider label) < 92 (slider, bottom 100)
 * < 106 (inv label, bottom 114) < 116 (slots). Hotbar bottom = 200, window
 * bottom margin 10px. Slider label ≈135px ends x=143, clear of bar tags
 * (y=82, x=197+ — different row anyway).
 */
public class EnergyExtractorScreen extends AbstractContainerScreen<EnergyExtractorMenu> {
    // ---- layout constants ----
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "textures/gui/energy_extractor.png");
    private static final int WINDOW_W = 240;
    private static final int WINDOW_H = 210;
    /** Flame sprite: shown while stage-1 fuel conversion is running. Lives below
     *  the window area in the texture (the window is 240 wide, so the old
     *  right-of-window spot at u=176 no longer fits). */
    private static final int FLAME_X = 52;
    private static final int FLAME_Y = 18;
    private static final int FLAME_SIZE = 14;
    private static final int FLAME_TEX_U = 0;
    private static final int FLAME_TEX_V = 212;
    /** Dual vertical bars (groove frames are in the texture; fills via fill()).
     *  Left = heat pool (orange-red), right = FE (cyan). */
    private static final int HEAT_BAR_X = 195;
    private static final int HEAT_BAR_Y = 18;
    private static final int HEAT_BAR_W = 10;
    private static final int HEAT_BAR_H = 60;
    private static final int HEAT_BAR_COLOR = 0xFFE05030;
    private static final int FE_BAR_X = 213;
    private static final int FE_BAR_Y = 18;
    private static final int FE_BAR_W = 10;
    private static final int FE_BAR_H = 60;
    private static final int FE_BAR_COLOR = 0xFF35B5E8;
    /** Bar tags under the grooves ("H" / "FE"). */
    private static final int HEAT_TAG_X = 197;
    private static final int FE_TAG_X = 214;
    private static final int BAR_TAG_Y = 82;
    /** Readout block: four lines below the slot/flame row, full width. */
    private static final int READOUT_X = 8;
    private static final int READOUT_Y = 38;
    private static final int READOUT_PITCH = 10;
    /** "No distributor" hint: top row, right of the title (bars start at y=17,
     *  so y=6 is clear of them). */
    private static final int HINT_X = 100;
    private static final int HINT_Y = 6;
    /** Rate slider + its label (label shows the pending value while dragging). */
    private static final int SLIDER_LABEL_X = 8;
    private static final int SLIDER_LABEL_Y = 80;
    private static final int SLIDER_X = 8;
    private static final int SLIDER_Y = 92;
    private static final int SLIDER_W = 180;
    private static final int SLIDER_H = 8;
    private static final int SLIDER_TRACK = 0xFF1A1E26;
    private static final int SLIDER_FILL = 0xFF35B5E8;
    private static final int SLIDER_HANDLE = 0xFFC7D0DD;
    private static final int[] SLIDER_TICKS = {5, 50, 100};
    /** Inventory label: 2px above the horizontally centered player-inventory
     *  slot area (frames at x=39, slots at x=40, y=116). */
    private static final int INV_LABEL_X = 40;
    private static final int INV_LABEL_Y = 106;
    private static final int TEXT_COLOR = 0xFFC7D0DD;
    private static final int WARN_COLOR = 0xFFFF8855;

    /** Optimistic slider value after a local drag/click, until the ContainerData
     *  broadcast catches up (-1 = no pending change). */
    private int pendingRate = -1;
    private boolean sliderDragging;

    public EnergyExtractorScreen(EnergyExtractorMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        imageWidth = WINDOW_W;
        imageHeight = WINDOW_H;
    }

    // ---- rate slider (custom widget; no external lib) ----

    private int displayedRate() {
        if (pendingRate >= 0) {
            if (menu.ratePercent() == pendingRate) {
                pendingRate = -1; // server confirmed
            } else {
                return pendingRate;
            }
        }
        return menu.ratePercent();
    }

    private void setRateFromMouse(double mouseX) {
        int pct = Math.max(0, Math.min(100,
                (int) Math.round((mouseX - leftPos - SLIDER_X) * 100.0 / SLIDER_W)));
        if (pct != displayedRate()) {
            pendingRate = pct;
            PacketDistributor.sendToServer(new ExtractorRatePayload(menu.extractorPos(), pct));
        }
    }

    private boolean overSlider(double mouseX, double mouseY) {
        return mouseX >= leftPos + SLIDER_X - 2 && mouseX <= leftPos + SLIDER_X + SLIDER_W + 2
                && mouseY >= topPos + SLIDER_Y - 2 && mouseY <= topPos + SLIDER_Y + SLIDER_H + 2;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && overSlider(mouseX, mouseY)) {
            sliderDragging = true;
            setRateFromMouse(mouseX);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (sliderDragging) {
            setRateFromMouse(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        sliderDragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // ---- rendering ----

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, WINDOW_W, WINDOW_H, 256, 256);
        // Flame: lit while stage-1 conversion can run (fuel present, heat not full).
        if (!menu.getSlot(0).getItem().isEmpty() && menu.heatStored() < menu.heatCapacity()) {
            guiGraphics.blit(TEXTURE, leftPos + FLAME_X, topPos + FLAME_Y,
                    FLAME_TEX_U, FLAME_TEX_V, FLAME_SIZE, FLAME_SIZE, 256, 256);
        }
        // Heat bar (stage-1 pool).
        if (menu.heatCapacity() > 0 && menu.heatStored() > 0) {
            int h = Math.min(HEAT_BAR_H, (int) ((long) menu.heatStored() * HEAT_BAR_H / menu.heatCapacity()));
            guiGraphics.fill(leftPos + HEAT_BAR_X, topPos + HEAT_BAR_Y + HEAT_BAR_H - h,
                    leftPos + HEAT_BAR_X + HEAT_BAR_W, topPos + HEAT_BAR_Y + HEAT_BAR_H, HEAT_BAR_COLOR);
        }
        // FE bar: same-structure pool total when a pool exists, else the buffer.
        int fe = menu.poolCapFe() > 0 ? menu.poolTotalFe() : menu.bufferFe();
        int cap = menu.poolCapFe() > 0 ? menu.poolCapFe() : menu.bufferCapFe();
        if (cap > 0 && fe > 0) {
            int h = Math.min(FE_BAR_H, (int) ((long) fe * FE_BAR_H / cap));
            guiGraphics.fill(leftPos + FE_BAR_X, topPos + FE_BAR_Y + FE_BAR_H - h,
                    leftPos + FE_BAR_X + FE_BAR_W, topPos + FE_BAR_Y + FE_BAR_H, FE_BAR_COLOR);
        }
        // Rate slider: track, filled portion, 5/50/100% ticks, handle.
        int rate = displayedRate();
        int sx = leftPos + SLIDER_X;
        int sy = topPos + SLIDER_Y;
        guiGraphics.fill(sx - 1, sy - 1, sx + SLIDER_W + 1, sy + SLIDER_H + 1, SLIDER_TRACK);
        guiGraphics.fill(sx, sy, sx + rate * SLIDER_W / 100, sy + SLIDER_H, SLIDER_FILL);
        for (int tick : SLIDER_TICKS) {
            int tx = sx + tick * SLIDER_W / 100;
            guiGraphics.fill(tx, sy - 1, tx + 1, sy + SLIDER_H + 1, SLIDER_HANDLE);
        }
        int handleX = sx + rate * SLIDER_W / 100;
        guiGraphics.fill(handleX - 1, sy - 2, handleX + 2, sy + SLIDER_H + 2, SLIDER_HANDLE);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(font, title, 8, 6, TEXT_COLOR, false);
        // Readout block: heat pool / energy loss / live output / stored energy.
        int y = READOUT_Y;
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.heat",
                        String.format("%,d", menu.heatStored()), String.format("%,d", menu.heatCapacity())),
                READOUT_X, y, TEXT_COLOR, false);
        y += READOUT_PITCH;
        // Empty slot AND empty pool = nothing ever converted: show 0% instead of
        // the 10% fallback tax.
        boolean neverFueled = menu.getSlot(0).getItem().isEmpty() && menu.heatStored() == 0;
        int bp = menu.totalTaxBasisPoints();
        String taxText = neverFueled ? "0%"
                : (bp % 100 == 0 ? Integer.toString(bp / 100) : Double.toString(bp / 100.0)) + "%";
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.fuel_tax", taxText),
                READOUT_X, y, TEXT_COLOR, false);
        y += READOUT_PITCH;
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.output",
                        String.format("%,d", menu.outputFePerTick())),
                READOUT_X, y, TEXT_COLOR, false);
        y += READOUT_PITCH;
        boolean hasPool = menu.poolCapFe() > 0;
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.stored",
                        String.format("%,d", hasPool ? menu.poolTotalFe() : menu.bufferFe()),
                        String.format("%,d", hasPool ? menu.poolCapFe() : menu.bufferCapFe())),
                READOUT_X, y, TEXT_COLOR, false);
        if (!hasPool) {
            guiGraphics.drawString(font, Component.translatable("gui.bridgecore.no_distributor"),
                    HINT_X, HINT_Y, WARN_COLOR, false);
        }
        // Slider label: live FE/t + percent (reflects pendingRate while dragging).
        int rate = displayedRate();
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.rate",
                        String.format("%,d", EnergyExtractorBlockEntity.rateFor(rate)), rate),
                SLIDER_LABEL_X, SLIDER_LABEL_Y, TEXT_COLOR, false);
        // Bar tags.
        guiGraphics.drawString(font, "H", HEAT_TAG_X, BAR_TAG_Y, TEXT_COLOR, false);
        guiGraphics.drawString(font, "FE", FE_TAG_X, BAR_TAG_Y, TEXT_COLOR, false);
        guiGraphics.drawString(font, Component.translatable("container.inventory"),
                INV_LABEL_X, INV_LABEL_Y, TEXT_COLOR, false);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);
    }
}
