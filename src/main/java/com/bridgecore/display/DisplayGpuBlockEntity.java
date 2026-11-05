package com.bridgecore.display;

import com.bridgecore.BCConfig;
import com.bridgecore.BCRegistry;
import com.bridgecore.BridgeCoreMod;
import com.bridgecore.EnduranceEstimator;
import com.bridgecore.network.DisplayTileSyncPayload;
import com.bridgecore.network.DisplayGpuOpenPayload;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Display controller ("GPU"): the single brain of a tiled screen. It scans the
 * adjacent tile array, owns the whole-screen frame buffer, cuts per-tile slices
 * and pushes them to clients, and converts tile clicks into whole-screen pixels.
 *
 * Structural constraint: the controller must sit directly against the array —
 * a seed tile is found by scanning the four horizontal neighbor faces, and a
 * flood fill from the seed collects the whole connected wall, so the controller
 * works from any edge of it.
 *
 * Array shape: the largest all-filled rectangle inside the flooded set
 * (histogram + monotone stack over the grid). A row or column short one tile
 * is dropped whole rather than shrinking everything to the shortest run;
 * holes are routed around. Ties break toward the smallest left/bottom edge,
 * then the wider rect, so the origin stays put and content doesn't jump.
 *
 * Ownership: a tile belongs to the first controller that claims it (gpuPos is
 * persisted in the tile's NBT). Tiles owned by another controller count as
 * occupied — scans stop at them, so a second controller placed against the same
 * wall cannot steal tiles; it claims them only after the first controller is
 * removed (which releases and clears its tiles and pokes neighboring
 * controllers to rescan). Two touching same-facing walls with one controller
 * form ONE array.
 *
 * Content path: this BE implements {@link DisplayContentSink} — paint with
 * setPixel/fill/drawText, then flush(). Flush requests merge: one sync pass per
 * tick at most, one packet per dirty tile.
 *
 * Rescans are automatic: tile/GPU neighbor changes and tile removal set a dirty
 * flag that the server ticker consumes, and the ticker also rescans
 * unconditionally every display.rescanIntervalTicks (default 1 s) — events
 * alone cannot report tiles added at the far end of the array.
 */
public class DisplayGpuBlockEntity extends BlockEntity implements DisplayContentSink {
    public static final int ERROR_NONE = 0;
    /** Array exceeds the configured max screen size. */
    public static final int ERROR_SCREEN_TOO_BIG = 1;
    /** Frame buffer would exceed the configured byte cap. */
    public static final int ERROR_BUFFER_LIMIT = 2;

    /** Players within this radius of the logical center get tile updates. */
    static final double SYNC_RADIUS = 64.0;

    /** Array members with their slot in tile units: col = right of the rect
     *  origin, row = below the rect top (buffer y is top-origin). */
    private List<TileSlot> monitors;
    private final List<DisplayClickListener> clickListeners = new ArrayList<>();
    /** 0 idle, 1 sync pass queued/running, 2 written to while syncing. */
    private int syncState;

    private int tilesX;
    private int tilesY;
    /** Actual connected density; choice is persisted independently per GPU. */
    private int tileRes;
    /** Whole-screen frame buffer, [x][y], ABGR. */
    private int[][] buffer = new int[0][0];
    private int error = ERROR_NONE;
    private boolean monitorsDirty = true;
    /** Ticks until the next periodic rescan (see serverTick). */
    private int rescanCountdown;
    /** Production app id, persisted per controller. Retired IDs load as off. */
    private String appId = DisplayApps.FIRE_CONTROL;
    private int resolutionChoice; // 0 = automatic, at least 256
    private int settingsRevision;
    private final Map<UUID, Long> settingsViewers = new HashMap<>();
    /** Array facing = the seed tile's direction; the GPU block itself has no
     *  FACING property. Feeds DisplayContext.facing for app rendering. */
    private Direction screenFacing = Direction.NORTH;
    /** App surface, sized tiles*tileRes; rebuilt when the geometry changes. */
    private Canvas appCanvas;
    private int appTick;
    /** END estimate input for the apps (same shared builder input as the HUD). */
    private final EnduranceEstimator endurance = new EnduranceEstimator();
    /** One instance per app id, lazily created from the registry factories —
     *  apps keep their own state (click maps, smoothed values) across frames. */
    private final Map<String, DisplayApp> appInstances = new HashMap<>();
    // No optional-mod class references cross this boundary. Lease/frame are transient, never NBT.
    private ExternalDisplaySession externalSession;
    private boolean resumeInternalRequested;
    private boolean externalSyncPending;
    public boolean externalContentSelected() { return DisplayApps.CC_MODE.equals(appId); }
    public ExternalDisplaySession externalSession() { return externalSession; }
    public void setExternalSession(ExternalDisplaySession session) { externalSession = session; }
    public void prepareExternalDisplay() { getMonitors(false); }
    public void resumeInternalDisplay() { resumeInternalRequested = true; externalSyncPending = false; }
    private boolean externalActive() { return externalSession != null && externalSession.isActive(); }

    public DisplayGpuBlockEntity(BlockPos pos, BlockState state) {
        super(BCRegistry.DISPLAY_GPU_BE.get(), pos, state);
        // The active production app alone receives touch input.
        addClickListener((player, x, y, sneak) -> {
            if (externalActive()) {
                externalSession.touch(x, y, sneak);
                return;
            }
            DisplayApp app = activeApp();
            if (app != null) {
                app.onClick(player, x, y, sneak);
            }
        });
    }

    // ---- DisplayContentSink -------------------------------------------------

    @Override
    public int width() {
        return tilesX * tileRes;
    }

    @Override
    public int height() {
        return tilesY * tileRes;
    }

    @Override
    public void setPixel(int x, int y, int rgb) {
        if (x < 0 || y < 0 || x >= buffer.length || buffer.length == 0 || y >= buffer[0].length) return;
        buffer[x][y] = Integer.reverseBytes(rgb) >> 8 | 0xFF000000;
    }

    @Override
    public void fill(int x, int y, int w, int h, int rgb) {
        int x0 = Math.max(x, 0), y0 = Math.max(y, 0);
        int x1 = Math.min(x + w, width()), y1 = Math.min(y + h, height());
        for (int xx = x0; xx < x1; xx++) {
            for (int yy = y0; yy < y1; yy++) {
                setPixel(xx, yy, rgb);
            }
        }
    }

    @Override
    public void drawText(int x, int y, String text, int rgb) {
        int cx = x;
        for (char c : text.toCharArray()) {
            drawGlyph(cx, y, Character.toUpperCase(c), rgb);
            cx += 6;
        }
    }

    private void drawGlyph(int x, int y, char c, int rgb) {
        String[] glyph = Font5x7.get(c);
        if (glyph == null) return;
        for (int row = 0; row < 7; row++) {
            String bits = glyph[row];
            for (int col = 0; col < 5; col++) {
                if (bits.charAt(col) == '#') {
                    setPixel(x + col, y + row, rgb);
                }
            }
        }
    }

    @Override
    public void clear() {
        for (int[] row : buffer) {
            Arrays.fill(row, 0xFF000000);
        }
    }

    @Override
    public void flush() {
        // CC calls and structural refreshes share one presentation at the end of the GPU tick.
        if (externalActive()) externalSyncPending = true;
        else sync();
    }

    // ---- active app -------------------------------------------------------------

    public String appId() {
        return appId;
    }

    public int resolutionChoice() { return resolutionChoice; }
    public int effectiveResolution() {
        return DisplayResolution.effective(resolutionChoice, BCConfig.DISPLAY_TILE_RESOLUTION.get());
    }

    private void settingsSnapshot(ServerPlayer player, int feedback) {
        settingsViewers.entrySet().removeIf(entry -> entry.getValue() < level.getGameTime());
        settingsViewers.put(player.getUUID(), level.getGameTime() + 1200);
        PacketDistributor.sendToPlayer(player, new DisplayGpuOpenPayload(worldPosition, appId,
                resolutionChoice, effectiveResolution(), DisplayResolution.effective(0, BCConfig.DISPLAY_TILE_RESOLUTION.get()),
                tilesX, tilesY, settingsRevision, feedback));
    }
    public void openSettings(ServerPlayer player) {
        if (level == null || level.isClientSide || isRemoved()) return;
        getMonitors(false);
        settingsSnapshot(player, 0);
    }
    /** Menu-less editor writes require a recent actual block interaction.
     * Revisions reject stale concurrent editors; no raw client allocation sizes. */
    public void applySettings(ServerPlayer player, String id, int choice, int revision) {
        if (level == null || level.isClientSide || isRemoved() || player.isSpectator()) return;
        Long until = settingsViewers.get(player.getUUID());
        if (until == null) return;
        if (until < level.getGameTime()) { settingsSnapshot(player, 4); return; }
        if (revision != settingsRevision) { settingsSnapshot(player, 2); return; }
        if (!DisplayApps.isValid(id) || !DisplayResolution.validChoice(choice)) return;
        settingsSnapshot(player, configure(id, choice) ? 1 : 3);
    }
    /** Touchscreen settings already arrive through a server-validated tile hit. */
    public void setResolution(Player player, int choice) {
        if (level == null || level.isClientSide || isRemoved() || player.isSpectator()
                || !DisplayResolution.validChoice(choice)) return;
        if (!configure(appId, choice)) player.sendSystemMessage(Component.literal("[显示屏] 此分辨率超过当前拼屏的显存上限，请降低分辨率或缩小拼屏"));
    }
    private boolean configure(String id, int choice) {
        int resolution = DisplayResolution.effective(choice, BCConfig.DISPLAY_TILE_RESOLUTION.get());
        if (!DisplayResolution.fits(tilesX, tilesY, resolution, BCConfig.DISPLAY_FRAMEBUFFER_MAX_BYTES.get())) return false;
        // A successful physical settings Apply returns ownership to the selected built-in app.
        if (externalSession != null) externalSession.revoke("settings");
        String previousApp = appId;
        int previousChoice = resolutionChoice;
        appId = id; resolutionChoice = choice;
        reconnect();
        if (error != ERROR_NONE) {
            appId = previousApp; resolutionChoice = previousChoice;
            reconnect();
            if (DisplayApps.isLiveApp(appId)) renderActiveApp(); else { clear(); flush(); }
            return false;
        }
        settingsRevision++;
        setChanged();
        if (DisplayApps.isLiveApp(appId)) renderActiveApp(); else { clear(); flush(); }
        return true;
    }

    /** The active app's cached instance, null for the off mode. */
    private DisplayApp activeApp() {
        DisplayApps.Entry e = DisplayApps.get(appId);
        if (e == null || e.factory() == null) return null;
        return appInstances.computeIfAbsent(appId, k -> e.factory().get());
    }

    /**
     * Live-app frame: build the zero-link context off the containing sub-level
     * (same shared builder as the HUD screen), let the app bind its own data
     * source, render onto an indexed-color canvas sized to the physical array,
     * then transfer pixel-by-pixel into the ABGR frame buffer — palette index
     * to RGB, and index 0 (transparent on the holo) to black: this screen is
     * opaque.
     */
    private void renderActiveApp() {
        if (level == null || externalActive()) return;
        int w = width();
        int h = height();
        if (w == 0 || h == 0) return;
        DisplayApp app = activeApp();
        if (app == null) return;
        if (appCanvas == null || appCanvas.width() != w || appCanvas.height() != h) {
            appCanvas = new Canvas(w, h);
        }
        ServerSubLevel sub = Sable.HELPER.getContaining(level, worldPosition)
                instanceof ServerSubLevel s ? s : null;
        DisplayApp.DisplayContext ctx = DisplayContexts.build(level, sub, screenFacing, endurance);
        app.bind(level, sub, worldPosition);
        appCanvas.clear();
        app.render(appCanvas, ctx);
        // Palette bake: resolve through the canvas's EFFECTIVE palette
        // (per-instance custom or the global default, 2026-09-27). Index 0
        // stays "transparent -> opaque black" on this screen; indices beyond
        // the palette length fall back to entry 0 (same convention as the
        // holo consumer).
        int[] pal = appCanvas.palette();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int idx = appCanvas.getPixel(x, y) & 0xFF;
                setPixel(x, y, idx == 0 || idx >= pal.length ? 0x000000 : pal[idx] & 0xFFFFFF);
            }
        }
        flush();
    }

    // ---- sync pipeline --------------------------------------------------------

    /**
     * Sync state machine: the first flush queues a sync pass on the server
     * thread; further flushes during that pass just raise state 2, which makes
     * the pass re-run next tick. Multiple flushes per tick merge into one pass.
     */
    private void sync() {
        if (level == null || level.isClientSide) return;
        boolean exec;
        synchronized (this) {
            if (syncState == 0) {
                syncState = 1;
                exec = true;
            } else {
                syncState = 2;
                exec = false;
            }
        }
        if (exec) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                server.execute(this::sync0);
            }
        }
    }

    private void sync0() {
        if (isRemoved() || level == null || level.isClientSide) { syncState = 0; return; }
        List<TileSlot> mons = getMonitors(false);
        if (level instanceof ServerLevel serverLevel) {
            double[] center = logicalCenter(level, worldPosition);
            double cx = center[0], cy = center[1], cz = center[2];
            for (TileSlot slot : mons) {
                int[] fresh = separateIntArray(buffer, slot.col(), slot.row(), tileRes);
                int[] old = slot.tile().setSlice(fresh, tileRes);
                if (!Arrays.equals(old, fresh)) {
                    PacketDistributor.sendToPlayersNear(serverLevel, null, cx, cy, cz, SYNC_RADIUS,
                            new DisplayTileSyncPayload(slot.tile().getBlockPos(), tileRes, fresh));
                }
            }
        }
        boolean exec;
        synchronized (this) {
            if (syncState != 1) {
                syncState = 1;
                exec = true;
            } else {
                syncState = 0;
                exec = false;
            }
        }
        if (exec) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                server.tell(new TickTask(server.getTickCount() + 1, this::sync0));
            }
        }
    }

    // ---- array scanning -------------------------------------------------------

    /** Seed tile = first claimable screen tile on a horizontal neighbor face. */
    private DisplayTileBlockEntity findMonitor() {
        for (Direction f : Direction.values()) {
            if (f.getAxis() == Direction.Axis.Y) continue;
            if (level.hasChunkAt(worldPosition.relative(f))
                    && level.getBlockEntity(worldPosition.relative(f)) instanceof DisplayTileBlockEntity tile
                    && isClaimable(tile)) {
                return tile;
            }
        }
        return null;
    }

    /** A tile is claimable when no controller owns it, or we already do. */
    private boolean isClaimable(DisplayTileBlockEntity tile) {
        if (tile.gpuPos() == null || worldPosition.equals(tile.gpuPos())) return true;
        // A removed GPU may have unloaded tiles which it could not release. Repair only
        // when the owner's chunk is loaded: an unloaded, legitimate controller keeps its claim.
        if (level.hasChunkAt(tile.gpuPos()) && !(level.getBlockEntity(tile.gpuPos()) instanceof DisplayGpuBlockEntity)) {
            tile.disconnect();
            tile.setSlice(new int[0], 0);
            broadcastClear(level, tile.getBlockPos());
            return true;
        }
        return false;
    }

    /**
     * Array semantics: flood-collect every connected, same-facing, claimable
     * tile starting at the seed (4-neighborhood in the screen plane via
     * getOffset; foreign-owned tiles are opaque obstacles, not traversed),
     * rasterize the set onto its grid bounding box, then take the LARGEST
     * ALL-FILLED RECTANGLE (histogram + monotone stack). A row or column short
     * one tile is dropped whole instead of shrinking the screen to the shortest
     * run; holes are routed around; tiles outside the chosen rect stay
     * unclaimed (or are released by the reconnect diff).
     * Tie-break, deterministic: largest area, then smallest left edge, then
     * smallest bottom edge (origin moves as little as possible so content
     * doesn't jump), then widest.
     */
    private List<TileSlot> connectMonitors(DisplayTileBlockEntity seed) {
        error = ERROR_NONE;
        if (seed == null) {
            resetSurface();
            return Collections.emptyList();
        }
        Direction dir = seed.getDirection();
        screenFacing = dir; // the GPU block has no FACING of its own; the array's is the screen's
        int maxTiles = BCConfig.DISPLAY_MAX_SCREEN_SIZE.get();

        // 1. Flood collect; grid coords relative to the seed (+x right, +y up).
        List<Cell> cells = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Deque<Cell> queue = new ArrayDeque<>();
        queue.add(new Cell(seed, 0, 0));
        visited.add(seed.getBlockPos());
        int visitCap = maxTiles * maxTiles;
        while (!queue.isEmpty()) {
            Cell c = queue.poll();
            cells.add(c);
            if (cells.size() >= visitCap) continue; // stop expanding: pathological wall guard
            for (int[] d : GRID_DELTAS) {
                BlockPos np = c.tile().getOffset(d[0], d[1], dir);
                if (visited.add(np) && level.hasChunkAt(np)
                        && level.getBlockEntity(np) instanceof DisplayTileBlockEntity t
                        && t.getDirection() == dir && isClaimable(t)) {
                    queue.add(new Cell(t, c.gx() + d[0], c.gy() + d[1]));
                }
            }
        }

        // 2. Rasterize onto the grid bounding box.
        int minGx = Integer.MAX_VALUE, maxGx = Integer.MIN_VALUE;
        int minGy = Integer.MAX_VALUE, maxGy = Integer.MIN_VALUE;
        for (Cell c : cells) {
            minGx = Math.min(minGx, c.gx()); maxGx = Math.max(maxGx, c.gx());
            minGy = Math.min(minGy, c.gy()); maxGy = Math.max(maxGy, c.gy());
        }
        int gridW = maxGx - minGx + 1;
        int gridH = maxGy - minGy + 1;
        if (gridW > maxTiles || gridH > maxTiles) {
            error = ERROR_SCREEN_TOO_BIG;
            resetSurface();
            return Collections.emptyList();
        }
        boolean[][] filled = new boolean[gridH][gridW]; // [row from bottom][col]
        for (Cell c : cells) {
            filled[c.gy() - minGy][c.gx() - minGx] = true;
        }

        // 3. Largest all-filled rectangle: per-row column-height histogram,
        //    monotone stack. Rows/cols in grid coords, y counted from the bottom.
        int[] heights = new int[gridW];
        int bestArea = 0, bx0 = 0, by0 = 0, bx1 = -1, by1 = -1;
        for (int r = 0; r < gridH; r++) {
            for (int c = 0; c < gridW; c++) {
                heights[c] = filled[r][c] ? heights[c] + 1 : 0;
            }
            int[] stack = new int[gridW + 1];
            int sp = -1;
            for (int c = 0; c <= gridW; c++) {
                int h = c == gridW ? 0 : heights[c];
                while (sp >= 0 && heights[stack[sp]] > h) {
                    int height = heights[stack[sp--]];
                    int left = sp >= 0 ? stack[sp] + 1 : 0;
                    int right = c - 1;
                    int bottom = r - height + 1;
                    int area = (right - left + 1) * height;
                    if (area > bestArea || (area == bestArea && (left < bx0
                            || (left == bx0 && (bottom < by0
                            || (bottom == by0 && right - left > bx1 - bx0)))))) {
                        bestArea = area;
                        bx0 = left; by0 = bottom; bx1 = right; by1 = r;
                    }
                }
                stack[++sp] = c;
            }
        }

        // 4. Frame buffer for the chosen rect (realloc only on geometry change).
        int newTilesX = bx1 - bx0 + 1;
        int newTilesY = by1 - by0 + 1;
        int res = effectiveResolution();
        if (tilesX != newTilesX || tilesY != newTilesY || tileRes != res) {
            if (!DisplayResolution.fits(newTilesX, newTilesY, res, BCConfig.DISPLAY_FRAMEBUFFER_MAX_BYTES.get())) {
                error = ERROR_BUFFER_LIMIT;
                resetSurface();
                return Collections.emptyList();
            }
            buffer = new int[newTilesX * res][newTilesY * res];
            appCanvas = null;
            tileRes = res;
            // Fresh buffers are all-zero = fully transparent ABGR; an unwritten
            // screen should read as opaque black, not as "no quad at all".
            clear();
        }
        tilesX = newTilesX;
        tilesY = newTilesY;

        // 5. Claim the rect members; slot row is counted from the rect top.
        List<TileSlot> mons = new ArrayList<>();
        for (Cell c : cells) {
            int gx = c.gx() - minGx;
            int gy = c.gy() - minGy;
            if (gx >= bx0 && gx <= bx1 && gy >= by0 && gy <= by1) {
                mons.add(new TileSlot(c.tile().connect(worldPosition), gx - bx0, by1 - gy));
            }
        }
        return mons;
    }

    private static final int[][] GRID_DELTAS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** A flooded tile and its grid coords relative to the seed. */
    private record Cell(DisplayTileBlockEntity tile, int gx, int gy) {
    }

    /** An array member and its slot: col right of rect origin, row below rect top. */
    private record TileSlot(DisplayTileBlockEntity tile, int col, int row) {
    }

    /** Cut the (col, row) tile's width² slice out of the whole-screen buffer.
     *  Slice layout is row-major from the tile's top-left, matching NativeImage. */
    private static int[] separateIntArray(int[][] in, int col, int row, int size) {
        int[] ret = new int[size * size];
        try {
            int x0 = col * size;
            int y0 = row * size;
            int i2 = 0;
            for (int x = x0; x < x0 + size; x++) {
                int i1 = 0;
                for (int y = y0; y < y0 + size; y++) {
                    ret[i1 * size + i2] = in[x][y];
                    i1++;
                }
                i2++;
            }
        } catch (RuntimeException e) {
            Arrays.fill(ret, 0xFF000000); // the RGB24 transport requires opaque pixels even on this fallback
            // Never expected (buffer and array dims move together); log instead of
            // the upstream version's silent catch.
            BridgeCoreMod.LOGGER.warn("Display slice extraction failed at col {} row {} size {}",
                    col, row, size, e);
        }
        return ret;
    }

    private List<TileSlot> getMonitors(boolean forceRefresh) {
        if (monitors == null || forceRefresh) {
            reconnect();
        }
        return monitors;
    }

    // ---- rescans --------------------------------------------------------------

    /** Structural change near the array: the server ticker picks this up. */
    public void markMonitorsDirty() {
        monitorsDirty = true;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, DisplayGpuBlockEntity be) {
        // Periodic unconditional rescan: event-driven marks (neighbor changes,
        // tile removal) only reach the controller when they happen next to a
        // connected tile or the controller itself — a tile added at the far end
        // of the array reports to nobody. A 64-tile scan is trivial, so we just
        // poll. This also covers a second controller waiting for tiles to be
        // released by a removed one.
        if (--be.rescanCountdown <= 0) {
            be.rescanCountdown = BCConfig.DISPLAY_RESCAN_INTERVAL_TICKS.get();
            be.monitorsDirty = true;
        }
        boolean rendered = false;
        if (be.monitorsDirty) {
            be.monitorsDirty = false;
            be.reconnect();
            if (!be.externalActive() && DisplayApps.isLiveApp(be.appId)) { be.renderActiveApp(); rendered = true; }
            be.flush();
        }
        if (be.resumeInternalRequested && !be.externalActive()) {
            be.resumeInternalRequested = false;
            be.clear();
            if (DisplayApps.isLiveApp(be.appId)) be.renderActiveApp(); else be.flush();
            rendered = true;
        }
        if (!be.externalActive() && be.tilesX > 0 && DisplayApps.isLiveApp(be.appId) && ++be.appTick % 2 == 0 && !rendered)
            be.renderActiveApp();
        if (be.externalSession != null) be.externalSession.tick();
        if (be.externalSyncPending) { be.externalSyncPending = false; be.sync(); }
    }

    private void resetSurface() {
        tilesX = tilesY = 0;
        tileRes = effectiveResolution();
        buffer = new int[0][0];
        appCanvas = null;
    }

    private void reconnect() {
        int previousWidth = width(), previousHeight = height(), previousRes = tileRes;
        Direction previousFacing = screenFacing;
        List<TileSlot> old = monitors;
        monitors = connectMonitors(findMonitor());
        if (old != null) {
            // Tiles dropped from the array must stop reporting clicks to us AND
            // stop showing their last frame — release clears the slice and tells
            // clients to drop the cached texture.
            Set<DisplayTileBlockEntity> kept = new HashSet<>();
            for (TileSlot slot : monitors) {
                kept.add(slot.tile());
            }
            for (TileSlot slot : old) {
                if (!kept.contains(slot.tile()) && worldPosition.equals(slot.tile().gpuPos())) {
                    releaseTile(slot.tile());
                }
            }
        }
        if (externalSession != null && (previousWidth != width() || previousHeight != height()
                || previousRes != tileRes || previousFacing != screenFacing || old == null
                || !new HashSet<>(old).equals(new HashSet<>(monitors)))) externalSession.surfaceChanged();
    }

    @Override
    public void setRemoved() {
        if (externalSession != null) { externalSession.close(); externalSession = null; }
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (externalSession != null) { externalSession.close(); externalSession = null; }
        super.onChunkUnloaded();
    }

    /** GPU removed: release every connected tile. */
    public void disconnectAll() {
        if (externalSession != null) { externalSession.close(); externalSession = null; }
        if (monitors != null) {
            for (TileSlot slot : monitors) {
                if (worldPosition.equals(slot.tile().gpuPos())) {
                    releaseTile(slot.tile());
                }
            }
            monitors = null;
        }
    }

    /** Detach a tile from this controller: drop the ownership link, wipe its
     *  server-side slice, clear the clients' cached texture for its position,
     *  and poke horizontally adjacent controllers so a waiting second GPU can
     *  claim the freed tile on its next rescan. */
    private void releaseTile(DisplayTileBlockEntity tile) {
        tile.disconnect();
        tile.setSlice(new int[0], 0);
        if (level != null) {
            broadcastClear(level, tile.getBlockPos());
            for (Direction f : Direction.values()) {
                if (f.getAxis() == Direction.Axis.Y) continue;
                if (level.hasChunkAt(tile.getBlockPos().relative(f))
                        && level.getBlockEntity(tile.getBlockPos().relative(f)) instanceof DisplayGpuBlockEntity gpu
                        && gpu != this) {
                    gpu.markMonitorsDirty();
                }
            }
        }
    }

    /** Center for display packets: the logical center when on a ship (plot
     *  chunks are untracked), the block center on the ground. */
    static double[] logicalCenter(Level level, BlockPos pos) {
        if (Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel sub) {
            var c = sub.logicalPose().position();
            return new double[]{c.x(), c.y(), c.z()};
        }
        return new double[]{pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5};
    }

    /** Tell nearby clients to drop the cached texture for a tile position. */
    static void broadcastClear(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            double[] c = logicalCenter(level, pos);
            PacketDistributor.sendToPlayersNear(serverLevel, null, c[0], c[1], c[2], SYNC_RADIUS,
                    DisplayTileSyncPayload.clearOf(pos));
        }
    }

    // ---- clicks -----------------------------------------------------------------

    public void addClickListener(DisplayClickListener listener) {
        clickListeners.add(listener);
    }

    public void removeClickListener(DisplayClickListener listener) {
        clickListeners.remove(listener);
    }

    /** Tile-local click → whole-screen pixel (0-based), then to the listeners. */
    public void monitorClick(DisplayTileBlockEntity tile, Player player, int x, int y, boolean sneak) {
        if (monitorsDirty || tile.sliceWidth() != tileRes || x < 0 || y < 0 || x >= tileRes || y >= tileRes) return;
        for (TileSlot slot : getMonitors(false)) {
            if (slot.tile() == tile) {
                int gx = x + tileRes * slot.col();
                int gy = y + tileRes * slot.row();
                for (DisplayClickListener listener : clickListeners) {
                    listener.onDisplayClick(player, gx, gy, sneak);
                }
                return;
            }
        }
    }

    // ---- introspection -----------------------------------------------------------

    public int error() {
        return error;
    }

    public int tilesX() {
        return tilesX;
    }

    public int tilesY() {
        return tilesY;
    }

    public int tileResolution() {
        return tileRes;
    }

    // ---- persistence ------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("appId", appId);
        tag.putInt("resolution", resolutionChoice);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // Retired test/energy and unknown ids become off; a new GPU starts on fire control.
        String saved = tag.getString("appId");
        appId = !tag.contains("appId") ? DisplayApps.FIRE_CONTROL : DisplayApps.isValid(saved) ? saved : DisplayApps.OFF;
        int choice = tag.getInt("resolution");
        resolutionChoice = DisplayResolution.validChoice(choice) ? choice : 0;
        monitorsDirty = true;
    }
}
