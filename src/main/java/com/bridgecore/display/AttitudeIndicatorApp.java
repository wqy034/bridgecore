package com.bridgecore.display;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Locale;

/**
 * Artificial horizon, ladder style — 2026-10-28 layout pass: canvas layout
 * follows the HUD visual blueprint (hud-handoff-2026-10-28 index.html, ported
 * per HUD-移植交接说明.md §6 飞控侧第一批): flight reference at the canvas
 * center, heading tape with true bearings on top, static instrument frame
 * (side rails + roll arc), SPD/ALT pods, FLT/mode + P/R top-left, ship energy
 * cluster bottom-left. Transparent background — gauge panel, not a
 * world-locked HUD.
 *
 * 2026-10-28 fidelity pass: the canvas renders at 1024×1024 (512² NEAREST
 * showed Bresenham staircases on the magnified panel). Layout constants below
 * stay in the blueprint's 512-logical coordinate system (hud-handoff §4)
 * multiplied by {@link #S}; diagonal accent strokes (flight-reference wings,
 * carets, pod frames, rung end ticks) draw as 3 px lines so they read
 * smooth. HUD uses the instance alpha palette and RLE sync at 10 Hz. Text is
 * outline-rendered; dominant readouts are sized by visible digit height.
 *
 * Layout semantics kept from the legacy/tuned design:
 *  - The ladder SCROLLS with pitch and counter-rotates with roll (world-locked,
 *    aviation convention); the moving 0° horizon line is bright yellow with a
 *    center gap.
 *  - The fixed flight reference (blueprint wings symbol + center circle) does
 *    NOT move; the input line (merged stick demand, yellow, max 77 logical px,
 *    hidden at zero input) emanates from it.
 *  - Ladder step / zone size / zone border / input-line switch are core-pushed
 *    config (GUI tab 3) via {@link #configure}; the border intersects the new
 *    fixed clip window (empty window draws nothing). cleanLayout uses the
 *    blueprint spacing; disabling it restores configured dense presentation.
 *  - Flight mode / coupled state are read-only bindings from the same-structure
 *    core ({@link #bindFlightStatus}); without a core the mode reads "--" and
 *    the coupled line hides.
 */
public final class AttitudeIndicatorApp implements DisplayApp {
    /** Layout scale: blueprint coordinates are 512-logical px (hud-handoff §4);
     *  the canvas renders at 1024×1024 -> S=2. All geometry constants are
     *  blueprint values × S; font sizes are the exception (below). */
    private static final int S = 2;
    /** CJK grade for labels: TRUE 12px font ×2 (the interim 16 = 8px×2 read
     *  blurry on the panel, 2026-10-28 user acceptance). Relative size now
     *  matches the 512-era 12px exactly. */
    private static final int CJK_LABEL = 26;
    /** ASCII scale replacing the blueprint's scale-3 big digits (exact 2x). */
    private static final int BIG_NUM_SCALE = 6;
    /** Outline-font grade for the big numeric readouts (2026-10-29 outline
     *  vertical slice): 42 px ≈ the dot-matrix 7·BIG_NUM_SCALE line height.
     *  Font pick = BCConfig hud.digitFont (Barlow vs Rajdhani), white ladder. */
    private static final int OUTLINE_NUM = 44;
    /** Stroke widths (canvas px). FRAME_W 3 (2026-10-29): at 2 px the ladder
     *  still broke up on a 1080p screen under minification; 3 px keeps the
     *  mip chain something to sample. */
    private static final int WINGS_W = 3;
    private static final int FRAME_W = 3;

    private static final int CX = 256 * S;
    /** Flight reference height (blueprint default centerY). */
    private static final int CY = 256 * S;
    /** Zoom: 8 logical px per degree of pitch (blueprint's zoom=5 was a visual
     *  sample; the tuned value stays). */
    private static final double PIX_PER_DEG = 8.0 * S;
    private static final int LADDER_MAX_DEG = 90;
    /** Ladder clip window (blueprint rect(115,120,282,248)): the heading tape
     *  owns y < LADDER_CLIP_TOP, the SPD/ALT pods own y > LADDER_CLIP_BOTTOM.
     *  The configurable att_border can only SHRINK the window from these
     *  (vertical only). */
    private static final int LADDER_CLIP_TOP = 120 * S;
    private static final int LADDER_CLIP_BOTTOM = 368 * S;
    private static final int LADDER_CLIP_LEFT = 115 * S;
    private static final int LADDER_CLIP_RIGHT = 397 * S;
    /** Ladder rung center gap half-width (horizon-frame px); the horizon line's
     *  own gap is slightly wider (blueprint 36 vs 34). */
    private static final int CENTER_GAP = 34 * S;
    private static final int HORIZON_GAP = 36 * S;
    /** Ladder rung half-width (blueprint ladderWidth 150). */
    private static final int RUNG_HALF = 75 * S;
    /** Horizon (0°) line half-length, horizon-frame px. */
    private static final int HORIZON_HALF = 79 * S;
    /** Rung end tick length, horizon-frame px (up = above horizon, down = below). */
    private static final int RUNG_TICK = 4 * S;
    /** Input line full-stick length, px (legacy: midPoint.x * 0.3 = 76.8). */
    private static final double INPUT_LINE_LEN = 77.0 * S;

    // heading tape geometry (blueprint drawModernHeading)
    private static final int HDG_NUM_Y = 29 * S - 7 * BIG_NUM_SCALE; // baseline 58
    private static final int TAPE_Y = 54 * S;                        // tick baseline
    private static final int TAPE_LABEL_Y = 70 * S - CJK_LABEL;      // baseline 140
    private static final int TAPE_LEFT = 138 * S;                    // tick x clip
    private static final int TAPE_RIGHT = 374 * S;
    private static final double TAPE_PIX_PER_DEG = 3.0 * S;
    private static final int TAPE_HALF_RANGE_DEG = 40;

    // static instrument frame (blueprint drawInstrumentStructure): side rails
    // with outward ticks (the pod-height band is skipped), diagonal end caps,
    // top roll arc with outward radial ticks and a white apex triangle
    private static final int RAIL_LEFT_X = 108 * S;
    private static final int RAIL_RIGHT_X = 404 * S;
    private static final int RAIL_TOP = 174 * S;
    private static final int RAIL_BOTTOM = 338 * S;
    private static final int RAIL_TICK_Y0 = 176 * S;
    private static final int RAIL_TICK_STEP = 10 * S;
    private static final int RAIL_TICK_COUNT = 17;
    private static final int RAIL_TICK_SKIP_TOP = 235 * S;
    private static final int RAIL_TICK_SKIP_BOTTOM = 275 * S;
    private static final int ROLL_ARC_R = 103 * S;

    // SPD / ALT pods (blueprint drawReadouts, cy = 256 logical; y = 2x baseline
    // minus the font height)
    private static final int SPD_LABEL_X = 66 * S;
    private static final int SPD_VALUE_X = 63 * S;
    private static final int ALT_LABEL_X = 446 * S;
    private static final int ALT_VALUE_X = 449 * S;
    private static final int POD_LABEL_Y = (256 - 44) * S - CJK_LABEL;
    /** Usable text width inside a pod at mid-height (28..99 logical incl. the
     *  chevron tip row). */
    private static final int POD_TEXT_W = 71 * S;
    private static final int POD_UNIT_Y = (256 + 32) * S - CJK_LABEL;
    private static final int POD_AUX_Y = (256 + 60) * S - CJK_LABEL;   // G / V-S
    private static final int POD_SUB_Y = (256 + 77) * S - CJK_LABEL;   // coupled / 爬升

    // ship energy cluster (blueprint: FE baseline 475, bar y487, END baseline 491)
    private static final int FE_Y = 475 * S - CJK_LABEL;
    private static final int ENERGY_BAR_Y = 487 * S;
    private static final int ENERGY_BAR_W = 112 * S;
    private static final int ENERGY_BAR_H = 3 * S;
    private static final int ENERGY_RIGHT_X = 484 * S;
    private static final int END_Y = 491 * S - CJK_LABEL;

    // core-pushed config (GUI tab 3); defaults preserve the tuned layout
    private boolean cleanLayout = true;
    private double pixelsPerDegree = PIX_PER_DEG;
    private double pitchStep = 5.0;
    private double attSize = 1.0;
    private double attBorder;
    private boolean inputLine = true;
    // per-frame clip window, derived from attBorder in render()
    private int clipTop = LADDER_CLIP_TOP;
    private int clipBottom = LADDER_CLIP_BOTTOM;

    // ---- flight status (read-only binding from the same-structure core,
    //      HudScreenBlockEntity.tickServer): mode text for the FLT line and the
    //      effective coupled flag for the STARSHIP-only coupled line. ----
    /** ControlMode ordinal, -1 = no core bound. */
    private int flightModeOrdinal = -1;
    private boolean flightCoupled;

    // ---- lock-box overlay state (fire-control SPI, docs/bridge-fire-shared-handoff
    //      §2.27): snapshot pulled server-side each tick; projection calibration
    //      mirrors HudScreenBlockEntity's defaults until configureLockProjection
    //      pushes the live values. null snapshot = overlay fully off. ----
    private final HudLockBoxLayer lockLayer = new HudLockBoxLayer();
    private HudTargetProvider.HudTargetSnapshot lockSnapshot;
    private java.util.List<String> lockIds = java.util.List.of();
    private String lockFilterText;
    /** SPI v3 (§2.29): red boxes = the active channel's assignedTargetIds
     *  (authoritative core feedback); taskTag = "FCS"/"LOCK" source marker,
     *  taskActive = the channel runs a real task (drives the filter-text
     *  color); commandHud = this screen is the command HUD (CMD marker). */
    private java.util.List<String> lockRedIds = java.util.List.of();
    private String lockTaskTag;
    private boolean lockTaskActive;
    private boolean lockPreciseTask;
    private boolean lockCommandHud;
    /** v3.1 no-lock Tab notch: roam layer (aux boxes + dim triangles) off. */
    private boolean lockTargetsOff;
    private Level lockLevel;
    private ServerSubLevel lockSub;
    private BlockPos lockPos = BlockPos.ZERO;
    private double lockScreenScale = 1.0;
    private double lockCalibX;
    private double lockCalibY;
    private double lockCalibScale = 1.0;
    private double lockCalibEyeX = 2.0;
    private double lockCalibEyeY;

    private final HudContrastPass contrastPass = new HudContrastPass();
    private boolean outlineEnabled = true;

    /** Per-core appearance preference, bound server-side every render tick.
     * Disabling skips both contour generation and dark-backed text remapping. */
    public void configureOutline(boolean enabled) {
        outlineEnabled = enabled;
    }
    private final HudWeaponStatusLayer weaponLayer = new HudWeaponStatusLayer();
    private HudTargetProvider.WeaponStatusView weaponStatus;

    /** Read-only core view; null actively clears disconnected weapon data. */
    public void bindWeaponStatus(HudTargetProvider.WeaponStatusView view) {
        weaponStatus = view;
    }

    // scratch
    private final Vector3d fwd = new Vector3d();
    private final Vector3d right = new Vector3d();
    private final Vector3d up = new Vector3d();

    /** Core config GUI tab 3 push (via HudScreenBlockEntity): ladder layout params. */
    public void configure(double pitchStepDeg, double attSize, double attBorder,
                          boolean inputLine) {
        this.pitchStep = pitchStepDeg;
        this.attSize = attSize;
        this.attBorder = attBorder;
        this.inputLine = inputLine;
    }

    /** Flight-control status binding (HUD-移植交接说明 §2 飞控模式/耦合 rows):
     *  called every server tick by HudScreenBlockEntity with the same-structure
     *  core's READ-ONLY state (mode ordinal per ControlMode declaration order,
     *  coupledOn() effective value). hasCore=false clears both displays. */
    public void bindFlightStatus(int modeOrdinal, boolean coupled, boolean hasCore) {
        this.flightModeOrdinal = hasCore ? modeOrdinal : -1;
        this.flightCoupled = hasCore && coupled;
    }

    /** Calibration push for the lock-box projection (the same five params the
     *  core GUI tab 3 edits; v2 2026-10-26: eyeX/eyeY place ONLY the projection
     *  eye point — the client renderer no longer reads them); called every
     *  server tick by HudScreenBlockEntity. */
    public void configureLockProjection(double calibX, double calibY, double calibScale,
                                        double eyeX, double eyeY, double screenScale) {
        this.lockCalibX = calibX;
        this.lockCalibY = calibY;
        this.lockCalibScale = calibScale;
        this.lockCalibEyeX = eyeX;
        this.lockCalibEyeY = eyeY;
        this.lockScreenScale = screenScale;
    }

    /** Server-side lock-target binding; called every tick by
     *  HudScreenBlockEntity. A null snapshot disables the overlay (attitude
     *  picture then renders exactly as before). lockIds (primary first) and
     *  filterText come from the HUD-side filter chain (HudTrackFilter, §2.28
     *  + the 2026-09-24 multi-lock extension). redIds/taskTag/taskActive come
     *  from the SPI v3 channel task views (§2.29) — red boxes are core
     *  assignment feedback only. targetsOff (v3.1 no-lock notch): the roam
     *  layer is suppressed — the red task layer and the filter readout
     *  still draw (裁断稿 §3 末段). */
    public void bindHudTargets(Level level, ServerSubLevel sub, BlockPos hudPos,
                               HudTargetProvider.HudTargetSnapshot snapshot,
                               java.util.List<String> lockIds, String lockFilterText,
                               java.util.List<String> redIds, String taskTag,
                               boolean taskActive, boolean preciseTask, boolean targetsOff,
                               boolean commandHud) {
        this.lockLevel = level;
        this.lockSub = sub;
        this.lockPos = hudPos;
        this.lockSnapshot = snapshot;
        this.lockIds = lockIds;
        this.lockFilterText = lockFilterText;
        this.lockRedIds = redIds;
        this.lockTaskTag = taskTag;
        this.lockTaskActive = taskActive;
        this.lockPreciseTask = preciseTask;
        this.lockTargetsOff = targetsOff;
        this.lockCommandHud = commandHud;
    }

    @Override
    public void render(Canvas canvas, DisplayContext ctx) {
        cleanLayout = com.bridgecore.BCConfig.HUD_CLEAN_LAYOUT.get();
        pixelsPerDegree = (cleanLayout ? 5.0 : 8.0) * S;
        canvas.clear();
        if (!ctx.hasSignal()) {
            canvas.drawRect(CX - 124 * S, CY - 28 * S, 248 * S, 56 * S, 5);
            drawCjkTextCentered(canvas, CX, CY - 18 * S, "NO SIGNAL", 5, 48);
            if (outlineEnabled) contrastPass.apply(canvas);
            return;
        }

        // Ship basis vectors in world frame; grid axes are body axes, block FACING = bow.
        Quaterniond q = ctx.orientation();
        Direction f = ctx.facing();
        fwd.set(f.getStepX(), f.getStepY(), f.getStepZ());
        Direction r = f.getClockWise();
        right.set(r.getStepX(), r.getStepY(), r.getStepZ());
        up.set(0, 1, 0);
        q.transform(fwd);
        q.transform(right);
        q.transform(up);

        double pitch = Math.asin(clamp(fwd.y, -1, 1));      // nose-up positive, rad
        double roll = Math.atan2(-right.y, up.y);           // right-wing-down positive
        // Scroll follows pitch across the WHOLE ladder range: the old ±400 px
        // clamp froze the picture at ±50° (v15 report: ladder dies past ~55°).
        // Clamp to the ladder's own extent (±90° scaled) as a runaway guard
        // only; rung visibility is the clip window's business, same as the
        // legacy tan-projection window culling (startup.lua:2258).
        double maxPitchPx = LADDER_MAX_DEG * pixelsPerDegree * attSize;
        double pitchPx = clamp(Math.toDegrees(pitch) * pixelsPerDegree * attSize,
                -maxPitchPx, maxPitchPx);
        double cosR = Math.cos(roll), sinR = Math.sin(roll);
        clipTop = Math.max(LADDER_CLIP_TOP, (int) Math.round(canvas.height() * attBorder));
        clipBottom = Math.min(LADDER_CLIP_BOTTOM,
                canvas.height() - (int) Math.round(canvas.height() * attBorder));

        // ---- pitch ladder + moving horizon, clipped to the blueprint window
        //      (x 115..397, y 120..368 logical — attBorder can only shrink it;
        //      an empty window draws nothing) ----
        if (clipTop <= clipBottom) {
            canvas.pushClip(LADDER_CLIP_LEFT, clipTop,
                    LADDER_CLIP_RIGHT - LADDER_CLIP_LEFT, clipBottom - clipTop + 1);
            double displayStep = cleanLayout ? Math.max(10.0, pitchStep) : pitchStep;
            for (double deg = displayStep; deg <= LADDER_MAX_DEG + 1e-9; deg += displayStep) {
                ladderRung(canvas, deg, pitchPx, cosR, sinR);
                ladderRung(canvas, -deg, pitchPx, cosR, sinR);
            }
            // center horizon line (bright yellow, moves with attitude)
            if (horizonVisible(pitchPx, cosR)) {
                horizonSegment(canvas, -HORIZON_HALF, -HORIZON_GAP, 0, pitchPx, cosR, sinR, 4);
                horizonSegment(canvas, HORIZON_GAP, HORIZON_HALF, 0, pitchPx, cosR, sinR, 4);
            }
            canvas.popClip();
        }

        // ---- fixed flight reference (blueprint wings symbol + center circle;
        //      does NOT move with attitude; 3 px strokes so the diagonals read
        //      smooth) ----
        canvas.drawPolyline(new int[]{230 * S, 239 * S, 245 * S, 251 * S},
                new int[]{CY - 3 * S, CY - 3 * S, CY + 3 * S, CY + 3 * S}, 1, WINGS_W);
        canvas.drawPolyline(new int[]{261 * S, 267 * S, 273 * S, 282 * S},
                new int[]{CY + 3 * S, CY + 3 * S, CY - 3 * S, CY - 3 * S}, 1, WINGS_W);
        canvas.drawCircle(CX, CY, 2 * S, 1);
        canvas.drawCircle(CX, CY, 2 * S - 1, 1);

        // ---- legacy "Input Line": merged stick demand from the center
        //      (x = roll, y = pitch — stick forward = line up), length ∝ magnitude ----
        if (inputLine) {
            double ix = ctx.stickRoll(), iy = -ctx.stickPitch();
            double len = Math.sqrt(ix * ix + iy * iy);
            if (len > 1e-3) {
                double mag = Math.min(1.0, len) / len; // clamp magnitude, keep direction
                // 3 px (2026-10-30): at 1 px the contrast pass's 2 px outline halo
                // swallowed the stroke, rendering it half-black/half-yellow.
                canvas.drawThickLine(CX, CY, CX + (int) Math.round(ix * mag * INPUT_LINE_LEN),
                        CY + (int) Math.round(iy * mag * INPUT_LINE_LEN), 4, FRAME_W);
            }
        }

        // ---- static instrument frame (blueprint: rails + roll arc) ----
        drawInstrumentFrame(canvas);

        // ---- heading tape (blueprint): 3-digit readout + caret over a 5°
        //      tick grid; 15° labels show the true bearing ----
        double heading = (Math.toDegrees(Math.atan2(fwd.x, -fwd.z)) + 360.0) % 360.0;
        String hdgText = String.format(Locale.ROOT, "%03d", (int) Math.round(heading) % 360);
        OutlineTextRenderer.drawReadout(canvas, CX, 21 * S, hdgText, 30,
                90 * S, digitFont(), HudPalette.LADDER_WHITE);
        canvas.drawThickLine(CX - 4 * S, 35 * S, CX, 39 * S, 1, FRAME_W);
        canvas.drawThickLine(CX, 39 * S, CX + 4 * S, 35 * S, 1, FRAME_W);
        canvas.drawThickLine(139 * S, TAPE_Y, 373 * S, TAPE_Y, 8, FRAME_W);
        int tapeBase = (int) (Math.round(heading / 5.0) * 5);
        for (int d = -TAPE_HALF_RANGE_DEG; d <= TAPE_HALF_RANGE_DEG; d += 5) {
            int bearing = ((tapeBase + d) % 360 + 360) % 360;
            int x = CX + (int) Math.round((tapeBase + d - heading) * TAPE_PIX_PER_DEG);
            if (x < TAPE_LEFT || x > TAPE_RIGHT) continue;
            boolean labeled = bearing % 15 == 0;
            canvas.drawThickLine(x, TAPE_Y - (labeled ? 8 * S : 4 * S), x, TAPE_Y, 8, FRAME_W);
            if (labeled) {
                drawCjkTextCentered(canvas, x, TAPE_LABEL_Y,
                        bearing == 0 ? "N" : String.format(Locale.ROOT, "%03d", bearing),
                        8, CJK_LABEL);
            }
        }

        // ---- top-left: FLT / flight mode (read-only core binding) + P/R ----
        drawText(canvas, 28 * S, 28 * S - CJK_LABEL, "FLT / " + flightModeText(),
                8, CJK_LABEL);
        drawText(canvas, 28 * S, 43 * S - CJK_LABEL, "P " + Math.round(Math.toDegrees(pitch))
                + "   R " + Math.round(Math.toDegrees(roll)), 8, CJK_LABEL);

        // ---- SPD / ALT pods (blueprint drawReadouts) ----
        double speed = Math.sqrt(ctx.velX() * ctx.velX() + ctx.velY() * ctx.velY()
                + ctx.velZ() * ctx.velZ());
        canvas.drawPolyline(new int[]{28 * S, 89 * S, 99 * S, 89 * S, 28 * S},
                new int[]{CY - 15 * S, CY - 15 * S, CY, CY + 15 * S, CY + 15 * S}, 8, FRAME_W);
        canvas.drawPolyline(new int[]{484 * S, 423 * S, 413 * S, 423 * S, 484 * S},
                new int[]{CY - 15 * S, CY - 15 * S, CY, CY + 15 * S, CY + 15 * S}, 8, FRAME_W);
        drawOutlineCentered(canvas, SPD_LABEL_X, POD_LABEL_Y, "速度 / SPD", CJK_LABEL,
                OutlineTextRenderer.FONT_NOTO, HudPalette.LADDER_LABEL);
        drawOutlineCentered(canvas, ALT_LABEL_X, POD_LABEL_Y, "高度 / ALT", CJK_LABEL,
                OutlineTextRenderer.FONT_NOTO, HudPalette.LADDER_LABEL);
        // magnitude degrades precision (real-HUD convention), then the glyph
        // scale auto-fits the pod inner width so wide values stay in frame
        String spdText = speed >= 100.0
                ? String.format(Locale.ROOT, "%.0f", speed)
                : String.format(Locale.ROOT, "%.1f", speed);
        drawPodValue(canvas, SPD_VALUE_X, spdText);
        drawPodValue(canvas, ALT_VALUE_X, Long.toString(Math.round(ctx.posY())));
        drawCjkTextCentered(canvas, SPD_VALUE_X, POD_UNIT_Y, "m/s", 8, CJK_LABEL);
        drawCjkTextCentered(canvas, ALT_VALUE_X, POD_UNIT_Y, "m", 8, CJK_LABEL);
        if (ctx.gear() >= 0) {
            drawCjkTextCentered(canvas, SPD_VALUE_X, POD_AUX_Y,
                    "G " + String.format(Locale.ROOT, "%02d", ctx.gear() + 1), 1, CJK_LABEL);
        }
        drawCjkTextCentered(canvas, ALT_VALUE_X, POD_AUX_Y,
                String.format(Locale.ROOT, "%+.1f", ctx.velY()), 1, CJK_LABEL);
        if (flightModeOrdinal == 2) { // STARSHIP only — effective coupled value
            drawOutlineCentered(canvas, SPD_VALUE_X, POD_SUB_Y,
                    flightCoupled ? "耦合飞行" : "解耦飞行", CJK_LABEL,
                    OutlineTextRenderer.FONT_NOTO, HudPalette.LADDER_LABEL);
        }
        drawOutlineCentered(canvas, ALT_VALUE_X, POD_SUB_Y, "爬升 m/s", CJK_LABEL,
                OutlineTextRenderer.FONT_NOTO, HudPalette.LADDER_LABEL);

        // ---- ship energy cluster (blueprint): FE stock left, smoothed draw
        //      right, stock/capacity bar, END right. Same values and alarm
        //      thresholds as the pre-blueprint row; END stays the
        //      HudScreenBlockEntity EnduranceEstimator output (stock-decline
        //      measurement — do NOT recompute from draw/stored here). ----
        double totalFe = ctx.totalFe();
        double cap = ctx.totalFeCap();
        double draw = ctx.drawFePerTick();
        double endSec = ctx.endSeconds();
        String endText = Double.isInfinite(endSec) ? "INF"
                : endSec < 60 ? (int) endSec + "S"
                : endSec < 99 * 60 ? Math.min(99, (int) (endSec / 60)) + "M"
                : endSec / 3600.0 > 99 ? "99H"
                : String.format(Locale.ROOT, "%.1fH", endSec / 3600.0);
        int feColor = cap > 0 && totalFe < cap * 0.1 ? 5        // low total: red
                : endSec < 30 ? 4                               // < 30 s left: yellow
                : 7;                                            // cyan
        drawText(canvas, 28 * S, FE_Y, cap > 0 ? "FE " + compactFe(totalFe) : "FE --",
                feColor, CJK_LABEL);
        drawCjkTextRight(canvas, ENERGY_RIGHT_X, FE_Y,
                draw > 0.05 ? compactFe(draw) + "/T" : "--", 8, CJK_LABEL);
        if (cap > 0) {
            double ratio = clamp(totalFe / cap, 0, 1);
            canvas.fillRect(28 * S, ENERGY_BAR_Y, ENERGY_BAR_W, ENERGY_BAR_H, 8);
            int fillW = (int) Math.round(ENERGY_BAR_W * ratio);
            if (fillW > 0) {
                canvas.fillRect(28 * S, ENERGY_BAR_Y, fillW, ENERGY_BAR_H,
                        ratio > 0.5 ? 6 : ratio > 0.2 ? 4 : 5);
            }
        } else {
            canvas.hLine(28 * S, 28 * S + ENERGY_BAR_W, ENERGY_BAR_Y + ENERGY_BAR_H / 2, 8);
            drawCjkTextCentered(canvas, 28 * S + ENERGY_BAR_W / 2, END_Y, "--", 8, CJK_LABEL);
        }
        drawCjkTextRight(canvas, ENERGY_RIGHT_X, END_Y, "END " + endText, feColor, CJK_LABEL);

        // ---- lock-box overlay (fire-control SPI §2.27): conformal projection of
        //      locked targets through the same eye-calibrated pipeline as the
        //      client hologram. Drawn above the attitude picture; zero picture
        //      change when no provider yields a snapshot (lockSnapshot stays
        //      null). ----
        if (lockSnapshot != null) {
            lockLayer.render(canvas, ctx, lockSnapshot, lockIds, lockFilterText,
                    lockRedIds, lockTaskTag, lockTaskActive, lockPreciseTask, lockTargetsOff,
                    lockCommandHud, lockLevel, lockSub, lockPos,
                    new HudLockBoxLayer.Calib(lockScreenScale, lockCalibX, lockCalibY,
                            lockCalibScale, lockCalibEyeX, lockCalibEyeY));
        }
        weaponLayer.render(canvas, weaponStatus, lockLevel == null ? 0 : lockLevel.getGameTime());
        if (outlineEnabled) contrastPass.apply(canvas);
    }

    /** FLT line mode text: ControlMode ordinal -> blueprint abbreviation
     *  (OFF/DAMP/STAR); no core bound -> "--". */
    private String flightModeText() {
        return switch (flightModeOrdinal) {
            case 0 -> "OFF";
            case 1 -> "DAMP";
            case 2 -> "STAR";
            default -> "--";
        };
    }

    /** Static instrument frame (blueprint drawInstrumentStructure): side rails
     *  with outward ticks (the pod-height band 235<y<275 logical is skipped),
     *  diagonal end caps, the top roll arc with outward radial ticks and a
     *  white apex triangle. None of this moves with attitude — it is the
     *  gauge bezel. Everything draws at FRAME_W (2026-10-29): 1-2 px strokes
     *  break up on a 1080p panel under minification. */
    private static void drawInstrumentFrame(Canvas canvas) {
        for (int rail = 0; rail < 2; rail++) {
            int x = rail == 0 ? RAIL_LEFT_X : RAIL_RIGHT_X;
            int dir = rail == 0 ? -1 : 1;   // ticks point outward, toward the pods
            canvas.drawThickLine(x, RAIL_TOP, x, RAIL_BOTTOM, 8, FRAME_W);
            for (int i = 0; i < RAIL_TICK_COUNT; i++) {
                int y = RAIL_TICK_Y0 + i * RAIL_TICK_STEP;
                if (y > RAIL_TICK_SKIP_TOP && y < RAIL_TICK_SKIP_BOTTOM) continue;
                boolean major = i % 4 == 0;
                canvas.drawThickLine(x, y, x + dir * (major ? 10 * S : 5 * S), y,
                        8, FRAME_W);
            }
            canvas.drawThickLine(x, RAIL_TOP, x - dir * 5 * S, RAIL_TOP - 5 * S, 8, FRAME_W);
            canvas.drawThickLine(x, RAIL_BOTTOM, x - dir * 5 * S, RAIL_BOTTOM + 5 * S, 8, FRAME_W);
        }
        // 3 px arc = three concentric 1 px passes (drawArc has no width param)
        canvas.drawArc(CX, CY, ROLL_ARC_R - 1, -120, -60, 8);
        canvas.drawArc(CX, CY, ROLL_ARC_R, -120, -60, 8);
        canvas.drawArc(CX, CY, ROLL_ARC_R + 1, -120, -60, 8);
        for (int deg = -30; deg <= 30; deg += 15) {
            double a = Math.toRadians(-90 + deg);
            int len = (deg % 30 == 0 ? 7 : 4) * S;
            canvas.drawThickLine(CX + (int) Math.round(ROLL_ARC_R * Math.cos(a)),
                    CY + (int) Math.round(ROLL_ARC_R * Math.sin(a)),
                    CX + (int) Math.round((ROLL_ARC_R + len) * Math.cos(a)),
                    CY + (int) Math.round((ROLL_ARC_R + len) * Math.sin(a)), 8, FRAME_W);
        }
        canvas.drawThickLine(CX - 4 * S, CY - 94 * S, CX, CY - 100 * S, 1, FRAME_W);
        canvas.drawThickLine(CX, CY - 100 * S, CX + 4 * S, CY - 94 * S, 1, FRAME_W);
    }

    /** Pod big-number readout (2026-10-29 outline vertical slice): the digit
     *  font (BCConfig hud.digitFont A/B: Barlow vs Rajdhani) on the white
     *  alpha ladder; sized by visible ink height, shrunk to fit POD_TEXT_W
     *  and centered by actual ink bounds (see OutlineTextRenderer.drawReadout). */
    private static void drawPodValue(Canvas canvas, int cx, String text) {
        OutlineTextRenderer.drawReadout(canvas, cx, CY, text, OUTLINE_NUM,
                POD_TEXT_W - 8, digitFont(), HudPalette.LADDER_WHITE);
    }

    /** Digit font for the big-number readouts: BCConfig hud.digitFont
     *  (2026-10-29 A/B experiment; read per frame, no live-switch support —
     *  a mid-session edit just picks up at the next render, glyph caches
     *  are keyed per font so nothing goes stale). */
    private static int digitFont() {
        return com.bridgecore.BCConfig.HUD_DIGIT_FONT.get()
                == com.bridgecore.BCConfig.HudDigitFont.RAJDHANI
                ? OutlineTextRenderer.FONT_RAJDHANI : OutlineTextRenderer.FONT_BARLOW;
    }

    private static void drawOutlineCentered(Canvas canvas, int x, int y, String text,
                                            int sizePx, int fontId, int ladderBase) {
        OutlineTextRenderer.drawOutlineText(canvas,
                x - OutlineTextRenderer.outlineTextWidth(fontId, sizePx, text) / 2,
                y, text, sizePx, fontId, ladderBase);
    }

    private static void drawCjkTextCentered(Canvas canvas, int x, int y, String text,
                                            int color, int sizePx) {
        drawText(canvas, x - textWidth(text, sizePx) / 2, y, text, color, sizePx);
    }

    private static void drawCjkTextRight(Canvas canvas, int x, int y, String text,
                                         int color, int sizePx) {
        drawText(canvas, x - textWidth(text, sizePx), y, text, color, sizePx);
    }

    private static int textWidth(String text, int sizePx) {
        return OutlineTextRenderer.outlineTextWidth(OutlineTextRenderer.FONT_NOTO, sizePx, text);
    }

    private static void drawText(Canvas canvas, int x, int y, String text, int color, int sizePx) {
        OutlineTextRenderer.drawOutlineText(canvas, x, y, text, sizePx,
                OutlineTextRenderer.FONT_NOTO, HudPalette.textLadder(color));
    }

    /** One ladder rung at the given pitch angle (deg, up-positive); number
     *  right-aligned just left of the rung (blueprint: '+'-prefixed for
     *  positive angles). End ticks point UP for above-horizon rungs and DOWN
     *  for below-horizon ones (legacy/aviation cue: sign readable at a glance). */
    private void ladderRung(Canvas canvas, double deg, double pitchPx,
                            double cosR, double sinR) {
        double hy = deg * pixelsPerDegree;                      // horizon frame, up-positive
        // clip: transformed rung center must stay below the heading tape and on-canvas
        double[] c = toCanvas(0, hy, pitchPx, cosR, sinR);
        double cy = CY + c[1];
        if (cy < clipTop || cy > clipBottom) return;
        horizonSegment(canvas, -RUNG_HALF, -CENTER_GAP, hy, pitchPx, cosR, sinR, 8);
        horizonSegment(canvas, CENTER_GAP, RUNG_HALF, hy, pitchPx, cosR, sinR, 8);
        double tick = deg > 0 ? RUNG_TICK : -RUNG_TICK;
        rungTick(canvas, -RUNG_HALF, hy, hy + tick, pitchPx, cosR, sinR);
        rungTick(canvas, RUNG_HALF, hy, hy + tick, pitchPx, cosR, sinR);
        double abs = Math.abs(deg);
        String label = abs == Math.rint(abs) ? Integer.toString((int) abs)
                : String.format(Locale.ROOT, "%.1f", abs);
        label = (deg > 0 ? "+" : "-") + label;
        // right edge at horizon-frame x = -(RUNG_HALF + 10 logical), baseline
        // 4 logical px below the rung (label top ≈ rung - 16 canvas px)
        double[] n = toCanvas(-(RUNG_HALF + 10 * S), hy + 8 * S, pitchPx, cosR, sinR);
        drawText(canvas, CX + (int) Math.round(n[0]) - textWidth(label, CJK_LABEL),
                CY + (int) Math.round(n[1]), label, 8, CJK_LABEL);
    }

    /** The horizon line scrolls too; only draw it inside the ladder window. */
    private boolean horizonVisible(double pitchPx, double cosR) {
        double cy = CY + pitchPx * cosR;                    // canvas y of horizon center
        return cy >= clipTop && cy <= clipBottom;
    }

    /** Segment in the horizon frame from (hx0,hy) to (hx1,hy); transformed to
     *  canvas. 3 px stroke (2026-10-29): rungs and the horizon line are the
     *  most motion-critical marks on the gauge — even 2 px still broke up on
     *  a 1080p screen under minification despite the mip chain. */
    private void horizonSegment(Canvas canvas, double hx0, double hx1, double hy,
                                double pitchPx, double cosR, double sinR, int color) {
        double[] a = toCanvas(hx0, hy, pitchPx, cosR, sinR);
        double[] b = toCanvas(hx1, hy, pitchPx, cosR, sinR);
        canvas.drawThickLine(CX + (int) Math.round(a[0]), CY + (int) Math.round(a[1]),
                CX + (int) Math.round(b[0]), CY + (int) Math.round(b[1]), color, FRAME_W);
    }

    /** Vertical tick in the horizon frame at hx from hy0 to hy1 (rung end
     *  marker); 3 px stroke — it is diagonal whenever the ship rolls. */
    private void rungTick(Canvas canvas, double hx, double hy0, double hy1,
                          double pitchPx, double cosR, double sinR) {
        double[] a = toCanvas(hx, hy0, pitchPx, cosR, sinR);
        double[] b = toCanvas(hx, hy1, pitchPx, cosR, sinR);
        canvas.drawThickLine(CX + (int) Math.round(a[0]), CY + (int) Math.round(a[1]),
                CX + (int) Math.round(b[0]), CY + (int) Math.round(b[1]), 8, FRAME_W);
    }

    /**
     * Horizon frame (x right, y up) -> canvas offset (x right, y down).
     * Single source of truth for the transform. The ladder is WORLD-locked
     * (aviation convention): it counter-rotates against the ship.
     *   - pitch up (pitchPx > 0): every world direction sinks on screen, so the
     *     horizon moves DOWN — ly = hy - pitchPx.
     *   - roll right (right wing down, roll > 0): the real horizon appears
     *     tilted counter-clockwise (right end up), so the picture is rotated
     *     by +roll in the y-up frame; canvas y is then negated (y down).
     * (Sign convention verified by derivation + in-game roll/pitch tests,
     *  2026-09-05 — the earlier version moved WITH the ship, which reads as
     *  an airframe symbol, not a horizon.)
     */
    private double[] toCanvas(double hx, double hy, double pitchPx,
                              double cosR, double sinR) {
        double lx = hx * attSize, ly = hy * attSize - pitchPx;
        double vx = lx * cosR - ly * sinR;
        double vyUp = lx * sinR + ly * cosR;
        return new double[]{vx, -vyUp};
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** Compact FE formatting: 843 / 1.2K / 843K / 12M (capped at 999M). */
    private static String compactFe(double v) {
        if (v < 1000) {
            return Integer.toString((int) v);
        }
        if (v < 10_000) {
            return String.format(Locale.ROOT, "%.1fK", v / 1000.0);
        }
        if (v < 1_000_000) {
            return (int) (v / 1000.0) + "K";
        }
        return Math.min(999, (int) (v / 1_000_000.0)) + "M";
    }
}
