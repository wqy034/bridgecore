package com.bridgecore.client;

import com.bridgecore.display.DisplayApps;
import com.bridgecore.network.DisplayGpuOpenPayload;
import com.bridgecore.network.DisplayGpuSelectPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Production display editor: application, per-tile density, whole-canvas preview. */
public final class DisplayGpuSelectScreen extends Screen {
    private static final int W = 300, H = 224;
    private static final int BG = 0xFF101925, PANEL = 0xFF182637, LINE = 0xFF314457;
    private static final int TEXT = 0xFFE3EDF5, DIM = 0xFF9CB0C1, CYAN = 0xFF62D9EB;
    private DisplayGpuOpenPayload state;
    private String app;
    private int choice;
    private boolean pending;
    private int waitingTicks;
    private boolean timedOut;

    private DisplayGpuSelectScreen(DisplayGpuOpenPayload state) {
        super(Component.literal("显示设置"));
        accept(state);
    }
    private void accept(DisplayGpuOpenPayload next) {
        state = next; app = next.appId(); choice = next.resolutionChoice(); pending = false; waitingTicks = 0; timedOut = false;
    }
    public static void open(DisplayGpuOpenPayload state) {
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof DisplayGpuSelectScreen screen && screen.state.pos().equals(state.pos())) {
            screen.accept(state); screen.rebuildControls();
        } else if (state.feedback() == 0) client.setScreen(new DisplayGpuSelectScreen(state));
        // A late acknowledgement must not reopen a closed editor.
    }
    private int left() { return (width - W) / 2; }
    private int top() { return (height - H) / 2; }
    @Override protected void init() { rebuildControls(); }
    private void rebuildControls() {
        clearWidgets();
        int x = left(), y = top();
        add(x + 12, y + 43, 90, "火控控制屏", DisplayApps.FIRE_CONTROL.equals(app), () -> { app = DisplayApps.FIRE_CONTROL; rebuildControls(); });
        add(x + 108, y + 43, 90, "CC模式", DisplayApps.CC_MODE.equals(app), () -> { app = DisplayApps.CC_MODE; rebuildControls(); });
        add(x + 204, y + 43, 84, "灭屏", DisplayApps.OFF.equals(app), () -> { app = DisplayApps.OFF; rebuildControls(); });
        int[] values = {0, 128, 256, 512};
        String[] labels = {"自动≥256", "128", "256", "512"};
        for (int i = 0; i < values.length; i++) {
            int value = values[i];
            add(x + 12 + i * 70, y + 105, 66, labels[i], choice == value, () -> { choice = value; rebuildControls(); });
        }
        Button apply = add(x + W - 136, y + H - 30, 60, "应用/收回", false, () -> {
            pending = true;
            waitingTicks = 0; timedOut = false;
            PacketDistributor.sendToServer(new DisplayGpuSelectPayload(state.pos(), app, choice, state.revision()));
            rebuildControls();
        });
        // Applying the same choice is also the physical user's explicit CC lease release.
        apply.active = !pending;
        apply.setTooltip(Tooltip.create(Component.literal("此控制器决定整屏内容；只有选择CC模式，脚本才可写屏。应用将释放当前写入者")));
        Button close = add(x + W - 70, y + H - 30, 58, "关闭", false, this::onClose);
        close.active = true;
    }
    private Button add(int x, int y, int w, String text, boolean selected, Runnable action) {
        Button button = new DisplayButton(x, y, w, text, selected, b -> action.run());
        button.active = !pending;
        return addRenderableWidget(button);
    }
    private final class DisplayButton extends Button {
        private final boolean selected;
        DisplayButton(int x, int y, int w, String label, boolean selected, OnPress action) {
            super(x, y, w, 20, Component.literal(label), action, DEFAULT_NARRATION);
            this.selected = selected;
        }
        @Override protected void renderWidget(GuiGraphics g, int mx, int my, float tick) {
            boolean hover = isHoveredOrFocused();
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), selected ? 0xFF254C5C : PANEL);
            g.renderOutline(getX(), getY(), getWidth(), getHeight(), active && (hover || selected) ? CYAN : LINE);
            g.drawCenteredString(font, getMessage(), getX() + getWidth() / 2, getY() + 6, active ? TEXT : DIM);
        }
    }
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float tick) {
        super.renderBackground(g, mx, my, tick);
        int x = left(), y = top();
        g.fill(x, y, x + W, y + H, BG);
        g.renderOutline(x, y, W, H, LINE);
        g.fill(x + 1, y + 1, x + W - 1, y + 27, PANEL);
    }
    @Override public void render(GuiGraphics g, int mx, int my, float tick) {
        super.render(g, mx, my, tick);
        int x = left(), y = top();
        g.drawString(font, title, x + 12, y + 10, TEXT, false);
        g.drawString(font, "显示内容", x + 12, y + 31, DIM, false);
        g.drawString(font, "单屏分辨率 · 每台控制器独立保存", x + 12, y + 83, TEXT, false);
        int resolution = choice == 0 ? state.automaticResolution() : choice;
        String canvas = state.columns() > 0 && state.rows() > 0
                ? state.columns() + "×" + state.rows() + " 面板 → " + state.columns() * resolution + "×" + state.rows() * resolution
                : "未连接屏幕 · 放置后自动识别拼屏";
        g.drawString(font, canvas, x + 12, y + 138, CYAN, false);
        g.drawString(font, "3×3 /128：密度与触控更均衡", x + 12, y + 155, DIM, false);
        String feedback = pending ? "正在应用…" : timedOut ? "未收到回执，请重新打开显示设置" : switch (state.feedback()) {
            case 1 -> "已保存并应用";
            case 2 -> "设置已被修改，已刷新，请重新选择";
            case 3 -> "未应用：屏幕尺寸或显存超过上限";
            case 4 -> "编辑已超时，已刷新，请重新选择";
            default -> "单格 /512：可显示接近全量信息";
        };
        g.drawString(font, feedback, x + 12, y + 175, state.feedback() == 3 ? 0xFFFFCE78 : DIM, false);
    }
    @Override public void tick() {
        if (pending && ++waitingTicks >= 200) { pending = false; timedOut = true; rebuildControls(); }
    }
    @Override public boolean isPauseScreen() { return false; }
}
