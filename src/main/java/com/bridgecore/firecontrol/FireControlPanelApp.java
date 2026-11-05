package com.bridgecore.firecontrol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import com.bridgecore.display.Canvas;
import com.bridgecore.display.DisplayApp;
import com.bridgecore.display.DisplayGpuBlockEntity;
import com.bridgecore.display.DisplayResolution;
import com.bridgecore.firecontrol.ballistics.Quat;
import com.bridgecore.firecontrol.ballistics.Vec3;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Fire-control panel — the Java-port control center (contract: shared
 * handbook §2.25). Modern CIC console layout (2026-09-27 user-approved
 * scheme, landscape 768×512 baseline (standard 3×2 at 256/tile)): a top status bar (title + global
 * readouts + page tabs), the bow-up tactical scope as the main area, a
 * full-height track list on the right, and a bottom toolbar (filter
 * segments + range slider with steppers).
 *
 * <p>Pages:
 *
 * <p>[状态] — ship line + fire-gate chip, track line, hairline-separated
 * turret table (炮塔/状态/目标/误差/射界). Management stays in the internal
 * Menu/Screen GUI.
 *
 * <p>[雷达] — scope left (dotted range rings, bearing ticks, rotating
 * sweep, pixel-dot tracks with velocity vectors on the marked tracks, precise
 * lock = red box + CJK data tag, console-selected = cyan box), track list
 * right (dot + name + right-aligned distance, header shows 已选 m/N),
 * toolbar bottom (type filter segments, scan-range slider 16..1024).
 * Tap a track or a row = toggle it in the ACTIVE channel's console selection
 * (m/N, 满则提示不覆盖 — 频道任务协议 v1); tap empty = clear the selection;
 * sneak-tap = whitelist.
 *
 * <p>Typography per the flight-control GUI discipline (exchange log
 * 2026-09-27): three tiers only — CJK 24 title, CJK 12 body (512/tile: 36/24), 5×7 ASCII for
 * tiny hints; selection = frame/bg inversion, never colored text alone;
 * fixed 16px row grid. Colors come from the custom 16-entry palette below
 * (semantic discipline: one color = one meaning).
 *
 * <p>Data path per the contract's open-item ruling: the app reads the fire
 * control core itself (zero-link via findFor); render and clicks both run on
 * the server thread.
 */
public final class FireControlPanelApp implements DisplayApp {

    // ---- semantic palette: custom 16-entry table (display-layer per-canvas
    //      palettes, delivered by flight-control 2026-09-27); index 0 keeps
    //      the transparent semantic. Deep-navy CIC scheme. ----
    private static final int[] FC_PALETTE = {
            0x00000000, // 0 transparent
            0xFF0B1220, // 1 background deep navy
            0xFF14203A, // 2 panel band
            0xFF2A3B55, // 3 hairline
            0xFF1D2C45, // 4 scope grid (subtler than hairline)
            0xFF3FD4FF, // 5 primary cyan
            0xFFFFE14D, // 6 land-animal yellow (MOBS view hash color)
            0xFFE8F1F8, // 7 text
            0xFF7E90A8, // 8 dim text
            0xFFFF4D5E, // 9 hostile red / lock
            0xFFFFB340, // 10 amber
            0xFF3DFF8C, // 11 friendly green (player only)
            0xFFFF8C42, // 12 monster orange
            0xFF4D8DFF, // 13 ship blue
            0xFF2E8FB8, // 14 sweep cyan-dim
            0xFFFF55FF, // 15 magenta
    };
    private static final int C_BG = 1;
    private static final int C_PANEL = 2;
    private static final int C_HAIR = 3;
    private static final int C_GRID = 4;
    private static final int C_PRI = 5;
    private static final int C_LAND = 6;
    private static final int C_TEXT = 7;
    private static final int C_DIM = 8;
    private static final int C_HOSTILE = 9;
    private static final int C_WARN = 10;
    private static final int C_FRIEND = 11;
    private static final int C_SHIP = 12;
    private static final int C_SHIPBLUE = 13;
    private static final int C_SWEEP = 14;
    private static final int C_MAGENTA = 15;

    /** Track hit radius in px, the Lua clickDis <= 5 scaled up for touch. */
    private static final int DOT_HIT = 8;
    /** Scan-radius slider bounds (mirrors the core's NBT clamp). */
    private static final double RANGE_MIN = 16, RANGE_MAX = 1024;
    /** Slider stepper multiplier. */
    private static final double RANGE_STEP = 1.25;

    private int uiScale = 1, bodyFont = 12, titleFont = 24;
    private int STATUS_ROW = 34, MEMBER_ROW = 30, ROW = 16, MARGIN = 6, TOPBAR = 54;
    private int px(int value) { return value * uiScale; }
    private void density(Canvas canvas) {
        int resolution = level != null && gpuPos != null && level.getBlockEntity(gpuPos) instanceof DisplayGpuBlockEntity gpu
                ? gpu.tileResolution() : 256;
        uiScale = resolution >= 512 && canvas.width() >= 960 && canvas.height() >= 640 ? 2 : 1;
        bodyFont = px(12); titleFont = uiScale == 2 ? 36 : 24;
        STATUS_ROW = px(34); MEMBER_ROW = px(30); ROW = px(16); MARGIN = px(6); TOPBAR = px(54);
    }

    /** Type filter segments (the four Target categories + 全部). */
    private static final String[] FILTER_LABELS = {"全部", "船", "玩家", "怪物", "生物"};

    // ---- per-frame binding (GPU -> app, server thread) ----
    private @Nullable Level level;
    private @Nullable ServerSubLevel sub;
    private @Nullable BlockPos gpuPos;
    /** Core resolved by the last render pass; null when none matches. */
    private @Nullable FireControlCoreBlockEntity core;

    /** Category filter index into FILTER_LABELS (panel-local, display only). */
    private int filterIdx;
    /** Rotating sweep bearing, degrees from bow; visual only. */
    private int sweepDeg;

    // ---- click map of the last rendered radar page (server thread only) ----
    private final List<String> rowTargetIds = new ArrayList<>();
    private int listY0 = -1;
    private int listX0, listX1;

    // ---- radar page widget geometry of the last render ----
    private int scopeCx, scopeCy, scopeR;
    private double scopePpb;          // pixels per block
    private double[] scopeCenter;     // world
    private @Nullable Quat scopeRot;
    private final int[] segX0 = new int[FILTER_LABELS.length];
    private final int[] segX1 = new int[FILTER_LABELS.length];
    private int toolbarY0 = -1, toolbarY1, rangeY0, rangeY1;
    private int sliderX0, sliderX1, minusX0, minusX1, plusX0, plusX1;

    // ---- page/tab state (last render); 雷达 is the main page ----
    private static final int PAGE_RADAR = 0, PAGE_STATUS = 1, PAGE_SETTINGS = 2, PAGE_PREFS = 3;
    private int pageIdx = PAGE_RADAR;
    private int tabY0 = -1, tabY1 = -1;
    private int tabStatusX0, tabStatusX1, tabRadarX0, tabRadarX1, tabSettingsX0, tabSettingsX1;
    private int tabPrefsX0, tabPrefsX1;

    /** Radar orientation (settings page): 0=北 1=东 2=南 3=西 up. */
    private int orientIdx;
    private final int[] orientX0 = new int[4];
    private final int[] orientX1 = new int[4];
    private int orientY0 = -1, orientY1;

    /** Channel page (settings tab) click map of the last render. */
    private final int[] chTabX0 = new int[FireControlCoreBlockEntity.CHANNEL_COUNT];
    private final int[] chTabX1 = new int[FireControlCoreBlockEntity.CHANNEL_COUNT];
    private int chTabY0 = -1, chTabY1;
    private int chAutoX0, chAutoX1, chAutoY0 = -1, chAutoY1;
    /** 自动升格 toggle (火力指挥层④), same row as 自动开火. */
    private int chPromoteX0, chPromoteX1;
    private int memberY0 = -1, memberRows, memberX1;
    /** Member-list paging (炮塔多于页容量时): current page, page count, the
     *  row index of the first row on the current page, pager segment rect. */
    private int memberPage, memberPages = 1, memberRowBase;
    private int pagerX0, pagerX1, pagerY0 = -1, pagerY1;

    /** Status-page cadence steppers: [x0,x1] per rendered turret row (each
     *  edits only the displayed turret). */
    private int stRowY0 = -1;
    private int statusPage, statusPages = 1, statusPagerY0 = -1;
    private int renderedChannel, lastCanvasWidth;
    private final List<java.util.UUID> statusTurretIds = new ArrayList<>();
    private final List<java.util.UUID> memberTurretIds = new ArrayList<>();
    private final List<Integer> memberChannels = new ArrayList<>();
    private final List<int[]> stMinusX = new ArrayList<>();
    private final List<int[]> stPlusX = new ArrayList<>();
    private static final String[] ORIENT_LABELS = {"北", "东", "南", "西"};

    /** Identity guard so setPalette runs once per canvas instance. */
    private @Nullable Canvas paletteOwner;
    private int resolutionY0 = -1, resolutionY1;
    private final int[] resolutionX0 = new int[3], resolutionX1 = new int[3];
    private static final int[] RESOLUTIONS = {128, 256, 512};

    /** Called by the GPU every frame before render(). */
    @Override
    public void bind(Level level, @Nullable ServerSubLevel sub, BlockPos gpuPos) {
        this.level = level;
        this.sub = sub;
        this.gpuPos = gpuPos;
    }

    @Override
    public void render(Canvas canvas, DisplayContext ctx) {
        density(canvas);
        canvas.clear();
        resolutionY0 = -1;
        rowTargetIds.clear();
        listY0 = -1;
        scopeR = 0;
        toolbarY0 = -1;
        orientY0 = -1;
        chTabY0 = -1;
        chAutoY0 = -1;
        memberY0 = -1;
        memberRows = 0;
        pagerY0 = -1;
        stRowY0 = -1;
        statusPagerY0 = -1;
        statusTurretIds.clear();
        memberTurretIds.clear();
        memberChannels.clear();
        stMinusX.clear();
        stPlusX.clear();
        int w = canvas.width();
        lastCanvasWidth = w;
        int h = canvas.height();
        if (paletteOwner != canvas) {
            canvas.setPalette(FC_PALETTE);
            paletteOwner = canvas;
        }
        canvas.fillRect(0, 0, w, h, C_BG);

        core = level != null && gpuPos != null
                ? FireControlCoreBlockEntity.findFor(level, sub, gpuPos)
                : null;
        if (core == null) {
            canvas.drawRect(2, 2, w - 4, h - 4, C_HOSTILE);
            canvas.drawCjkText((w - canvas.cjkTextWidth("无火控核心", titleFont)) / 2,
                    h / 2 - titleFont / 2, "无火控核心", C_HOSTILE, titleFont);
            return;
        }

        if (w < 240 || h < 180) {
            canvas.drawCjkText(MARGIN, 12, "火控控制屏", C_TEXT, 16);
            canvas.drawCjkText(MARGIN, 40, "提高单屏分辨率", C_WARN, bodyFont);
            canvas.drawCjkText(MARGIN, 58, "或扩大拼屏", C_DIM, bodyFont);
            canvas.drawCjkText(MARGIN, 76, "画布建议至少240x180", C_DIM, bodyFont);
            tabY0 = -1;
            return;
        }
        renderedChannel = core.activeChannel();
        renderTopBar(canvas, w);

        switch (pageIdx) {
            case PAGE_STATUS -> renderStatusPage(canvas, w, h);
            case PAGE_SETTINGS -> renderSettingsPage(canvas, w, h);
            case PAGE_PREFS -> renderPrefsPage(canvas, w, h);
            default -> renderRadarPage(canvas, w, h);
        }
    }

    // ---------------- top bar: title + global readouts + page tabs ----------------

    private void renderTopBar(Canvas canvas, int w) {
        canvas.fillRect(0, 0, w, TOPBAR, C_PANEL);
        canvas.drawCjkText(MARGIN, px(3), "火控控制屏", C_TEXT, titleFont);
        String context = fit(core.channelName(core.activeChannel()), 5) + " · " + (int) core.scanRadius() + "M";
        canvas.drawCjkText(w - MARGIN - canvas.cjkTextWidth(context, bodyFont), px(9), context, C_DIM, bodyFont);
        int gap = px(3), tabW = Math.min(px(112), (w - MARGIN * 2 - gap * 3) / 4);
        tabY0 = px(32); tabY1 = tabY0 + px(18);
        tabRadarX0 = MARGIN; tabRadarX1 = tabRadarX0 + tabW;
        tabStatusX0 = tabRadarX1 + gap; tabStatusX1 = tabStatusX0 + tabW;
        tabSettingsX0 = tabStatusX1 + gap; tabSettingsX1 = tabSettingsX0 + tabW;
        tabPrefsX0 = tabSettingsX1 + gap; tabPrefsX1 = tabPrefsX0 + tabW;
        drawSegment(canvas, tabRadarX0, tabY0, tabW, px(18), "雷达", pageIdx == PAGE_RADAR);
        drawSegment(canvas, tabStatusX0, tabY0, tabW, px(18), "状态", pageIdx == PAGE_STATUS);
        drawSegment(canvas, tabSettingsX0, tabY0, tabW, px(18), "频道", pageIdx == PAGE_SETTINGS);
        drawSegment(canvas, tabPrefsX0, tabY0, tabW, px(18), "设置", pageIdx == PAGE_PREFS);
        canvas.hLine(MARGIN, w - MARGIN - 1, TOPBAR, C_HAIR);
    }

    private void drawSegment(Canvas canvas, int x, int y, int w, int h,
                                    String label, boolean active) {
        if (active) {
            canvas.fillRect(x, y, w, h, C_PRI);
            canvas.drawCjkText(x + (w - canvas.cjkTextWidth(label, bodyFont)) / 2, y + (h - bodyFont) / 2, label, C_BG, bodyFont);
        } else {
            canvas.drawRect(x, y, w, h, C_HAIR);
            canvas.drawCjkText(x + (w - canvas.cjkTextWidth(label, bodyFont)) / 2, y + (h - bodyFont) / 2, label, C_DIM, bodyFont);
        }
    }

    // ---------------- [状态] ----------------

    private void renderStatusPage(Canvas canvas, int w, int h) {
        int y = TOPBAR + px(6);
        String ship = core.shipName();
        canvas.drawCjkText(MARGIN, y, fitPixels(canvas, ship == null ? "地面阵地" : "载具 " + ship, w - px(160)), C_TEXT, bodyFont);
        drawGateChip(canvas, w - MARGIN, y - 2);
        y += ROW;
        ChannelTask task = core.taskOf(core.activeChannel());
        String taskText = FireControlUiText.source(task.source().ordinal()) + " · " + FireControlUiText.mode(task.mode().ordinal());
        if (task.mode() == ChannelTask.Mode.MULTI_LOCK) {
            taskText += " · 交战 " + core.assignedUniqueTargetCount(core.activeChannel()) + " 目标";
        } else if (task.mode() == ChannelTask.Mode.PRECISE_LOCK) {
            TargetRegistry.Target target = task.preciseId() == null ? null : core.targets().targetById(task.preciseId());
            taskText += target == null ? " · 目标暂不可见" : " · " + labelOf(target);
        } else if (task.promoteSuppressed()) taskText += " · 升格已暂停";
        canvas.drawCjkText(MARGIN, y, fitPixels(canvas, taskText, w - 2 * MARGIN), C_PRI, bodyFont);
        y += ROW + 2;

        canvas.drawCjkText(MARGIN, y, "炮塔 / 武器", C_DIM, bodyFont);
        canvas.drawCjkText(w - MARGIN - px(108), y, "射速上限", C_DIM, bodyFont);
        y += ROW;
        canvas.hLine(MARGIN, w - MARGIN - 1, y - 3, C_HAIR);

        List<TurretUnit> units = List.copyOf(core.turrets());
        int capacity = Math.max(1, (h - px(26) - y) / STATUS_ROW);
        statusPages = Math.max(1, (units.size() + capacity - 1) / capacity);
        statusPage = Math.min(statusPage, statusPages - 1);
        int start = statusPage * capacity;
        stRowY0 = y;
        for (int row = start; row < Math.min(units.size(), start + capacity); row++) {
            TurretUnit unit = units.get(row);
            statusTurretIds.add(unit.id());
            boolean ready = unit.isReady();
            FireDirector.GateResult gate = core.gateOf(unit.id());
            String state = !unit.actuator().isPaired() ? "未配对" : !ready ? "校准中" : gate == null ? "就绪" : gateLabel(gate.gate());
            int color = !ready ? C_WARN : gate == null ? C_FRIEND : gateColor(gate.gate());
            if (unit.channel() == core.activeChannel()) canvas.fillRect(MARGIN, y - 1, w - MARGIN * 2, STATUS_ROW - 2, C_PANEL);
            int nameWidth = Math.max(px(78), (w - MARGIN * 2) / 3);
            canvas.drawCjkText(MARGIN + 2, y, fitPixels(canvas, unit.name(), nameWidth - 4), C_TEXT, bodyFont);
            String gun = core.turretGunLabel(row);
            canvas.drawCjkText(MARGIN + nameWidth + 4, y,
                    fitPixels(canvas, gun == null ? "未装配武器" : gun, w - MARGIN * 2 - nameWidth - 4), C_DIM, bodyFont);
            double error = unit.aimErrorDeg();
            String detail = state + (Double.isNaN(error) ? "" : String.format(Locale.ROOT, " · %.1f°", error));
            canvas.drawCjkText(MARGIN + 2, y + px(16), fitPixels(canvas, detail, w - MARGIN * 2 - px(126)), color, bodyFont);
            int minus = w - MARGIN - px(116), plus = w - MARGIN - px(14);
            if (core.turretCadenceAdjustable(row)) {
                drawSegment(canvas, minus, y + px(14), px(14), px(16), "-", false);
                stMinusX.add(new int[]{minus, minus + px(14)});
                int interval = unit.actuator().fireIntervalTicks();
                String rate = interval > 0 ? (1200 / interval) + "/min" : "--";
                canvas.drawCjkText(minus + px(20), y + px(16), rate, C_TEXT, bodyFont);
                drawSegment(canvas, plus, y + px(14), px(14), px(16), "+", false);
                stPlusX.add(new int[]{plus, plus + px(14)});
            } else {
                canvas.drawCjkText(minus + px(12), y + px(16), "装填控制", C_DIM, bodyFont);
                stMinusX.add(new int[]{-1, -1}); stPlusX.add(new int[]{-1, -1});
            }
            y += STATUS_ROW;
            canvas.hLine(MARGIN, w - MARGIN - 1, y - 3, C_GRID);
        }
        if (units.isEmpty()) canvas.drawCjkText(MARGIN, y, "尚未配对炮塔", C_DIM, bodyFont);
        statusPagerY0 = h - px(19);
        canvas.drawCjkText(MARGIN, h - px(17), (statusPage + 1) + "/" + statusPages + " · " + units.size() + " 座炮塔", C_DIM, bodyFont);
        if (statusPages > 1) drawSegment(canvas, w - MARGIN - px(64), statusPagerY0, px(64), px(16), "下一页", false);
    }

    private String fitPixels(Canvas canvas, String text, int width) {
        if (width <= 0) return "";
        if (canvas.cjkTextWidth(text, bodyFont) <= width) return text;
        String tail = "..";
        while (!text.isEmpty() && canvas.cjkTextWidth(text + tail, bodyFont) > width) text = text.substring(0, text.length() - 1);
        return text.isEmpty() ? "" : text + tail;
    }

    private int turretIndex(java.util.UUID id) {
        int row = 0;
        for (TurretUnit unit : core.turrets()) {
            if (unit.id().equals(id)) return row;
            row++;
        }
        return -1;
    }

    /** Fire-gate chip, right-anchored (火力指挥层③, 2026-10-08): reads the
     *  per-turret FireDirector verdicts of the ACTIVE channel instead of
     *  re-deriving its own err/arc aggregate — the chip now matches the real
     *  gate chain (mounts, cadence, LOF included). 允许 n/m = n turrets can
     *  fire right now; 待机 = nothing firing only because auto switches are
     *  off; otherwise the first real blocking gate names the problem. */
    private void drawGateChip(Canvas canvas, int rightX, int y) {
        int fire = 0, total = 0;
        FireDirector.Gate firstBlock = null;
        int ch = core.activeChannel();
        for (TurretUnit u : core.turrets()) {
            if (u.channel() != ch || !u.actuator().isPaired() || u.actuator().mountCount() <= 0) {
                continue;
            }
            total++;
            FireDirector.GateResult g = core.gateOf(u.id());
            if (g == null) {
                continue;
            }
            if (g.fires()) {
                fire++;
            } else if (g.gate() != FireDirector.Gate.AUTO_OFF && g.gate() != FireDirector.Gate.NO_CHANNEL
                    && firstBlock == null) {
                firstBlock = g.gate();
            }
        }
        String text;
        int color;
        if (total == 0) {
            text = "开火门 --";
            color = C_DIM;
        } else if (fire > 0) {
            text = "开火门 允许 " + fire + "/" + total;
            color = C_FRIEND;
        } else if (firstBlock == null) {
            text = "开火门 待机"; // blocked by nothing but the auto switches
            color = C_DIM;
        } else {
            text = "开火门 " + gateLabel(firstBlock);
            color = C_HOSTILE;
        }
        int cw = canvas.cjkTextWidth(text, bodyFont) + px(12);
        canvas.drawRect(rightX - cw, y, cw, px(16), color);
        canvas.drawCjkText(rightX - cw + px(6), y + 2, text, color, bodyFont);
    }

    /** Short narration of the gate a turret is parked at (状态格). */
    private static String gateLabel(FireDirector.Gate g) {
        return switch (g) {
            case FIRE -> "可开火";
            case NO_TARGET -> "无目标";
            case NO_CHANNEL -> "未编组";
            case AUTO_OFF -> "自动关";
            case NOT_READY -> "未就绪";
            case AIMING -> "瞄准中";
            case OUT_OF_ARC -> "射界外";
            case CADENCE -> "间隔";
            case BLOCKED -> "遮挡";
        };
    }

    private static int gateColor(FireDirector.Gate g) {
        return switch (g) {
            case FIRE -> C_FRIEND;
            case AIMING, OUT_OF_ARC -> C_WARN;
            case NOT_READY, BLOCKED -> C_HOSTILE;
            default -> C_DIM;
        };
    }

    private static String fit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ---------------- [频道] (武器频道 — the old Lua control-center group page) ----------------

    /** 武器频道: channel tabs + channel-level auto-fire switch + the paged
     *  turret membership list (click a row to join/leave the ACTIVE channel —
     *  exclusive, the old Lua group semantics). 射速在状态页调;雷达方向在
     *  设置页. */
    private void renderSettingsPage(Canvas canvas, int w, int h) {
        int y = TOPBAR + px(6);
        chTabY0 = y; chTabY1 = y + px(18);
        int tabW = (w - MARGIN * 2 - px(12)) / 4;
        for (int ch = 0; ch < 4; ch++) {
            chTabX0[ch] = MARGIN + ch * (tabW + 4); chTabX1[ch] = chTabX0[ch] + tabW;
            drawSegment(canvas, chTabX0[ch], y, tabW, px(18), fitPixels(canvas, core.channelName(ch), tabW - px(10)), core.activeChannel() == ch);
        }
        y += px(24);
        int ch = core.activeChannel();
        boolean promote = core.channelAutoPromote(ch), suppressed = core.taskOf(ch).promoteSuppressed();
        chAutoY0 = y; chAutoY1 = y + px(18);
        int half = (w - MARGIN * 2 - px(6)) / 2;
        chPromoteX0 = MARGIN; chPromoteX1 = chPromoteX0 + half;
        drawSegment(canvas, chPromoteX0, y, half, px(18), "升格:" + (!promote ? "关" : suppressed ? "已暂停" : "开"), promote && !suppressed);
        chAutoX0 = chPromoteX1 + px(6); chAutoX1 = chAutoX0 + half;
        boolean auto = core.channelAutoFire(ch);
        drawSegment(canvas, chAutoX0, y, half, px(18), "自动开火:" + (auto ? "开" : "关"), auto);
        y += px(23);
        ChannelTask task = core.taskOf(ch);
        String taskText = FireControlUiText.source(task.source().ordinal()) + " · " + FireControlUiText.mode(task.mode().ordinal())
                + " · 成员 " + core.pairedCountOf(ch);
        canvas.drawCjkText(MARGIN, y, fitPixels(canvas, taskText, w - 2 * MARGIN), C_TEXT, bodyFont);
        y += ROW;
        canvas.hLine(MARGIN, w - MARGIN - 1, y - 3, C_HAIR);
        memberY0 = y; memberX1 = w - MARGIN;
        List<TurretUnit> units = List.copyOf(core.turrets());
        int perPage = Math.max(1, (h - px(25) - y) / MEMBER_ROW);
        memberPages = Math.max(1, (units.size() + perPage - 1) / perPage);
        memberPage = Math.min(memberPage, memberPages - 1);
        memberRowBase = memberPage * perPage;
        memberRows = 0;
        for (int row = memberRowBase; row < Math.min(units.size(), memberRowBase + perPage); row++) {
            TurretUnit unit = units.get(row);
            memberTurretIds.add(unit.id());
            memberChannels.add(unit.channel());
            boolean inActive = unit.channel() == ch;
            if (inActive) canvas.fillRect(MARGIN, y - 2, w - MARGIN * 2, MEMBER_ROW - 1, C_PANEL);
            canvas.drawCjkText(MARGIN + 2, y, fitPixels(canvas, unit.name(), w - MARGIN * 2 - px(86)), inActive ? C_PRI : C_TEXT, bodyFont);
            String gun = core.turretGunLabel(row);
            canvas.drawCjkText(MARGIN + 2, y + px(14), fitPixels(canvas, gun == null ? "未装配武器" : gun, w - 2 * MARGIN - px(86)), C_DIM, bodyFont);
            String action = inActive ? "移出本频道" : unit.channel() < 0 ? "加入本频道" : "转入本频道";
            int actionW = canvas.cjkTextWidth(action, bodyFont) + px(10);
            drawSegment(canvas, w - MARGIN - actionW, y + 3, actionW, px(19), action, inActive);
            y += MEMBER_ROW; memberRows++;
            canvas.hLine(MARGIN, w - MARGIN - 1, y - 3, C_GRID);
        }
        if (units.isEmpty()) canvas.drawCjkText(MARGIN, y, "先配对炮塔，再编入频道", C_DIM, bodyFont);
        pagerY0 = h - px(19); pagerY1 = h - 3;
        canvas.drawCjkText(MARGIN, h - px(17), (memberPage + 1) + "/" + memberPages + " · 单塔独占频道", C_DIM, bodyFont);
        pagerX1 = w - MARGIN; pagerX0 = pagerX1 - px(64);
        if (memberPages > 1) drawSegment(canvas, pagerX0, pagerY0, px(64), px(16), "下一页", false);
    }

    // ---------------- [设置] ----------------

    private void renderPrefsPage(Canvas canvas, int w, int h) {
        int y = TOPBAR + px(6);
        canvas.drawCjkText(MARGIN, y, "雷达方向", C_TEXT, bodyFont);
        y += px(18);
        orientY0 = y; orientY1 = y + px(18);
        int ox = MARGIN;
        for (int k = 0; k < 4; k++) {
            int segW = px(44);
            orientX0[k] = ox; orientX1[k] = ox + segW;
            drawSegment(canvas, ox, y, segW, px(18), ORIENT_LABELS[k], orientIdx == k);
            ox += segW + px(4);
        }
        y += px(26);
        canvas.drawCjkText(MARGIN, y, "单屏分辨率 · 点选保存", C_TEXT, bodyFont);
        y += px(18);
        resolutionY0 = y; resolutionY1 = y + px(22);
        var gpu = level != null && gpuPos != null && level.getBlockEntity(gpuPos) instanceof DisplayGpuBlockEntity found ? found : null;
        int tileRes = gpu == null ? 0 : gpu.tileResolution();
        int segment = Math.min(px(100), (w - MARGIN * 2 - px(8)) / 3);
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            resolutionX0[i] = MARGIN + i * (segment + px(4)); resolutionX1[i] = resolutionX0[i] + segment;
            drawSegment(canvas, resolutionX0[i], y, segment, px(22), String.valueOf(RESOLUTIONS[i]), tileRes == RESOLUTIONS[i]);
        }
        y += px(30);
        canvas.drawCjkText(MARGIN, y, fitPixels(canvas, "画布 " + w + "x" + h + " · 单屏 " + tileRes, w - MARGIN * 2), C_PRI, bodyFont);
        if (y + px(32) < h) canvas.drawCjkText(MARGIN, y + px(18), fitPixels(canvas, "3×3 /128：密度与触控更均衡", w - MARGIN * 2), C_DIM, bodyFont);
        if (y + px(50) < h) canvas.drawCjkText(MARGIN, y + px(36), fitPixels(canvas, "单格 /512：可显示接近全量信息", w - MARGIN * 2), C_DIM, bodyFont);
    }

    /** Display label: Chinese for known mob species, raw otherwise (player
     *  names and ship slugs pass through). */
    private static String labelOf(TargetRegistry.Target t) {
        if (t.category() == TargetRegistry.Category.PLAYER
                || t.category() == TargetRegistry.Category.SHIP) return t.label();
        return switch (t.species()) {
            case "zombie" -> "僵尸";
            case "skeleton" -> "骷髅";
            case "creeper" -> "苦力怕";
            case "spider" -> "蜘蛛";
            case "cave_spider" -> "洞穴蜘蛛";
            case "enderman" -> "末影人";
            case "witch" -> "女巫";
            case "slime" -> "史莱姆";
            case "phantom" -> "幻翼";
            case "drowned" -> "溺尸";
            case "husk" -> "尸壳";
            case "stray" -> "流浪者";
            case "pillager" -> "掠夺者";
            case "vindicator" -> "卫道士";
            case "ravager" -> "劫掠兽";
            case "vex" -> "恼鬼";
            case "guardian" -> "守卫者";
            case "blaze" -> "烈焰人";
            case "hoglin" -> "疣猪兽";
            case "zoglin" -> "僵尸疣猪兽";
            case "piglin" -> "猪灵";
            case "piglin_brute" -> "猪灵蛮兵";
            case "ghast" -> "恶魂";
            case "magma_cube" -> "岩浆怪";
            case "silverfish" -> "蠹虫";
            case "warden" -> "监守者";
            case "wither" -> "凋灵";
            case "ender_dragon" -> "末影龙";
            case "sheep" -> "羊";
            case "pig" -> "猪";
            case "cow" -> "牛";
            case "chicken" -> "鸡";
            case "horse" -> "马";
            case "donkey" -> "驴";
            case "mule" -> "骡";
            case "goat" -> "山羊";
            case "llama" -> "羊驼";
            case "rabbit" -> "兔子";
            case "fox" -> "狐狸";
            case "wolf" -> "狼";
            case "cat" -> "猫";
            case "ocelot" -> "豹猫";
            case "parrot" -> "鹦鹉";
            case "bat" -> "蝙蝠";
            case "bee" -> "蜜蜂";
            case "villager" -> "村民";
            case "wandering_trader" -> "流浪商人";
            case "salmon" -> "鲑鱼";
            case "cod" -> "鳕鱼";
            case "tropical_fish" -> "热带鱼";
            case "pufferfish" -> "河豚";
            case "squid" -> "鱿鱼";
            case "glow_squid" -> "发光鱿鱼";
            case "turtle" -> "海龟";
            case "dolphin" -> "海豚";
            case "axolotl" -> "美西螈";
            case "frog" -> "青蛙";
            case "panda" -> "熊猫";
            case "polar_bear" -> "北极熊";
            case "camel" -> "骆驼";
            default -> t.label();
        };
    }

    // ---------------- [雷达]: tactical scope + track list ----------------

    private void renderRadarPage(Canvas canvas, int w, int h) {
        // 频道任务协议 v1: 雷达页的"锁定"显示改为当前频道任务状态 —
        // 精确锁目标红框, 控制台点选集成员青框; 点击 = 切换选择集 (m/N)
        ChannelTask task = core.taskOf(core.activeChannel());
        String preciseId = task.mode() == ChannelTask.Mode.PRECISE_LOCK ? task.preciseId() : null;
        java.util.Set<String> selection = task.selection();
        TargetRegistry.Target precise = preciseId != null ? core.targets().targetById(preciseId) : null;
        List<TargetRegistry.Target> targets = new ArrayList<>();
        for (TargetRegistry.Target t : core.targets().targets()) {
            if (passFilter(t)) targets.add(t);
        }

        int bodyTop = TOPBAR + 4;
        int toolbarHeight = w < px(360) ? px(43) : px(27);
        int bodyBottom = h - toolbarHeight - 2;
        int listW = Math.max(px(110), Math.min(px(256), w / 3));
        listX0 = w - MARGIN - listW;
        listX1 = w - MARGIN;

        // ---- scope (main area, left) ----
        int scopeRegionW = listX0 - px(8) - MARGIN;
        int scopeSize = Math.min(scopeRegionW, bodyBottom - bodyTop);
        if (scopeSize >= px(64)) {
            int scopeLeft = MARGIN + Math.max(0, (scopeRegionW - scopeSize) / 2);
            int scopeTop = bodyTop + Math.max(0, (bodyBottom - bodyTop - scopeSize) / 2);
            renderScope(canvas, scopeTop, scopeLeft, scopeSize, targets, precise, selection);
        }

        // ---- track list (right column, full height) ----
        canvas.vLine(listX0 - 4, bodyTop, bodyBottom, C_HAIR);
        canvas.drawCjkText(listX0, bodyTop, "目标", C_DIM, bodyFont);
        // 已选 m/N (裁断稿 §3: 点选容量 = 频道已配对炮塔数)
        String selInfo = "选" + selection.size() + "/" + core.pairedCountOf(core.activeChannel());
        String countInfo = "N=" + targets.size();
        canvas.drawCjkText(listX1 - canvas.cjkTextWidth(countInfo, bodyFont), bodyTop, countInfo, C_DIM, bodyFont);
        canvas.drawCjkText(listX1 - canvas.cjkTextWidth(countInfo, bodyFont)
                - canvas.cjkTextWidth(selInfo, bodyFont) - px(6), bodyTop, selInfo,
                selection.isEmpty() ? C_DIM : C_PRI, bodyFont);
        int y = bodyTop + ROW - 4;
        canvas.hLine(listX0, listX1, y, C_HAIR);
        y += 2;
        listY0 = y;
        for (TargetRegistry.Target t : targets) {
            if (y + ROW > bodyBottom) break;
            boolean isPrecise = t.id().equals(preciseId);
            boolean isSelected = selection.contains(t.id());
            int color = t.whitelisted() ? C_DIM : isPrecise ? C_HOSTILE
                    : isSelected ? C_PRI : trackColor(t);
            if (isPrecise) {
                canvas.drawRect(listX0, y, listW, ROW, C_HOSTILE);
            } else if (isSelected) {
                canvas.drawRect(listX0, y, listW, ROW, C_PRI);
            }
            drawSymbol(canvas, listX0 + 7, y + ROW / 2, 3, color);
            String dist = (int) t.dist() + "M";
            canvas.drawCjkText(listX0 + px(15), y + 2,
                    fitPixels(canvas, labelOf(t), listW - px(23) - canvas.cjkTextWidth(dist, bodyFont)), color, bodyFont);
            canvas.drawCjkText(listX1 - 2 - canvas.cjkTextWidth(dist, bodyFont), y + 2, dist,
                    isPrecise ? C_HOSTILE : isSelected ? C_PRI : C_DIM, bodyFont);
            rowTargetIds.add(t.id());
            y += ROW;
            canvas.hLine(listX0, listX1, y - 4, C_GRID);
        }
        if (targets.isEmpty()) {
            canvas.drawCjkText(listX0 + 2, y + 2, "(无目标)", C_DIM, bodyFont);
        }

        // ---- bottom toolbar: filter segments + range slider ----
        toolbarY0 = h - toolbarHeight;
        toolbarY1 = h - 2;
        canvas.hLine(MARGIN, w - MARGIN - 1, toolbarY0 - 2, C_HAIR);
        int ty = toolbarY0 + 1;
        int x = MARGIN;
        for (int i = 0; i < FILTER_LABELS.length; i++) {
            int segW = w < px(360) ? (w - MARGIN * 2 - px(12)) / 5 : canvas.cjkTextWidth(FILTER_LABELS[i], bodyFont) + px(16);
            segX0[i] = x;
            segX1[i] = x + segW;
            drawSegment(canvas, x, ty, segW, px(16), FILTER_LABELS[i], filterIdx == i);
            x += segW + 3;
        }
        // Narrow screens put range controls on their own row; click areas
        // use the same two-row geometry instead of overlapping filters.
        int rangeY = w < px(360) ? ty + px(20) : ty;
        rangeY0 = rangeY; rangeY1 = rangeY + px(16);
        plusX1 = w - MARGIN;
        plusX0 = plusX1 - px(16);
        minusX1 = w < px(360) ? MARGIN + px(16) : plusX0 - px(88);
        minusX0 = minusX1 - px(16);
        sliderX0 = minusX1 + 4;
        sliderX1 = plusX0 - 4;
        drawSegment(canvas, minusX0, rangeY, px(16), px(16), "-", false);
        drawSegment(canvas, plusX0, rangeY, px(16), px(16), "+", false);
        double range = core.scanRadius();
        int baseY = rangeY + px(8);
        canvas.hLine(sliderX0, sliderX1, baseY, C_HAIR);
        double frac = (range - RANGE_MIN) / (RANGE_MAX - RANGE_MIN);
        int knobX = sliderX0 + (int) Math.round(frac * (sliderX1 - sliderX0));
        canvas.hLine(sliderX0, knobX, baseY, C_PRI);
        canvas.fillRect(knobX - 1, rangeY + 3, 3, px(10), C_PRI);

        // tiny ASCII hint, bottom-right corner
        if (w >= px(360)) canvas.drawTextRight(w - MARGIN, h - px(8), "TAP=SEL S+TAP=WL", C_DIM, 1);
    }

    private boolean passFilter(TargetRegistry.Target t) {
        return switch (filterIdx) {
            case 1 -> t.category() == TargetRegistry.Category.SHIP;
            case 2 -> t.category() == TargetRegistry.Category.PLAYER;
            case 3 -> t.category() == TargetRegistry.Category.MONSTER;
            case 4 -> t.category() == TargetRegistry.Category.CREATURE;
            default -> true;
        };
    }

    /** Track color (2026-09-27 user rulings): 全部 view = category colors
     *  (player green ONLY, creature white, monster orange, ship blue) so red
     *  stays reserved for the lock; sub-filter views color by species —
     *  怪物 view bans green AND white, 生物 view hashes per-species. Phantom
     *  is a vanilla Monster, so it lands in the monster camp by itself. */
    private int trackColor(TargetRegistry.Target t) {
        if (filterIdx == 0) {
            return switch (t.category()) {
                case SHIP -> C_SHIPBLUE;
                case PLAYER -> C_FRIEND;
                case MONSTER -> C_SHIP;
                default -> C_TEXT;
            };
        }
        return switch (t.category()) {
            case SHIP -> C_SHIPBLUE;
            case PLAYER -> C_FRIEND;
            case MONSTER -> monsterColor(t);
            default -> creatureColor(t);
        };
    }

    /** 怪物 view species colors — green and white are both banned here. */
    private static int monsterColor(TargetRegistry.Target t) {
        return switch (t.species()) {
            case "phantom" -> C_PRI;                         // cyan
            case "creeper" -> C_MAGENTA;
            case "spider", "cave_spider", "warden", "wither", "ender_dragon" -> C_SHIP;
            case "pillager", "vindicator", "ravager", "vex", "witch", "guardian" -> C_SHIPBLUE;
            default -> C_WARN;                               // undead/nether/other: amber
        };
    }

    /** 生物 view: per-species hash color (2026-09-27 user ruling: a farmyard
     *  scene is all one "livestock" class, so group coloring came out
     *  monochrome — hash gives each species its own fixed hue). */
    private static final int[] CREATURE_COLORS = {C_LAND, C_PRI, C_SHIPBLUE, C_MAGENTA, C_WARN};

    private static int creatureColor(TargetRegistry.Target t) {
        return CREATURE_COLORS[Math.floorMod(t.species().hashCode(), CREATURE_COLORS.length)];
    }

    /** Track symbol: a small filled dot everywhere (2026-09-27 user ruling). */
    private static void drawSymbol(Canvas canvas, int cx, int cy, int r, int color) {
        canvas.fillCircle(cx, cy, r, color);
    }

    /** The bow-up tactical scope: corner-bracketed frame, dotted range rings,
     *  bearing ticks, rotating sweep, dot tracks; precise-lock target = red box
     *  + tag, console-selected = cyan box. */
    private void renderScope(Canvas canvas, int top, int left, int size,
                             List<TargetRegistry.Target> targets,
                             @Nullable TargetRegistry.Target precise,
                             java.util.Set<String> selection) {
        int cx = left + size / 2;
        int cy = top + size / 2;
        int rr = size / 2 - 6;
        scopeCx = cx;
        scopeCy = cy;
        scopeR = rr;
        scopeCenter = core.radarCenter();
        scopeRot = core.radarRotation();
        double range = core.scanRadius();
        scopePpb = rr / range;

        // frame: hairline + primary corner brackets
        canvas.drawRect(left, top, size, size, C_HAIR);
        cornerBrackets(canvas, left, top, left + size - 1, top + size - 1, 6, C_PRI);

        // dotted range rings (half + full)
        dottedCircle(canvas, cx, cy, rr / 2, C_GRID);
        dottedCircle(canvas, cx, cy, rr, C_GRID);

        // crosshair with a center gap
        int gap = 5;
        canvas.hLine(cx - rr, cx - gap, cy, C_GRID);
        canvas.hLine(cx + gap, cx + rr, cy, C_GRID);
        canvas.vLine(cx, cy - rr, cy - gap, C_GRID);
        canvas.vLine(cx, cy + gap, cy + rr, C_GRID);

        // bearing ticks every 45 deg + N marker
        for (int d = 0; d < 360; d += 45) {
            double a = Math.toRadians(d);
            canvas.drawLine(cx + (int) Math.round(Math.sin(a) * (rr - 1)),
                    cy - (int) Math.round(Math.cos(a) * (rr - 1)),
                    cx + (int) Math.round(Math.sin(a) * (rr - 4)),
                    cy - (int) Math.round(Math.cos(a) * (rr - 4)), C_GRID);
        }
        // N marker at the actual north rim position (orientation-aware)
        double[] nm = orient(0, -1);
        canvas.drawText(cx + (int) Math.round(nm[0] * (rr - 9)) - 3,
                cy + (int) Math.round(nm[1] * (rr - 9)) - 3, "N", C_DIM, 1);

        // rotating sweep (visual only)
        sweepDeg = (sweepDeg + 4) % 360;
        double sa = Math.toRadians(sweepDeg);
        canvas.drawLine(cx, cy,
                cx + (int) Math.round(Math.sin(sa) * (rr - 2)),
                cy - (int) Math.round(Math.cos(sa) * (rr - 2)), C_SWEEP);

        // own-ship center mark
        canvas.hLine(cx - 3, cx + 3, cy, C_TEXT);
        canvas.vLine(cx, cy - 3, cy + 3, C_TEXT);

        // tracks: dot + velocity vector; precise lock gets a red box + data
        // tag, console selection a cyan box
        String preciseId = precise != null ? precise.id() : null;
        for (TargetRegistry.Target t : targets) {
            int[] dot = scopeDot(t);
            if (dot == null) continue;
            int x = dot[0], yy = dot[1];
            boolean isPrecise = t.id().equals(preciseId);
            boolean isSelected = selection.contains(t.id());
            int color = t.whitelisted() ? C_DIM : isPrecise ? C_HOSTILE
                    : isSelected ? C_PRI : trackColor(t);
            drawSymbol(canvas, x, yy, px(isPrecise || isSelected ? 3 : 2), color);
            // velocity vectors only in sub-filter views or for marked tracks
            // (2026-09-27 user ruling: 全部 view gets cluttered at large scan radii)
            if (!t.whitelisted() && (filterIdx != 0 || isPrecise || isSelected)) {
                drawVelocityVector(canvas, t, x, yy, color);
            }
            if (isPrecise) { // precise lock = red box + data tag, always
                canvas.drawRect(x - px(6), yy - px(6), px(12), px(12), C_HOSTILE);
                String tag = fit(labelOf(t), 8) + " " + (int) t.dist() + "M";
                int tagX = Math.max(left + 2, Math.min(x + px(8), left + size - canvas.cjkTextWidth(tag, bodyFont) - 2));
                int tagY = Math.max(yy - px(16), top + 2);
                canvas.drawCjkText(tagX, tagY, tag, C_HOSTILE, bodyFont);
            } else if (isSelected) {
                canvas.drawRect(x - px(5), yy - px(5), px(10), px(10), C_PRI);
            }
        }
    }

    /** Short leader line in the track's horizontal velocity direction,
     *  length ~ 2 s of travel, capped. */
    private void drawVelocityVector(Canvas canvas, TargetRegistry.Target t,
                                    int x, int y, int color) {
        if (scopeRot == null) return;
        double speed = Math.hypot(t.vx(), t.vz());
        if (speed < 0.05) return; // blocks/tick
        Vec3 rv = scopeRot.rotate(new Vec3(t.vx(), 0, t.vz()));
        if (rv == null) return;
        double len = Math.min(speed * 40 * scopePpb, px(12));
        double[] o = orient(rv.x(), rv.z());
        double mag = Math.hypot(o[0], o[1]);
        if (mag < 1e-6) return;
        int x1 = x + (int) Math.round(o[0] / mag * len);
        int y1 = y + (int) Math.round(o[1] / mag * len);
        int dx = x1 - scopeCx, dy = y1 - scopeCy;
        if (dx * dx + dy * dy > scopeR * scopeR) return;
        canvas.drawLine(x, y, x1, y1, color);
    }

    private static void dottedCircle(Canvas canvas, int cx, int cy, int r, int color) {
        if (r < 4) return;
        int steps = Math.max(24, (int) (Math.PI * r)); // ~2px arc per step
        for (int i = 0; i < steps; i += 2) {
            double a = 2 * Math.PI * i / steps;
            canvas.setPixel(cx + (int) Math.round(Math.cos(a) * r),
                    cy + (int) Math.round(Math.sin(a) * r), color);
        }
    }

    private static void cornerBrackets(Canvas canvas, int x0, int y0, int x1, int y1,
                                       int len, int color) {
        canvas.drawLine(x0, y0, x0 + len, y0, color);
        canvas.drawLine(x0, y0, x0, y0 + len, color);
        canvas.drawLine(x1, y0, x1 - len, y0, color);
        canvas.drawLine(x1, y0, x1, y0 + len, color);
        canvas.drawLine(x0, y1, x0 + len, y1, color);
        canvas.drawLine(x0, y1, x0, y1 - len, color);
        canvas.drawLine(x1, y1, x1 - len, y1, color);
        canvas.drawLine(x1, y1, x1, y1 - len, color);
    }

    /** Scope pixel position of a target, or null when off-scope. */
    private int @Nullable [] scopeDot(TargetRegistry.Target t) {
        if (scopeRot == null || scopeCenter == null) return null;
        Vec3 rv = scopeRot.rotate(new Vec3(
                t.x() - scopeCenter[0], t.y() - scopeCenter[1], t.z() - scopeCenter[2]));
        if (rv == null) return null;
        double[] o = orient(rv.x(), rv.z());
        int x = scopeCx + (int) Math.round(o[0] * scopePpb);
        int y = scopeCy + (int) Math.round(o[1] * scopePpb);
        int dx = x - scopeCx, dy = y - scopeCy;
        if (dx * dx + dy * dy > scopeR * scopeR) return null;
        return new int[]{x, y};
    }

    /** Rotate a scope-space vector (rx, rz) by the settings-page orientation:
     *  0=north-up (identity), 1=east-up, 2=south-up, 3=west-up. */
    private double[] orient(double rx, double rz) {
        return switch (orientIdx) {
            case 1 -> new double[]{rz, -rx};
            case 2 -> new double[]{-rx, -rz};
            case 3 -> new double[]{-rz, rx};
            default -> new double[]{rx, rz};
        };
    }

    /** Tabs switch pages. Radar page: filter segments set the category; the
     *  slider sets the scan radius ([−]/[+] step it); scope tracks and list
     *  rows toggle the ACTIVE channel's console selection (m/N), sneak toggles
     *  the whitelist, tap-empty clears the selection. 状态: per-row cadence
     *  steppers edit only the displayed turret. 频道: channel tabs
     *  select the active channel, the auto-fire toggle edits it, member rows
     *  join/leave it. */
    @Override
    public void onClick(Player player, int x, int y, boolean sneak) {
        if (core == null || core.isRemoved() || tabY0 < 0) return;
        if (y >= tabY0 && y <= tabY1) {
            if (x >= tabRadarX0 && x < tabRadarX1) {
                pageIdx = PAGE_RADAR;
                return;
            }
            if (x >= tabStatusX0 && x < tabStatusX1) {
                pageIdx = PAGE_STATUS;
                return;
            }
            if (x >= tabSettingsX0 && x < tabSettingsX1) {
                pageIdx = PAGE_SETTINGS;
                return;
            }
            if (x >= tabPrefsX0 && x < tabPrefsX1) {
                pageIdx = PAGE_PREFS;
                return;
            }
        }
        if (pageIdx == PAGE_PREFS) {
            if (resolutionY0 >= 0 && y >= resolutionY0 && y < resolutionY1) {
                for (int i = 0; i < RESOLUTIONS.length; i++) if (x >= resolutionX0[i] && x < resolutionX1[i]) {
                    if (level != null && gpuPos != null && level.getBlockEntity(gpuPos) instanceof DisplayGpuBlockEntity gpu)
                        gpu.setResolution(player, RESOLUTIONS[i]);
                    return;
                }
            }
            if (orientY0 >= 0 && y >= orientY0 && y <= orientY1) {
                for (int i = 0; i < 4; i++) {
                    if (x >= orientX0[i] && x < orientX1[i]) {
                        orientIdx = i;
                        return;
                    }
                }
            }
            return;
        }
        if (pageIdx == PAGE_SETTINGS) {
            if (chTabY0 >= 0 && y >= chTabY0 && y <= chTabY1) {
                for (int c = 0; c < FireControlCoreBlockEntity.CHANNEL_COUNT; c++) {
                    if (x >= chTabX0[c] && x < chTabX1[c]) {
                        core.setActiveChannel(c);
                        return;
                    }
                }
            }
            if (chAutoY0 >= 0 && y >= chAutoY0 && y <= chAutoY1) {
                if (x >= chPromoteX0 && x < chPromoteX1) {
                    core.toggleChannelAutoPromote(renderedChannel);
                    return;
                }
                if (x >= chAutoX0 && x < chAutoX1) {
                    core.toggleChannelAutoFire(renderedChannel);
                    return;
                }
            }
            if (memberPages > 1 && pagerY0 >= 0 && y >= pagerY0 && y <= pagerY1
                    && x >= pagerX0 && x < pagerX1) {
                memberPage = (memberPage + 1) % memberPages;
                return;
            }
            if (memberY0 >= 0 && y >= memberY0 - 2 && x >= MARGIN && x < memberX1) {
                int visible = (y - memberY0 + 2) / MEMBER_ROW;
                if (visible >= 0 && visible < memberTurretIds.size()) {
                    int row = turretIndex(memberTurretIds.get(visible));
                    if (row >= 0) {
                        TurretUnit unit = core.turretByIndex(row);
                        if (unit.channel() == memberChannels.get(visible))
                            core.setTurretChannel(row, memberChannels.get(visible) == renderedChannel ? -1 : renderedChannel);
                    }
                    return;
                }
            }
            return;
        }
        if (pageIdx == PAGE_STATUS) {
            if (statusPages > 1 && y >= statusPagerY0 && y < statusPagerY0 + px(16)
                    && x >= lastCanvasWidth - MARGIN - px(64) && x < lastCanvasWidth - MARGIN) {
                statusPage = (statusPage + 1) % statusPages;
                return;
            }
            // per-row cadence steppers (each edits only that turret)
            if (stRowY0 >= 0 && y >= stRowY0) {
                int row = (y - stRowY0) / STATUS_ROW;
                if (row >= 0 && row < stMinusX.size()
                        && (y - stRowY0) % STATUS_ROW >= px(14) && (y - stRowY0) % STATUS_ROW < px(30)) {
                    int[] mn = stMinusX.get(row);
                    int[] pl = stPlusX.get(row);
                    // [-] 降 rpm = 加 tick(dir +1); [+] 升 rpm = 减 tick(dir −1)
                    if (mn[0] >= 0 && x >= mn[0] && x < mn[1]) {
                        int actual = turretIndex(statusTurretIds.get(row));
                        if (actual >= 0 && core.turretCadenceAdjustable(actual)) core.cycleFireInterval(actual, 1);
                        return;
                    }
                    if (pl[0] >= 0 && x >= pl[0] && x < pl[1]) {
                        int actual = turretIndex(statusTurretIds.get(row));
                        if (actual >= 0 && core.turretCadenceAdjustable(actual)) core.cycleFireInterval(actual, -1);
                        return;
                    }
                }
            }
            return;
        }
        if (pageIdx != PAGE_RADAR) return;
        // bottom toolbar
        if (toolbarY0 >= 0 && y >= toolbarY0 && y <= toolbarY1) {
            for (int i = 0; i < FILTER_LABELS.length; i++) {
                if (y < toolbarY0 + px(19) && x >= segX0[i] && x < segX1[i]) {
                    filterIdx = i;
                    return;
                }
            }
            if (y < rangeY0 || y >= rangeY1) return;
            double range = core.scanRadius();
            if (x >= minusX0 && x < minusX1) {
                core.setScanRadius(Math.max(RANGE_MIN, range / RANGE_STEP));
                return;
            }
            if (x >= plusX0 && x < plusX1) {
                core.setScanRadius(Math.min(RANGE_MAX, range * RANGE_STEP));
                return;
            }
            if (x >= sliderX0 && x <= sliderX1) {
                double frac = (double) (x - sliderX0) / Math.max(1, sliderX1 - sliderX0);
                core.setScanRadius(RANGE_MIN + frac * (RANGE_MAX - RANGE_MIN));
                return;
            }
            return;
        }
        // scope tracks
        if (scopeR > 0) {
            int dx = x - scopeCx, dy = y - scopeCy;
            if (dx * dx + dy * dy <= scopeR * scopeR) {
                TargetRegistry.Target best = null;
                int bestDis = Integer.MAX_VALUE;
                for (TargetRegistry.Target t : core.targets().targets()) {
                    if (!passFilter(t)) continue;
                    int[] dot = scopeDot(t);
                    if (dot == null) continue;
                    int dis = Math.abs(x - dot[0]) + Math.abs(y - dot[1]);
                    if (dis < bestDis) {
                        bestDis = dis;
                        best = t;
                    }
                }
                int hit = px(DOT_HIT) * Math.max(1, scopeR / px(60));
                if (best != null && bestDis <= hit) {
                    if (sneak) {
                        core.toggleWhitelistById(player, best.id());
                    } else {
                        core.toggleSelection(player, best.id()); // 点选 = 切换当前频道选择集
                    }
                } else if (!sneak) {
                    core.unlockFromPanel(player); // tap empty = 清空选择
                }
                return;
            }
        }
        // list rows
        if (listY0 < 0 || y < listY0 || rowTargetIds.isEmpty() || x < listX0 || x >= listX1) return;
        int row = (y - listY0) / ROW;
        if (row < 0 || row >= rowTargetIds.size()) return;
        if (sneak) {
            core.toggleWhitelistById(player, rowTargetIds.get(row));
        } else {
            core.toggleSelection(player, rowTargetIds.get(row));
        }
    }
}
