package com.bridgecore.display;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * HUD lock-box layer (contract: shared handbook §2.28, 2026-09-23; §2.27's
 * primary/others model retired). Owned and invoked by {@link
 * AttitudeIndicatorApp}; draws nothing when the snapshot is null or empty, so
 * a HUD with no provider looks exactly as before.
 *
 * Inputs: the HUD filter chain's lock list ({@code lockedIds}: primary first,
 * then same-species multi-locks, 2026-09-24) and filter-state readout
 * ({@code filterText}, e.g. "MONSTER/ZOMBIE") from {@link HudTrackFilter},
 * plus the SPI v3 task layer (§2.29, 2026-10-30 落地②): {@code redIds} =
 * the active channel's assignedTargetIds (authoritative core assignment
 * feedback — the ONLY red boxes), {@code taskTag} = "FCS"/"LOCK" source
 * marker, {@code taskActive} drives the filter-text color, {@code commandHud}
 * adds the CMD marker. Rendering: roam primary = YELLOW box + blueprint
 * target-detail block (species / HP bar / distance); secondary candidates =
 * yellow boxes; red boxes = the core's assignedTargetIds (drawn through the
 * shared aux-box path in red, NOT gated by the no-lock notch — OFF 档照常
 * 显示任务层, 裁断稿 §3 末段); every other track is a dim triangle
 * (skipping lockedIds ∪ redIds). A roam primary that projects off-canvas or
 * sits behind the eye plane gets a yellow edge triangle pointing at it
 * instead of a box; other out-of-frustum tracks are not drawn.
 *
 * Projection: the legacy getLockPos (startup.lua:2414) re-derived for our
 * server-rendered canvas, with the 2026-10-26 v2 OFF-AXIS correction: the eye
 * is a fixed block-relative point (calibEyeX in front of the panel, calibEyeY
 * above EYE_BASE_Y) that does NOT move with the panel, so E−C generally has
 * in-plane components and the plain gnomonic form (which assumes the eye on
 * the panel center's normal) misplaces the boxes. project() adds the center
 * offset terms (k = W/2a, d = (E−C)·N): px = W/2 + k·[(E−C)·R + d·(T−E)·R/z],
 * py = W/2 − k·[(E−C)·U + d·(T−E)·U/z] — it degenerates to the old formula
 * on-axis and pins a ray through the panel center to canvas center regardless
 * of panel offset. We keep the plain gnomonic form (no tan(asin(...)) sundial
 * hybrid); identical at small angles, simpler, and the edge deviation is
 * inside the panel's margin of error.
 *
 * Frames: ship-body frame = plot grid axes; local→world via
 * logicalPose().transformPosition (same call the fire-control turret aims with).
 * Freshness (contract revision ②): entity ids resolve per render tick through
 * ServerLevel.getEntity; SHIP ids and misses fall back to the snapshot pos.
 *
 * Entry animation: 12 frames, legacy spin-and-shrink — frame a (counting
 * 12→1) draws the square rotated (a−1)·12° and scaled min(1.5, 0.82^-(a−1)),
 * cycling cyan→yellow→orange; it lands exactly on the final (distance-scaled)
 * size and the lock color (primary red, secondary orange) on the last frame.
 * Settled boxes carry the distance text right of the box.
 * Aux boxes animate independently of the primary (per-id frame counters).
 *
 * 2026-10-28 fidelity pass: layout constants follow the same 512-logical →
 * 1024 canvas mapping as AttitudeIndicatorApp ({@link #S}); the projected
 * geometry scales itself (k = W/2a tracks canvas.width()). Lock boxes draw
 * 3 px strokes, the edge triangle 2 px, so diagonals read smooth.
 */
public final class HudLockBoxLayer {
    /** Layout scale: blueprint/512-logical px -> the 1024×1024 canvas. */
    private static final int S = 2;
    /** CJK grade for labels: TRUE 12px font ×2 (the interim 16 = 8px×2 read
     *  blurry, 2026-10-28 user acceptance). */
    private static final int CJK_LABEL = 26;
    /** CJK grade for the target name: TRUE 12px font ×3. */
    private static final int CJK_NAME = 36;
    /** Stroke widths: lock boxes / off-canvas edge triangle. */
    private static final int BOX_W = 3;
    private static final int EDGE_W = 2;

    private static final int COLOR_BOX = 5;          // red (primary lock)
    private static final int COLOR_TRI = 7;      // visible cyan awareness markers
    private static final int COLOR_DIST = 7;     // cyan (primary readout)
    private static final int COLOR_LABEL = 1;    // white
    private static final int COLOR_HP = 6;       // green
    private static final int COLOR_HP_LOST = 5;  // red (green-over-red bar)
    private static final int COLOR_FILTER = 8;   // dim blue filter-state readout
    private static final int COLOR_FCS = 4;      // yellow "FCS" pin source tag

    private static final int ENTRY_FRAMES = 12;
    private static final double ENTRY_ROT_STEP_DEG = 12.0;
    private static final double ENTRY_SCALE_STEP = 0.82;
    /** Legacy look (2026-09-23 ruling, replacing the grow-in version): the box
     *  starts BIG and shrinks into place — scale = 0.82^-(a-1), capped at
     *  1.5x final size (2.5x spun too wide to read; the cap holds while the
     *  spin catches up, the tail then shrinks x0.82/frame and lands exactly
     *  on 1.0 at the last frame). */
    private static final double ENTRY_START_SCALE = 1.5;
    /** Color cycle while the entry animation runs (cyan -> yellow -> orange);
     *  settled boxes go back to their fixed lock colors. */
    private static final int[] ENTRY_COLORS = {7, 4, 11};
    /** Apparent-size model (2026-09-22 acceptance: a fixed-angle box eats the
     *  picture on far targets — 35 m skeleton had a giant box): box half-width
     *  in canvas px = focal·TARGET_HALF_SIZE/dist, so the box tracks the
     *  target's angular size. TARGET_HALF_SIZE = 0.55 m (2026-09-23: 0.9 still
     *  read too big; 35 m skeleton now gets a ~13 px box). */
    private static final double TARGET_HALF_SIZE = 0.55;
    /** Clamp band: never smaller than a readable marker, never larger than
     *  1/12 of the canvas width (was 1/8 — tightened 2026-09-23) so a
     *  point-blank target cannot swallow the attitude picture. */
    private static final double BOX_MIN_PX = 10.0 * S;
    /** Triangles are half the box's angular size and clamp tighter — they are
     *  situation markers, not readouts. Cap follows the box cap proportionally
     *  (w/16 -> w/24 with the 2026-09-23 tightening). Display capped at 16. */
    private static final double TRI_MIN_PX = 2.0 * S;
    private static final int MAX_TRIANGLES = 16;
    /** Reject cone: target behind the eye plane or projected way off-panel. */
    private static final double MIN_FORWARD = 0.1;
    private static final int OFFSCREEN_MARGIN = 40 * S;
    /** Off-canvas primary indicator (2026-09-25): red edge triangle this many
     *  px inside the frame, half-size clamped to [4, 8] logical (w/32 nominal). */
    private static final int EDGE_MARGIN = 8 * S;
    private static final double EDGE_TRI_MIN = 4.0 * S;
    private static final double EDGE_TRI_MAX = 8.0 * S;

    // Target-detail block (2026-10-28 HUD blueprint, hud-handoff §4): x 28..185,
    // rail y84, title baseline 99, name/distance baseline 118, HP bar y128 (3 px),
    // HP numbers right-aligned baseline 132 — all logical, ×S below; y tops are
    // 2x baseline minus the font height. Drawn only while a primary exists.
    private static final int INFO_X = 28 * S;
    private static final int INFO_RIGHT_X = 185 * S;
    private static final int INFO_RAIL_Y = 84 * S;
    private static final int INFO_TITLE_Y = 99 * S - CJK_LABEL;
    private static final int INFO_NAME_Y = 118 * S - CJK_NAME;
    private static final int INFO_DIST_Y = 118 * S - CJK_LABEL;
    private static final int INFO_BAR_Y = 128 * S;
    private static final int INFO_BAR_W = 98 * S;
    private static final int INFO_BAR_H = 3 * S;
    private static final int INFO_HP_Y = 132 * S - CJK_LABEL;
    // Filter-state readout + pin source tag: top-right rows (blueprint mode
    // rows), right-aligned at x484 logical, baselines 28/43.
    private static final int FILTER_RIGHT_X = 484 * S;
    private static final int INFO_FILTER_Y = 28 * S - CJK_LABEL;
    private static final int PIN_TAG_Y = 43 * S - CJK_LABEL;

    /** Panel center height above the block's own base at calibY=0 (2026-10-26
     *  v2: fixed anchor replaces the old anchorHalf = max(half, 1.25)
     *  bottom-edge rule that coupled size into position). Chosen so the
     *  reference panel (screenScale 1, calibScale 1 -> half 1.25) floats its
     *  bottom edge 1.6 blocks above the block top: 1.0 (block top) + 1.6
     *  (clearance — a one-block obstacle in front no longer occludes) + 1.25
     *  (reference half) = 3.85. Single source of truth — HudScreenRenderer
     *  uses the same constant. */
    public static final double PANEL_ANCHOR_Y = 3.85;
    /** Nominal pilot eye height above the block base at calibEyeY=0 (2026-10-26
     *  v2). Equals the legacy default panel center height 1.1 + 1.25 = 2.35,
     *  so migrated default saves keep their effective eye point exactly. The
     *  eye is FIXED to the block: moving the panel (calibX/calibY) never moves
     *  it, and the eye params never move the panel. */
    public static final double EYE_BASE_Y = 2.35;

    /** HUD calibration bundle (config GUI tab 3 + the block's own scale).
     *  v2 semantics (2026-10-26): screenScale/scale size the panel around its
     *  anchor center (half = 1.25·screenScale·scale — the old eyeX/2 factor
     *  was a Java-side invention; the legacy Lua never scaled the physical
     *  panel by eye distance); offX/offY position it. eyeX/eyeY are COCKPIT
     *  GEOMETRY compensation — they place the projection eye point only. */
    public record Calib(double screenScale, double offX, double offY, double scale,
                        double eyeX, double eyeY) {
    }

    /** Panel frame + pilot eye in world space, shared verbatim between the
     *  projection below and HudTrackFilter's LOS raycast (v15: the lock is what
     *  the PILOT can see — do not fork this math). Package-visible.
     *  2026-10-26 v2: panel center pinned to the fixed anchor (PANEL_ANCHOR_Y)
     *  + offY, sized around that center (half = 1.25·screenScale·scale — size
     *  NEVER moves the panel anymore); the eye is a fixed block-relative point
     *  (eyeX in front of the panel, EYE_BASE_Y + eyeY above the block base,
     *  no offX/offY) — moving the panel never moves the eye and vice versa.
     *  Null when the panel half-size degenerates (bad calibration). */
    record PanelGeometry(Vector3d centerW, Vector3d rightW, Vector3d upW,
                         Vector3d normW, Vector3d eye, double half) {
    }

    static @Nullable PanelGeometry panelGeometry(@Nullable ServerSubLevel sub,
                                                 BlockPos hudPos, net.minecraft.core.Direction facing,
                                                 Quaterniondc shipQ, Calib cal) {
        double half = 1.25 * cal.screenScale() * cal.scale();
        if (half <= 0) return null;
        double theta = Math.toRadians(-facing.toYRot() + 180.0);
        double cos = Math.cos(theta), sin = Math.sin(theta);

        Vector3d centerLocal = new Vector3d(hudPos.getX() + 0.5,
                hudPos.getY() + PANEL_ANCHOR_Y, hudPos.getZ() + 0.5)
                .add(yawRot(cal.offX(), cal.offY(), 0, cos, sin));
        Vector3d rightL = yawRot(1, 0, 0, cos, sin);
        Vector3d normL = yawRot(0, 0, 1, cos, sin);
        Vector3d upL = new Vector3d(0, 1, 0);
        // Eye: fixed block-relative point — eyeX in front of the panel along
        // its normal, EYE_BASE_Y + eyeY above the block base. Deliberately NOT
        // offset by (offX, offY): the panel moves, the eye stays.
        Vector3d eyeLocal = new Vector3d(hudPos.getX() + 0.5,
                hudPos.getY() + EYE_BASE_Y + cal.eyeY(), hudPos.getZ() + 0.5)
                .add(yawRot(0, 0, cal.eyeX(), cos, sin));

        Vector3d centerW = new Vector3d(centerLocal);
        Vector3d eye = new Vector3d(eyeLocal);
        if (sub != null) {
            Pose3d pose = sub.logicalPose();
            centerW = pose.transformPosition(centerLocal, new Vector3d());
            eye = pose.transformPosition(eyeLocal, new Vector3d());
        }
        Vector3d rightW = shipQ.transform(new Vector3d(rightL));
        Vector3d upW = shipQ.transform(new Vector3d(upL));
        Vector3d normW = shipQ.transform(new Vector3d(normL));
        return new PanelGeometry(centerW, rightW, upW, normW, eye, half);
    }

    // entry-animation state: primary (single) + aux boxes (per id: the
    // secondary multi-locks share this map)
    private @Nullable String animId;
    private int animFrames;
    private final java.util.Map<String, Integer> boxAnims = new java.util.HashMap<>();

    public void render(Canvas canvas, DisplayApp.DisplayContext ctx,
                       HudTargetProvider.HudTargetSnapshot snap,
                       java.util.List<String> lockedIds, @Nullable String filterText,
                       java.util.List<String> redIds, @Nullable String taskTag,
                       boolean taskActive, boolean preciseTask, boolean targetsOff, boolean commandHud,
                       Level level, @Nullable ServerSubLevel sub, BlockPos hudPos, Calib cal) {
        int w = canvas.width();
        int h = canvas.height();
        int cx = w / 2, cy = h / 2;

        // Panel geometry via the shared helper (HudTrackFilter's LOS raycast
        // uses the same eye — the lock is what the pilot can see).
        PanelGeometry geo = panelGeometry(sub, hudPos, ctx.facing(), ctx.orientation(), cal);
        if (geo == null) return;
        Vector3d rightW = geo.rightW(), upW = geo.upW(), normW = geo.normW(), eye = geo.eye();
        // Off-axis gnomonic terms (2026-10-26 v2): k scales panel units to
        // canvas px; d = eye distance from the panel center along its normal.
        // The eye is a fixed block-relative point, NOT on the center normal
        // once the panel is offset — project() adds the E−C center terms.
        // kD (canvas px per unit tangent, = the old on-axis focal) scales the
        // apparent-size boxes/triangles.
        double k = w / (2.0 * geo.half());
        double dEye = new Vector3d(eye).sub(geo.centerW()).dot(normW);
        if (dEye <= 0) return; // degenerate: eye at/behind the panel plane
        double kD = k * dEye;

        double boxMax = w / 12.0;
        double triMax = w / 24.0;

        // Resolve the box-carrying tracks: lockedIds[0] = roam primary
        // (YELLOW pre-lock box), lockedIds[1..] = explicit-filter survivors;
        // redIds (SPI v3) always take precedence over candidate yellow.
        java.util.Map<String, HudTargetProvider.Track> byId = new java.util.HashMap<>();
        for (HudTargetProvider.Track t : snap.tracks()) {
            byId.put(t.id(), t);
        }
        // Precise mode keeps its authoritative anchor, independent of roam focus.
        HudTargetProvider.Track primary = preciseTask
                ? (redIds.isEmpty() ? null : byId.get(redIds.get(0)))
                : (lockedIds.isEmpty() ? null : byId.get(lockedIds.get(0)));
        java.util.List<HudTargetProvider.Track> secondaries = new java.util.ArrayList<>();
        for (int i = 1; !preciseTask && i < lockedIds.size(); i++) {
            HudTargetProvider.Track t = byId.get(lockedIds.get(i));
            if (t != null) {
                secondaries.add(t);
            }
        }

        // ---- roam primary: YELLOW pre-lock box + entry animation + settled
        //      distance + info; off-canvas / behind the eye -> edge triangle.
        //      When the core has ACTUALLY assigned this very target (redIds)
        //      the authoritative state wins: box + indicator go red. ----
        if (primary != null) {
            int primaryColor = redIds.contains(primary.id()) ? COLOR_BOX : COLOR_FCS;
            Vector3d p = freshPos(level, primary.id(), primary.pos());
            double[] proj = project(p, eye, geo.centerW(), rightW, upW, normW, k, dEye, cx, cy, w, h);
            if (proj != null) {
                // Angular size: box tracks kD·halfSize/dist, clamped.
                double boxHalf = clamp(kD * TARGET_HALF_SIZE / proj[2], BOX_MIN_PX, boxMax);
                if (!primary.id().equals(animId)) {
                    animId = primary.id();
                    animFrames = ENTRY_FRAMES;
                }
                if (drawLockBox(canvas, proj[0], proj[1], boxHalf, animFrames,
                        primaryColor, proj[2])) {
                    animFrames--;
                }
            } else {
                drawEdgeIndicator(canvas, p, eye, rightW, upW, cx, cy, w, h, primaryColor);
            }
            drawInfoArea(canvas, level, primary, p, eye);
        } else {
            animId = null;
            animFrames = 0;
        }

        // ---- aux boxes: secondary roam multi-locks (orange), per-id entry
        //      animations in one shared map. Skipped in the v3.1 no-lock
        //      notch (targetsOff): roam layer fully off. ----
        java.util.Set<String> liveAux = new java.util.HashSet<>();
        if (!targetsOff) {
            for (HudTargetProvider.Track t : secondaries) {
                drawAuxBox(canvas, level, t, redIds.contains(t.id()) ? COLOR_BOX : COLOR_FCS, liveAux,
                        eye, geo.centerW(), rightW, upW, normW, k, dEye, kD, cx, cy, w, h, boxMax);
            }
        }
        // ---- red boxes (SPI v3 §2.29): the core's assignedTargetIds — the
        //      ONLY authoritative lock display ("HUD 发出请求" ≠ "炮塔已实锁").
        //      NOT gated by targetsOff: OFF 档照常显示任务层 (裁断稿 §3 末段).
        //      Skips ids already drawn by the roam layer (no double box). ----
        for (String id : redIds) {
            if (primary != null && id.equals(primary.id())) continue;
            if (!preciseTask && !targetsOff && lockedIds.contains(id)) continue;
            HudTargetProvider.Track t = byId.get(id);
            if (t == null) continue;
            drawAuxBox(canvas, level, t, COLOR_BOX, liveAux,
                    eye, geo.centerW(), rightW, upW, normW, k, dEye, kD, cx, cy, w, h, boxMax);
        }
        boxAnims.keySet().retainAll(liveAux);

        // ---- everyone else: dim triangles, capped (roam layer — off in the
        //      v3.1 no-lock notch; the red task layer above survives) ----
        int drawn = 0;
        if (!targetsOff) {
            for (HudTargetProvider.Track t : snap.tracks()) {
                if (drawn >= MAX_TRIANGLES) break;
                if ((!preciseTask && lockedIds.contains(t.id())) || redIds.contains(t.id())) continue;
                Vector3d p = freshPos(level, t.id(), t.pos());
                double[] proj = project(p, eye, geo.centerW(), rightW, upW, normW, k, dEye, cx, cy, w, h);
                if (proj == null) continue;
                int th = (int) Math.round(
                        clamp(kD * TARGET_HALF_SIZE / proj[2] * 0.5, TRI_MIN_PX, triMax));
                int px = (int) proj[0], py = (int) proj[1];
                canvas.drawPolyline(new int[]{px - th, px, px + th, px - th},
                        new int[]{py + th - 1, py - th, py + th - 1, py + th - 1}, COLOR_TRI, 2);
                drawn++;
            }
        }

        // ---- filter-state readout (§2.28: no GUI echo; HUD corner only) —
        //      blueprint top-right mode row, right-aligned at x484 logical.
        //      Color (SPI v3): active task -> red, no-lock notch -> flight
        //      cyan, otherwise (roam/pre-lock) -> yellow. ----
        if (filterText != null) {
            int color = taskActive ? COLOR_BOX : targetsOff ? COLOR_DIST : COLOR_FCS;
            drawText(canvas, FILTER_RIGHT_X - textWidth(filterText, CJK_LABEL),
                    INFO_FILTER_Y, filterText, color, CJK_LABEL);
        }
        // Task source tag (SPI v3 §2.29), second top-right row: CONSOLE-sourced
        // task = yellow FCS; HUD-sourced = red LOCK; the command HUD carries an
        // additional CMD marker. No task -> nothing.
        if (taskTag != null) {
            String tag = commandHud ? taskTag + " CMD" : taskTag;
            int tagColor = "FCS".equals(taskTag) ? COLOR_FCS : COLOR_BOX;
            drawText(canvas, FILTER_RIGHT_X - textWidth(tag, CJK_LABEL),
                    PIN_TAG_Y, tag, tagColor, CJK_LABEL);
        } else if (commandHud) {
            drawText(canvas, FILTER_RIGHT_X - textWidth("CMD", CJK_LABEL),
                    PIN_TAG_Y, "CMD", COLOR_DIST, CJK_LABEL);
        }
    }

    /** Off-frustum primary indicator (2026-09-25): a small triangle on the
     *  canvas frame, pointing at the roam primary when it projects outside the
     *  canvas or sits behind the eye plane. Color follows the primary box
     *  (yellow pre-lock / red when the core actually assigned it). Direction
     *  comes from the raw (right, up) components of the eye->target vector —
     *  NOT the gnomonic projection, which divides by depth and flips sign
     *  behind the eye. (For small angles these components ARE focal-scaled
     *  yaw/pitch deltas; for a target dead ahead/behind they degenerate and
     *  nothing is drawn.) The direction is pushed from the canvas center until
     *  it hits the frame, EDGE_MARGIN px inside, and the triangle's apex
     *  points outward along it. */
    private static void drawEdgeIndicator(Canvas canvas, Vector3d target, Vector3d eye,
                                          Vector3d rightW, Vector3d upW,
                                          int cx, int cy, int w, int h, int color) {
        Vector3d d = new Vector3d(target).sub(eye);
        double dx = d.dot(rightW);
        double dy = -d.dot(upW); // canvas y grows downward
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6) return; // on the view axis — no meaningful direction
        dx /= len;
        dy /= len;
        // ray (cx,cy) + t·(dx,dy) against the inset frame rectangle
        double t = Double.MAX_VALUE;
        if (dx > 1e-9) t = Math.min(t, (w - EDGE_MARGIN - cx) / dx);
        else if (dx < -1e-9) t = Math.min(t, (EDGE_MARGIN - cx) / dx);
        if (dy > 1e-9) t = Math.min(t, (h - EDGE_MARGIN - cy) / dy);
        else if (dy < -1e-9) t = Math.min(t, (EDGE_MARGIN - cy) / dy);
        if (t == Double.MAX_VALUE || t <= 0) return;
        double px = cx + dx * t, py = cy + dy * t;
        double a = Math.atan2(dy, dx);
        double s = clamp(w / 32.0, EDGE_TRI_MIN, EDGE_TRI_MAX);
        // apex along the direction; base corners swept back ±2.5 rad (~143 deg)
        int ax = (int) Math.round(px + Math.cos(a) * s);
        int ay = (int) Math.round(py + Math.sin(a) * s);
        int bx = (int) Math.round(px + Math.cos(a + 2.5) * s);
        int by = (int) Math.round(py + Math.sin(a + 2.5) * s);
        int cx2 = (int) Math.round(px + Math.cos(a - 2.5) * s);
        int cy2 = (int) Math.round(py + Math.sin(a - 2.5) * s);
        canvas.drawThickLine(ax, ay, bx, by, color, EDGE_W);
        canvas.drawThickLine(bx, by, cx2, cy2, color, EDGE_W);
        canvas.drawThickLine(cx2, cy2, ax, ay, color, EDGE_W);
    }

    /** One aux lock box (secondary multi-lock): projects the track, registers
     *  it as live, and draws the shared entry-animation / settled-box path
     *  with the given settle color. No-op off-frustum. */
    private void drawAuxBox(Canvas canvas, Level level, HudTargetProvider.Track t,
                            int settleColor, java.util.Set<String> liveAux,
                            Vector3d eye, Vector3d center, Vector3d rightW, Vector3d upW,
                            Vector3d normW, double k, double dEye, double kD,
                            int cx, int cy, int w, int h, double boxMax) {
        Vector3d p = freshPos(level, t.id(), t.pos());
        double[] proj = project(p, eye, center, rightW, upW, normW, k, dEye, cx, cy, w, h);
        if (proj == null) return;
        liveAux.add(t.id());
        double boxHalf = clamp(kD * TARGET_HALF_SIZE / proj[2], BOX_MIN_PX, boxMax);
        int frames = boxAnims.getOrDefault(t.id(), ENTRY_FRAMES);
        if (drawLockBox(canvas, proj[0], proj[1], boxHalf, frames, settleColor, proj[2])) {
            boxAnims.put(t.id(), frames - 1);
        }
    }

    /** One lock box: while {@code frames > 0} draws the legacy entry animation
     *  (starts at 1.5x, shrinks x0.82 and spins 12 deg per frame, cycling
     *  ENTRY_COLORS; no distance text yet) and returns true (caller decrements
     *  its frame counter); settled (frames <= 0) draws the final box in
     *  {@code settleColor} plus the distance at its right edge. */
    private static boolean drawLockBox(Canvas canvas, double px, double py, double boxHalf,
                                       int frames, int settleColor, double dist) {
        if (frames > 0) {
            double s = Math.min(ENTRY_START_SCALE, Math.pow(ENTRY_SCALE_STEP, -(frames - 1)));
            double rot = Math.toRadians((frames - 1) * ENTRY_ROT_STEP_DEG);
            int color = settleColor; // Animation must not impersonate a different lock state.
            drawBox(canvas, px, py, boxHalf * s, rot, color);
            return true;
        }
        drawBox(canvas, px, py, boxHalf, 0, settleColor);
        // distance rides the box's right edge (scaled box, not fixed)
        drawText(canvas, (int) Math.round(px + boxHalf) + 4 * S, (int) py - 5 * S,
                Integer.toString((int) dist), COLOR_DIST, 22);
        return false;
    }

    /** Y-axis rotation (MC/JOML convention: +90° maps +Z to +X). */
    private static Vector3d yawRot(double x, double y, double z, double cos, double sin) {
        return new Vector3d(x * cos + z * sin, y, -x * sin + z * cos);
    }

    /** Off-axis gnomonic projection into canvas coords (2026-10-26 v2); null
     *  when behind the eye or projected off the panel (contract: out-of-frustum
     *  targets are dropped). The eye sits OFF the panel center's normal whenever
     *  the panel is offset from the anchor, so the center terms matter:
     *    k = W/(2a), d = (E−C)·N, z = −(T−E)·N   (d > 0, z >= MIN_FORWARD)
     *    px = W/2 + k·[(E−C)·R + d·(T−E)·R/z]
     *    py = W/2 − k·[(E−C)·U + d·(T−E)·U/z]
     *  On-axis ((E−C)⊥R,U) this degenerates to the old focal formula; a ray
     *  through the panel center always lands on canvas center. */
    private static double[] project(Vector3d target, Vector3d eye, Vector3d center,
                                    Vector3d right, Vector3d up, Vector3d norm,
                                    double k, double dEye,
                                    int cx, int cy, int w, int h) {
        Vector3d t = new Vector3d(target).sub(eye);          // T−E
        double z = -t.dot(norm);                             // forward is negative
        if (z < MIN_FORWARD) return null;
        Vector3d e = new Vector3d(eye).sub(center);          // E−C
        var point = HudProjectionMath.project(t.dot(right), t.dot(up), z,
                e.dot(right), e.dot(up), dEye);
        if (point == null) return null;
        double sx = cx + k * point.x();
        double sy = cy - k * point.y();
        if (sx < -OFFSCREEN_MARGIN || sx > w + OFFSCREEN_MARGIN
                || sy < -OFFSCREEN_MARGIN || sy > h + OFFSCREEN_MARGIN) return null;
        return new double[]{sx, sy, t.length()};
    }

    /** Freshness: entity ids resolve live; SHIP uuids / parse misses / unloaded
     *  entities fall back to the snapshot position. */
    static Vector3d freshPos(Level level, String id, Vec3 snapshotPos) {
        if (level instanceof ServerLevel serverLevel) {
            try {
                Entity e = serverLevel.getEntity(UUID.fromString(id));
                if (e != null) {
                    Vec3 v = e.position();
                    return new Vector3d(v.x, v.y + e.getBbHeight() * 0.5, v.z);
                }
            } catch (IllegalArgumentException ignored) {
                // SHIP ids are SubLevel uuids — no entity to resolve
            }
        }
        return new Vector3d(snapshotPos.x, snapshotPos.y, snapshotPos.z);
    }

    /** Closed square centered at (px,py), rotated by rot rad in canvas space,
     *  3 px strokes (1024 canvas: diagonals during the entry spin read smooth). */
    private static void drawBox(Canvas canvas, double px, double py, double half, double rot, int color) {
        double rc = Math.cos(rot), rs = Math.sin(rot);
        int[] xs = new int[4];
        int[] ys = new int[4];
        double[][] corners = {{-half, -half}, {half, -half}, {half, half}, {-half, half}};
        for (int i = 0; i < 4; i++) {
            xs[i] = (int) Math.round(px + corners[i][0] * rc - corners[i][1] * rs);
            ys[i] = (int) Math.round(py + corners[i][0] * rs + corners[i][1] * rc);
        }
        for (int i = 0; i < 4; i++) {
            canvas.drawThickLine(xs[i], ys[i], xs[(i + 1) % 4], ys[(i + 1) % 4], color, BOX_W);
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** Blueprint target-detail block (index.html drawTargets): rail + title
     *  (always "TRK / 目标" — SPI v3 dropped the console-pin title swap; the
     *  task source lives on the top-right tag row), species name in CJK36
     *  pixel-truncated to fit the right-aligned distance (eye-to-target
     *  meters, same eye as the projection), then the HP bar (dim base +
     *  green/yellow/red fill by the resourceBar thresholds) and health
     *  numbers. Health is re-read live when the entity resolves; unknown
     *  health (ships, unresolved entities) hides bar and numbers — no
     *  fictional 20/20. */
    private void drawInfoArea(Canvas canvas, Level level, HudTargetProvider.Track track,
                              Vector3d pos, Vector3d eye) {
        canvas.drawThickLine(INFO_X, INFO_RAIL_Y, INFO_RIGHT_X, INFO_RAIL_Y, COLOR_FILTER, BOX_W);
        canvas.drawThickLine(INFO_X, INFO_RAIL_Y, INFO_X, INFO_RAIL_Y + 6 * S, COLOR_DIST, BOX_W);
        // 2026-10-29 outline vertical slice: title + species name go through
        // OutlineTextRenderer (Noto on the dim-blue / white alpha ladders);
        // distance and HP numbers stay dot-matrix CJK.
        OutlineTextRenderer.drawOutlineText(canvas, INFO_X, INFO_TITLE_Y,
                "TRK / 目标", CJK_LABEL,
                OutlineTextRenderer.FONT_NOTO, HudPalette.LADDER_LABEL);
        String distText = (int) Math.round(pos.distance(eye)) + " m";
        int distW = textWidth(distText, CJK_LABEL);
        int nameMaxW = INFO_RIGHT_X - INFO_X - distW - 8 * S;
        if (nameMaxW > 0) {
            OutlineTextRenderer.drawOutlineText(canvas, INFO_X, INFO_NAME_Y,
                    truncateName(track.species(), nameMaxW), CJK_NAME,
                    OutlineTextRenderer.FONT_NOTO, HudPalette.LADDER_WHITE);
        }
        drawText(canvas, INFO_RIGHT_X - distW, INFO_DIST_Y, distText, COLOR_LABEL, CJK_LABEL);

        Double health = track.health();
        Double maxHealth = track.maxHealth();
        if (level instanceof ServerLevel serverLevel) {
            try {
                if (serverLevel.getEntity(UUID.fromString(track.id())) instanceof LivingEntity le) {
                    health = (double) le.getHealth();
                    maxHealth = (double) le.getMaxHealth();
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (health == null || maxHealth == null || maxHealth <= 0) return;
        double frac = Math.max(0, Math.min(1, health / maxHealth));
        canvas.fillRect(INFO_X, INFO_BAR_Y, INFO_BAR_W, INFO_BAR_H, COLOR_FILTER);
        int fillW = (int) Math.round(INFO_BAR_W * frac);
        if (fillW > 0) {
            canvas.fillRect(INFO_X, INFO_BAR_Y, fillW, INFO_BAR_H,
                    frac > 0.5 ? COLOR_HP : frac > 0.2 ? COLOR_FCS : COLOR_HP_LOST);
        }
        String hpText = (int) Math.ceil(health) + "/" + (int) Math.ceil(maxHealth);
        drawText(canvas, INFO_RIGHT_X - textWidth(hpText, CJK_LABEL),
                INFO_HP_Y, hpText, COLOR_FILTER, CJK_LABEL);
    }

    private static int textWidth(String text, int sizePx) {
        return OutlineTextRenderer.outlineTextWidth(OutlineTextRenderer.FONT_NOTO, sizePx, text);
    }

    private static void drawText(Canvas canvas, int x, int y, String text, int color, int sizePx) {
        OutlineTextRenderer.drawOutlineText(canvas, x, y, text, sizePx,
                OutlineTextRenderer.FONT_NOTO, HudPalette.textLadder(color));
    }

    /** Pixel-width truncation (CJK_NAME, outline metrics — 2026-10-29): drop
     *  trailing chars until the name fits; no ellipsis. Falls back to pixel-
     *  font widths when the outline fonts failed to load (same numbers at
     *  this grade, see OutlineTextRenderer.fallbackWidth). */
    private static String truncateName(String name, int maxW) {
        String s = name;
        while (s.length() > 1
                && OutlineTextRenderer.outlineTextWidth(OutlineTextRenderer.FONT_NOTO,
                        CJK_NAME, s) > maxW) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
