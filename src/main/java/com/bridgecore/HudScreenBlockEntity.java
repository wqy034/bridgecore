package com.bridgecore;

import com.bridgecore.display.AttitudeIndicatorApp;
import com.bridgecore.display.Canvas;
import com.bridgecore.display.DisplayApp;
import com.bridgecore.display.DisplayContexts;
import com.bridgecore.network.CanvasSyncPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

/**
 * Screen block entity: owns the server-side canvas and runs the current display app.
 *
 * Data path (astrocompass pattern): the server ticker polls the containing sub-level
 * directly — {@code Sable.HELPER.getContaining} + {@code logicalPose()} +
 * {@code ServerSubLevel.latestLinearVelocity/latestAngularVelocity} — so no physics
 * callbacks and no cross-thread snapshot plumbing. The ship's pose IS the attitude
 * source; no link to the bridge core is needed (zero-binding design).
 *
 * Sync: app re-renders every server tick (20 Hz); when the canvas is dirty the whole
 * buffer is broadcast every 2nd tick (10 Hz) to players near the LOGICAL position
 * (plot chunks are untracked by players — a plot-chunk-targeted packet would reach
 * nobody; VoidPower's tracking-chunk broadcast works for them only because… test
 * finding pending, we use sendToPlayersNear at the logical center).
 */
public class HudScreenBlockEntity extends BlockEntity {
    /** Right-click size steps (2026-09-24 re-calibration: the old ladder
     *  {0.5,1.0,1.5,2.0} read ~2.5..5 blocks wide at stock calibration and the
     *  user measured even step 2 as ~3x3 — unusable past a bench). Panel width
     *  at stock calibration (calibScale 1) = 2.5 x screenScale blocks:
     *  0.4 -> 1.0, 0.6 -> 1.5 (default), 0.9 -> 2.25, 1.2 -> 3.0 blocks. */
    private static final float[] SCALE_STEPS = {0.4f, 0.6f, 0.9f, 1.2f};
    /** Players within this radius of the logical center get canvas updates. */
    private static final double SYNC_RADIUS = 64.0;

    private final Canvas canvas = new Canvas();
    private final AttitudeIndicatorApp app = new AttitudeIndicatorApp();
    private float screenScale = 0.6f;
    private int tickCount = 0;
    /** Last shipped frame (2026-10-28): render() repaints every tick, so the
     *  canvas dirty flag is always set — ship only when the pixels or the
     *  palette actually changed (1 MB memcmp @10 Hz is free vs 512 KB
     *  packets). */
    private byte[] lastSentPixels;
    private int lastSentPaletteVersion = -1;
    /** END estimate from the measured stock decline (shared with the core's CC
     *  channel — see EnduranceEstimator for the why). */
    private final EnduranceEstimator endurance = new EnduranceEstimator();
    /** HUD calibration pushed by the structure's bridge core (config GUI tab 3):
     *  pilot-relative panel offsets (blocks) + projection scale + cockpit
     *  eye-point compensation (2026-10-26 v2: calibEyeX/calibEyeY now place ONLY
     *  the projection eye — fixed block-relative, EYE_BASE_Y + eyeY high, eyeX
     *  in front — they no longer touch the panel; eyeY semantics redefined from
     *  "panel height tweak" to "eye height offset", default 0.12 -> 0)
     *  + attitude-zone layout params + input-line switch. Persisted here too so a
     *  display keeps its look if the core is removed; the core re-asserts its values
     *  on registration when one is present. */
    private double calibX;
    private double calibY;
    private double calibScale = 1.0;
    private double calibEyeX = 2.0;
    private double calibEyeY;
    private double calibPitchStep = 5.0;
    private double calibAttSize = 1.0;
    private double calibAttBorder;
    private boolean calibInputLine = true;
    /** Per-screen lock filter (§2.28 → SPI v3 落地②, 2026-10-30): pure
     *  candidate/selection chain — cone cull, category/species filter,
     *  crosshair-following auto-select. Zero write-back: uplink commands
     *  (PrelockUpdate/GPress/HPress) are sent by the flight core, and only
     *  the COMMAND HUD's candidate set feeds PrelockUpdate. */
    private final com.bridgecore.display.HudTrackFilter lockFilter = new com.bridgecore.display.HudTrackFilter();
    /** Tab notch (§2.29 修订 C, 2026-10-30): -1 = OFF ("无锁定"), 0..3 =
     *  HudTargetProvider.Category ordinal. STRUCTURE-LEVEL state owned by the
     *  flight core; this screen is a pure mirror ({@link #setHudLockCategory})
     *  — no NBT, no update-tag sync, no local cycling. */
    public static final int LOCK_NOTCH_OFF = -1;
    private int lockCategory = LOCK_NOTCH_OFF;
    /** Sub-level this screen is registered under in CoreDirectory (server thread). */
    private ServerSubLevel registeredSubLevel;
    /** §2.29 §4: 指挥 HUD = CoreDirectory 注册序第一块屏（自动指定）。Cached
     *  per tick; only the command HUD heartbeats PrelockUpdate and carries
     *  the CMD marker. */
    private boolean commandHud;
    /** Active-channel task view cache (pulled per tick from the provider). */
    private com.bridgecore.display.HudTargetProvider.@Nullable ChannelTaskView activeTaskView;
    /** Prelock heartbeat state (§2.29 §3/§5): last pushed candidate set; the
     *  OFF notch pushes ONE empty set then goes deliberately silent. */
    private java.util.List<String> lastPrelockIds = java.util.List.of();
    private boolean prelockEmptySent;

    public HudScreenBlockEntity(BlockPos pos, BlockState state) {
        super(BCRegistry.HUD_SCREEN_BE.get(), pos, state);
        // 2026-10-29 outline-font vertical slice: the HUD canvas runs the
        // 256-entry HudPalette (16 legacy colors + six antialiasing alpha
        // ladders for OutlineTextRenderer). One-time install: the version
        // bump makes the next sync reship, and the palette rides every
        // packet so late joiners are covered. Side effect: HUD frames no
        // longer nibble-pack — the sync layer ships RLE instead.
        canvas.setPalette(com.bridgecore.display.HudPalette.create());
    }

    /** Tab notch (§2.29): current category ordinal, -1 = OFF (target layer
     *  fully off). Mirror of the flight core's structure-level value. */
    public int lockCategory() {
        return lockCategory;
    }

    /** §2.29 修订 C: the flight core pushes the structure-level notch here.
     *  Pure mirror — no persistence, no client sync, no species side effect
     *  (the core clears the species notch itself before pushing). */
    public void setHudLockCategory(int category) {
        lockCategory = category;
    }

    /** §2.29: 指挥 HUD 标记（注册序第一块屏）。 */
    public boolean isCommandHud() {
        return commandHud;
    }

    /** G 的 focusId 来源：准星焦点候选（无 = null）。 */
    public @org.jetbrains.annotations.Nullable String lockFocusId() {
        return lockFilter.focusId();
    }

    /** V 精确态换目标：候选链中 anchor 的下一个（环绕；无可换 = null）。 */
    public @org.jetbrains.annotations.Nullable String nextLockCandidateAfter(String anchorId) {
        return lockFilter.nextCandidateAfter(anchorId);
    }

    /** 当前火控频道任务视图（每 tick 拉取缓存；无火控 = null）。 */
    public com.bridgecore.display.HudTargetProvider.@Nullable ChannelTaskView activeTaskView() {
        return activeTaskView;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, HudScreenBlockEntity be) {
        be.tickServer(level);
    }

    private void tickServer(Level level) {
        Direction facing = getBlockState().getValue(HudScreenBlock.FACING);
        var containing = Sable.HELPER.getContaining(level, getBlockPos());
        ServerSubLevel sub = containing instanceof ServerSubLevel server ? server : null;
        // Keep the CoreDirectory membership current so the core's config GUI can
        // push HUD calibration to us; on (re)joining a structure adopt the core's
        // calibration if one is online (the core is the authority for tab 3).
        if (sub != registeredSubLevel) {
            CoreDirectory.deregisterHudScreen(registeredSubLevel, this);
            CoreDirectory.registerHudScreen(sub, this);
            registeredSubLevel = sub;
            if (sub != null) {
                var cores = CoreDirectory.coresOf(sub);
                if (!cores.isEmpty()) {
                    BridgeCoreBlockEntity core = cores.get(0);
                    setCalib(core.hudOffsetX(), core.hudOffsetY(), core.hudScale(),
                            core.hudEyeX(), core.hudEyeY(), core.hudPitchStep(),
                            core.hudAttSize(), core.hudAttBorder(), core.hudInputLine());
                    // §2.29: adopt the structure-level lock notch together with
                    // the calib — the core is the authority for both.
                    setHudLockCategory(core.hudLockCategory());
                }
            }
        }
        // Frame inputs come from the shared builder (2026-09-17 extraction; the
        // tiled-screen energy panel reads the same numbers via the same code).
        DisplayApp.DisplayContext ctx = DisplayContexts.build(level, sub, facing, endurance);
        app.configure(calibPitchStep, calibAttSize, calibAttBorder, calibInputLine);
        app.configureLockProjection(calibX, calibY, calibScale, calibEyeX, calibEyeY,
                screenScale);
        // Flight-control status (HUD-移植交接说明 §2/§6 飞控侧第一批): read-only
        // binding from the same-structure core — FLT mode text + the effective
        // coupled flag (coupledOn(), not the raw persisted toggle). No core ->
        // "--" and the coupled line hides.
        BridgeCoreBlockEntity flightCore = null;
        if (sub != null) {
            var cores = CoreDirectory.coresOf(sub);
            if (!cores.isEmpty()) {
                flightCore = cores.get(0);
            }
        }
        app.configureOutline(flightCore == null || flightCore.hudOutline());
        app.bindFlightStatus(flightCore == null ? -1 : flightCore.mode().ordinal(),
                flightCore != null && flightCore.coupledOn(), flightCore != null);
        // §2.29 修订 C + suppress 核移 (2026-10-30): the suppressHudTargeting
        // OFF-forcing now lives in the flight core's tick (it owns the notch);
        // this screen just follows the mirrored value.
        // §2.29 §4: command-HUD determination — registration order, first
        // screen of the structure. Cached for the CMD marker + PrelockUpdate
        // gating.
        commandHud = false;
        if (sub != null) {
            var screens = CoreDirectory.hudScreensOf(sub);
            commandHud = !screens.isEmpty() && screens.get(0) == this;
        }
        // Lock-box overlay (§2.28 → SPI v3): pull a raw track snapshot from the
        // registered provider, run the HUD-side filter chain (frustum ->
        // category/species -> nearest-to-crosshair), then hand the selection +
        // filter state to the renderer. No provider -> null -> no roam layer.
        var lockSnapshot = com.bridgecore.display.HudTargetProviders.isEmpty() ? null
                : com.bridgecore.display.HudTargetProviders.pull(level, sub,
                        getBlockPos(), facing);
        java.util.List<String> lockIds = java.util.List.of();
        String lockFilterText = null;
        boolean lockTargetsOff = false;
        if (lockSnapshot != null) {
            var r = lockFilter.update(level, sub, getBlockPos(), facing, lockSnapshot,
                    new com.bridgecore.display.HudLockBoxLayer.Calib(screenScale, calibX,
                            calibY, calibScale, calibEyeX, calibEyeY),
                    ctx.orientation(), lockCategory, commandHud);
            lockIds = r.lockedIds();
            lockFilterText = r.stateText();
            lockTargetsOff = r.targetsOff();
        } else {
            lockFilter.reset();
        }
        // SPI v3 下行: every channel's authoritative task view + the command
        // channel; the HUD's red boxes and the task line read ONLY this (黄框
        // 预锁来自上面的候选链; "HUD 发出请求" ≠ "炮塔已实锁").
        var channelTasks = com.bridgecore.display.HudTargetProviders.pullChannelTasks(level, sub);
        int activeChannel = com.bridgecore.display.HudTargetProviders.pullActiveChannel(level, sub);
        activeTaskView = null;
        for (var v : channelTasks) {
            if (v.channel() == activeChannel) {
                activeTaskView = v;
                break;
            }
        }
        java.util.List<String> redIds = activeTaskView != null
                && activeTaskView.mode() != com.bridgecore.display.HudTargetProvider.TaskMode.IDLE
                ? activeTaskView.assignedTargetIds() : java.util.List.of();
        String taskTag = null;
        boolean taskActive = false;
        if (activeTaskView != null
                && activeTaskView.mode() != com.bridgecore.display.HudTargetProvider.TaskMode.IDLE) {
            taskActive = true;
            taskTag = switch (activeTaskView.source()) {
                case CONSOLE -> "FCS";
                case HUD -> "LOCK";
                default -> null;
            };
        }
        if (commandHud) {
            com.bridgecore.firecontrol.FireControlCoreBlockEntity fireCore = null;
            for (var fc : com.bridgecore.firecontrol.FireControlCoreBlockEntity.coresOf(level, sub)) {
                fireCore = fc;
                break;
            }
            if (fireCore != null) {
                if (lockCategory == LOCK_NOTCH_OFF) {
                    if (!prelockEmptySent) {
                        prelockEmptySent = true;
                        lastPrelockIds = java.util.List.of();
                        fireCore.prelockUpdate(level, java.util.List.of(),
                                level.getGameTime(), 10, false);
                    }
                } else {
                    prelockEmptySent = false;
                    java.util.List<String> cand = lockFilter.candidateIds();
                    if (cand.size() > 32) {
                        cand = cand.subList(0, 32);
                    }
                    // Heartbeat payload is a set: angular reordering is not a new task.
                    cand = new java.util.ArrayList<>(cand);
                    cand.sort(String::compareTo);
                    if (!cand.equals(lastPrelockIds) || tickCount % 5 == 0) {
                        lastPrelockIds = java.util.List.copyOf(cand);
                        fireCore.prelockUpdate(level, cand, level.getGameTime(), 10, true);
                    }
                }
            }
        }
        channelTasks = com.bridgecore.display.HudTargetProviders.pullChannelTasks(level, sub);
        activeTaskView = null;
        for (var view : channelTasks) if (view.channel() == activeChannel) { activeTaskView = view; break; }
        taskActive = activeTaskView != null && activeTaskView.mode()
                != com.bridgecore.display.HudTargetProvider.TaskMode.IDLE;
        redIds = taskActive ? activeTaskView.assignedTargetIds() : java.util.List.of();
        taskTag = !taskActive ? null : activeTaskView.source()
                == com.bridgecore.display.HudTargetProvider.TaskSource.CONSOLE ? "FCS" : "LOCK";
        boolean preciseTask = taskActive && activeTaskView.mode()
                == com.bridgecore.display.HudTargetProvider.TaskMode.PRECISE_LOCK;
        app.bindHudTargets(level, sub, getBlockPos(), lockSnapshot, lockIds,
                lockFilterText, redIds, taskTag, taskActive, preciseTask, lockTargetsOff, commandHud);
        app.bindWeaponStatus(com.bridgecore.display.HudTargetProviders.pullWeaponStatus(
                level, sub, getBlockPos(), facing));
        app.render(canvas, ctx);

        tickCount++;
        if (tickCount % 2 == 0) {
            byte[] raw = canvas.raw();
            if (lastSentPixels != null && java.util.Arrays.equals(raw, lastSentPixels)
                    && lastSentPaletteVersion == canvas.paletteVersion()) {
                return; // frame unchanged — skip the 512 KB sync entirely
            }
            lastSentPixels = raw.clone();
            lastSentPaletteVersion = canvas.paletteVersion();
            // 2026-10-29 explicit-encoding rework: 16-color indices nibble-pack
            // to half size; the HUD's 256-entry palette (outline-text alpha
            // ladders) always exceeds a nibble, so HUD frames take RLE —
            // mostly-empty 1024² frames collapse to a few KiB. A frame still
            // over MAX_BAND_BYTES after encoding ships as band-sliced packets
            // (the ENCODED stream is sliced; the receiver decodes once after
            // reassembly). Raw over the cap is never sent.
            int encoding;
            byte[] data;
            if (CanvasSyncPayload.canPackNibbles(raw)) {
                encoding = CanvasSyncPayload.ENCODING_NIBBLE;
                data = CanvasSyncPayload.packNibbles(raw);
            } else {
                encoding = CanvasSyncPayload.ENCODING_RLE;
                data = CanvasSyncPayload.packRle(raw);
            }
            int bandCount = (data.length + CanvasSyncPayload.MAX_BAND_BYTES - 1)
                    / CanvasSyncPayload.MAX_BAND_BYTES;
            // Send from the LOGICAL center: on a ship the block lives in an untracked
            // plot chunk; on the ground pose==actual position and this is a no-op shift.
            double x = getBlockPos().getX() + 0.5, y = getBlockPos().getY() + 0.5, z = getBlockPos().getZ() + 0.5;
            if (sub != null) {
                var c = sub.logicalPose().position();
                x = c.x(); y = c.y(); z = c.z();
            }
            byte[] paletteBytes = CanvasSyncPayload.encodePalette(canvas.customPalette());
            for (int b = 0; b < bandCount; b++) {
                int from = b * CanvasSyncPayload.MAX_BAND_BYTES;
                int to = Math.min(data.length, from + CanvasSyncPayload.MAX_BAND_BYTES);
                byte[] slice = bandCount == 1 ? data : java.util.Arrays.copyOfRange(data, from, to);
                PacketDistributor.sendToPlayersNear((ServerLevel) level, null, x, y, z, SYNC_RADIUS,
                        new CanvasSyncPayload(getBlockPos(), canvas.width(), canvas.height(),
                                encoding, b, bandCount, slice, canvas.paletteVersion(),
                                paletteBytes));
            }
        }
    }

    /** Right-click: cycle projection scale. Synced to clients via BE update tag. */
    public void cycleScale() {
        int idx = 0;
        for (int i = 0; i < SCALE_STEPS.length; i++) {
            if (Math.abs(SCALE_STEPS[i] - screenScale) < 1e-6) { idx = i; break; }
        }
        screenScale = SCALE_STEPS[(idx + 1) % SCALE_STEPS.length];
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public float getScreenScale() {
        return screenScale;
    }

    /** Core config GUI tab 3 push: store the calibration and re-sync to clients. */
    public void setCalib(double x, double y, double scale, double eyeX, double eyeY,
                         double pitchStep, double attSize, double attBorder,
                         boolean inputLine) {
        calibX = x;
        calibY = y;
        calibScale = scale;
        calibEyeX = eyeX;
        calibEyeY = eyeY;
        calibPitchStep = pitchStep;
        calibAttSize = attSize;
        calibAttBorder = attBorder;
        calibInputLine = inputLine;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public double getCalibX() {
        return calibX;
    }

    public double getCalibY() {
        return calibY;
    }

    public double getCalibScale() {
        return calibScale;
    }

    public double getCalibEyeX() {
        return calibEyeX;
    }

    public double getCalibEyeY() {
        return calibEyeY;
    }

    @Override
    public void setRemoved() {
        CoreDirectory.deregisterHudScreen(registeredSubLevel, this);
        registeredSubLevel = null;
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putFloat("screenScale", screenScale);
        tag.putDouble("calibX", calibX);
        tag.putDouble("calibY", calibY);
        tag.putDouble("calibScale", calibScale);
        tag.putDouble("calibEyeX", calibEyeX);
        tag.putDouble("calibEyeY", calibEyeY);
        // 2026-10-26 v2 calibration model marker (guards the one-time migration
        // in loadAdditional; the calib* keys themselves are unchanged — the
        // params all survive v2, only their semantics were orthogonalized).
        tag.putBoolean("calibV2", true);
        tag.putDouble("calibPitchStep", calibPitchStep);
        tag.putDouble("calibAttSize", calibAttSize);
        tag.putDouble("calibAttBorder", calibAttBorder);
        tag.putBoolean("calibInputLine", calibInputLine);
        // §2.29 (2026-10-30): the lock notch is structure-level flight-core
        // state — the screen no longer persists its mirror.
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("screenScale")) {
            screenScale = mapLegacyScale(tag.getFloat("screenScale"));
        }
        if (tag.contains("calibScale")) {
            calibX = tag.getDouble("calibX");
            calibY = tag.getDouble("calibY");
            calibScale = tag.getDouble("calibScale");
            calibEyeX = tag.getDouble("calibEyeX");
            calibEyeY = tag.getDouble("calibEyeY");
            // 2026-10-26 v2 migration (direct write, bypasses the GUI clamps —
            // a core push re-clamps on registration anyway; see
            // BridgeCoreBlockEntity's hudCalibV2 block for the full formula
            // writeup). This cache knows the block's real right-click notch
            // (screenScale, loaded above), so the max(oldHalf, 1.25) branch is
            // exact here. New saves carry calibV2 and skip this.
            if (!tag.getBoolean("calibV2")) {
                double oldHalf = 1.25 * screenScale * calibScale * (calibEyeX / 2.0);
                double oldAnchorHalf = Math.max(oldHalf, 1.25);
                double oldCenterY = 1.1 + (calibEyeY - 0.12) + oldAnchorHalf + calibY;
                calibY = oldCenterY - com.bridgecore.display.HudLockBoxLayer.PANEL_ANCHOR_Y;
                calibScale = calibScale * calibEyeX / 2.0;
                calibEyeY = (1.1 + oldAnchorHalf)
                        - com.bridgecore.display.HudLockBoxLayer.EYE_BASE_Y;
            }
            calibPitchStep = tag.getDouble("calibPitchStep");
            calibAttSize = tag.getDouble("calibAttSize");
            calibAttBorder = tag.getDouble("calibAttBorder");
            calibInputLine = !tag.contains("calibInputLine") || tag.getBoolean("calibInputLine");
        }
        // §2.29: legacy per-screen "lockCategory" keys in old saves are simply
        // ignored — the notch is structure-level core state now.
    }

    /** Pre-2026-09-24 saves stored the old steps {0.5,1.0,1.5,2.0}. Map by step
     *  INDEX onto the new ladder (old default 1.0 -> new default 0.6); values
     *  already on the new ladder (new saves) pass through, anything else snaps
     *  to the nearest new step. The old and new ladders are disjoint, so the
     *  pass-through check is unambiguous. */
    private static float mapLegacyScale(float stored) {
        for (float step : SCALE_STEPS) {
            if (Math.abs(step - stored) < 1e-6) {
                return stored; // new-format save
            }
        }
        float[] legacy = {0.5f, 1.0f, 1.5f, 2.0f};
        int idx = 0;
        for (int i = 1; i < legacy.length; i++) {
            if (Math.abs(legacy[i] - stored) < Math.abs(legacy[idx] - stored)) {
                idx = i;
            }
        }
        return SCALE_STEPS[idx];
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putFloat("screenScale", screenScale);
        tag.putDouble("calibX", calibX);
        tag.putDouble("calibY", calibY);
        tag.putDouble("calibScale", calibScale);
        tag.putDouble("calibEyeX", calibEyeX);
        tag.putDouble("calibEyeY", calibEyeY);
        // Must accompany the calib keys: the client's handleUpdateTag funnels
        // through loadAdditional, and without the marker it would re-run the
        // v2 migration on already-migrated values.
        tag.putBoolean("calibV2", true);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** The projection floats above the block and scales with the size step;
     *  extend the render bounds or it disappears when the block itself
     *  scrolls off-screen. */
    public net.minecraft.world.phys.AABB getRenderBoundingBox() {
        return new net.minecraft.world.phys.AABB(getBlockPos()).inflate(4.0, 6.0, 4.0);
    }
}
