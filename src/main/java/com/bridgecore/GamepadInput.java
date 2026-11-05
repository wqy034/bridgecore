package com.bridgecore;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M3a gamepad pilot channel (Create: Tweaked Controllers, MIT — clean license,
 * API-level integration is fine).
 *
 * Data path: with the controller in a TC lectern (TC polls the hardware itself
 * in that mode), the client reader (com.bridgecore.client.GamepadClientReader,
 * loaded only when the mod is present) forwards TC's already-refreshed
 * GamepadInputs state via GamepadInputPayload ->
 * onInput() stores the latest frame per player (server thread) -> each core's
 * Poller picks up the freshest frame from any player within 32 blocks of the
 * ship's LOGICAL position and resolves it into flight semantics -> an immutable
 * State snapshot goes through an AtomicReference to the physics thread
 * (v6.1 copy-on-write pattern, same as the typewriter channel).
 *
 * Pairing is player-centric and proximity-based — same ruling as the keyboard
 * channel: stand at the lectern on your ship and you fly it. Physicalized ships
 * pair against the structure's real-world bounding box (+8 blocks); a bench core
 * falls back to a 32-block point radius.
 * No link item, no frequency config. Multi-pilot: first matching player wins
 * (multi-crew authority is component-bus business, not v1).
 *
 * Failsafe: frame older than FRESH_NS (client stopped sending = lectern use
 * ended, disconnect, gamepad unplugged) -> IDLE, which the control law sees as
 * stick release (the v6.1 confirm window then captures cleanly).
 *
 * v2 semantic mapping (flight-standard layout; logical axis ids are TC's
 * channels — the player can rebind physical axes to them in the TC GUI).
 * v3 (config GUI): the layout below is now the DEFAULT of a per-core mapping
 * table (BE gamepadMap, NBT-persisted); the semantics editor lives in the core
 * GUI tab 2, gamepad sub-section:
 *   right stick Y -> pitch (pull back = nose up), X -> roll (right = starboard)
 *   left stick  X -> yaw (left = port), Y -> forward (push up = ahead)
 *   LT/RT -> descend/ascend (triggers rest at -1, halved to [-1,1])
 *   LB/RB -> lateral port/starboard (digital strafe, hold)
 *   A -> auto-level (edge), X/Y -> gear down/up (edge)
 */
public final class GamepadInput {

    /** Freshness window: 30 ticks = 600 ms at 20 Hz. */
    private static final long FRESH_NS = 600_000_000L;
    // Pairing constants and the bounds metric live in ShipPairing (shared with
    // the keyboard channel since 2026-09-10 — one pairing logic, no copies).

    // Logical axis ids (TC channels, GamepadInputs.axis): 0=leftX 1=leftY
    // 2=rightX 3=rightY 4=LT 5=RT (triggers GLFW-native [-1,1], rest = -1).
    // Button ids (GamepadInputs.buttons, GLFW order): 0=A 1=B 2=X 3=Y 4=LB 5=RB ...
    private static final int BTN_A = 0, BTN_X = 2, BTN_Y = 3, BTN_LB = 4, BTN_RB = 5;

    /** Axis-source ids selectable in the GUI. 0..5 = the six raw TC axes;
     *  TRIGGER_PAIR / SHOULDER_PAIR are compound digital-analog sources;
     *  NONE parks the channel at 0. */
    public static final int SRC_TRIGGER_PAIR = 6;   // (RT - LT) / 2
    public static final int SRC_SHOULDER_PAIR = 7;  // RB - LB (digital)
    public static final int SRC_NONE = 8;
    public static final int SRC_COUNT = 9;

    /** Axis channels (map layout order). */
    public static final int AXIS_PITCH = 0;
    public static final int AXIS_ROLL = 1;
    public static final int AXIS_YAW = 2;
    public static final int AXIS_FWD = 3;
    public static final int AXIS_VERT = 4;
    public static final int AXIS_LAT = 5;
    public static final int AXIS_CHANNEL_COUNT = 6;
    /** Button channels (map tail order). */
    public static final int BTN_LEVEL = 0;
    public static final int BTN_GEAR_UP = 1;
    public static final int BTN_GEAR_DOWN = 2;
    public static final int BTN_CHANNEL_COUNT = 3;
    /** gamepadMap layout: 6 axis channels x (source, invert) + 3 button ids. */
    public static final int MAP_LENGTH = AXIS_CHANNEL_COUNT * 2 + BTN_CHANNEL_COUNT;
    public static final int MAX_BUTTON_ID = 14;

    /** The pre-GUI hardcoded v2 layout, now just the factory default (used when the
     *  core's NBT has no gamepadMap yet). Values verified line-by-line against the
     *  old hardcoded resolve: pitch=rightY inverted, roll=rightX, yaw=leftX
     *  inverted, fwd=leftY inverted, vert=(RT-LT)/2, lat=RB-LB; A/Y/X buttons. */
    public static int[] defaultPadMap() {
        return new int[]{
                3, 1,   // pitch: right stick Y, inverted
                2, 0,   // roll:  right stick X
                0, 1,   // yaw:   left stick X, inverted
                1, 1,   // fwd:   left stick Y, inverted
                SRC_TRIGGER_PAIR, 0,  // vert: (RT - LT) / 2
                SRC_SHOULDER_PAIR, 0, // lat:  RB - LB
                BTN_A, BTN_Y, BTN_X}; // level / gear up / gear down
    }

    /**
     * One immutable per-core snapshot with flight semantics resolved. pitch/yaw/roll
     * use the SAME sign conventions as the torque-face channels (pitchD/yawD/rollD):
     * pitch + = nose down, yaw + = to port, roll + = to starboard. fwd/lat/vert are
     * stick-unit demands (-1..1): fwd + = toward bow, lat + = starboard, vert + = up.
     * The three edge flags are true in exactly ONE State instance per press
     * (identity-consumed on the physics thread, same as the keyboard gear keys).
     */
    public record State(float pitch, float yaw, float roll,
                        float fwd, float lat, float vert,
                        boolean levelEdge, boolean gearUpEdge, boolean gearDownEdge) {
        public static final State IDLE = new State(0, 0, 0, 0, 0, 0, false, false, false);
    }

    /** Latest raw frame per player (written/read on the server thread). */
    private record Entry(float[] axes, int buttons, long stampNanos) {
    }

    private static final Map<UUID, Entry> PLAYERS = new ConcurrentHashMap<>();

    /** Payload handler entry (server thread via enqueueWork). */
    public static void onInput(UUID playerId, float[] axes, int buttons) {
        PLAYERS.put(playerId, new Entry(axes, buttons, System.nanoTime()));
    }

    /**
     * Per-core poller (server thread, core block ticker). Returns the SAME State
     * instance while the resolved input is unchanged, so the physics thread can
     * edge-consume with an identity check — identical pattern to TypewriterInput.
     */
    public static final class Poller {
        private final float[] prevAxes = new float[6];
        private int prevButtons;
        private State last = State.IDLE;
        private boolean linked;

        /** @param coreLogical the ship's real-world position (BE lastPosition), or the
         *                     plain block position for a never-physicalized bench core.
         *  @param shipBounds the structure's real-world bounding box (Sable
         *                     {@code subLevel.boundingBox()}), or null for a bench
         *                     core — bounds + PAIR_MARGIN replaces the point radius.
         *  @param map the core's volatile mapping snapshot (never mutated here). */
        public State poll(Level level, Vec3 coreLogical, @Nullable AABB shipBounds, int[] map) {
            var server = level.getServer();
            if (server == null) {
                return unlink();
            }
            Entry best = null;
            long now = System.nanoTime();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.level() != level) {
                    continue;
                }
                Entry e = PLAYERS.get(p.getUUID());
                if (e == null || now - e.stampNanos() > FRESH_NS) {
                    continue;
                }
                double dist = shipBounds != null
                        ? ShipPairing.distanceTo(shipBounds, p.position())
                        : p.position().distanceTo(coreLogical);
                if (dist > (shipBounds != null ? ShipPairing.PAIR_MARGIN : ShipPairing.PAIR_RANGE)) {
                    continue;
                }
                best = e; // single-pilot assumption: first match wins
                break;
            }
            if (best == null) {
                return unlink();
            }
            linked = true;
            boolean changed = !Arrays.equals(prevAxes, best.axes()) || prevButtons != best.buttons();
            if (!changed) {
                return last;
            }
            int pressed = best.buttons() & ~prevButtons; // rising edges
            prevButtons = best.buttons();
            System.arraycopy(best.axes(), 0, prevAxes, 0, prevAxes.length);
            float[] a = best.axes();
            // Resting-stick noise (~1e-4) otherwise counts as rate demand every
            // frame and pins the loop in RATE mode, defeating auto-level's
            // attitude target (observed 2026-09-03: A engaged 9x, pitch frozen
            // at 15.26 deg). Keyboard never needed this — keys release to exact 0.
            // The deadzone lives inside axisValue() so it applies per source.
            last = new State(
                    axisValue(map, AXIS_PITCH, a, best.buttons()),
                    axisValue(map, AXIS_YAW, a, best.buttons()),
                    axisValue(map, AXIS_ROLL, a, best.buttons()),
                    axisValue(map, AXIS_FWD, a, best.buttons()),
                    axisValue(map, AXIS_LAT, a, best.buttons()),
                    axisValue(map, AXIS_VERT, a, best.buttons()),
                    bit(pressed, map[AXIS_CHANNEL_COUNT * 2 + BTN_LEVEL]),
                    bit(pressed, map[AXIS_CHANNEL_COUNT * 2 + BTN_GEAR_UP]),
                    bit(pressed, map[AXIS_CHANNEL_COUNT * 2 + BTN_GEAR_DOWN]));
            return last;
        }

        /** Resolve one axis channel from the map: source value with deadzone applied,
         *  then the invert flag. Compound sources combine their inputs first. */
        private static float axisValue(int[] map, int channel, float[] a, int buttons) {
            int src = map[channel * 2];
            float v = switch (src) {
                case 0, 1, 2, 3, 4, 5 -> dz(a[src]);
                case SRC_TRIGGER_PAIR -> (dz(a[5]) - dz(a[4])) * 0.5f;
                case SRC_SHOULDER_PAIR -> (bit(buttons, BTN_RB) ? 1f : 0f)
                        - (bit(buttons, BTN_LB) ? 1f : 0f);
                default -> 0.0f; // SRC_NONE or out of range
            };
            if (map[channel * 2 + 1] != 0) {
                v = -v;
            }
            return clamp(v);
        }

        /** GUI status tab: whether a player frame is currently paired. */
        public boolean isLinked() {
            return linked;
        }

        private static final float DEADZONE = 0.02f;

        private static float dz(float v) {
            return Math.abs(v) < DEADZONE ? 0.0f : v;
        }

        private State unlink() {
            linked = false;
            Arrays.fill(prevAxes, 0.0f);
            prevButtons = 0;
            last = State.IDLE;
            return State.IDLE;
        }

        private static boolean bit(int mask, int id) {
            return (mask & (1 << id)) != 0;
        }

        private static float clamp(float v) {
            return Math.max(-1.0f, Math.min(1.0f, v));
        }
    }

    private GamepadInput() {
    }
}
