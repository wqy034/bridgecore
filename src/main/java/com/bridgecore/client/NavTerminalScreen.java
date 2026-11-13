package com.bridgecore.client;

import com.bridgecore.control.CruiseHeading;
import com.bridgecore.control.NavigationController;
import com.bridgecore.control.RemoteManeuver;
import com.bridgecore.firecontrol.StrikePreview;
import com.bridgecore.nav.NavTerminalData;
import com.bridgecore.nav.NavTerminalItem;
import com.bridgecore.network.TerminalCruisePayload;
import com.bridgecore.network.TerminalKeyMapPayload;
import com.bridgecore.network.TerminalLinkPayload;
import com.bridgecore.network.TerminalReplyPayload;
import com.bridgecore.network.TerminalStrikeRequest;
import com.bridgecore.network.TerminalStrikeSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntConsumer;

/** Shared terminal frame with navigation, strike and device settings pages. */
public class NavTerminalScreen extends Screen {
    private static final int BG = 0xFF101925, PANEL = 0xFF182637, LINE = 0xFF314457;
    private static final int TEXT = 0xFFE3EDF5, DIM = 0xFF9CB0C1, CYAN = 0xFF62D9EB;
    private static final int GREEN = 0xFF81D69C, AMBER = 0xFFFFCE78;
    private static final int BODY_Y = 61, ROW_HEIGHT = 17;
    private static final int STRIKE_MARKS_Y = 96, STRIKE_CHANNEL_Y = 104;
    private static final int STRIKE_RESULTS_Y = 122, RESULT_ROW_HEIGHT = 11;
    private static final String[] AXES = {"X", "Y", "Z"};

    private enum Page {
        NAVIGATION("导航"), STRIKE("打击"), SETTINGS("综合设置");
        final String label;
        Page(String label) { this.label = label; }
    }

    private enum StrikeView { TARGETS, CONFIGURATION, PREVIEW, QUICK_SETTINGS }

    private enum StrikeOperation {
        CHANNELS(TerminalStrikeRequest.Action.PREVIEW), QUICK_CHANNELS(TerminalStrikeRequest.Action.PREVIEW),
        PREVIEW(TerminalStrikeRequest.Action.PREVIEW), EXECUTE(TerminalStrikeRequest.Action.EXECUTE),
        CANCEL(TerminalStrikeRequest.Action.CANCEL);

        final TerminalStrikeRequest.Action action;
        StrikeOperation(TerminalStrikeRequest.Action action) { this.action = action; }
    }

    private final InteractionHand hand;
    private Page page = Page.NAVIGATION;
    private int left, top, panelWidth, panelHeight, footer;
    private CustomData lastData;
    private UUID terminal;
    private BlockPos core;
    private ResourceLocation coreDimension;
    private String shipStatus = "";
    private String reply = "";
    private int nextStatusTick, screenTick, statusReceivedTick = -1, statusStartedTick;
    private long keyMapRequest;
    private int keyMapDeadline;
    private List<NavTerminalData.Mark> marks = List.of();
    private List<NavTerminalData.Mark> navigationMarks = List.of();
    private List<NavTerminalData.Mark> strikeMarks = List.of();
    private NavTerminalData.Settings saved = NavTerminalData.Settings.DEFAULT;

    private boolean coordinates;
    private final String[] coordinateText = {"", "", ""};
    private final String[] settingText = new String[3];
    private String nameText = "", strikeNameText = "";
    private NavTerminalData.Mark navMark, strikeMark, deleteMark;
    private int navScroll, strikeMarkScroll, resultScroll;
    private StrikeView strikeView = StrikeView.TARGETS;
    private UUID strikeRequest, strikeConfirmation;
    private StrikeOperation pendingStrikeOperation;
    private int strikeDeadline;
    private TerminalStrikeSnapshot strikeRoster;
    private List<StrikePreview.Row> strikeRows = List.of();
    private String strikeSummary = "";
    private String previewChannel = "";
    private int strikeChannel;
    private boolean allowHullTurn = true;
    private TerminalStrikeSnapshot quickRoster;
    private int quickChannel = -1;
    private boolean quickTurn = true;
    private NavTerminalData.QuickStrike quickProfile;

    private NavTerminalScreen(InteractionHand hand) {
        super(Component.literal("导航终端"));
        this.hand = hand;
    }

    public static void open(InteractionHand hand) {
        Minecraft.getInstance().setScreen(new NavTerminalScreen(hand));
    }

    private ItemStack heldStack() {
        var player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
    }

    public static boolean acceptReply(TerminalReplyPayload packet) {
        if (!(Minecraft.getInstance().screen instanceof NavTerminalScreen screen) || !packet.terminal().equals(screen.terminal)) {
            return false;
        }
        if (packet.status()) {
            screen.shipStatus = packet.message();
            screen.statusReceivedTick = screen.screenTick;
        } else {
            screen.reply = packet.message();
        }
        return true;
    }

    public static void acceptKeyMap(TerminalKeyMapPayload packet) {
        if (!(Minecraft.getInstance().screen instanceof NavTerminalScreen screen) || screen.page != Page.NAVIGATION
                || screen.keyMapRequest == 0 || packet.request() != screen.keyMapRequest
                || !packet.terminal().equals(NavTerminalData.peekUuid(screen.heldStack()))) return;
        screen.keyMapRequest = 0;
        NavTerminalKeyboard.enter(screen.hand, packet.terminal(), packet.keys(), screen.saved.nudgeTenths());
    }

    public static void acceptStrikeSnapshot(TerminalStrikeSnapshot packet) {
        if (!(Minecraft.getInstance().screen instanceof NavTerminalScreen screen)
                || !packet.request().equals(screen.strikeRequest) || !packet.terminal().equals(screen.terminal)) return;
        var operation = screen.pendingStrikeOperation;
        screen.clearStrikeRequest();
        if (operation == StrikeOperation.QUICK_CHANNELS) {
            screen.quickRoster = packet.channels().isEmpty() ? null : packet;
            screen.validateQuickChannel();
            screen.reply = packet.message();
            screen.rebuildWidgets();
            return;
        }
        screen.strikeRoster = packet.channels().isEmpty() ? null : packet;
        screen.strikeChannel = Math.clamp(screen.strikeChannel, 0, Math.max(0, packet.channels().size() - 1));
        if (operation == StrikeOperation.PREVIEW) {
            screen.strikeConfirmation = packet.executableWeapons() > 0 ? packet.request() : null;
            screen.strikeRows = packet.rows();
            screen.strikeSummary = packet.message();
            screen.reply = "";
        } else {
            screen.reply = packet.message();
        }
        if (screen.page == Page.STRIKE) screen.rebuildWidgets();
    }

    @Override
    public void tick() {
        var held = heldStack();
        if (!(held.getItem() instanceof NavTerminalItem)) {
            onClose();
            return;
        }
        if (syncItem(held)) rebuildWidgets();
        screenTick++;
        if (keyMapRequest != 0 && screenTick >= keyMapDeadline) {
            keyMapRequest = 0;
            if (reply.equals("正在读取飞控键位…")) reply = "未收到飞控键位";
            rebuildWidgets();
        }
        if (strikeRequest != null && screenTick >= strikeDeadline) {
            if (pendingStrikeOperation == StrikeOperation.PREVIEW) {
                strikeSummary = "未收到预瞄回报，请重试";
                reply = "";
            } else {
                reply = "未收到打击回报，请重试";
            }
            clearStrikeRequest();
            rebuildWidgets();
        }
        if (bound() && screenTick >= nextStatusTick) {
            nextStatusTick = screenTick + 40;
            send(TerminalLinkPayload.Cmd.SHIP_STATUS, "", "", 0);
        }
    }

    private boolean syncItem(ItemStack held) {
        var data = held.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (data.equals(lastData)) return false;
        boolean initial = lastData == null;
        lastData = data;
        var id = NavTerminalData.peekUuid(held);
        var position = NavTerminalData.boundCorePos(held);
        var dimension = NavTerminalData.boundCoreDim(held);
        boolean newBinding = !Objects.equals(id, terminal) || !Objects.equals(position, core)
                || !Objects.equals(dimension, coreDimension);
        if (newBinding) {
            if (!Objects.equals(id, terminal)) {
                navMark = strikeMark = deleteMark = null;
                nameText = strikeNameText = "";
                navScroll = strikeMarkScroll = 0;
            }
            terminal = id;
            core = position;
            coreDimension = dimension;
            shipStatus = "";
            statusReceivedTick = -1;
            statusStartedTick = screenTick;
            nextStatusTick = screenTick;
            keyMapRequest = 0;
            clearStrikeRequest();
            strikeRoster = null;
            strikeChannel = 0;
            quickRoster = null;
            clearStrikeResults();
            if (strikeView != StrikeView.QUICK_SETTINGS) strikeView = StrikeView.TARGETS;
        }
        var settings = NavTerminalData.settings(held);
        if (initial || newBinding || !saved.equals(settings)) {
            saved = settings;
            restoreSettings();
        }
        var profile = NavTerminalData.quickStrike(held);
        if (initial || newBinding || !Objects.equals(quickProfile, profile)) {
            quickProfile = profile;
            quickChannel = profile == null ? -1 : profile.channel();
            quickTurn = profile == null || profile.mode() != StrikePreview.Mode.TURRET.wireId;
            validateQuickChannel();
        }
        marks = NavTerminalData.marks(held);
        navigationMarks = marks.stream().filter(mark -> !mark.strike()).toList();
        strikeMarks = marks.stream().filter(NavTerminalData.Mark::strike).toList();
        if (navMark != null && !navigationMarks.contains(navMark)) navMark = null;
        if (strikeMark != null && !strikeMarks.contains(strikeMark)) {
            strikeMark = null;
            if (strikeView == StrikeView.CONFIGURATION || strikeView == StrikeView.PREVIEW) {
                changeStrikeView(StrikeView.TARGETS);
            }
        }
        return true;
    }

    private boolean bound() { return terminal != null && core != null && coreDimension != null; }

    @Override
    protected void init() {
        syncItem(heldStack());
        panelWidth = Math.min(344, width - 12);
        panelHeight = Math.min(252, height - 10);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        footer = panelHeight - 22;
        if (panelHeight < 190 || panelWidth < 235) return;
        int tab = (panelWidth - 26) / 3;
        for (var item : Page.values()) {
            button(10 + item.ordinal() * (tab + 3), 34, tab, 18, item.label, page == item, true, () -> {
                if (page == item) return;
                if (page == Page.STRIKE) changeStrikeView(StrikeView.TARGETS);
                page = item;
                keyMapRequest = 0;
                deleteMark = null;
                rebuildWidgets();
            }, "");
        }
        switch (page) {
            case NAVIGATION -> initNavigation();
            case STRIKE -> initStrike();
            case SETTINGS -> initSettings();
        }
    }

    private void initNavigation() {
        int w = (panelWidth - 26) / 3;
        button(10, BODY_Y, w, 17, "唤醒飞船", false, bound(),
                () -> send(TerminalLinkPayload.Cmd.WAKE, "", "", 0), "");
        button(13 + w, BODY_Y, w, 17, "短程召回", false, bound(),
                () -> send(TerminalLinkPayload.Cmd.RECALL, Double.toString(saved.cruiseSpeed()),
                        Double.toString(saved.turnDegrees()), 0), "召回到玩家所在的水平位置");
        button(16 + w * 2, BODY_Y, w, 17, "键盘微调", false, bound() && keyMapRequest == 0,
                this::requestKeys, "短按半格 · 长按移动 · Q/E 转向 · Esc 退出");
        button(10, 83, 58, 16, "标定点", !coordinates, true, () -> selectCoordinates(false), "");
        button(71, 83, 58, 16, "手输坐标", coordinates, true, () -> selectCoordinates(true), "");
        button(panelWidth - 76, 83, 66, 16, "取消航行", false, bound(),
                () -> send(TerminalLinkPayload.Cmd.CANCEL, "", "", 0), "取消巡航、唤醒或短程召回");
        if (coordinates) {
            int column = (panelWidth - 26) / 3;
            for (int i = 0; i < AXES.length; i++) {
                int index = i;
                var field = editBox(10 + i * (column + 3), 122, column, AXES[i], coordinateText[i], 18);
                field.setResponder(value -> coordinateText[index] = value);
            }
            button(10, 146, 82, 18, "开始导航", false, bound(), this::submitCoordinates, "");
        } else {
            initMarkList(false);
        }
    }

    private void selectCoordinates(boolean coordinates) {
        this.coordinates = coordinates;
        deleteMark = null;
        rebuildWidgets();
    }

    private void requestKeys() {
        keyMapRequest = UUID.randomUUID().getLeastSignificantBits() | 1L;
        keyMapDeadline = screenTick + 40;
        reply = "正在读取飞控键位…";
        send(TerminalLinkPayload.Cmd.KEY_MAP, "", "", keyMapRequest);
        rebuildWidgets();
    }

    private int listY(boolean strike) { return strike ? STRIKE_MARKS_Y : 105; }

    private int listActionY() { return footer - 24; }

    private int visibleMarks(boolean strike) {
        return Math.max(1, (listActionY() - listY(strike) - 3) / ROW_HEIGHT);
    }

    private void initMarkList(boolean strike) {
        var list = strike ? strikeMarks : navigationMarks;
        var selected = strike ? strikeMark : navMark;
        int scroll = Math.clamp(strike ? strikeMarkScroll : navScroll, 0, Math.max(0, list.size() - visibleMarks(strike)));
        if (strike) strikeMarkScroll = scroll;
        else navScroll = scroll;
        for (int i = scroll; i < Math.min(list.size(), scroll + visibleMarks(strike)); i++) {
            var mark = list.get(i);
            int y = listY(strike) + (i - scroll) * ROW_HEIGHT;
            button(10, y, panelWidth - 20, 15, "", mark.equals(selected), true, () -> {
                if (strike) {
                    strikeMark = mark;
                    strikeNameText = mark.name();
                } else {
                    navMark = mark;
                    nameText = mark.name();
                }
                deleteMark = null;
                rebuildWidgets();
            }, mark.name() + " · " + mark.pos().toShortString() + " · " + mark.dim());
        }
        int y = listActionY();
        int action = Math.min(51, (panelWidth - 96) / 4), gap = action + 3;
        int start = strike ? panelWidth - 165 : panelWidth - 10 - action * 4 - 9;
        int renameX = strike ? start : start + gap;
        int deleteX = strike ? start + 37 : start + 2 * gap;
        var name = editBox(10, y, start - 14, "标定点名称", strike ? strikeNameText : nameText, 24);
        name.setResponder(value -> {
            if (strike) strikeNameText = value;
            else nameText = value;
        });
        if (!strike) {
            button(start, y, action, 17, "开始平飞", false, selected != null && bound(),
                    () -> submitCruise(selected.name(), null), "");
        }
        initMarkEditing(selected, strike, renameX, deleteX, action);
        if (strike) {
            button(start + 87, y, 68, 17, "配置打击", true, selected != null && bound() && strikeRequest == null,
                    () -> showStrikeView(StrikeView.CONFIGURATION), "");
        } else {
            button(start + 3 * gap, y, action, 17, "标定此处", false, true, () -> {
                String nameValue = nameText.isBlank() ? "点" + (marks.size() + 1) : nameText.strip();
                send(TerminalLinkPayload.Cmd.MARK_ADD, nameValue, "", 0);
            }, "");
        }
    }

    private void initMarkEditing(@Nullable NavTerminalData.Mark selected, boolean strike,
                                 int renameX, int deleteX, int actionWidth) {
        int y = listActionY();
        button(renameX, y, strike ? 34 : actionWidth, 17, "改名", false, selected != null, () -> {
            String newName = strike ? strikeNameText : nameText;
            if (newName.isBlank()) {
                reply = "请输入标定点名称";
                return;
            }
            send(TerminalLinkPayload.Cmd.MARK_RENAME, selected.name(), newName.strip(), 0);
        }, "");
        button(deleteX, y, strike ? 47 : actionWidth, 17, deleteMark == null ? "删除" : "确认删除",
                deleteMark != null, selected != null, () -> {
                    if (!selected.equals(deleteMark)) {
                        deleteMark = selected;
                        reply = "再次点击确认删除 · " + selected.name();
                        rebuildWidgets();
                    } else {
                        send(TerminalLinkPayload.Cmd.MARK_REMOVE, selected.name(), "", 0);
                        deleteMark = null;
                        rebuildWidgets();
                    }
                }, "");
    }

    private void showStrikeView(StrikeView view) {
        changeStrikeView(view);
        if (bound() && strikeRequest == null) {
            if (view == StrikeView.CONFIGURATION && strikeRoster == null) requestStrikeChannels(false);
            if (view == StrikeView.QUICK_SETTINGS && quickRoster == null) requestStrikeChannels(true);
        }
        rebuildWidgets();
    }

    private void changeStrikeView(StrikeView view) {
        strikeView = view;
        deleteMark = null;
        clearStrikeResults();
        if (pendingStrikeOperation != StrikeOperation.EXECUTE && pendingStrikeOperation != StrikeOperation.CANCEL) {
            clearStrikeRequest();
            reply = "";
        }
    }

    private void initStrike() {
        switch (strikeView) {
            case TARGETS -> {
                button(panelWidth - 149, BODY_Y, 68, 17, "快捷设置", false, true,
                        () -> showStrikeView(StrikeView.QUICK_SETTINGS), "设置开镜快捷打击的默认频道");
                cancelStrikeButton(panelWidth - 78, BODY_Y);
                initMarkList(true);
            }
            case CONFIGURATION -> initStrikeConfiguration();
            case PREVIEW -> {
                strikeBackButton("返回配置", StrikeView.CONFIGURATION);
                resultScroll = Math.clamp(resultScroll, 0, Math.max(0, strikeRows.size() - visibleResults()));
                button(10, listActionY(), 75, 17, "确认执行", strikeConfirmation != null,
                        strikeRequest == null && strikeConfirmation != null && bound(),
                        () -> sendStrikeCommand(StrikeOperation.EXECUTE), "按本次预瞄结果提交一轮打击");
                cancelStrikeButton(panelWidth - 78, listActionY());
            }
            case QUICK_SETTINGS -> initQuickSettings();
        }
    }

    private void initStrikeConfiguration() {
        strikeBackButton("返回目标", StrikeView.TARGETS);
        boolean ready = bound() && strikeRequest == null;
        String channel = strikeRoster == null ? "频道未读取" : strikeRoster.channels().get(strikeChannel);
        strikeChannelPicker(channel, strikeRoster != null && strikeRoster.channels().size() > 1, this::selectStrikeChannel);
        if (strikeRoster != null && !strikeRoster.axialChannel(strikeChannel)) {
            button(10, 123, 98, 16, "允许转舰 · " + (allowHullTurn ? "开" : "关"), allowHullTurn, ready, () -> {
                allowHullTurn = !allowHullTurn;
                rebuildWidgets();
            }, "当前舰姿无可用炮塔时，搜索可用舰姿");
        }
        button(10, listActionY(), 75, 17, "开始预瞄", true, ready && strikeRoster != null, this::previewStrike, "");
        cancelStrikeButton(88, listActionY());
        refreshChannelsButton(false);
    }

    private void strikeBackButton(String label, StrikeView destination) {
        button(panelWidth - 78, BODY_Y, 68, 17, label, false, true, () -> showStrikeView(destination), "");
    }

    private void cancelStrikeButton(int x, int y) {
        button(x, y, 66, 17, "取消打击", false, strikeRequest == null && bound(),
                () -> sendStrikeCommand(StrikeOperation.CANCEL), "取消本终端发起的打击");
    }

    private StrikePreview.Mode requestedStrikeMode() {
        if (strikeRoster != null && strikeRoster.axialChannel(strikeChannel)) return StrikePreview.Mode.AXIS_BEAM;
        return allowHullTurn ? StrikePreview.Mode.TURRET_POSE : StrikePreview.Mode.TURRET;
    }

    private void selectStrikeChannel(int step) {
        strikeChannel = Math.floorMod(strikeChannel + step, strikeRoster.channels().size());
        reply = "";
        rebuildWidgets();
    }

    private void clearStrikeResults() {
        strikeConfirmation = null;
        strikeRows = List.of();
        strikeSummary = "";
        previewChannel = "";
        resultScroll = 0;
    }

    private void clearStrikeRequest() {
        strikeRequest = null;
        pendingStrikeOperation = null;
    }

    private void previewStrike() {
        if (!bound() || strikeMark == null || strikeRequest != null || strikeRoster == null) return;
        clearStrikeResults();
        strikeView = StrikeView.PREVIEW;
        previewChannel = strikeRoster.channels().get(strikeChannel) + " · " + strikeChannelType(strikeRoster, strikeChannel);
        strikeSummary = "正在预瞄…";
        reply = "";
        sendStrikePacket(UUID.randomUUID(), StrikeOperation.PREVIEW, strikeChannel);
        rebuildWidgets();
    }

    private void sendStrikeCommand(StrikeOperation operation) {
        if (!bound() || strikeRequest != null) return;
        boolean execute = operation == StrikeOperation.EXECUTE;
        UUID request = execute ? strikeConfirmation : UUID.randomUUID();
        if (request == null || (execute && (strikeRoster == null || strikeMark == null))) return;
        strikeConfirmation = null;
        sendStrikePacket(request, operation, strikeChannel);
        reply = execute ? "正在提交打击…" : "正在取消打击…";
        rebuildWidgets();
    }

    private void sendStrikePacket(UUID request, StrikeOperation operation, int channel) {
        strikeRequest = request;
        pendingStrikeOperation = operation;
        strikeDeadline = screenTick + 100;
        PacketDistributor.sendToServer(new TerminalStrikeRequest(terminal, request,
                strikeRoster == null ? BlockPos.ZERO : strikeRoster.core(),
                strikeRoster == null ? 0 : strikeRoster.revision(), strikeMark == null ? "" : strikeMark.name(),
                channel, requestedStrikeMode().wireId, operation.action));
    }

    private void restoreSettings() {
        settingText[0] = Double.toString(saved.cruiseSpeed());
        settingText[1] = Double.toString(saved.turnDegrees());
        settingText[2] = Double.toString(saved.nudgeTenths() / 10.0);
    }

    private void initSettings() {
        addSetting(0, 75, "巡航速度", "m/s", NavigationController.MIN_TERMINAL_SPEED, NavigationController.MAX_SPEED, saved.cruiseSpeed());
        addSetting(1, 98, "舰首转向率", "°/s", CruiseHeading.MIN_RATE_DEGREES, CruiseHeading.MAX_RATE_DEGREES, saved.turnDegrees());
        addSetting(2, 121, "微调长按速度", "m/s", RemoteManeuver.MIN_SPEED_TENTHS / 10.0,
                RemoteManeuver.MAX_SPEED_TENTHS / 10.0, saved.nudgeTenths() / 10.0);
        button(10, 146, 77, 17, "保存飞行参数", true, terminal != null, this::saveSettings, "设置随终端物品保存");
        button(90, 146, 75, 17, "恢复默认", false, true, () -> {
            var defaults = NavTerminalData.Settings.DEFAULT;
            settingText[0] = Double.toString(defaults.cruiseSpeed());
            settingText[1] = Double.toString(defaults.turnDegrees());
            settingText[2] = Double.toString(defaults.nudgeTenths() / 10.0);
            rebuildWidgets();
        }, "恢复默认值后点保存");
    }

    private void requestStrikeChannels(boolean quick) {
        sendStrikePacket(UUID.randomUUID(), quick ? StrikeOperation.QUICK_CHANNELS : StrikeOperation.CHANNELS, -1);
        reply = "正在读取火控频道…";
    }

    private void refreshChannelsButton(boolean quick) {
        button(panelWidth - 78, listActionY(), 68, 17, "刷新频道", false, bound() && strikeRequest == null, () -> {
            requestStrikeChannels(quick);
            rebuildWidgets();
        }, "");
    }

    private int strikeChannelWidth() { return (panelWidth - 23) / 2; }

    private void strikeChannelPicker(String label, boolean canCycle, IntConsumer select) {
        String tip = canCycle ? label + " · 滚轮或点击切换频道 · Shift 点击反向切换" : label;
        button(10, STRIKE_CHANNEL_Y, strikeChannelWidth(), 16, label, false,
                canCycle && strikeRequest == null && bound(), () -> select.accept(hasShiftDown() ? -1 : 1), tip);
    }

    private void initQuickSettings() {
        strikeBackButton("返回目标", StrikeView.TARGETS);
        boolean ready = bound() && strikeRequest == null;
        String channel = quickChannel < 0 ? "无默认频道"
                : quickRoster != null ? quickRoster.channels().get(quickChannel)
                : quickProfile == null ? "未读取" : quickProfile.name();
        strikeChannelPicker(channel, quickRoster != null, this::selectQuickChannel);
        if (quickChannel >= 0 && quickRoster != null && !quickRoster.axialChannel(quickChannel)) {
            button(10, 123, 98, 16, "允许转舰 · " + (quickTurn ? "开" : "关"), quickTurn, ready, () -> {
                quickTurn = !quickTurn;
                rebuildWidgets();
            }, "当前舰姿无可用炮塔时，搜索可用舰姿");
        }
        button(10, listActionY(), 94, 17, "保存快捷配置", true, ready
                && (quickChannel == -1 || quickRoster != null), () -> {
            String name = quickChannel == -1 ? "" : quickRoster.channels().get(quickChannel);
            send(TerminalLinkPayload.Cmd.SAVE_QUICK_STRIKE, name, quickTurn ? "turn" : "turret", quickChannel);
        }, "");
        refreshChannelsButton(true);
    }

    private void validateQuickChannel() {
        if (quickRoster == null || quickChannel < 0) return;
        if (quickChannel >= quickRoster.channels().size()
                || quickProfile != null && quickChannel == quickProfile.channel()
                && !quickRoster.channels().get(quickChannel).equals(quickProfile.name())) quickChannel = -1;
    }

    private void selectQuickChannel(int step) {
        quickChannel = Math.floorMod(quickChannel + 1 + step, quickRoster.channels().size() + 1) - 1;
        rebuildWidgets();
    }

    private void addSetting(int index, int y, String label, String units, double min, double max, double fallback) {
        var field = editBox(panelWidth - 72, y + 1, 62, label + " " + units, settingText[index], 14);
        double initial = parse(settingText[index]);
        var slider = new SettingSlider(left + 10, top + y, panelWidth - 87, label, units, min, max,
                initial >= min && initial <= max ? initial : fallback, field);
        field.setResponder(value -> {
            settingText[index] = value;
            slider.sync(parse(value));
        });
        addRenderableWidget(slider);
    }

    private void saveSettings() {
        double speed = parse(settingText[0]), turn = parse(settingText[1]), nudge = parse(settingText[2]);
        var settings = new NavTerminalData.Settings(speed, turn, (int) Math.round(nudge * 10));
        if (!settings.valid() || !(nudge >= RemoteManeuver.MIN_SPEED_TENTHS / 10.0 && nudge <= RemoteManeuver.MAX_SPEED_TENTHS / 10.0)) {
            reply = "参数超出允许范围，悬停滑条查看范围";
            return;
        }
        send(TerminalLinkPayload.Cmd.SAVE_SETTINGS, Double.toString(speed), Double.toString(turn), settings.nudgeTenths());
    }

    private void submitCoordinates() {
        double x = parse(coordinateText[0]), y = parse(coordinateText[1]), z = parse(coordinateText[2]);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            reply = "请输入有效的世界坐标 X、Y、Z";
            return;
        }
        submitCruise(null, new Vec3(x, y, z));
    }

    private void submitCruise(@Nullable String mark, @Nullable Vec3 position) {
        if (!bound()) return;
        PacketDistributor.sendToServer(new TerminalCruisePayload(terminal, mark, position,
                saved.cruiseSpeed(), Math.toRadians(saved.turnDegrees())));
    }

    private void send(TerminalLinkPayload.Cmd command, String name, String extra, long value) {
        if (terminal == null) {
            reply = "导航终端初始化中，请再试一次";
            return;
        }
        PacketDistributor.sendToServer(new TerminalLinkPayload(TerminalLinkPayload.PROTOCOL_VERSION,
                command.ordinal(), terminal, name, extra, value, "", false));
    }

    private EditBox editBox(int x, int y, int w, String label, String value, int maxLength) {
        var field = new EditBox(font, left + x, top + y, w, 17, Component.literal(label));
        field.setMaxLength(maxLength);
        field.setHint(Component.literal(label));
        field.setValue(value == null ? "" : value);
        return addRenderableWidget(field);
    }

    private void button(int x, int y, int w, int h, String label, boolean selected, boolean enabled, Runnable action, String tip) {
        var button = new PanelButton(left + x, top + y, w, h, Component.literal(label), selected, b -> action.run());
        button.active = enabled;
        if (!tip.isEmpty()) button.setTooltip(Tooltip.create(Component.literal(tip)));
        addRenderableWidget(button);
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // The terminal draws its own dimmer and case before widgets.
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(0, 0, width, height, 0x89070B11);
        gui.fill(left, top, left + panelWidth, top + panelHeight, BG);
        gui.renderOutline(left, top, panelWidth, panelHeight, LINE);
        gui.fill(left + 1, top + 1, left + panelWidth - 1, top + 30, PANEL);
        gui.fill(left + 10, top + 8, left + 13, top + 23, bound() ? CYAN : AMBER);
        draw(gui, "导航终端", 19, 8, TEXT, 100);
        draw(gui, bound() ? "已绑定飞控" : "未绑定飞控", 19, 19, bound() ? DIM : AMBER, 104);
        String state = !bound() ? "潜行右键飞控核心绑定"
                : statusReceivedTick < 0 ? (screenTick - statusStartedTick < 100 ? "正在读取飞船状态…" : "未收到飞船状态")
                : screenTick - statusReceivedTick > 120 ? "飞船状态未更新" : shipStatus;
        draw(gui, state, 120, 14, bound() ? CYAN : DIM, panelWidth - 130);
        gui.fill(left + 1, top + footer, left + panelWidth - 1, top + panelHeight - 1, PANEL);
        gui.fill(left + 1, top + footer, left + panelWidth - 1, top + footer + 1, LINE);
        if (panelHeight < 190 || panelWidth < 235) {
            draw(gui, "请调低 GUI 缩放", 10, 49, AMBER, panelWidth - 20);
            super.render(gui, mouseX, mouseY, partialTick);
            return;
        }
        super.render(gui, mouseX, mouseY, partialTick);
        draw(gui, reply, 10, footer + 7, AMBER, panelWidth - 20);
        switch (page) {
            case NAVIGATION -> {
                if (coordinates) {
                    int column = (panelWidth - 26) / 3;
                    for (int i = 0; i < AXES.length; i++) draw(gui, AXES[i], 10 + i * (column + 3), 110, DIM, column);
                    if (footer > 179) draw(gui, "世界坐标 · Y 为目标高度", 10, 171, DIM, panelWidth - 20);
                } else drawMarks(gui, false);
            }
            case STRIKE -> drawStrike(gui, mouseX, mouseY);
            case SETTINGS -> draw(gui, "本终端的飞行参数", 10, 62, DIM, panelWidth - 20);
        }
        if (within(mouseX, mouseY, 117, 3, panelWidth - 125, 25) && bound()) {
            tooltip(gui, state + " · " + coreDimension + " · " + core.toShortString(), mouseX, mouseY);
        } else if (within(mouseX, mouseY, 8, footer, panelWidth - 16, 21) && !reply.isEmpty()) {
            tooltip(gui, reply, mouseX, mouseY);
        }
    }

    private void drawMarks(GuiGraphics gui, boolean strike) {
        var list = strike ? strikeMarks : navigationMarks;
        int scroll = strike ? strikeMarkScroll : navScroll;
        if (list.isEmpty()) {
            if (!strike) draw(gui, "暂无导航标定点", 12, listY(false) + 4, DIM, panelWidth - 24);
        }
        for (int i = scroll; i < Math.min(list.size(), scroll + visibleMarks(strike)); i++) {
            var mark = list.get(i);
            int y = listY(strike) + (i - scroll) * ROW_HEIGHT + 3;
            int middle = panelWidth / 2;
            draw(gui, mark.name(), 17, y, mark.equals(strike ? strikeMark : navMark) ? CYAN : TEXT, middle - 22);
            draw(gui, mark.pos().toShortString(), middle, y, DIM, panelWidth - middle - 22);
        }
    }

    private void drawStrike(GuiGraphics gui, int mouseX, int mouseY) {
        int middle = panelWidth / 2;
        switch (strikeView) {
            case TARGETS -> {
                draw(gui, "打击目标", 10, BODY_Y + 5, CYAN, panelWidth - 164);
                if (strikeMarks.isEmpty()) {
                    draw(gui, "潜行右键开镜，左键标定打击点", 10, 85, DIM, panelWidth - 20);
                } else {
                    draw(gui, "标定点", 10, 84, DIM, middle - 14);
                    draw(gui, "世界坐标", middle, 84, DIM, middle - 10);
                    drawMarks(gui, true);
                }
            }
            case CONFIGURATION -> {
                draw(gui, "配置打击", 10, BODY_Y + 5, CYAN, panelWidth - 96);
                drawStrikeTarget(gui, mouseX, mouseY, panelWidth - 20);
                draw(gui, "武器频道", 10, 94, DIM, strikeChannelWidth());
                drawStrikeChannelType(gui, strikeRoster, strikeChannel);
            }
            case PREVIEW -> {
                draw(gui, "预瞄结果", 10, BODY_Y + 5, CYAN, panelWidth - 96);
                drawStrikeTarget(gui, mouseX, mouseY, middle - 14);
                draw(gui, previewChannel, middle, 82, TEXT, panelWidth - middle - 10);
                if (within(mouseX, mouseY, middle, 80, panelWidth - middle - 10, 11)) {
                    tooltip(gui, previewChannel, mouseX, mouseY);
                }
                drawStrikeResults(gui, mouseX, mouseY);
            }
            case QUICK_SETTINGS -> {
                draw(gui, "快捷打击设置", 10, BODY_Y + 5, CYAN, panelWidth - 96);
                String key = NavTerminalQuickStrike.KEY.getTranslatedKeyMessage().getString();
                String help = "开镜后滚轮换台，按 " + key + " 预瞄和确认";
                draw(gui, help, 10, 82, DIM, panelWidth - 20);
                if (within(mouseX, mouseY, 10, 80, panelWidth - 20, 12)) {
                    tooltip(gui, "开镜后滚轮换台。第一次按 " + key + " 预瞄，第二次确认。", mouseX, mouseY);
                }
                draw(gui, "默认频道", 10, 94, DIM, strikeChannelWidth());
                drawStrikeChannelType(gui, quickRoster, quickChannel);
            }
        }
    }

    private void drawStrikeTarget(GuiGraphics gui, int mouseX, int mouseY, int maxWidth) {
        if (strikeMark == null) return;
        String target = strikeMark.name() + " · " + strikeMark.pos().toShortString();
        draw(gui, target, 10, 82, TEXT, maxWidth);
        if (within(mouseX, mouseY, 10, 80, maxWidth, 11)) {
            tooltip(gui, target + " · " + strikeMark.dim(), mouseX, mouseY);
        }
    }

    private static String strikeChannelType(TerminalStrikeSnapshot roster, int channel) {
        return roster.axialChannel(channel) ? "轴炮直射" : "转塔炮击";
    }

    private void drawStrikeChannelType(GuiGraphics gui, @Nullable TerminalStrikeSnapshot roster, int channel) {
        if (roster != null && channel >= 0) {
            int x = 15 + strikeChannelWidth();
            draw(gui, strikeChannelType(roster, channel), x, STRIKE_CHANNEL_Y + 4, TEXT, panelWidth - x - 10);
        }
    }

    private void drawStrikeResults(GuiGraphics gui, int mouseX, int mouseY) {
        var lines = font.split(Component.literal(strikeSummary), panelWidth - 20);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            gui.drawString(font, lines.get(i), left + 10, top + 92 + i * 9, CYAN, false);
        }
        if (within(mouseX, mouseY, 10, 91, panelWidth - 20, 19) && !strikeSummary.isEmpty()) {
            tooltip(gui, strikeSummary, mouseX, mouseY);
        }
        if (strikeRows.isEmpty()) return;
        int split = panelWidth / 2;
        int end = Math.min(strikeRows.size(), resultScroll + visibleResults());
        draw(gui, "炮位", 10, STRIKE_RESULTS_Y - 11, DIM, split - 14);
        String verdict = end - resultScroll < strikeRows.size()
                ? "判定 · " + (resultScroll + 1) + "–" + end + "/" + strikeRows.size() : "预瞄判定";
        draw(gui, verdict, split, STRIKE_RESULTS_Y - 11, DIM, panelWidth - split - 10);
        for (int i = resultScroll; i < end; i++) {
            var row = strikeRows.get(i);
            int y = STRIKE_RESULTS_Y + (i - resultScroll) * RESULT_ROW_HEIGHT;
            String label = row.weapon().isEmpty() ? row.name() : row.name() + " · " + row.weapon();
            draw(gui, label, 10, y, TEXT, split - 14);
            draw(gui, row.reason(), split, y, row.eligible() ? GREEN : AMBER, panelWidth - split - 10);
            if (within(mouseX, mouseY, 10, y, panelWidth - 20, RESULT_ROW_HEIGHT)) {
                tooltip(gui, label + " · " + row.reason(), mouseX, mouseY);
            }
        }
    }

    private int visibleResults() { return Math.max(0, (listActionY() - STRIKE_RESULTS_Y) / RESULT_ROW_HEIGHT); }

    private boolean within(double mx, double my, int x, int y, int w, int h) {
        return mx >= left + x && mx < left + x + w && my >= top + y && my < top + y + h;
    }

    private void draw(GuiGraphics gui, String value, int x, int y, int color, int maxWidth) {
        gui.drawString(font, font.plainSubstrByWidth(value, maxWidth), left + x, top + y, color, false);
    }

    private void tooltip(GuiGraphics gui, String value, int x, int y) {
        gui.renderTooltip(font, font.split(Component.literal(value), panelWidth - 18), x, y);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int step = (int) Math.signum(scrollY);
        if (step == 0) return super.mouseScrolled(x, y, scrollX, scrollY);
        boolean strike = page == Page.STRIKE;
        if (strike && bound() && strikeRequest == null && within(x, y, 10, STRIKE_CHANNEL_Y, strikeChannelWidth(), 16)) {
            if (strikeView == StrikeView.CONFIGURATION && strikeRoster != null && strikeRoster.channels().size() > 1) {
                selectStrikeChannel(-step);
                return true;
            }
            if (strikeView == StrikeView.QUICK_SETTINGS && quickRoster != null) {
                selectQuickChannel(-step);
                return true;
            }
        }
        if (strike && strikeView == StrikeView.PREVIEW
                && within(x, y, 9, STRIKE_RESULTS_Y - 2, panelWidth - 18, listActionY() - STRIKE_RESULTS_Y + 2)) {
            resultScroll = Math.clamp(resultScroll - step, 0, Math.max(0, strikeRows.size() - visibleResults()));
            return true;
        }
        if ((strike && strikeView == StrikeView.TARGETS || page == Page.NAVIGATION && !coordinates)
                && within(x, y, 9, listY(strike), panelWidth - 18, listActionY() - listY(strike))) {
            if (strike) strikeMarkScroll -= step;
            else navScroll -= step;
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override public boolean isPauseScreen() { return false; }

    private static double parse(String value) {
        try { return Double.parseDouble(value); }
        catch (NumberFormatException ignored) { return Double.NaN; }
    }

    private final class PanelButton extends Button {
        private final boolean selected;

        PanelButton(int x, int y, int w, int h, Component text, boolean selected, OnPress action) {
            super(x, y, w, h, text, action, DEFAULT_NARRATION);
            this.selected = selected;
        }

        @Override
        protected void renderWidget(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY();
            boolean hover = active && isHoveredOrFocused();
            gui.fill(x, y, x + width, y + height, selected ? 0xFF203B4A : hover ? 0xFF233549 : PANEL);
            gui.renderOutline(x, y, width, height, hover || selected && active ? CYAN : LINE);
            var caption = font.plainSubstrByWidth(getMessage().getString(), width - 6);
            gui.drawString(font, caption, x + (width - font.width(caption)) / 2,
                    y + (height - 8) / 2, !active ? DIM : selected ? CYAN : TEXT, false);
        }
    }

    private static final class SettingSlider extends AbstractSliderButton {
        private final String label, units;
        private final double min, max;
        private final EditBox field;

        SettingSlider(int x, int y, int width, String label, String units, double min, double max, double initial, EditBox field) {
            super(x, y, width, 19, Component.empty(), (initial - min) / (max - min));
            this.label = label;
            this.units = units;
            this.min = min;
            this.max = max;
            this.field = field;
            setTooltip(Tooltip.create(Component.literal(min + "–" + max + " " + units)));
            updateMessage();
        }

        void sync(double number) {
            if (number >= min && number <= max) {
                value = (number - min) / (max - min);
                updateMessage();
            }
        }

        private String number() { return String.format(Locale.ROOT, "%.1f", min + (max - min) * value); }

        @Override protected void updateMessage() { setMessage(Component.literal(label + "  " + number() + " " + units)); }
        @Override protected void applyValue() { field.setValue(number()); }

        @Override
        public void renderWidget(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY();
            gui.fill(x, y, x + width, y + height, PANEL);
            gui.renderOutline(x, y, width, height, isHoveredOrFocused() ? CYAN : LINE);
            int end = x + 2 + (int) Math.round((width - 4) * value);
            gui.fill(x + 2, y + height - 4, end, y + height - 2, CYAN);
            var font = Minecraft.getInstance().font;
            gui.drawString(font, getMessage(), x + 5, y + 4, TEXT, false);
        }
    }
}
