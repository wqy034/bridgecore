package com.bridgecore.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.bridgecore.firecontrol.FireControlUiText;
import com.bridgecore.menu.FireControlMenu;
import com.bridgecore.menu.FireControlMenuActions;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Core management: select a turret before changing it. Channel-wide controls
 *  have their own page; global maintenance is deliberately separate. Existing
 *  server commands remain authoritative. No inventory slots or textures. */
public class FireControlScreen extends AbstractContainerScreen<FireControlMenu> {
    private static final int BG = 0xFF101925, PANEL = 0xFF182637, LINE = 0xFF314457;
    private static final int TEXT = 0xFFE3EDF5, DIM = 0xFF9CB0C1, CYAN = 0xFF62D9EB;
    private static final int GREEN = 0xFF81D69C, AMBER = 0xFFFFCE78, RED = 0xFFFF8D8D;
    private static final int BODY_Y = 55, DETAIL_X = 120, LIST_ROW = 30;
    private static final String[] PAGES = {"炮塔管理", "武器频道", "核心设置"};
    private static final String[] DETAILS = {"概览", "射界", "轴承", "炮座", "维护"};

    private final List<Button> buttons = new ArrayList<>();
    private int page, detail, selected = -1, listOffset, memberPage;
    private int selectedIdentity = -1, lastRosterEpoch = -1;
    private String signature = "";
    private int pendingAction = -1;
    private String pendingTitle = "", pendingText = "", feedback = "";
    private int dragParam = -1, dragValue, activeBound;

    public FireControlScreen(FireControlMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 360;
        imageHeight = 240;
    }

    @Override
    protected void init() {
        imageWidth = Math.min(360, width - 8);
        imageHeight = Math.min(240, height - 8);
        super.init();
        signature = "";
        dragParam = -1;
        refresh();
    }

    private int contentWidth() { return imageWidth - DETAIL_X - 12; }
    private int visibleRows() { return Math.max(1, (imageHeight - BODY_Y - 60) / LIST_ROW); }
    private int memberRows() { return Math.max(1, (imageHeight - 190) / 20); }
    private boolean hasSelection() { return selected >= 0 && selected < menu.turretCount(); }
    private String turretName() { return hasSelection() ? menu.turretName(selected) : "未选择炮塔"; }

    private void refresh() {
        int count = menu.turretCount();
        if (count == 0) selected = -1;
        else if (selected < 0 || selected >= count) selected = 0;
        int identity = hasSelection() ? menu.identity(selected) : -1;
        if (identity != selectedIdentity || menu.rosterEpoch() != lastRosterEpoch) {
            selectedIdentity = identity;
            lastRosterEpoch = menu.rosterEpoch();
            pendingAction = -1;
            dragParam = -1;
            feedback = "";
        }
        listOffset = Math.max(0, Math.min(listOffset, Math.max(0, count - visibleRows())));
        memberPage = Math.min(memberPage, Math.max(0, (count - 1) / memberRows()));
        StringBuilder state = new StringBuilder().append(page).append(':').append(detail)
                .append(':').append(selected).append(':').append(listOffset).append(':').append(memberPage)
                .append(':').append(menu.identityVersion()).append(':').append(pendingAction).append(':').append(menu.activeChannel())
                .append(':').append(menu.skyFilterOn()).append(':').append(menu.followOn()).append(':').append(menu.pairingActive());
        for (int r = 0; r < count; r++) state.append('|').append(menu.identity(r)).append(',')
                .append(menu.status(r)).append(',').append(menu.turretChannel(r)).append(',')
                .append(menu.fireInterval(r)).append(',').append(menu.cadenceAdjustable(r))
                .append(',').append(menu.mountCount(r)).append(',').append(menu.paired(r))
                .append(',').append(menu.arcYawMin(r)).append(',').append(menu.arcYawMax(r))
                .append(',').append(menu.arcPitchMin(r)).append(',').append(menu.arcPitchMax(r));
        for (int ch = 0; ch < 4; ch++) state.append('/').append(menu.channelAuto(ch))
                .append(menu.channelPromote(ch)).append(menu.promoteSuppressed(ch));
        String next = state.toString();
        if (!next.equals(signature)) {
            signature = next;
            rebuild();
        }
    }

    private void rebuild() {
        for (Button button : buttons) removeWidget(button);
        buttons.clear();
        for (int p = 0; p < PAGES.length; p++) {
            final int target = p;
            button(12 + p * 82, 27, 78, 18, PAGES[p], page == p, true, () -> {
                page = target; dragParam = -1; feedback = ""; signature = "";
            }, "");
        }
        button(imageWidth - 64, imageHeight - 21, 52, 16, "关闭", false, true, this::onClose, "Esc 也可关闭");
        if (page == 0) buildTurrets();
        else if (page == 1) buildChannels();
        else buildSettings();
        if (pendingAction >= 0) {
            for (Button button : buttons) button.active = false;
            int x = 18, y = modalY();
            button(x + 12, y + modalHeight() - 26, 104, 18, "确认操作", false, true, () -> {
                int action = pendingAction;
                pendingAction = -1;
                send(action);
                feedback = "操作已发送，状态以服务端回传为准";
                signature = "";
            }, "");
            button(imageWidth - x - 92, y + modalHeight() - 26, 80, 18, "取消", false, true, () -> {
                pendingAction = -1; signature = "";
            }, "Esc 取消本次操作");
        }
    }

    private void buildTurrets() {
        int count = menu.turretCount();
        for (int i = listOffset; i < Math.min(count, listOffset + visibleRows()); i++) {
            final int row = i;
            button(10, BODY_Y + 17 + (i - listOffset) * LIST_ROW, 98, 28, "", i == selected, true, () -> {
                selected = row; pendingAction = -1; dragParam = -1; feedback = ""; signature = "";
            }, menu.turretName(i) + " · " + menu.weaponLabel(i) + "\n" + menu.channelName(menu.turretChannel(i)) + " · " + status(i));
        }
        if (count > visibleRows()) {
            button(10, imageHeight - 43, 46, 16, "上一组", false, listOffset > 0, () -> {
                listOffset = Math.max(0, listOffset - visibleRows()); signature = "";
            }, "鼠标滚轮也可浏览列表");
            button(60, imageHeight - 43, 48, 16, "下一组", false,
                    listOffset + visibleRows() < count, () -> {
                        listOffset = Math.min(Math.max(0, count - visibleRows()), listOffset + visibleRows()); signature = "";
                    }, "");
        }
        if (!hasSelection()) return;
        int cw = contentWidth(), tabW = (cw - 8) / 5;
        for (int d = 0; d < DETAILS.length; d++) {
            final int target = d;
            button(DETAIL_X + d * (tabW + 2), 87, tabW, 17, DETAILS[d], detail == d, true, () -> {
                detail = target; dragParam = -1; feedback = ""; signature = "";
            }, "");
        }
        if (detail == 0 && menu.cadenceAdjustable(selected)) {
            int y = imageHeight - 48;
            button(DETAIL_X, y, 38, 17, "降速", false, true,
                    () -> send(FireControlMenuActions.CADENCE_DOWN_BASE + selected), "只调整本塔的射速上限");
            button(DETAIL_X + cw - 38, y, 38, 17, "升速", false, true,
                    () -> send(FireControlMenuActions.CADENCE_UP_BASE + selected), "炮闩装填节奏仍独立生效");
        } else if (detail == 1) {
            for (int p = 0; p < 4; p++) {
                final int param = p;
                int half = cw / 2, x = DETAIL_X + (p / 2) * half;
                int bw = (half - 6) / 2;
                button(x + (p % 2) * (bw + 2), 123, bw, 16, boundText(p), activeBound == p, true, () -> {
                    activeBound = param; signature = "";
                }, "先选择边界，再拖动黄点；青点表示当前朝向");
            }
        } else if (detail == 3) {
            for (int slot = 0; slot < 4; slot++) {
                final int target = slot;
                int packed = menu.mountPacked(selected, slot);
                if (packed == FireControlMenu.MOUNT_SLOT_EMPTY) continue;
                int no = packed >> 4;
                button(DETAIL_X + cw - 42, mountY(slot) + 3, 42, 17, "解绑", false, true, () -> confirm(
                        FireControlMenu.MOUNT_UNBIND_BASE + selected * 16 + target,
                        "解绑炮座", "只解绑" + turretName() + "的炮座 #" + no + "。保留本塔轴承配对和其他炮座。"), mountTip(selected, target));
            }
        } else if (detail == 4) {
            button(DETAIL_X, 111, Math.min(150, cw), 19, "重新校准本塔", false,
                    menu.paired(selected) && menu.status(selected) != FireControlMenu.STATUS_CALIBRATING, () -> {
                        send(FireControlMenuActions.RECALIBRATE_BASE + selected);
                        feedback = "仅重校" + turretName() + "，其余炮塔保持工作";
                    }, "本塔会摆动并暂时退出分配；其他炮塔不重校");
            button(DETAIL_X, 174, Math.min(150, cw), 19, "解除本塔配对…", false, true, () -> confirm(
                    selected, "解除炮塔配对", "解除" + turretName() + "的配对与全部炮座链接。其他炮塔不受此操作影响。"), "重新配对会自动校准新配对的炮塔");
        }
    }

    private void buildChannels() {
        int ch = menu.activeChannel(), gap = 4, segment = (imageWidth - 24 - gap * 3) / 4;
        for (int c = 0; c < 4; c++) {
            final int target = c;
            button(12 + c * (segment + gap), BODY_Y, segment, 19, menu.channelName(c), ch == c, true,
                    () -> send(FireControlMenuActions.CHANNEL_SELECT_BASE + target), "切换指挥频道；不改其他频道的任务");
        }
        int half = (imageWidth - 28) / 2;
        button(12, 105, half, 19, "自动升格：" + (!menu.channelPromote(ch) ? "关" : menu.promoteSuppressed(ch) ? "已暂停" : "开"),
                menu.channelPromote(ch) && !menu.promoteSuppressed(ch), true,
                () -> send(FireControlMenuActions.CHANNEL_PROMOTE_BASE + ch), "作用于整条频道；将 HUD 预锁升为多目标任务");
        button(16 + half, 105, half, 19, "自动开火：" + (menu.channelAuto(ch) ? "开" : "关"), menu.channelAuto(ch), true,
                () -> send(FireControlMenuActions.CHANNEL_AUTO_BASE + ch), "作用于整条频道；开关本身不创建目标任务");
        button(12, 129, half, 19, "视线跟随：" + (menu.followOn() ? "开" : "关"), menu.followOn(), true,
                () -> send(FireControlMenu.BUTTON_TOGGLE_FOLLOW), "当前频道跟随操作者视线");
        button(16 + half, 129, half, 19, "当前频道齐射", false, membersIn(ch) > 0,
                () -> send(FireControlMenu.BUTTON_FIRE_VOLLEY), "只向当前频道发出一次齐射请求，仍受开火闸门限制");
        int start = memberPage * memberRows();
        for (int r = start; r < Math.min(menu.turretCount(), start + memberRows()); r++) {
            final int row = r;
            int y = 171 + (r - start) * 20;
            boolean included = menu.turretChannel(r) == ch;
            button(imageWidth - 92, y - 3, 80, 16, included ? "移出当前频道" : "编入当前频道", included, true,
                    () -> send(FireControlMenuActions.membership(row, included ? -1 : ch)), "每座炮塔只属于一个频道");
        }
        int pages = Math.max(1, (menu.turretCount() + memberRows() - 1) / memberRows());
        if (pages > 1) {
            button(12, imageHeight - 21, 52, 16, "上一页", false, memberPage > 0,
                    () -> { memberPage--; signature = ""; }, "");
            button(68, imageHeight - 21, 52, 16, "下一页", false, memberPage + 1 < pages,
                    () -> { memberPage++; signature = ""; }, "");
        }
    }

    private void buildSettings() {
        button(12, 79, Math.min(190, imageWidth - 24), 20,
                "露天过滤：" + (menu.skyFilterOn() ? "开" : "关"), menu.skyFilterOn(), true,
                () -> send(FireControlMenu.BUTTON_TOGGLE_SKY_FILTER), "全核心扫描设置；开启时过滤遮蔽目标");
        button(12, 130, Math.min(190, imageWidth - 24), 19, "取消我的配对流程", false, menu.pairingActive(),
                () -> send(FireControlMenu.BUTTON_CANCEL_PAIR), "只取消当前玩家尚未完成的配对，不解除已配对炮塔");
        button(12, imageHeight - 64, Math.min(190, imageWidth - 24), 19, "全部炮塔重新校准…", false,
                menu.turretCount() > 0, () -> confirm(FireControlMenu.BUTTON_RECALIBRATE_ALL,
                        "全核心维护", "将重新校准全部 " + menu.turretCount() + " 座炮塔。全体暂时退出战斗分配，频道任务按现有规则重新评估。"), "全局操作；单塔校准请到炮塔管理 → 维护");
    }

    private int membersIn(int ch) {
        int count = 0;
        for (int row = 0; row < menu.turretCount(); row++) if (menu.turretChannel(row) == ch) count++;
        return count;
    }

    private void button(int x, int y, int w, int h, String label, boolean chosen, boolean enabled, Runnable action, String tip) {
        Button button = new FlatButton(leftPos + x, topPos + y, w, h, Component.literal(label), b -> action.run(), chosen);
        button.active = enabled;
        if (!tip.isEmpty()) button.setTooltip(Tooltip.create(Component.literal(tip)));
        buttons.add(button);
        addRenderableWidget(button);
    }

    private void confirm(int action, String title, String text) {
        pendingAction = action; pendingTitle = title; pendingText = text; signature = "";
        refresh();
    }

    private void send(int id) {
        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, FireControlMenuActions.guarded(id, menu.rosterEpoch()));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        refresh();
        super.render(g, mouseX, mouseY, partialTick);
        if (pendingAction < 0) {
            renderTooltip(g, mouseX, mouseY);
            int x = mouseX - leftPos, y = mouseY - topPos;
            String tip = "";
            if (page == 0 && hasSelection() && x >= DETAIL_X && x < imageWidth - 12) {
                if (y >= BODY_Y && y < 83) tip = turretName() + " · " + menu.weaponLabel(selected);
                var info = menu.turretInfo(selected);
                if (detail == 2 && info != null && y >= 109 && y < imageHeight - 26)
                    tip = "偏航：" + info.yaw() + " · 俯仰：" + info.pitch() + " · 核心格偏移：" + info.coreOffset();
                if (detail == 3 && x < imageWidth - 60) {
                    for (int slot = 0; slot < 4; slot++) if (menu.mountPacked(selected, slot) != FireControlMenu.MOUNT_SLOT_EMPTY
                            && y >= mountY(slot) && y < mountY(slot) + 22) tip = mountTip(selected, slot);
                }
            }
            if (!tip.isEmpty()) g.renderTooltip(font, font.split(Component.literal(tip), Math.min(280, width - 20)), mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BG);
        g.renderOutline(leftPos, topPos, imageWidth, imageHeight, LINE);
        g.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + 23, PANEL);
        g.fill(leftPos + 8, topPos + 49, leftPos + imageWidth - 8, topPos + 50, LINE);
        g.fill(leftPos + 8, topPos + imageHeight - 26, leftPos + imageWidth - 8, topPos + imageHeight - 25, LINE);
        if (page == 0) g.fill(leftPos + 113, topPos + BODY_Y, leftPos + 114, topPos + imageHeight - 30, LINE);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        text(g, "火控核心", 12, 9, TEXT);
        right(g, fit(menu.turretCount() + " 座炮塔 · " + menu.channelName(menu.activeChannel()), imageWidth - 120), imageWidth - 12, 9, DIM);
        if (page == 0) drawTurrets(g);
        else if (page == 1) drawChannels(g);
        else drawSettings(g);
        if (page != 1 || menu.turretCount() <= memberRows()) {
            String hint = !feedback.isEmpty() ? feedback : page == 0
                    ? detail == 1 ? "拖动黄点改边界 · 青点为当前角度" : "炮塔操作只作用于选中对象"
                    : page == 1 ? "频道开关作用于该频道全部成员" : "扫描设置与全局维护";
            text(g, fit(hint, imageWidth - 92), 12, imageHeight - 17, DIM);
        } else text(g, (memberPage + 1) + "/" + Math.max(1, (menu.turretCount() + memberRows() - 1) / memberRows()), 128, imageHeight - 17, DIM);
        if (pendingAction >= 0) {
            // Modal is drawn over labels; its two widgets render on the same
            // foreground plane, with the underlying controls disabled.
            g.fill(1, 24, imageWidth - 1, imageHeight - 1, 0xDF101925);
            int x = 18, y = modalY();
            g.fill(x, y, imageWidth - x, y + modalHeight(), PANEL);
            g.renderOutline(x, y, imageWidth - x * 2, modalHeight(), AMBER);
            text(g, pendingTitle, x + 12, y + 10, AMBER);
            int ty = y + 27;
            for (var line : font.split(Component.literal(pendingText), imageWidth - 2 * x - 24)) {
                g.drawString(font, line, x + 12, ty, TEXT, false); ty += 11;
            }
            // Container labels render after widgets, so redraw only the modal
            // buttons here to keep them above its opaque surface.
            g.pose().pushPose();
            g.pose().translate(-leftPos, -topPos, 0);
            for (int i = Math.max(0, buttons.size() - 2); i < buttons.size(); i++) buttons.get(i).render(g, mouseX, mouseY, 0);
            g.pose().popPose();
        }
    }

    private void drawTurrets(GuiGraphics g) {
        text(g, "已配对炮塔", 12, BODY_Y + 3, DIM);
        for (int r = listOffset; r < Math.min(menu.turretCount(), listOffset + visibleRows()); r++) {
            int y = BODY_Y + 20 + (r - listOffset) * LIST_ROW;
            text(g, fit(menu.turretName(r), 86), 16, y, r == selected ? CYAN : TEXT);
            text(g, fit(menu.weaponLabel(r), 86), 16, y + 13, DIM);
            g.fill(104, y + 2, 107, y + 6, statusColor(r));
        }
        if (!hasSelection()) {
            text(g, "尚未配对炮塔", DETAIL_X, 76, TEXT);
            wrapped(g, "手持配对工具右键核心开始，再依次选择偏航轴承、俯仰轴承和炮口。", DETAIL_X, 99, contentWidth(), DIM);
            return;
        }
        text(g, fit(turretName(), contentWidth()), DETAIL_X, BODY_Y + 3, TEXT);
        text(g, fit(menu.weaponLabel(selected), contentWidth()), DETAIL_X, BODY_Y + 16, DIM);
        switch (detail) {
            case 1 -> drawArcs(g);
            case 2 -> drawBearings(g);
            case 3 -> drawMounts(g);
            case 4 -> {
                wrapped(g, "仅本塔摆动并暂时退出分配；频道按剩余可用成员继续工作。", DETAIL_X, 135, contentWidth(), DIM);
            }
            default -> {
                text(g, fit(status(selected) + " · " + menu.channelName(menu.turretChannel(selected)), contentWidth()), DETAIL_X, 112, statusColor(selected));
                text(g, fit("偏航 " + angle(menu.relYaw10(selected)) + "   俯仰 " + angle(menu.relPitch10(selected)), contentWidth()), DETAIL_X, 129, TEXT);
                int err = menu.aimErr10(selected);
                text(g, fit("误差 " + (err < 0 ? "—" : String.format(Locale.ROOT, "%.1f°", err / 10.0))
                        + "   炮座 " + menu.mountCount(selected) + "/4", contentWidth()), DETAIL_X, 146, TEXT);
                text(g, fit("开火：" + FireControlUiText.gate(menu.gate(selected)), contentWidth()), DETAIL_X, 163, DIM);
                if (!menu.cadenceAdjustable(selected)) {
                    text(g, fit("射速由武器装填机构控制", contentWidth()), DETAIL_X, imageHeight - 42, DIM);
                } else {
                    int interval = menu.fireInterval(selected);
                    String rate = interval > 0 ? 1200 / interval + " 发/分" : "—";
                    text(g, rate, DETAIL_X + (contentWidth() - font.width(rate)) / 2, imageHeight - 44, TEXT);
                }
            }
        }
    }

    private String status(int row) {
        if (!menu.paired(row)) return "未配对";
        return menu.status(row) == FireControlMenu.STATUS_READY ? "已校准" : "校准中";
    }
    private int statusColor(int row) { return menu.status(row) == FireControlMenu.STATUS_READY ? GREEN : AMBER; }

    private void drawChannels(GuiGraphics g) {
        int ch = menu.activeChannel();
        text(g, fit("任务：" + FireControlUiText.source(menu.channelSource(ch)) + " · " + FireControlUiText.mode(menu.channelMode(ch))
                + "   成员 " + membersIn(ch), imageWidth - 24), 12, 87, TEXT);
        text(g, "炮塔成员 · 每塔仅属于一个频道", 12, 155, DIM);
        int start = memberPage * memberRows();
        for (int r = start; r < Math.min(menu.turretCount(), start + memberRows()); r++) {
            int y = 171 + (r - start) * 20;
            text(g, fit(menu.turretName(r) + " · " + menu.weaponLabel(r) + " · " + menu.channelName(menu.turretChannel(r)), imageWidth - 115), 12, y, TEXT);
        }
        if (menu.turretCount() == 0) text(g, "先配对炮塔，再编入频道", 12, 175, DIM);
    }

    private void drawSettings(GuiGraphics g) {
        text(g, "扫描规则 · 全核心", 12, 63, TEXT);
        wrapped(g, menu.skyFilterOn() ? "过滤受遮蔽的目标，避免隔墙锁定。" : "允许扫描受遮蔽目标；可能增加扫描开销。", 12, 105, imageWidth - 24, DIM);
        text(g, menu.pairingActive() ? "我的配对流程进行中" : "当前没有进行中的配对流程", 12, 154, DIM);
        text(g, "影响所有炮塔；单塔维护请到炮塔管理", 12, imageHeight - 39, AMBER);
    }

    private String angle(int value) {
        return value == FireControlMenu.REL_UNKNOWN ? "—" : String.format(Locale.ROOT, "%.1f°", value / 10.0);
    }

    private int modalHeight() {
        return Math.max(97, 52 + font.split(Component.literal(pendingText), imageWidth - 60).size() * 11);
    }
    private int modalY() { return (imageHeight - modalHeight()) / 2; }
    private int mountY(int slot) { return 110 + slot * ((imageHeight - 138) / 4); }

    private String mountTip(int row, int slot) {
        var mount = menu.mountInfo(row, slot);
        return mount == null ? "正在同步炮座信息" : menu.turretName(row) + " · 炮座 #" + mount.no()
                + " · " + mount.weapon() + "\n世界坐标 " + mount.location() + "\n仅解绑这一个炮座";
    }

    private void drawBearings(GuiGraphics g) {
        var info = menu.turretInfo(selected);
        text(g, "偏航轴承 · 世界坐标", DETAIL_X, 112, CYAN);
        text(g, fit(info == null ? "信息同步中" : info.yaw(), contentWidth()), DETAIL_X, 126, TEXT);
        text(g, "俯仰轴承 · 世界坐标", DETAIL_X, 148, CYAN);
        text(g, fit(info == null ? "信息同步中" : info.pitch(), contentWidth()), DETAIL_X, 162, TEXT);
        text(g, fit("偏航轴承相对核心的格坐标", contentWidth()), DETAIL_X, imageHeight - 57, DIM);
        text(g, fit(info == null ? "信息同步中" : info.coreOffset(), contentWidth()), DETAIL_X, imageHeight - 43, TEXT);
    }

    private void drawMounts(GuiGraphics g) {
        if (menu.mountCount(selected) == 0) {
            text(g, "尚未挂载炮座", DETAIL_X, 113, TEXT);
            wrapped(g, "使用配对工具右键炮座，将它挂到对应炮塔。", DETAIL_X, 137, contentWidth(), DIM);
        }
        for (int slot = 0; slot < 4; slot++) {
            int packed = menu.mountPacked(selected, slot);
            if (packed == FireControlMenu.MOUNT_SLOT_EMPTY) continue;
            var mount = menu.mountInfo(selected, slot);
            int code = (packed & 15) - 1, y = mountY(slot);
            text(g, fit("#" + (packed >> 4) + " " + (mount == null ? "信息同步中" : mount.weapon()), contentWidth() - 48), DETAIL_X, y, TEXT);
            text(g, fit(FireControlUiText.mount(code) + (mount == null ? "" : " · " + mount.location()), contentWidth() - 48), DETAIL_X, y + 11,
                    code == 1 ? GREEN : code == 5 ? RED : AMBER);
        }
    }

    private int dialRadius() { return Math.min(26, Math.max(18, (imageHeight - 180) / 2)); }
    private int dialY() { return imageHeight - 60; }
    private int dialX(boolean yaw) { return DETAIL_X + contentWidth() * (yaw ? 1 : 3) / 4; }
    private int boundValue(int p) {
        if (dragParam == p) return dragValue;
        return switch (p) {
            case 0 -> menu.arcYawMin(selected);
            case 1 -> menu.arcYawMax(selected);
            case 2 -> menu.arcPitchMin(selected);
            default -> menu.arcPitchMax(selected);
        };
    }
    private String boundText(int p) {
        return (p < 2 ? p == 0 ? "左 " : "右 " : p == 2 ? "下 " : "上 ") + boundValue(p) + "°";
    }
    private int pointX(boolean yaw, double angle) {
        double radians = Math.toRadians(angle);
        return dialX(yaw) + (int) Math.round(dialRadius() * (yaw ? Math.sin(radians) : Math.cos(radians)));
    }
    private int pointY(boolean yaw, double angle) {
        double radians = Math.toRadians(angle);
        return dialY() - (int) Math.round(dialRadius() * (yaw ? Math.cos(radians) : Math.sin(radians)));
    }

    private void drawArcs(GuiGraphics g) {
        text(g, "偏航 · 俯视", DETAIL_X, 109, DIM);
        text(g, "俯仰 · 侧视", DETAIL_X + contentWidth() / 2, 109, DIM);
        for (int axis = 0; axis < 2; axis++) {
            boolean yaw = axis == 0;
            int min = boundValue(axis * 2), max = boundValue(axis * 2 + 1);
            for (int angle = yaw ? -180 : -90; angle <= (yaw ? 180 : 90); angle += 2) {
                int color = angle >= min && angle <= max ? GREEN : LINE;
                int x = pointX(yaw, angle), y = pointY(yaw, angle);
                g.fill(x, y, x + 1, y + 1, color);
            }
            int zeroX = pointX(yaw, 0), zeroY = pointY(yaw, 0);
            g.fill(zeroX - 1, zeroY - 1, zeroX + 2, zeroY + 2, TEXT);
            int live = yaw ? menu.relYaw10(selected) : menu.relPitch10(selected);
            if (live != FireControlMenu.REL_UNKNOWN) {
                int lx = pointX(yaw, live / 10.0), ly = pointY(yaw, live / 10.0);
                g.fill(lx - 2, ly - 2, lx + 3, ly + 3, CYAN);
            }
            for (int p = axis * 2; p < axis * 2 + 2; p++) {
                int hx = pointX(yaw, boundValue(p)), hy = pointY(yaw, boundValue(p));
                g.fill(hx - 3, hy - 3, hx + 4, hy + 4, activeBound == p ? TEXT : AMBER);
            }
            if (imageHeight >= 260) text(g, "安装零位为 0°", dialX(yaw) - 36, dialY() + dialRadius() + 12, DIM);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (pendingAction < 0 && page == 0 && detail == 1 && hasSelection() && button == 0) {
            double x = mouseX - leftPos, y = mouseY - topPos;
            int best = -1;
            double distance = 81;
            // Prefer the explicitly selected bound when two endpoints overlap.
            for (int offset = 0; offset < 4; offset++) {
                int p = (activeBound + offset) % 4;
                double dx = x - pointX(p < 2, boundValue(p)), dy = y - pointY(p < 2, boundValue(p));
                double d = dx * dx + dy * dy;
                if (d < distance) { best = p; distance = d; }
            }
            if (best >= 0) {
                int initial = boundValue(best);
                activeBound = best; dragParam = best; dragValue = initial;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragParam < 0) return super.mouseDragged(mouseX, mouseY, button, dx, dy);
        boolean yaw = dragParam < 2;
        double x = mouseX - leftPos - dialX(yaw), y = dialY() - (mouseY - topPos);
        int angle = (int) Math.round(Math.toDegrees(yaw ? Math.atan2(x, y) : Math.atan2(y, x)));
        int value = Math.max(yaw ? -180 : -90, Math.min(yaw ? 180 : 90, angle));
        if (value != dragValue) {
            dragValue = value;
            send(FireControlMenu.ARC_SET_BASE + selected * 4096 + dragParam * 512 + value + 180);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragParam >= 0) { dragParam = -1; signature = ""; return true; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (page == 0 && pendingAction < 0 && mouseX >= leftPos + 10 && mouseX < leftPos + 110 && vertical != 0) {
            listOffset = Math.max(0, Math.min(Math.max(0, menu.turretCount() - visibleRows()), listOffset + (vertical > 0 ? -1 : 1)));
            signature = ""; return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 256 && pendingAction >= 0) { pendingAction = -1; signature = ""; return true; }
        return super.keyPressed(key, scan, modifiers);
    }

    private void text(GuiGraphics g, String text, int x, int y, int color) { g.drawString(font, text, x, y, color, false); }
    private void right(GuiGraphics g, String text, int x, int y, int color) { text(g, text, x - font.width(text), y, color); }
    private String fit(String text, int width) {
        return font.width(text) <= width ? text : font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }
    private void wrapped(GuiGraphics g, String text, int x, int y, int width, int color) {
        for (var line : font.split(Component.literal(text), width)) {
            g.drawString(font, line, x, y, color, false); y += 11;
        }
    }

    private final class FlatButton extends Button {
        private final boolean chosen;
        FlatButton(int x, int y, int w, int h, Component label, OnPress action, boolean chosen) {
            super(x, y, w, h, label, action, DEFAULT_NARRATION);
            this.chosen = chosen;
        }
        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean hover = isHoveredOrFocused();
            int fill = !active ? BG : chosen ? 0xFF254C5C : hover ? 0xFF2A3E50 : PANEL;
            int border = active && (chosen || hover) ? CYAN : LINE;
            int color = !active ? 0xFF6C7F91 : chosen ? CYAN : TEXT;
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), fill);
            g.renderOutline(getX(), getY(), getWidth(), getHeight(), border);
            String label = fit(getMessage().getString(), getWidth() - 6);
            g.drawString(font, label, getX() + (getWidth() - font.width(label)) / 2,
                    getY() + (getHeight() - 8) / 2, color, false);
        }
    }
}
