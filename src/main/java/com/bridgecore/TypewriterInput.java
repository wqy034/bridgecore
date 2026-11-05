package com.bridgecore;

import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterBlockEntity;
import dev.simulated_team.simulated.mixin_interface.PlayerTypewriterExtension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * M3k keyboard pilot channel: reads the bundled simulated mod's Linked Typewriter
 * block entity DIRECTLY (no redstone, no CC), giving the flight core full-keyboard
 * digital input instead of the 12 redstone proxy channels.
 *
 * Why direct BE reads (investigation 2026-09-02, javap on the nested jar):
 *  - `LinkedTypewriterBlockEntity.getPressedKeys()` holds the GLFW key codes the
 *    current user is holding — full keyboard granularity, independent of which keys
 *    are bound to redstone-link frequencies (the wireless path is 0/15 only).
 *  - `PlayerTypewriterExtension.simulated$getCurrentTypewriter()` (mixin interface
 *    on Player) maps player -> the typewriter they are using, so pairing is
 *    player-centric: sit down, right-click the typewriter, the core picks you up.
 *    No link item, no channel config.
 *  - Pairing sanity gate: on a physicalized structure the typewriter must be
 *    within the structure's SHIPYARD-space bounding box + 8 blocks (ship blocks
 *    live at far-out shipyard coordinates, so a big hull puts the bridge
 *    typewriter way past any point radius — the gamepad channel failed the
 *    same way at dist=50.1, 2026-09-09); a bench core falls back to a 32-block
 *    radius. Metric shared with the gamepad channel (ShipPairing).
 *
 * Threading: poll() runs on the SERVER thread (core block ticker, 20 Hz); the result
 * is an immutable snapshot published through an AtomicReference in the core and
 * consumed on the physics thread (v6.1 copy-on-write pattern). getPressedKeys() is
 * maintained by veil packet handlers on the server thread too, so the poll is
 * same-thread — no lock needed at the source.
 *
 * Failsafe: typewriter out of range / not in use / player disconnects -> the polled
 * snapshot is IDLE (all faces zero), which the control law sees as stick release
 * (the v6.1 confirm window then captures the hover target cleanly).
 *
 * ALL references to simulated-mod classes live in this file; if the mod is ever
 * absent at runtime, this is the one place that needs a guard.
 */
public final class TypewriterInput {

    /** GLFW key codes, inlined — the server side must not class-load LWJGL. */
    private static final int KEY_W = 87, KEY_S = 83, KEY_A = 65, KEY_D = 68;
    private static final int KEY_SPACE = 32, KEY_LSHIFT = 340;
    private static final int KEY_UP = 265, KEY_DOWN = 264, KEY_LEFT = 263, KEY_RIGHT = 262;
    private static final int KEY_Q = 81, KEY_E = 69;
    private static final int KEY_R = 82, KEY_F = 70;
    private static final int KEY_C = 67;
    private static final int KEY_TAB = 258;
    private static final int KEY_V = 86;
    private static final int KEY_G = 71;
    private static final int KEY_X = 88;
    private static final int KEY_H = 72;

    /** Rebindable channel indices (config GUI tab 2; lang keys gui.bridgecore.key.*). */
    public static final int CH_TRANS_FORWARD = 0;
    public static final int CH_TRANS_BACKWARD = 1;
    public static final int CH_TRANS_LEFT = 2;
    public static final int CH_TRANS_RIGHT = 3;
    public static final int CH_TRANS_UP = 4;
    public static final int CH_TRANS_DOWN = 5;
    public static final int CH_PITCH_UP = 6;
    public static final int CH_PITCH_DOWN = 7;
    public static final int CH_YAW_LEFT = 8;
    public static final int CH_YAW_RIGHT = 9;
    public static final int CH_ROLL_LEFT = 10;
    public static final int CH_ROLL_RIGHT = 11;
    public static final int CH_GEAR_UP = 12;
    public static final int CH_GEAR_DOWN = 13;
    public static final int CH_LEVEL = 14;
    /** §2.28 (2026-09-23, supersedes the §2.27 lock_cycle): HUD lock filter
     *  category cycle (Tab) and species cycle (V) — press edges consumed by the
     *  core to drive the HUD-side HudTrackFilter state. */
    public static final int CH_LOCK_CATEGORY = 15;
    public static final int CH_LOCK_SPECIES = 16;
    /** Lock command (G; §2.29 落地②, 2026-10-30): edge + held level feed the
     *  core's short/long-press machine — short = prelock/release arbitration,
     *  long (>= 6 ticks) = precise lock; both carry the fire-control channel
     *  and the command HUD's focus target captured at the press edge. */
    public static final int CH_LOCK_RELEASE = 17;
    /** Weapon channel cycle (2026-10-26, exchange log): press edge -> the core
     *  calls cycleChannel() on EVERY fire-control core of the structure
     *  (ship-wide channel switch). Pure forwarding — the channel state lives
     *  on the fire-control cores; no bridgecore-side copy. */
    public static final int CH_CHANNEL_CYCLE = 18;
    /** Fire trigger (2026-10-30, 落地② decision A — replaces the client-side
     *  FireVolleyKeybind): H is a trigger, not a salvo key (裁断稿 §6). The
     *  core runs the unified CLICK/HOLD/DOUBLE state machine on the held
     *  level + edges below and ships HPress kinds to the fire-control core
     *  with the channel captured at the press edge (修订 G). */
    public static final int CH_FIRE = 19;
    public static final int CHANNEL_COUNT = 20;

    /** The pre-GUI hardcoded bindings, now just the factory default (used when the
     *  core's NBT has no keyMap yet — v0.9 saves upgrade transparently). */
    public static int[] defaultKeyMap() {
        return new int[]{KEY_W, KEY_S, KEY_A, KEY_D, KEY_SPACE, KEY_LSHIFT,
                KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT, KEY_Q, KEY_E,
                KEY_R, KEY_F, KEY_C, KEY_TAB, KEY_V, KEY_G, KEY_X, KEY_H};
    }

    /**
     * One polled snapshot, translated to the SAME per-face semantics the redstone
     * proxies use (0/15 per Direction.ordinal() face, bow-relative mapping already
     * applied), so the physics frame can simply add it into the proxy demand arrays.
     * gearUp/gearDown/levelNow/lockCategoryNow/lockSpeciesNow/lockReleaseNow/
     * channelCycleNow/firePressNow are PRESS EDGES (true for exactly one poll
     * each); lockReleaseHeld/fireHeld are LEVELS (the G short/long and H
     * click/hold/double machines run on the core — 落地②).
     */
    public record KeyFaces(int[] att, int[] trans, boolean gearUp, boolean gearDown,
                           boolean levelNow, boolean lockCategoryNow, boolean lockSpeciesNow,
                           boolean lockReleaseNow, boolean channelCycleNow,
                           boolean lockReleaseHeld, boolean firePressNow, boolean fireHeld) {
        public static final KeyFaces IDLE = new KeyFaces(new int[6], new int[6],
                false, false, false, false, false, false, false, false, false, false);
    }

    /** Server-thread poller state (edge detection). */
    public static final class Poller {
        private final Set<Integer> prevKeys = new HashSet<>();

        /** Poll once per server tick. `bow` = the core's FACING (keys are bow-relative).
         *  `map` = channel -> GLFW key code (the core's volatile snapshot, -1 = unbound);
         *  never mutated here, so cross-thread handoff needs no copy.
         *  `plotBounds` = the structure's SHIPYARD-space bounding box (the typewriter
         *  BE lives at shipyard coordinates), or null for a bench core — bounds +
         *  PAIR_MARGIN then replace the point radius (ShipPairing metric). */
        public KeyFaces poll(Level level, BlockPos corePos, @Nullable AABB plotBounds,
                             Direction bow, int[] map) {
            LinkedTypewriterBlockEntity tw = findActiveTypewriter(level, corePos, plotBounds);
            if (tw == null) {
                prevKeys.clear();
                return KeyFaces.IDLE;
            }
            Set<Integer> keys = new HashSet<>(tw.getPressedKeys());

            int[] att = new int[6];
            int[] trans = new int[6];
            // Translation (bow-relative): forward = push toward bow, backward = aft,
            // left = port, right = starboard; up/down = vertical.
            if (held(keys, map, CH_TRANS_FORWARD)) trans[bow.ordinal()] = 15;
            if (held(keys, map, CH_TRANS_BACKWARD)) trans[bow.getOpposite().ordinal()] = 15;
            if (held(keys, map, CH_TRANS_LEFT)) trans[bow.getCounterClockWise().ordinal()] = 15;
            if (held(keys, map, CH_TRANS_RIGHT)) trans[bow.getClockWise().ordinal()] = 15;
            if (held(keys, map, CH_TRANS_UP)) trans[Direction.UP.ordinal()] = 15;
            if (held(keys, map, CH_TRANS_DOWN)) trans[Direction.DOWN.ordinal()] = 15;
            // Attitude, same face semantics as the attitude proxy: bow face = tip
            // toward bow (nose DOWN), stern = nose UP; port face = yaw to port;
            // UP face = roll to port (rollD = down - up).
            if (held(keys, map, CH_PITCH_UP)) att[bow.getOpposite().ordinal()] = 15;      // pull up
            if (held(keys, map, CH_PITCH_DOWN)) att[bow.ordinal()] = 15;                  // nose down
            if (held(keys, map, CH_YAW_LEFT)) att[bow.getCounterClockWise().ordinal()] = 15;  // yaw port
            if (held(keys, map, CH_YAW_RIGHT)) att[bow.getClockWise().ordinal()] = 15;        // yaw starboard
            if (held(keys, map, CH_ROLL_LEFT)) att[Direction.UP.ordinal()] = 15;          // roll to port
            if (held(keys, map, CH_ROLL_RIGHT)) att[Direction.DOWN.ordinal()] = 15;       // roll to starboard

            boolean gearUp = edge(keys, map, CH_GEAR_UP);
            boolean gearDown = edge(keys, map, CH_GEAR_DOWN);
            boolean levelNow = edge(keys, map, CH_LEVEL);
            boolean lockCategoryNow = edge(keys, map, CH_LOCK_CATEGORY);
            boolean lockSpeciesNow = edge(keys, map, CH_LOCK_SPECIES);
            boolean lockReleaseNow = edge(keys, map, CH_LOCK_RELEASE);
            boolean channelCycleNow = edge(keys, map, CH_CHANNEL_CYCLE);
            boolean lockReleaseHeld = held(keys, map, CH_LOCK_RELEASE);
            boolean firePressNow = edge(keys, map, CH_FIRE);
            boolean fireHeld = held(keys, map, CH_FIRE);
            prevKeys.clear();
            prevKeys.addAll(keys);
            return new KeyFaces(att, trans, gearUp, gearDown, levelNow,
                    lockCategoryNow, lockSpeciesNow, lockReleaseNow, channelCycleNow,
                    lockReleaseHeld, firePressNow, fireHeld);
        }

        private static boolean held(Set<Integer> keys, int[] map, int channel) {
            int code = map[channel];
            return code >= 0 && keys.contains(code);
        }

        /** Press edge: bound, held now, not held last poll. */
        private boolean edge(Set<Integer> keys, int[] map, int channel) {
            int code = map[channel];
            return code >= 0 && keys.contains(code) && !prevKeys.contains(code);
        }

        /** The typewriter currently used by any player near the core, or null.
         *  Player-centric pairing: no link item, no channels to configure. The
         *  distance gate is the shared ShipPairing metric: structure bounds +
         *  margin when the core is physicalized, else a point radius — the
         *  typewriter BlockPos sits at shipyard coordinates on a real ship. */
        private static LinkedTypewriterBlockEntity findActiveTypewriter(Level level, BlockPos corePos,
                                                                        @Nullable AABB plotBounds) {
            var server = level.getServer();
            if (server == null) {
                return null;
            }
            for (var player : server.getPlayerList().getPlayers()) {
                if (!(player instanceof PlayerTypewriterExtension ext)) {
                    continue;
                }
                BlockPos twPos = ext.simulated$getCurrentTypewriter();
                if (twPos == null) {
                    continue;
                }
                double dist = plotBounds != null
                        ? ShipPairing.distanceTo(plotBounds, Vec3.atCenterOf(twPos))
                        : Math.sqrt(twPos.distSqr(corePos));
                if (dist > (plotBounds != null ? ShipPairing.PAIR_MARGIN : ShipPairing.PAIR_RANGE)) {
                    continue;
                }
                if (level.getBlockEntity(twPos) instanceof LinkedTypewriterBlockEntity tw && tw.isInUse()) {
                    return tw;
                }
            }
            return null;
        }
    }

    private TypewriterInput() {
    }
}
