package com.bridgecore;

import com.bridgecore.control.AttitudeController;
import com.bridgecore.display.HudTargetProvider;
import com.bridgecore.display.HudTargetProviders;
import com.bridgecore.firecontrol.FireControlCoreBlockEntity;
import com.bridgecore.telemetry.TelemetrySnapshot;
import com.bridgecore.menu.BridgeCoreMenu;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Control block entity. Phase 2 (handoff doc §8 step 2): attitude damping
 * (pure D) and attitude hold (PD), ground-tested via log readout.
 *
 * Threading: physics callbacks run on the physics thread, mode switching on
 * the server thread — shared state is volatile / AtomicReference accordingly.
 * Units are SI everywhere (m/s, rad/s, N*m); timeStep comes from the callback
 * (measured 40Hz physics, 0.025s — never assume 20Hz, handoff §4.1).
 */
public class BridgeCoreBlockEntity extends BlockEntity implements BlockEntitySubLevelActor, MenuProvider {
    /** Watchdog: alert after this many world ticks without a physics frame. */
    private static final long WATCHDOG_TICK_THRESHOLD = 10;
    /** Redstone passthrough (§8 step 2): angular acceleration at full signal, rad/s^2. */
    private static final double REDSTONE_ALPHA = 4.0;
    /** M1 rate command: full-stick target angular rates, rad/s (yaw / pitch / roll).
     *  Feel parameters — production exposes them in the UI. */
    private static final double OMEGA_MAX_YAW = 0.8;
    private static final double OMEGA_MAX_PITCH = 0.6;
    private static final double OMEGA_MAX_ROLL = 0.6;
    /** Redstone thrust (§8 step 4): linear acceleration at full signal, m/s^2 (scaled by mass -> N).
     *  12.0 post-ground-test: must beat gravity (~9.8) or vertical thrust can never lift off
     *  the ground — 5.0 only worked because early tests were airborne. */
    private static final double THRUST_ACCEL = 12.0;
    /** Energy price of thrust / linear control force, FE per newton per second.
     *  2026-09-05 user ruling, anchored to "1 kg hovering = 1 FE/t": with g = 11 N/kg
     *  that is 11 N x 20 t/s = 220 N·t/s -> 220/11 = 20 FE/s = 1 FE/t, so the price
     *  is 20/11 FE per N·s. (Previous 0.1 made energy effectively free.) */
    private static final double FE_PER_NEWTON_SECOND = 20.0 / 11.0;
    /** Energy price of torque, FE per newton-meter per second. All physics writes are
     *  priced (2026-09-01 user ruling: rotation is work just like translation).
     *  2026-09-09 user ruling: 5x CHEAPER than the linear anchor (20/11 -> 4/11).
     *  Torque = I*alpha and I grows with m*L^2, so at the linear price a mid-size
     *  hull's turn-in was unaffordable (user measurement: 9288 kg ship, 600K+ FE/t
     *  peak / 60K FE/t sustained). The linear anchor (1 kg hovering = 1 FE/t) is
     *  unchanged; per-core fine-tuning is the rotPriceMult tuning (GUI tab 1). */
    private static final double FE_PER_NM_SECOND = 4.0 / 11.0;
    /** Hover cascade: outer position-error -> climb-rate gain, s^-1.
     *  Now a per-core tuning (config GUI tab 4, see Tunings). */
    private static final double DEFAULT_HOVER_KP = 4.0;
    /** Hover acceleration clamp, m/s^2 — must exceed gravity (sable default -11.0,
     *  queried live via DimensionPhysicsData) with margin. */
    private static final double HOVER_MAX_ALPHA = 20.0;
    /** Climb-rate control (stick-held vertical channel): target climb speed at full
     *  signal (m/s) and inner velocity-loop gain (s^-1), both per-core tunings now.
     *  Gravity feed-forward is NOT a constant — DimensionPhysicsData.getGravity()
     *  says sable's default is (0,-11,0) and datapacks can override per dimension,
     *  so it is queried live every physics frame. (Guessing 10.0 cost us a
     *  1.0 m/s^2 deficit: the ship settled at v=-0.13 m/s and every stick tap lost
     *  altitude, 2026-09-01 log.) */
    private static final double DEFAULT_CLIMB_RATE = 4.0;
    private static final double DEFAULT_CLIMB_KV = 4.0;
    /** Predicted pull-out deceleration for the hover capture point, m/s^2.
     *  v6.1: was 3.0 (HOVER_BRAKE_ALPHA) — but the cascade settles AT its target
     *  (critically damped at the default tunings: zeta = climbKv / 2*sqrt(hoverKp)
     *  = 1, no overshoot), so the target IS where the ship parks, and the
     *  prediction must match the pull-out the force clamp can actually deliver:
     *  net upward authority is HOVER_MAX_ALPHA - |g| = 20 - 11 = 9 m/s^2 (gravity
     *  eats the rest of the clamp); 7.0 leaves margin for the hoverKp ramp near
     *  the target. The 3.0-vs-20 mismatch put a captured target 3x deeper than the
     *  ship could stop — see the 2026-09-02 mystery-descent analysis (capture
     *  y=113, v=-15 -> target 74.7 = the exact 38 m dive the log showed). NOTE:
     *  lowering climbKv / raising hoverKp in the tuning GUI makes the cascade
     *  underdamped — this constant does NOT adapt. */
    private static final double PULLOUT_ALPHA = 7.0;
    /** Release confirmation window, physics frames: the capture fires only after the
     *  stick has read zero for this many CONSECUTIVE frames (legacy-FC lesson: never
     *  capture on a single release edge). 5 frames @40Hz = 125 ms. Kills one-frame
     *  input glitches (proxy TTL blip, cross-thread timing) as capture triggers. */
    private static final int RELEASE_CONFIRM_FRAMES = 5;
    /** External-drift re-capture (M3 revision): once settled, being dragged this far
     *  from the hover target for DRIFT_CONFIRM_FRAMES physics frames re-captures here. */
    private static final double DRIFT_RECAPTURE_M = 8.0;
    private static final int DRIFT_CONFIRM_FRAMES = 40; // 1 s @ 40 Hz
    /** |y - targetY| below this marks the hover "settled" (arms drift detection). */
    private static final double SETTLE_M = 1.0;
    /** M2 thrust gears: factory-default force multiplier per gear. Default gear index 2
     *  reproduces the pre-M2 THRUST_ACCEL behavior. */
    private static final double[] DEFAULT_GEAR_MULT = {0.25, 0.5, 1.0, 2.0};
    public static final int GEAR_COUNT = DEFAULT_GEAR_MULT.length;
    /** Allowed multiplier range for the config GUI / payload validation. */
    public static final double GEAR_MULT_MIN = 0.05;
    public static final double GEAR_MULT_MAX = 10.0;
    /** Player-tunable per-gear force multipliers (config GUI tab 1). Copy-on-write:
     *  writers (server thread: payload handler, NBT load) publish a whole new array,
     *  readers (physics thread) grab the volatile reference once per frame; array
     *  contents are never mutated in place, so no lock and no tearing. */
    private volatile double[] gearMults = DEFAULT_GEAR_MULT.clone();
    /** v6.0 unified velocity-command loop (legacy FC pd_world_space_control pattern):
     *  per body axis, F = m * transKv * (v_target - v), v_target = stick * top speed.
     *  Subsumes the release brake, sideslip suppression, and the gear speed cap
     *  (a zero-demand axis is driven to zero = braking; top speed is commanded
     *  directly, so no drag coefficient estimate is needed). transKv is a per-core
     *  tuning (config GUI tab 4, see Tunings). */
    private static final double DEFAULT_TRANS_KV = 2.0;

    /**
     * Control-law feel parameters (config GUI: 0..5 on tab 4, 6..7 on tab 1), one
     * immutable snapshot per core. Field order is FIXED — the NBT array, the
     * CoreTuningsPayload ints, and the menu's ContainerData all use it:
     *   0 attitudeKd  姿态阻尼   (0.5..20,  default 6.0)
     *   1 attitudeKp  姿态刚度   (0.5..16,  default 4.0)
     *   2 transKv     平移阻尼   (0..8,     default 2.0)
     *   3 hoverKp     悬停刚度   (0.5..16,  default 4.0)
     *   4 climbKv     悬停阻尼   (0.5..16,  default 4.0)
     *   5 climbRate   满杆爬升速率 (1..12,  default 4.0)
     *   6 attMaxAlpha 角加速度上限 rad/s^2 (1..20, default 8.0 = the pre-tuning
     *     AttitudeController.MAX_ALPHA hardcode)
     *   7 rotPriceMult 旋转能耗倍率 (0.2..2, default 1.0; multiplies
     *     FE_PER_NM_SECOND at both torque pricing points)
     */
    public record Tunings(double attitudeKd, double attitudeKp, double transKv,
                          double hoverKp, double climbKv, double climbRate,
                          double attMaxAlpha, double rotPriceMult) {
        public static final int COUNT = 8;
        public static final double[] MIN = {0.5, 0.5, 0.0, 0.5, 0.5, 1.0, 1.0, 0.2};
        public static final double[] MAX = {20.0, 16.0, 8.0, 16.0, 16.0, 12.0, 20.0, 2.0};
        public static final Tunings DEFAULT = new Tunings(AttitudeController.DEFAULT_KD,
                AttitudeController.DEFAULT_KP, DEFAULT_TRANS_KV, DEFAULT_HOVER_KP,
                DEFAULT_CLIMB_KV, DEFAULT_CLIMB_RATE, AttitudeController.MAX_ALPHA, 1.0);

        public double[] toArray() {
            return new double[]{attitudeKd, attitudeKp, transKv, hoverKp, climbKv, climbRate,
                    attMaxAlpha, rotPriceMult};
        }

        /** Clamped funnel — payload handler and NBT load both come through here. */
        public static Tunings fromArray(double[] a) {
            if (a.length != COUNT) {
                return DEFAULT;
            }
            double[] c = new double[COUNT];
            for (int i = 0; i < COUNT; i++) {
                c[i] = Math.max(MIN[i], Math.min(MAX[i], a[i]));
            }
            return new Tunings(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]);
        }
    }

    /** Per-core control-law tunings (copy-on-write like gearMults: writers publish a
     *  whole new immutable record; the physics thread grabs the volatile reference
     *  once per frame — no lock, no tearing across the six values). */
    private volatile Tunings tunings = Tunings.DEFAULT;
    /** Full-stick top speed at gear multiplier 1.0, m/s. Gear top speed =
     *  TOP_SPEED_BASE * gearMult (default G1=6 / G2=12 / G3=24 / G4=48). Public: the
     *  config GUI shows the converted top speed per gear. */
    public static final double TOP_SPEED_BASE = 24.0;
    /** Current thrust gear (index into gearMults). Persisted. */
    private volatile int gear = 2;
    /** Smoothed gear multiplier (physics thread): slews toward gearMults[gear] at
     *  GEAR_SLEW_PER_S so a gear shift ramps thrust over ~0.5 s instead of stepping. */
    private double gearMultSmoothed = DEFAULT_GEAR_MULT[2];
    /** Gear slew rate, multiplier units per second (full 0.25..2.0 range in 0.5 s). */
    private static final double GEAR_SLEW_PER_S = 3.5;
    /** Altitude-follows-attitude switch (v6.0, feather click): while ON, horizontal
     *  stick relaxes the hover altitude lock so pitch converts to climb/dive. */
    private volatile boolean altitudeFollow = true;
    /** Horizontal-stick-held flag for the altitude-follow release edge (physics thread). */
    private boolean wasHorizontalInput;
    /** Consecutive zero-input frames toward the release-confirm window (physics thread). */
    private int inputZeroFrames;
    /** Starship translation-loop accel clamp (m/s^2). */
    private static final double MAX_LIN_ALPHA = 4.0;

    /**
     * Top-level control mode (2026-09-05 slimming, user ruling). Three modes:
     *   OFF      = fully off: no force/torque writes, no energy use — the physics
     *              frame keeps only the read-only telemetry snapshots.
     *   STARSHIP = the real product mode (handoff §8 step 5): attitude = rate
     *              command with release-stick hold, translation layer = coupled/
     *              decoupled + gravity compensation switches; the selfBalance
     *              sub-switch adds release-stick auto-level on top.
     *   DAMPING  = manual stabilized: attitude pure KD damping + full raw
     *              redstone/keyboard/gamepad passthrough.
     */
    public enum ControlMode {OFF, DAMPING, STARSHIP}

    private volatile ControlMode mode = ControlMode.OFF;

    /** Starship sub-switch (was the SELF_BALANCE mode, 2026-09-05 user ruling):
     *  release-stick capture auto-levels the hull (yaw-hold + pitch/roll -> 0) and
     *  forces coupled on. Only meaningful in STARSHIP. NBT-persisted. */
    private volatile boolean selfBalance;
    /** Gravity compensation switch (test hover; the real feature arrives with starship mode). */
    private volatile boolean gravityComp;
    /** Starship: coupled = release-stick auto-brake; decoupled = Newtonian drift. */
    private volatile boolean coupled = true;
    /** Latest world position from logicalPose, read by the server thread (gamepad
     *  pairing, GUI status tab). */
    private final AtomicReference<Vector3d> lastPosition = new AtomicReference<>();
    /** M1: starship release-stick attitude hold target (full attitude, or yaw-hold +
     *  pitch/roll -> 0 when the selfBalance switch captured the release). */
    private final AtomicReference<Quaterniond> starshipAttitudeTarget = new AtomicReference<>();
    /** M1: rate-command stick was held last physics frame (release-edge detection;
     *  written on the physics thread, reset on mode switch — volatile). */
    private volatile boolean hadRateInput;
    /** Latest orientation seen on the physics thread, read by the server thread on mode switch. */
    private final AtomicReference<Quaterniond> lastOrientation = new AtomicReference<>();
    /** Latest velocities seen on the physics thread, for the right-click snapshot. */
    private final AtomicReference<Vector3d> lastLinearVelocity = new AtomicReference<>();
    private final AtomicReference<Vector3d> lastAngularVelocity = new AtomicReference<>();
    /**
     * Keyboard pilot channel (M3k): direct read of a nearby in-use Linked Typewriter
     * (see TypewriterInput). Blaze-rod right-click or the config GUI toggles.
     * Polled on the server thread, consumed on the physics thread as an immutable
     * KeyFaces snapshot.
     * Default ON (2026-09-05 user ruling: pairing is player-centric and the poll is
     * zero-cost without a typewriter; a default-OFF channel reads as "broken" after
     * a core re-place). Old saves keep their persisted "kbd" value.
     */
    private volatile boolean keyboardChannel = true;
    private final TypewriterInput.Poller typewriterPoller = new TypewriterInput.Poller();
    private final AtomicReference<TypewriterInput.KeyFaces> keyFaces =
            new AtomicReference<>(TypewriterInput.KeyFaces.IDLE);
    /** Channel -> GLFW key code (config GUI tab 2). Copy-on-write like gearMults:
     *  read by the server-thread poller, swapped whole by the payload handler / NBT
     *  load. -1 = unbound. */
    private volatile int[] keyMap = TypewriterInput.defaultKeyMap();
    /** Physics-thread edge detector for the gear keys (snapshot identity changes per poll). */
    private TypewriterInput.KeyFaces lastConsumedKeys = TypewriterInput.KeyFaces.IDLE;
    /**
     * Gamepad pilot channel (M3a): raw frames arrive C2S (GamepadInputPayload), the
     * per-core Poller resolves semantics on the server thread, and an immutable State
     * snapshot crosses to the physics thread via this AtomicReference — same pattern
     * as the typewriter channel. Always-on (no toggle): player-centric pairing within
     * 32 blocks of the ship's logical position, untouched sticks send nothing.
     */
    private final GamepadInput.Poller gamepadPoller = new GamepadInput.Poller();
    private final AtomicReference<GamepadInput.State> padState =
            new AtomicReference<>(GamepadInput.State.IDLE);
    /** Gamepad channel mapping (config GUI tab 2, gamepad sub-section): volatile
     *  copy-on-write snapshot like keyMap; layout = GamepadInput.MAP_LENGTH ints. */
    private volatile int[] gamepadMap = GamepadInput.defaultPadMap();
    /** Physics-thread edge detector for the gamepad buttons (identity per poll). */
    private GamepadInput.State lastConsumedPad = GamepadInput.State.IDLE;
    /**
     * Proxy redstone feeds (2026-09-02: attitude/translation proxy blocks), keyed by
     * proxy position. Written on the server thread by proxies, consumed on the physics
     * thread; entries expire after PROXY_FEED_TTL_NS without a rewrite (proxies push
     * every tick, so TTL only trips when a proxy is removed/de-physicalized).
     * v6.1: TTL 0.3 s -> 1.0 s. A short server-thread stall (GC, chunk load) must NOT
     * zero the sticks mid-flight — a feed expiring at 0.3 s read as a phantom
     * stick-release frame. 1 s still catches a genuinely removed proxy quickly, and
     * the release-confirm window covers the residual blur.
     */
    private final java.util.concurrent.ConcurrentHashMap<BlockPos, ProxyFeed> attitudeProxyFeeds =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<BlockPos, ProxyFeed> translationProxyFeeds =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long PROXY_FEED_TTL_NS = 1_000_000_000L; // 1.0 s
    /** Sub-level this core is registered under in CoreDirectory (physics thread). */
    private ServerSubLevel registeredSubLevel;
    /** HUD calibration (config GUI tab 3): pilot-relative panel offsets in blocks and
     *  an extra projection scale multiplier. Server-thread state; pushed to every
     *  HudScreenBlockEntity on the same structure on change (they re-sync to clients
     *  via their BE update tag). Reserved anchor for the upcoming "reference lines
     *  auto-align to the horizon" feature. */
    public static final double HUD_OFFSET_RANGE = 2.0;
    /** 2026-10-26 v2: Y offset widened to ±8 — the panel center is now
     *  anchored at PANEL_ANCHOR_Y (3.85, higher than the legacy default
     *  center 2.35), so low placements and migrated saves need the range. */
    public static final double HUD_OFFSET_Y_RANGE = 8.0;
    /** 2026-10-26 v2: scale range widened from 0.5..2.0 to absorb the folded
     *  legacy eyeX/2 panel-size factor (old eyeX 0.5..6.0 -> /2 = 0.25..3.0)
     *  so migrated saves never clamp. The GUI slider itself still spans
     *  0.5..2.0. */
    public static final double HUD_SCALE_MIN = 0.1;
    public static final double HUD_SCALE_MAX = 6.0;
    public static final double HUD_EYE_X_MIN = 0.5;
    public static final double HUD_EYE_X_MAX = 6.0;
    /** 2026-10-26 v2: eyeY redefined — cockpit seat-height compensation for
     *  the projection eye point (was a second panel-height knob). ±3 blocks. */
    public static final double HUD_EYE_Y_RANGE = 3.0;
    public static final double HUD_PITCH_STEP_MIN = 1.0;
    public static final double HUD_PITCH_STEP_MAX = 15.0;
    public static final double HUD_ATT_SIZE_MIN = 0.2;
    public static final double HUD_ATT_SIZE_MAX = 1.5;
    public static final double HUD_ATT_BORDER_MAX = 0.5;
    /** HUD lock-box target search range, in integer blocks. */
    public static final int HUD_LOCK_RANGE_MIN = 64;
    public static final int HUD_LOCK_RANGE_MAX = 512;
    public static final int HUD_LOCK_RANGE_DEFAULT = 160;
    /** Multi-lock cone half-angle, degrees (2026-09-24): secondary lock boxes
     *  go to same-species tracks within this angle of the crosshair. */
    public static final int HUD_LOCK_CONE_MIN = 5;
    public static final int HUD_LOCK_CONE_MAX = 45;
    public static final int HUD_LOCK_CONE_DEFAULT = 20;
    /** Total lock-box count cap including the primary (hard cap 16 pending;
     *  GUI range 1..8). */
    public static final int HUD_LOCK_MAX_COUNT_MIN = 1;
    public static final int HUD_LOCK_MAX_COUNT_MAX = 8;
    public static final int HUD_LOCK_MAX_COUNT_DEFAULT = 8;
    private volatile double hudOffsetX;
    private volatile double hudOffsetY;
    private volatile double hudScale = 1.0;
    private volatile double hudEyeX = 2.0;      // eye distance (projection eye point), blocks
    private volatile double hudEyeY;            // eye seat-height offset, blocks (v2: was 0.12 panel-tweak)
    private volatile double hudPitchStep = 5.0; // ladder rung interval, degrees
    private volatile double hudAttSize = 1.0;   // attitude-zone size multiplier
    private volatile double hudAttBorder;       // attitude-zone border fraction 0..0.5
    private volatile boolean hudInputLine = true;
    /** Per-core display preference; old saves retain the enabled default. */
    private volatile boolean hudOutline = true;
    private volatile int hudLockRange = HUD_LOCK_RANGE_DEFAULT;
    private volatile int hudLockConeDeg = HUD_LOCK_CONE_DEFAULT;
    private volatile int hudLockMaxCount = HUD_LOCK_MAX_COUNT_DEFAULT;
    /** HUD lock filter chain state (§2.28, 2026-09-23): species registry path
     *  (null = all). Persisted. The HUD screens' HudTrackFilter reads it each
     *  tick; speciesSerial bumps on the lock_species channel (V) — the actual
     *  species list lives filter-side, so the HUD advances the notch and
     *  writes the result back through setHudLockSpecies. The CATEGORY notch
     *  is structure-level since 2026-10-30 (修订 C, 落地②): owned HERE
     *  (persisted), Tab edge cycles it and every HUD screen mirrors it. */
    private volatile String hudLockSpecies;
    private volatile int hudLockSpeciesSerial;
    /** Tab notch (修订 C): -1 = OFF (target layer fully off), 0..3 =
     *  HudTargetProvider.Category ordinal. Structure-level, persisted here;
     *  screens mirror it (no per-screen notch anymore). */
    private volatile int hudLockCategory = HudScreenBlockEntity.LOCK_NOTCH_OFF;
    // ---- 落地② uplink state machines (协议 §3, server thread, keyboard tick) ----
    /** G short/long: press tick counter, -1 = idle. 6 ticks = 300 ms long-press
     *  threshold (§2.29 §7 初值). Channel + focus captured at the press edge
     *  (修订 G); the short-press ruling is deferred to the release edge and a
     *  fired long press swallows the release (裁断稿 §4). */
    private int gHoldTicks = -1;
    private int gChannel;
    private String gFocusId;
    private boolean gLongFired;
    /** H unified click/hold/double machine (裁断稿 §6): the first release only
     *  records a PENDING click — CLICK ships when the 5-tick (250 ms) window
     *  expires without a second press, so a double-click never fires a plain
     *  round first. HOLD_START ships at window expiry while still held. */
    private static final int G_LONG_TICKS = 6;
    private static final int H_WINDOW_TICKS = 5;
    private static final int H_IDLE = 0, H_PRESS = 1, H_PENDING = 2,
            H_HOLDING = 3, H_CONSUMED = 4;
    private int hState = H_IDLE;
    private int hTicks;
    private int hChannel;
    /** Latest merged attitude stick demand (redstone torque faces + typewriter +
     *  gamepad), written by the physics thread each frame; HUD displays read it for
     *  the input line. -1..1 per axis; zeroed while the mode is OFF. */
    private volatile double stickPitch;
    private volatile double stickRoll;
    /** Structure the HUD calib was last pushed to (server thread; null = not pushed /
     *  de-physicalized). Drives the one-shot re-assert in serverTick. */
    private ServerSubLevel calibPushedSub;
    /** Structure energy account (v0.13): physics spends FE straight out of the
     *  structure's distributor pools — no core-local buffer, no trickle settlement.
     *  Pool snapshot rebound from serverTick; see StructureEnergyAccount. */
    private final StructureEnergyAccount energyAccount = new StructureEnergyAccount();
    /** Legacy core-buffer FE from pre-v0.13 saves, poured back into the pools on a
     *  server tick once the account has pools (or voided after a grace period).
     *  -1 = nothing pending (server thread only). */
    private long pendingLegacyFe = -1;
    /** Ticks spent waiting for pools to appear before voiding pendingLegacyFe. */
    private int legacyFlushWait;
    /** Low-passed consumption, FE/t (tau ≈ 0.5 s, per-tick alpha) — the HUD energy
     *  readout. Fed from the account's per-tick settled spend. */
    private double drawFePerTick;
    private static final double DRAW_SMOOTH_ALPHA = 1.0 - Math.exp(-0.05 / 0.5);
    /** END estimate from the measured stock decline (fed in serverTick; shared
     *  estimator with the HUD screen so the CC channel and the HUD agree). */
    private final EnduranceEstimator endurance = new EnduranceEstimator();
    private volatile double enduranceSeconds = Double.POSITIVE_INFINITY;

    // ---- interface v1 telemetry (fire-control request #2, 2026-09-11) ----
    /** Latest immutable telemetry snapshot, produced on the physics frame and
     *  volatile-published (runs even in OFF — read-only telemetry is exempt
     *  from the OFF skip). Null until the first physics frame with a valid
     *  handle; consumers poll at 20 Hz and treat null/stale as "static
     *  platform" (their degrade rule, not ours). */
    private volatile TelemetrySnapshot telemetry;
    /** structureId of the current structure, recomputed when CoreDirectory
     *  membership changes (physics thread writes, volatile reads). */
    private volatile String telemetryStructureId;
    /** Acceleration estimator state (physics thread only): the numerical
     *  derivative of world velocity per frame, EMA-smoothed. */
    private final Vector3d prevFrameVelocity = new Vector3d();
    private boolean hasPrevFrameVelocity;
    private final Vector3d accelEma = new Vector3d();
    private static final double ACCEL_EMA_TAU = 0.1;   // s
    /** Exit clamp on the published acceleration magnitude — garbage guard for
     *  physics spikes, not a signal shaper (HUD discipline: clamp at the
     *  snapshot exit). m/s². */
    private static final double ACCEL_CLAMP = 1000.0;

    // ---- interface v1 pointing link (fire-control request #3, 2026-09-11) ----
    /** Pointing renewal timeout (interface v1 §2.3): no contact for 500 ms =
     *  requester gone -> release to OFF and disconnect. */
    private static final long POINTING_TIMEOUT_NS = 500_000_000L;
    /** Active pointing request (immutable, volatile-published; null = none).
     *  Written on the server thread by the request endpoints; the physics-side
     *  attitude execution CONSUMES it only once execution lands (scheduled
     *  before fire-control phase 4 — this round it is state machine only). */
    private volatile PointingRequest pointingRequest;
    /** Link state riding the telemetry snapshot (interface v1 §2.3). Never
     *  reaches SETTLED until execution lands. */
    private volatile PointingLinkState pointingLink = PointingLinkState.DISCONNECTED;
    /** Completion flag (interface v1 §2.2). Always false until execution lands. */
    private volatile boolean pointingSettled;
    /** Current requester (server thread only; non-null whenever the link is not
     *  DISCONNECTED). */
    private UUID pointingRequester;
    /** Last contact with the requester, System.nanoTime (server thread only). */
    private long pointingContactNanos;

    /** Immutable pointing request snapshot (interface v1 §2 payload + limits);
     *  renewed by replacement — every requestPointing call swaps in a fresh
     *  record with a fresh timestamp. */
    private record PointingRequest(UUID requester, PointingMode mode,
                                   @Nullable Vec3 posOrDir, @Nullable UUID targetEntity,
                                   double maxAngularRate, double maxAngularAccel,
                                   long renewNanos) {
    }

    private long physicsFrames;
    /** World gameTime of the most recent physics callback; Long.MIN_VALUE = never physicalized. */
    private volatile long lastPhysicsGameTime = Long.MIN_VALUE;
    /**
     * NBT-persisted flag: this BE has been physicalized at least once. De-physicalization
     * drops the blocks back into the world and rebuilds the BE, wiping runtime state —
     * without this, the watchdog would treat the rebuilt BE as "never physicalized" and
     * stay silent exactly when it should alert.
     */
    private boolean wasPhysicalized;
    private boolean watchdogTripped;
    /** Last physics frame we logged an FE-starvation warning (throttled). */
    private long lastStarveLogFrame = -1000;

    // Scratch objects to avoid per-frame allocation on the physics thread.
    private final Vector3d angularVelocity = new Vector3d();
    private final Vector3d torque = new Vector3d();
    private final Vector3d angularImpulse = new Vector3d();
    private final Vector3d linearImpulse = new Vector3d();
    private final Vector3d linVel = new Vector3d();
    private final Vector3d linForce = new Vector3d();
    /** Scratch for sideslip suppression (physics thread only). */
    private final Vector3d vBody = new Vector3d();
    private final Vector3d latBody = new Vector3d();
    /** M1 scratch: body-frame target angular velocity from the attitude channel. */
    private final Vector3d omegaTargetBody = new Vector3d();
    /** Velocity loop's body-frame target (physics thread; diagnostic for the periodic
     *  STARSHIP log — NaN when the loop is not running). */
    private final Vector3d vTargetBody = new Vector3d(Double.NaN, Double.NaN, Double.NaN);
    /** Scratch for the per-frame gravity query (physics thread only). */
    private final Vector3d gravityVec = new Vector3d();
    /** Hover altitude target (physics thread only); NaN = disarmed. */
    private double hoverTargetY = Double.NaN;
    /** Stick-held flag for release-edge detection (physics thread only). */
    private boolean wasVerticalInput = false;
    /** External-drift re-capture state (physics thread only): settled = reached the
     *  target at least once since the last capture; driftFrames = consecutive frames
     *  spent beyond DRIFT_RECAPTURE_M while settled. */
    private boolean hoverSettled = false;
    private int driftFrames = 0;

    public BridgeCoreBlockEntity(BlockPos pos, BlockState state) {
        super(BCRegistry.BRIDGE_CORE_BE.get(), pos, state);
    }

    // ---- mode switching (server thread, from block right-click) ----

    /** @return chat-ready status line (also logged) */
    public String cycleMode() {
        // STARSHIP first (2026-09-02 user ruling): DAMPING was step-2 scaffolding;
        // day-to-day use is coupled/decoupled starship. Three-state cycle since the
        // 2026-09-05 slimming (HOLD bench deleted, SELF_BALANCE demoted to a switch).
        return setMode(switch (mode) {
            case OFF -> ControlMode.STARSHIP;
            case STARSHIP -> ControlMode.DAMPING;
            case DAMPING -> ControlMode.OFF;
        });
    }

    /** Direct mode set (CC write channel; right-click/payload go through cycleMode
     *  which lands here — one transition path, no parallel logic).
     *  @return chat-ready status line */
    public String setMode(ControlMode next) {
        if (next == ControlMode.STARSHIP) {
            // M1: fresh entry starts in pure damping; the release-stick attitude hold
            // arms only after the first rate-command input is released.
            starshipAttitudeTarget.set(null);
            hadRateInput = false;
        } else {
            starshipAttitudeTarget.set(null); // the M1 release-stick hold is starship-only
        }
        mode = next;
        setChanged();
        return "[bridgecore] mode -> " + next;
    }

    /** Starship switch: coupled (auto-brake) vs decoupled (Newtonian drift).
     *  Self-balance is always coupled — the toggle is inert while it is on. */
    public String toggleCoupled() {
        if (mode == ControlMode.STARSHIP && selfBalance) {
            return "[bridgecore] self-balance is always coupled";
        }
        coupled = !coupled;
        return "[bridgecore] coupled -> " + (coupled ? "ON (release = brake)" : "OFF (Newtonian drift)");
    }

    /** Starship sub-switch (was the SELF_BALANCE mode): release-stick capture
     *  auto-levels the hull and forces coupled on. Starship-mode only. */
    public String toggleSelfBalance() {
        if (mode != ControlMode.STARSHIP) {
            return "[bridgecore] self-balance only exists in starship mode";
        }
        selfBalance = !selfBalance;
        return "[bridgecore] self-balance -> " + (selfBalance
                ? "ON (release = auto-level, always coupled)" : "OFF");
    }

    /** Gravity compensation toggle (test hover). @return chat-ready status line */
    public String toggleGravityComp() {
        gravityComp = !gravityComp;
        return "[bridgecore] gravity compensation -> " + (gravityComp ? "ON" : "OFF");
    }

    public StructureEnergyAccount energyAccount() {
        return energyAccount;
    }

    /** Smoothed consumption rate, FE per tick (server thread; HUD energy readout). */
    public double drawFePerTick() {
        return drawFePerTick;
    }

    /** Endurance estimate in seconds (POSITIVE_INFINITY = not in decline); the CC
     *  channel reads this, the HUD computes its own via the shared estimator. */
    public double enduranceSeconds() {
        return enduranceSeconds;
    }

    /** M2: cycle the thrust gear (right-click on a translation proxy). */
    public String cycleGear() {
        return setGear((gear + 1) % gearMults.length);
    }

    /** Direct gear set, 0-based index (CC write channel; the right-click cycle
     *  lands here too — one transition path). @return chat-ready status line. */
    public String setGear(int index) {
        double[] gm = gearMults;
        gear = Math.max(0, Math.min(gm.length - 1, index));
        setChanged();
        double topSpeed = TOP_SPEED_BASE * gm[gear];
        return "[bridgecore] gear -> G" + (gear + 1) + " (thrust x" + gm[gear]
                + ", top ~" + Math.round(topSpeed) + " m/s)";
    }

    /** v6.0: toggle altitude-follows-attitude (feather click). */
    public String toggleAltitudeFollow() {
        altitudeFollow = !altitudeFollow;
        return "[bridgecore] altitude follow -> " + (altitudeFollow
                ? "ON (pitch converts to climb/dive)" : "OFF (hover locks altitude)");
    }

    /** M3k: toggle the keyboard pilot channel (blaze-rod click). */
    public String toggleKeyboardChannel() {
        keyboardChannel = !keyboardChannel;
        if (!keyboardChannel) {
            keyFaces.set(TypewriterInput.KeyFaces.IDLE);
        }
        return "[bridgecore] keyboard channel -> " + (keyboardChannel
                ? "ON (use a Linked Typewriter within 32 blocks)" : "OFF");
    }

    /** M3k: gear shift from the keyboard channel. Wraps around like cycleGear(). */
    private void shiftGear(int delta) {
        double[] gm = gearMults;
        gear = Math.floorMod(gear + delta, gm.length);
    }

    public int gear() {
        return gear;
    }

    // ---- config GUI state (server thread; see field docs for threading) ----

    /** Current gear multipliers (immutable snapshot — do NOT mutate the returned array). */
    public double[] gearMults() {
        return gearMults;
    }

    /** Config GUI tab 1: replace all four gear multipliers. Values pre-validated by
     *  the payload handler; clamped again here so NBT loads share one funnel. */
    public void setGearMults(double[] mults) {
        if (mults.length != GEAR_COUNT) {
            return;
        }
        double[] next = new double[GEAR_COUNT];
        for (int i = 0; i < GEAR_COUNT; i++) {
            next[i] = Math.max(GEAR_MULT_MIN, Math.min(GEAR_MULT_MAX, mults[i]));
        }
        gearMults = next; // copy-on-write publish; the physics slew picks it up next frame
        setChanged();
    }

    /** Current control-law tunings (immutable snapshot). */
    public Tunings tunings() {
        return tunings;
    }

    /** Config GUI (tabs 1+4): replace all eight tunings (values pre-validated AND
     *  clamped by the payload handler via Tunings.fromArray). */
    public void setTunings(Tunings next) {
        tunings = next; // copy-on-write publish; the physics frame picks it up next frame
        setChanged();
    }

    /** Current channel -> GLFW code map (immutable snapshot — do NOT mutate). */
    public int[] keyMap() {
        return keyMap;
    }

    /** Config GUI tab 2: rebind one channel (keyCode -1 = unbind). */
    public void setKeyBinding(int channel, int keyCode) {
        if (channel < 0 || channel >= TypewriterInput.CHANNEL_COUNT) {
            return;
        }
        int[] next = keyMap.clone();
        next[channel] = keyCode;
        keyMap = next;
        setChanged();
    }

    /** Current gamepad mapping (immutable snapshot — do NOT mutate). */
    public int[] gamepadMap() {
        return gamepadMap;
    }

    /** Config GUI tab 2 (gamepad sub-section): rebind one axis channel
     *  (source id + invert flag). Values pre-validated by the payload handler. */
    public void setPadAxisBinding(int channel, int source, int invert) {
        if (channel < 0 || channel >= GamepadInput.AXIS_CHANNEL_COUNT) {
            return;
        }
        int[] next = gamepadMap.clone();
        next[channel * 2] = source;
        next[channel * 2 + 1] = invert;
        gamepadMap = next;
        setChanged();
    }

    /** Config GUI tab 2 (gamepad sub-section): rebind one button channel. */
    public void setPadButtonBinding(int channel, int button) {
        if (channel < 0 || channel >= GamepadInput.BTN_CHANNEL_COUNT) {
            return;
        }
        int[] next = gamepadMap.clone();
        next[GamepadInput.AXIS_CHANNEL_COUNT * 2 + channel] = button;
        gamepadMap = next;
        setChanged();
    }

    // ---- read-only accessors for the config GUI status tab (server thread) ----

    public ControlMode mode() {
        return mode;
    }

    public boolean gravityCompOn() {
        return gravityComp;
    }

    public boolean coupledOn() {
        return coupled || (mode == ControlMode.STARSHIP && selfBalance);
    }

    public boolean altitudeFollowOn() {
        return altitudeFollow;
    }

    public boolean keyboardChannelOn() {
        return keyboardChannel;
    }

    public boolean selfBalanceOn() {
        return selfBalance;
    }

    public boolean gamepadLinked() {
        return gamepadPoller.isLinked();
    }

    /** Latest logical (real-world) position seen on the physics thread, or null. */
    /** Latest attitude snapshot (physics-published), null before first frame. */
    public Quaterniond lastOrientation() {
        return lastOrientation.get();
    }

    public Vector3d lastLogicalPosition() {
        return lastPosition.get();
    }

    /** Latest world-frame velocity seen on the physics thread, or null. */
    public Vector3d lastVelocity() {
        return lastLinearVelocity.get();
    }

    // ---- interface v1 telemetry + pointing (fire-control requests #2/#3, 2026-09-11) ----

    /** Interface v1 §1.2 first tier (same-structure direct read): the latest
     *  immutable telemetry snapshot, produced on the physics frame and
     *  volatile-published, or null before the first physics frame with a valid
     *  handle (never-physicalized bench core). Safe to poll from the server
     *  thread at 20 Hz. */
    public TelemetrySnapshot telemetrySnapshot() {
        return telemetry;
    }

    /** Physics thread: build and publish this frame's telemetry snapshot.
     *  acceleration = numerical derivative of world velocity per frame,
     *  EMA-smoothed (tau = ACCEL_EMA_TAU), magnitude-clamped at ACCEL_CLAMP on
     *  the way out. Runs every frame with a valid handle, including OFF. */
    private void publishTelemetry(ServerSubLevel subLevel, Pose3d pose, Quaterniond orientation,
                                  Vector3dc omegaWorld, Matrix3dc inertia, double timeStep) {
        if (timeStep > 0) {
            if (hasPrevFrameVelocity) {
                double alpha = 1.0 - Math.exp(-timeStep / ACCEL_EMA_TAU);
                accelEma.x += ((linVel.x - prevFrameVelocity.x) / timeStep - accelEma.x) * alpha;
                accelEma.y += ((linVel.y - prevFrameVelocity.y) / timeStep - accelEma.y) * alpha;
                accelEma.z += ((linVel.z - prevFrameVelocity.z) / timeStep - accelEma.z) * alpha;
            }
            prevFrameVelocity.set(linVel);
            hasPrevFrameVelocity = true;
        }
        Vector3d accelOut = new Vector3d(accelEma);
        double aLen = accelOut.length();
        if (aLen > ACCEL_CLAMP) {
            accelOut.mul(ACCEL_CLAMP / aLen);
        }
        // Interface §1.1 wants BODY-frame angular velocity; the handle reads world.
        Vector3d omegaBody = orientation.transformInverse(omegaWorld, new Vector3d());
        var massData = subLevel.getMassTracker();
        Vector3dc com = massData.getCenterOfMass(); // shipyard grid coords (MassTracker frame)
        telemetry = new TelemetrySnapshot(
                new Vec3(pose.position().x(), pose.position().y(), pose.position().z()),
                new Vec3(linVel.x, linVel.y, linVel.z),
                new Vec3(accelOut.x, accelOut.y, accelOut.z),
                new Quaterniond(orientation),
                new Vec3(omegaBody.x, omegaBody.y, omegaBody.z),
                massData.getMass(),
                com != null ? new Vec3(com.x(), com.y(), com.z()) : Vec3.ZERO,
                new Matrix3d(inertia),
                energyAccount.totalStored(),
                energyAccount.totalCapacity(),
                drawFePerTick,
                mode,
                gear + 1,
                telemetryStructureId,
                pointingSettled,
                pointingLink);
    }

    /** Interface v1 structure identity: "<dimension>:<plotX>:<plotY>" — the
     *  ServerSubLevel constructor's global plot coordinates, exposed as
     *  plot.plotPos.x/.z (verified against Sable 2.0.3 sources: the second
     *  axis is named plotY in the constructor but stored as ChunkPos.z).
     *  Stable for the structure's lifetime; splits allocate a new plot. */
    private static String structureIdOf(ServerSubLevel sub) {
        ChunkPos plot = sub.getPlot().plotPos;
        return sub.getLevel().dimension().location() + ":" + plot.x + ":" + plot.z;
    }

    // ---- pointing mode request endpoints (interface v1 §2) ----
    // SERVER THREAD ONLY (the fire-control module calls from its BE tick).
    // State machine this round; attitude EXECUTION lands before fire-control
    // phase 4 — until then a live request reads REQUESTING and pointingSettled
    // stays false. The request snapshot crosses to the physics thread as one
    // immutable volatile-published record; nothing here runs on the physics
    // thread yet.

    /** Request pointing (interface v1 §2.1). Renews the link on every call —
     *  fire control re-issues at 20 Hz; 500 ms without contact falls back to
     *  OFF + DISCONNECTED (checked in the server ticker, wall clock). One
     *  requester at a time: a later request from a DIFFERENT requester
     *  overrides (§2.3). mode=OFF releases the active request but keeps the
     *  link CONNECTED; clearPointing() disconnects fully. */
    public void requestPointing(UUID requester, PointingMode mode, @Nullable Vec3 posOrDir,
                                @Nullable UUID targetEntity, double maxAngularRate,
                                double maxAngularAccel) {
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(mode, "mode");
        if (mode == PointingMode.OFF) {
            PointingRequest cur = pointingRequest;
            if (cur != null && cur.requester().equals(requester)) {
                pointingRequest = null;
                pointingSettled = false;
                pointingLink = PointingLinkState.CONNECTED;
                pointingContactNanos = System.nanoTime();
            }
            return;
        }
        if ((mode == PointingMode.POINT_AT_POS || mode == PointingMode.POINT_AT_DIR) && posOrDir == null) {
            throw new IllegalArgumentException(mode + " requires posOrDir");
        }
        if (mode == PointingMode.TRACK_ENTITY && targetEntity == null) {
            throw new IllegalArgumentException("TRACK_ENTITY requires targetEntity");
        }
        if (!(maxAngularRate > 0) || !(maxAngularAccel > 0)) { // NaN fails too
            throw new IllegalArgumentException("pointing limits must be positive: rate="
                    + maxAngularRate + " accel=" + maxAngularAccel);
        }
        pointingRequester = requester;
        pointingContactNanos = System.nanoTime();
        pointingRequest = new PointingRequest(requester, mode, posOrDir, targetEntity,
                maxAngularRate, maxAngularAccel, pointingContactNanos);
        pointingLink = PointingLinkState.REQUESTING;
        pointingSettled = false; // stays false until execution lands
    }

    /** Full disconnect (interface v1 §2.3). No-op for a non-current requester. */
    public void clearPointing(UUID requester) {
        if (pointingRequester != null && pointingRequester.equals(requester)) {
            pointingRequest = null;
            pointingRequester = null;
            pointingLink = PointingLinkState.DISCONNECTED;
            pointingSettled = false;
        }
    }

    /** Server ticker: 500 ms without contact = requester gone -> release to OFF
     *  and disconnect (interface v1 §2.3). Wall clock, so a paused game clock
     *  cannot stall the failsafe. */
    private void tickPointingLink() {
        if (pointingLink != PointingLinkState.DISCONNECTED
                && System.nanoTime() - pointingContactNanos > POINTING_TIMEOUT_NS) {
            pointingRequest = null;
            pointingRequester = null;
            pointingLink = PointingLinkState.DISCONNECTED;
            pointingSettled = false;
        }
    }

    public double hudOffsetX() {
        return hudOffsetX;
    }

    public double hudOffsetY() {
        return hudOffsetY;
    }

    public double hudScale() {
        return hudScale;
    }

    public double hudEyeX() {
        return hudEyeX;
    }

    public double hudEyeY() {
        return hudEyeY;
    }

    public double hudPitchStep() {
        return hudPitchStep;
    }

    public double hudAttSize() {
        return hudAttSize;
    }

    public double hudAttBorder() {
        return hudAttBorder;
    }

    public boolean hudInputLine() {
        return hudInputLine;
    }

    /** GUI tab 3: local dark contours for HUD ink; high-visibility colors
     * remain the same in both states. Server-owned and NBT-persisted. */
    public boolean hudOutline() {
        return hudOutline;
    }

    public void setHudOutline(boolean enabled) {
        if (hudOutline == enabled) return;
        hudOutline = enabled;
        setChanged();
        // Same-structure HUDs pull this preference every render tick.
    }

    public void toggleHudOutline() {
        setHudOutline(!hudOutline);
    }

    /** HUD lock target search range, blocks (tab 3, 9th param). */
    public int hudLockRange() {
        return hudLockRange;
    }

    /** Multi-lock cone half-angle, degrees (tab 3, 10th param). */
    public int hudLockConeDeg() {
        return hudLockConeDeg;
    }

    /** Lock-box count cap including the primary (tab 3, 11th param). */
    public int hudLockMaxCount() {
        return hudLockMaxCount;
    }

    /** §2.28 lock filter state: species registry path (null = all). */
    public @Nullable String hudLockSpecies() {
        return hudLockSpecies;
    }

    /** §2.28: bumped on every lock_species channel (V) press; HUD filters watch
     *  the delta to advance their species notch. */
    public int hudLockSpeciesSerial() {
        return hudLockSpeciesSerial;
    }

    /** lock_species channel (V): the HUD filter computes the next notch from
     *  its live candidate list and writes it back via setHudLockSpecies. */
    public void cycleHudLockSpecies() {
        hudLockSpeciesSerial++;
    }

    /** Filter-side write-back of the advanced species notch (see above). */
    public void setHudLockSpecies(@Nullable String species) {
        hudLockSpecies = species;
        setChanged();
    }

    /** §2.29: 结构级 Tab 档位（飞控核心持有，屏纯镜像）。 */
    public int hudLockCategory() {
        return hudLockCategory;
    }

    /** Tab 边沿：OFF→0..3→OFF 循环；切档时清空结构级种类过滤并推送全部屏。 */
    public void cycleHudLockCategory(ServerSubLevel sub) {
        hudLockCategory = hudLockCategory >= 3 ? HudScreenBlockEntity.LOCK_NOTCH_OFF : hudLockCategory + 1;
        setHudLockSpecies(null);
        pushHudLockCategory(sub);
        setChanged();
    }

    /** 把结构级档位镜像到全部 HUD 屏。 */
    public void pushHudLockCategory(ServerSubLevel sub) {
        for (HudScreenBlockEntity hud : CoreDirectory.hudScreensOf(sub)) {
            hud.setHudLockCategory(hudLockCategory);
        }
    }

    /** §2.29 §4: 指挥 HUD = 结构注册序第一块 HUD 屏（自动指定）。 */
    private @Nullable HudScreenBlockEntity commandHud(@Nullable ServerSubLevel sub) {
        if (sub == null) return null;
        for (HudScreenBlockEntity hud : CoreDirectory.hudScreensOf(sub)) {
            return hud;
        }
        return null;
    }

    /** §2.29 (2026-10-30 裁决稿②): V/G/H 指挥链，逐 tick 状态机。G/H 在按下
     *  边沿捕获火控频道（G 另捕获指挥屏焦点目标），长按/双击窗口内的 X 切台
     *  不会把松开事件错送到另一频道。 */
    private void tickCommandInputs(Level level, @Nullable ServerSubLevel sub,
            TypewriterInput.KeyFaces keys) {
        FireControlCoreBlockEntity fc = null;
        for (FireControlCoreBlockEntity c : FireControlCoreBlockEntity.coresOf(level, sub)) {
            fc = c;
            break;
        }
        if (fc == null) {
            gHoldTicks = -1;
            gLongFired = false;
            hState = H_IDLE;
            return;
        }
        // V: HUD 源精确锁任务 → 在有效候选中换锚定目标（②G 窄入口）；
        // 其余情况维持旧义 = 种类过滤循环。
        if (keys.lockSpeciesNow()) {
            HudScreenBlockEntity cmd = commandHud(sub);
            HudTargetProvider.ChannelTaskView task = cmd != null ? cmd.activeTaskView() : null;
            if (task != null && task.source() == HudTargetProvider.TaskSource.HUD
                    && task.mode() == HudTargetProvider.TaskMode.PRECISE_LOCK
                    && !task.assignedTargetIds().isEmpty()) {
                String next = cmd.nextLockCandidateAfter(task.assignedTargetIds().get(0));
                if (next != null) {
                    fc.cyclePreciseTarget(task.channel(), next, task.taskRev());
                }
            } else {
                cycleHudLockSpecies();
            }
        }
        // G: 阈值前松开 = 短按（预锁/解锁定）；到阈值即发 = 长按（精确锁），
        // 并吞掉随后的松开。
        if (keys.lockReleaseNow()) {
            gChannel = fc.activeChannel();
            HudScreenBlockEntity cmd = commandHud(sub);
            gFocusId = cmd != null ? cmd.lockFocusId() : null;
            gHoldTicks = 0;
            gLongFired = false;
        } else if (gHoldTicks >= 0) {
            if (keys.lockReleaseHeld()) {
                gHoldTicks++;
                if (!gLongFired && gHoldTicks >= G_LONG_TICKS) {
                    fc.gPress(true, gFocusId, gChannel);
                    gLongFired = true;
                }
            } else {
                if (!gLongFired) {
                    fc.gPress(false, gFocusId, gChannel);
                }
                gHoldTicks = -1;
                gLongFired = false;
            }
        }
        // H: 单击 / 按住 / 双击三态。首次松开只记 PENDING（不发包），窗口期
        // 满才发 CLICK；期内第二击发 DOUBLE 并吞掉后续松开。单击与按住起始
        // 有 ≤H_WINDOW_TICKS(250ms) 的判定延迟（交付注明）。
        switch (hState) {
            case H_IDLE -> {
                if (keys.firePressNow()) {
                    hChannel = fc.activeChannel();
                    hTicks = 0;
                    hState = H_PRESS;
                }
            }
            case H_PRESS -> {
                hTicks++;
                if (!keys.fireHeld()) {
                    hTicks = 0;
                    hState = H_PENDING;
                } else if (hTicks >= H_WINDOW_TICKS) {
                    fc.hPress(FireControlCoreBlockEntity.HPressKind.HOLD_START, hChannel);
                    hState = H_HOLDING;
                }
            }
            case H_PENDING -> {
                hTicks++;
                if (keys.firePressNow()) {
                    fc.hPress(FireControlCoreBlockEntity.HPressKind.DOUBLE, hChannel);
                    hState = H_CONSUMED;
                } else if (hTicks >= H_WINDOW_TICKS) {
                    fc.hPress(FireControlCoreBlockEntity.HPressKind.CLICK, hChannel);
                    hState = H_IDLE;
                }
            }
            case H_HOLDING -> {
                if (!keys.fireHeld()) {
                    fc.hPress(FireControlCoreBlockEntity.HPressKind.HOLD_END, hChannel);
                    hState = H_IDLE;
                }
            }
            case H_CONSUMED -> {
                if (!keys.fireHeld()) {
                    hState = H_IDLE;
                }
            }
            default -> hState = H_IDLE;
        }
    }

    /** Merged pitch stick demand, -1..1 (nose-down positive); HUD input line. */
    public double stickPitch() {
        return stickPitch;
    }

    /** Merged roll stick demand, -1..1 (starboard-down positive); HUD input line. */
    public double stickRoll() {
        return stickRoll;
    }

    /** Config GUI tab 3: store the calibration and push it to every holo display on
     *  the same structure (they re-sync to clients via their BE update tag). Values
     *  pre-validated by the payload handler; clamped again for the NBT-load funnel.
     *  v2 (2026-10-26): x/y position the panel (around the fixed anchor), scale
     *  sizes it, eyeX/eyeY place the projection eye point only — all five params
     *  kept, couplings removed. */
    public void setHudCalib(double x, double y, double scale, double eyeX, double eyeY,
                            double pitchStep, double attSize, double attBorder,
                            boolean inputLine, int lockRange, int lockConeDeg,
                            int lockMaxCount) {
        hudOffsetX = Math.max(-HUD_OFFSET_RANGE, Math.min(HUD_OFFSET_RANGE, x));
        hudOffsetY = Math.max(-HUD_OFFSET_Y_RANGE, Math.min(HUD_OFFSET_Y_RANGE, y));
        hudScale = Math.max(HUD_SCALE_MIN, Math.min(HUD_SCALE_MAX, scale));
        hudEyeX = Math.max(HUD_EYE_X_MIN, Math.min(HUD_EYE_X_MAX, eyeX));
        hudEyeY = Math.max(-HUD_EYE_Y_RANGE, Math.min(HUD_EYE_Y_RANGE, eyeY));
        hudPitchStep = Math.max(HUD_PITCH_STEP_MIN, Math.min(HUD_PITCH_STEP_MAX, pitchStep));
        hudAttSize = Math.max(HUD_ATT_SIZE_MIN, Math.min(HUD_ATT_SIZE_MAX, attSize));
        hudAttBorder = Math.max(0.0, Math.min(HUD_ATT_BORDER_MAX, attBorder));
        hudInputLine = inputLine;
        hudLockRange = Math.max(HUD_LOCK_RANGE_MIN, Math.min(HUD_LOCK_RANGE_MAX, lockRange));
        hudLockConeDeg = Math.max(HUD_LOCK_CONE_MIN, Math.min(HUD_LOCK_CONE_MAX, lockConeDeg));
        hudLockMaxCount = Math.max(HUD_LOCK_MAX_COUNT_MIN,
                Math.min(HUD_LOCK_MAX_COUNT_MAX, lockMaxCount));
        setChanged();
        if (level != null
                && Sable.HELPER.getContaining(level, getBlockPos()) instanceof ServerSubLevel sub) {
            for (HudScreenBlockEntity hud : CoreDirectory.hudScreensOf(sub)) {
                hud.setCalib(hudOffsetX, hudOffsetY, hudScale, hudEyeX, hudEyeY,
                        hudPitchStep, hudAttSize, hudAttBorder, hudInputLine);
            }
        }
    }

    // ---- MenuProvider (config GUI; opened by empty-hand right-click) ----

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.bridgecore.bridge_core");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new BridgeCoreMenu(containerId, playerInventory, this);
    }

    /** Right-click state snapshot for the chat bar (handoff §8 unified interaction semantics). */
    public String snapshot(Level level) {
        StringBuilder sb = new StringBuilder("[bridgecore] mode=").append(mode)
                .append(" gravityComp=").append(gravityComp ? "ON" : "OFF")
                .append(" kbd=").append(keyboardChannel ? "ON" : "OFF")
                .append(" gear=G").append(gear + 1)
                .append(" fe=").append(energyAccount.totalStored())
                .append('/').append(energyAccount.totalCapacity());
        if (gravityComp) {
            sb.append(String.format(" g=%.4f", DimensionPhysicsData.getGravity(level).length()));
        }
        long last = lastPhysicsGameTime;
        boolean live = last != Long.MIN_VALUE && level.getGameTime() - last <= WATCHDOG_TICK_THRESHOLD;
        if (live) {
            sb.append(" physics=LIVE(frame ").append(physicsFrames).append(')');
        } else {
            sb.append(wasPhysicalized ? " physics=STALE(de-physicalized)" : " physics=NEVER");
        }
        Quaterniond q = lastOrientation.get();
        if (q != null) {
            Vector3d euler = q.getEulerAnglesXYZ(new Vector3d());
            sb.append(String.format(" | pitch/yaw/roll=%.1f/%.1f/%.1fdeg",
                    Math.toDegrees(euler.x), Math.toDegrees(euler.y), Math.toDegrees(euler.z)));
        }
        Vector3d v = lastLinearVelocity.get();
        if (v != null) {
            sb.append(String.format(" | v=(%.2f,%.2f,%.2f)m/s", v.x, v.y, v.z));
        }
        Vector3d w = lastAngularVelocity.get();
        if (w != null) {
            sb.append(String.format(" | |omega|=%.3f rad/s", w.length()));
        }
        if (mode == ControlMode.STARSHIP) {
            sb.append(" coupled=").append(coupledOn() ? "ON" : "OFF");
            sb.append(" selfBal=").append(selfBalance ? "ON" : "OFF");
            sb.append(" altFollow=").append(altitudeFollow ? "ON" : "OFF");
        }
        return sb.toString();
    }

    // ---- Sable actor callbacks (physics thread) ----

    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        // Logic-tick callback kept minimal; the real watchdog lives in the
        // world-side ticker (phase 1 lesson: a watchdog inside sable$tick dies
        // together with the callbacks it watches).
    }

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
        physicsFrames++;
        lastPhysicsGameTime = subLevel.getLevel().getGameTime();
        wasPhysicalized = true;
        watchdogTripped = false;

        // Keep the CoreDirectory entry current so redstone proxies on the same
        // structure can find us (re-registration is nearly free; the sub-level
        // object can change identity across reassembly).
        if (subLevel != registeredSubLevel) {
            CoreDirectory.deregister(registeredSubLevel, this);
            CoreDirectory.register(subLevel, this);
            registeredSubLevel = subLevel;
            telemetryStructureId = structureIdOf(subLevel);
        }

        Pose3d pose = subLevel.logicalPose();
        Quaterniond current = new Quaterniond(pose.orientation());
        lastOrientation.set(current);
        lastPosition.set(new Vector3d(pose.position()));

        // Gear smoothing (M2): slew the thrust multiplier toward the selected gear.
        // Snapshot the config array once per frame (copy-on-write — the GUI may swap
        // the reference mid-frame; the slew target just follows next frame).
        double[] gm = gearMults;
        double gearDiff = gm[Math.min(gear, gm.length - 1)] - gearMultSmoothed;
        double gearStep = GEAR_SLEW_PER_S * timeStep;
        gearMultSmoothed += Math.max(-gearStep, Math.min(gearStep, gearDiff));
        // Control-law tunings: one volatile grab per frame, used by the hover cascade,
        // the climb-rate channel, the translation loop, and the attitude PD below.
        Tunings tune = tunings;

        ControlMode activeMode = mode;
        // The selfBalance switch (was the SELF_BALANCE mode, 2026-09-05 slimming) is
        // coupled STARSHIP + release-stick auto-level: same translation/attitude
        // stack, coupled forced ON while the switch is on.
        boolean starshipFamily = activeMode == ControlMode.STARSHIP;
        boolean coupledEff = coupled || (starshipFamily && selfBalance);
        if (!handle.isValid()) {
            return;
        }

        Vector3dc omegaWorld = handle.getAngularVelocity(angularVelocity); // rad/s, world frame
        Matrix3dc inertia = subLevel.getMassTracker().getInertiaTensor();  // body frame, kg*m^2
        lastAngularVelocity.set(new Vector3d(omegaWorld));
        lastLinearVelocity.set(new Vector3d(handle.getLinearVelocity(linVel))); // m/s, world frame

        // Telemetry snapshot (interface v1 §1; fire-control request #2): produced
        // on the physics frame, volatile-published, and deliberately BEFORE the
        // OFF early-return — read-only telemetry is exempt from the OFF skip so
        // a parked ship still reports. Clamps live at this exit, never inside
        // the control law (HUD discipline).
        publishTelemetry(subLevel, pose, current, omegaWorld, inertia, timeStep);

        // OFF = fully off (2026-09-05 user ruling): no force/torque writes, no energy
        // use — hover, the raw redstone/keyboard/gamepad passthrough, and the attitude
        // loop are all skipped; only the read-only telemetry snapshots above run (the
        // GUI status tab and logs read them). Edge snapshots are still consumed so a
        // key held through an OFF stint cannot fire on re-enable, and the hover state
        // is disarmed so re-enabling never steers back to a pre-OFF altitude.
        if (activeMode == ControlMode.OFF) {
            lastConsumedKeys = keyFaces.get();
            lastConsumedPad = padState.get();
            hoverTargetY = Double.NaN;
            wasVerticalInput = false;
            wasHorizontalInput = false;
            inputZeroFrames = 0;
            hoverSettled = false;
            driftFrames = 0;
            stickPitch = 0.0;
            stickRoll = 0.0;
            return;
        }

        // Redstone demand, proxies only (2026-09-18 user ruling: the core body no
        // longer takes redstone — a core sitting next to wiring would eat stray
        // signals; all redstone enters via the proxy block). Two channels:
        //  - THRUST: translation proxy faces.
        //    Body/grid axes, -1..1; hover reads the vertical component for stick-following.
        //  - TORQUE: attitude proxy faces.
        //    Kept per-face (0..15); the bow-relative axis mapping below consumes faces.
        int[] torqueFaces = collectProxyDemand(attitudeProxyFeeds);
        int[] thrustFaces = collectProxyDemand(translationProxyFeeds);
        // Keyboard pilot channel (M3k): same face semantics as the proxies, so it
        // merges here and every downstream consumer (hover, velocity loop, attitude
        // rate command) sees it unchanged. Gear keys are press EDGES — act once per
        // new snapshot (identity check), never twice on the same poll.
        TypewriterInput.KeyFaces keys = keyFaces.get();
        for (int i = 0; i < 6; i++) {
            torqueFaces[i] = Math.min(15, torqueFaces[i] + keys.att()[i]);
            thrustFaces[i] = Math.min(15, thrustFaces[i] + keys.trans()[i]);
        }
        if (keys != lastConsumedKeys) {
            lastConsumedKeys = keys;
            if (keys.gearUp()) {
                shiftGear(1);
            }
            if (keys.gearDown()) {
                shiftGear(-1);
            }
            if (keys.levelNow() && starshipFamily) {
                engageAutoLevel(pose);
            }
        }
        // Gamepad pilot channel (M3a): analog demands compose onto the same body-frame
        // channels as the digital faces (bow-relative), then clamp into [-1,1]. Button
        // edges ride the same identity-consumption pattern as the keyboard keys.
        GamepadInput.State pad = padState.get();
        if (pad != lastConsumedPad) {
            lastConsumedPad = pad;
            if (pad.gearUpEdge()) {
                shiftGear(1);
            }
            if (pad.gearDownEdge()) {
                shiftGear(-1);
            }
            if (pad.levelEdge() && starshipFamily) {
                engageAutoLevel(pose);
            }
        }
        double rx = (thrustFaces[Direction.EAST.ordinal()] - thrustFaces[Direction.WEST.ordinal()]) / 15.0;
        double ry = (thrustFaces[Direction.UP.ordinal()] - thrustFaces[Direction.DOWN.ordinal()]) / 15.0;
        double rz = (thrustFaces[Direction.SOUTH.ordinal()] - thrustFaces[Direction.NORTH.ordinal()]) / 15.0;
        {
            Direction padBow = getBlockState().getValue(BridgeCoreBlock.FACING);
            Direction padStbd = padBow.getClockWise();
            rx = clampUnit(rx + pad.fwd() * padBow.getStepX() + pad.lat() * padStbd.getStepX());
            rz = clampUnit(rz + pad.fwd() * padBow.getStepZ() + pad.lat() * padStbd.getStepZ());
            ry = clampUnit(ry + pad.vert());
        }

        // Hover = world-frame vertical control, ONE unified cascade loop:
        //   outer: position error -> target climb rate;  inner: rate error -> force.
        //   v_target = climbRate*ry (stick) + hoverKp*(targetY - y) (position),
        //   F = m*(-g.y + climbKv*(v_target - v_y)),  g queried live per frame,
        //   all three gains from the per-core Tunings snapshot (config GUI tab 4).
        // Ground-test findings baked in:
        //  - Position integrates with a velocity the loop can't see; open-loop gravity
        //    cancel creeps ~0.13 m/s with v_y reading zero. Closed loops only.
        //  - Gravity must come from DimensionPhysicsData.getGravity() — sable's
        //    default is (0,-11,0), NOT 9.8. A hardcoded 10.0 left a 1.0 m/s^2
        //    deficit: the ship settled at v=-0.13 m/s and every 0.2s stick tap
        //    LOST 1-2m of altitude (log 2026-09-01 22:59).
        //  - Stick release captures targetY at the predicted pull-out point, but only
        //    after a 5-frame zero-input confirm window (v6.1: a one-frame input glitch
        //    mid-dive once captured a target 38 m low — see handoff §2.8).
        //  - The coupled auto-brake below must NOT touch the Y axis while hoverOn —
        //    stacked dampers braked at -8 m/s^2 on release (the "hard yank down").
        // In STARSHIP, coupled implies hover (user ruling); the stick toggle remains
        // for decoupled / test-bench use. While hoverOn, the vertical channel is owned
        // here and excluded from the raw body-frame thrust passthrough below.
        boolean hoverOn = gravityComp || (starshipFamily && coupledEff);
        // ry only carries thrust-semantics sources now (translation proxy +
        // keyboard/gamepad) — no mode gate needed here.
        boolean verticalInput = ry != 0.0;
        boolean horizontalInput = rx != 0.0 || rz != 0.0;
        if (verticalInput || horizontalInput) {
            inputZeroFrames = 0; // any stick activity resets the release-confirm window
        }
        if (hoverOn) {
            double y = pose.position().y();
            double mass = subLevel.getMassTracker().getMass();
            // Live gravity query (sable default (0,-11,0), datapack-overridable):
            // feed-forward = -g.y, NOT a hardcoded constant.
            DimensionPhysicsData.getGravity(subLevel.getLevel(), pose.position(), gravityVec);
            if (verticalInput) {
                hoverTargetY = y; // follow the stick; release edge captures below
                wasVerticalInput = true;
                wasHorizontalInput = false;
                double vTarget = tune.climbRate() * ry;
                linForce.set(0.0, mass * (-gravityVec.y + tune.climbKv() * (vTarget - linVel.y)), 0.0);
            } else if (altitudeFollow && horizontalInput) {
                // Altitude follow (v6.0): while the pilot drives horizontally with a
                // pitched hull, the nose-down component is SUPPOSED to cost altitude
                // (user ground-test: "real" flight converts pitch into climb/dive;
                // the hard hover lock made every flight perfectly level). Relax the
                // height lock while horizontal stick is held: only gravity feed-forward
                // is applied, so the pitch-converted vertical velocity survives.
                // Release capture below re-locks after the confirm window.
                hoverTargetY = y;
                wasHorizontalInput = true;
                linForce.set(0.0, mass * (-gravityVec.y), 0.0);
            } else {
                // Confirmed-release capture (v6.1; legacy-FC pattern "never capture on
                // a single release edge"): a release edge only ARMS the window — the
                // target is captured after RELEASE_CONFIRM_FRAMES consecutive zero
                // frames, using the THEN-current y/v. A one-frame zero (proxy TTL blip,
                // cross-thread timing) can no longer trigger a capture: the 2026-09-02
                // mystery descent was a mid-dive capture at y=113/v=-15 that parked the
                // ship 38 m low (target 74.7 in the log matched the formula exactly).
                if ((wasVerticalInput || wasHorizontalInput) && inputZeroFrames < RELEASE_CONFIRM_FRAMES) {
                    inputZeroFrames++;
                    if (inputZeroFrames == RELEASE_CONFIRM_FRAMES) {
                        hoverTargetY = y + linVel.y * Math.abs(linVel.y) / (2.0 * PULLOUT_ALPHA);
                        hoverSettled = false;
                        wasVerticalInput = false;
                        wasHorizontalInput = false;
                    }
                } else if (Double.isNaN(hoverTargetY)) {
                    // First arm (hover just switched on): capture immediately.
                    hoverTargetY = y + linVel.y * Math.abs(linVel.y) / (2.0 * PULLOUT_ALPHA);
                    hoverSettled = false;
                } else {
                    // External-drift re-capture (M3 revision, 2026-09-02 user ruling): once
                    // SETTLED on the target, being dragged beyond DRIFT_RECAPTURE_M for a
                    // sustained second means an external actor moved the ship (physics
                    // staff, another hull) — re-capture here instead of flying back to
                    // the stale anchor. Armed only after settling, so a deep-dive
                    // release capture (target up to ~16 m below) completes its pull-out
                    // undisturbed.
                    double dyAbs = Math.abs(y - hoverTargetY);
                    if (dyAbs < SETTLE_M) {
                        hoverSettled = true;
                    }
                    if (hoverSettled && dyAbs > DRIFT_RECAPTURE_M) {
                        driftFrames++;
                        if (driftFrames == DRIFT_CONFIRM_FRAMES) {
                            driftFrames = 0;
                            hoverSettled = false;
                            hoverTargetY = y + linVel.y * Math.abs(linVel.y) / (2.0 * PULLOUT_ALPHA);
                        }
                    } else {
                        driftFrames = 0;
                    }
                }
                double vTarget = Double.isNaN(hoverTargetY) ? 0.0 : tune.hoverKp() * (hoverTargetY - y);
                vTarget = Math.max(-tune.climbRate(), Math.min(tune.climbRate(), vTarget));
                linForce.set(0.0, mass * (-gravityVec.y + tune.climbKv() * (vTarget - linVel.y)), 0.0);
            }
            double fLen = Math.abs(linForce.y);
            double fMax = mass * HOVER_MAX_ALPHA;
            if (fLen > fMax) {
                linForce.y *= fMax / fLen;
            }
            double scale = priceWrite(fLen, timeStep, FE_PER_NEWTON_SECOND);
            if (scale > 0.0) {
                pose.orientation().transformInverse(linForce.mul(scale));  // -> local frame
                handle.applyImpulseAtPoint(subLevel.getMassTracker().getCenterOfMass(),
                        linForce.mul(timeStep));
            } else if (!Double.isNaN(hoverTargetY)) {
                // Starve-follow (M3 revision, 2026-09-02 user ruling): with no FE the
                // hover loop cannot push — drag the target down with the hull so a
                // refuel hovers IN PLACE instead of climbing back to the pre-crash
                // capture (the "returns to crash altitude" user observation).
                hoverTargetY = y;
                hoverSettled = false;
                driftFrames = 0;
            }
        } else {
            hoverTargetY = Double.NaN; // disarm; next hover-on re-captures altitude
            wasVerticalInput = false;
            wasHorizontalInput = false;
            inputZeroFrames = 0;
            hoverSettled = false;
            driftFrames = 0;
        }

        // Redstone passthrough application (direct write; §8 step 2 torque /
        // step 4 thrust). Two independent channels: translation proxy + own faces in
        // THRUST mode drive linear thrust; attitude proxy + own faces in TORQUE mode
        // drive torque. Both apply in every active mode (OFF returned above), on top
        // of the control law.
        // Raw thrust passthrough (handoff §8 step 4): face = push direction,
        // strength = throttle. Force = mass * THRUST_ACCEL * gearMult * throttle
        // (N, body frame), applied at the CoM so it produces no parasitic torque.
        // Priced per newton-second. While hover owns the vertical channel (hoverOn),
        // ry is zeroed — vertical command is executed world-frame by the climb loop.
        // v6.0: in STARSHIP+coupled the unified target-velocity loop below owns ALL
        // translation (demanded axes included), so the raw passthrough is skipped
        // there; the M2 soft speed cap (speedCapped/hysteresis) is deleted with it —
        // the velocity loop's top-speed target is the limiter now.
        boolean velocityLoopOn = starshipFamily && coupledEff;
        if (!velocityLoopOn && (rx != 0.0 || ry != 0.0 || rz != 0.0)) {
            double mass = subLevel.getMassTracker().getMass();
            linearImpulse.set(rx, hoverOn ? 0.0 : ry, rz);
            linearImpulse.mul(mass * THRUST_ACCEL * gearMultSmoothed);  // N
            double scale = priceWrite(linearImpulse.length(), timeStep, FE_PER_NEWTON_SECOND);
            if (scale > 0.0) {
                handle.applyImpulseAtPoint(subLevel.getMassTracker().getCenterOfMass(),
                        linearImpulse.mul(scale * timeStep));
            }
        }
        // Attitude channel. In STARSHIP the faces are a RATE COMMAND (M1: stick ->
        // target angular velocity, closed loop drives omega to it; torque = I*alpha
        // makes the response hull-independent). In the DAMPING bench they keep
        // the raw torque passthrough (heritage of §8 step 2; also the future capital-ship
        // attitude-actuator channel on the component bus).
        boolean rateCommand = false;
        {
            // Facing-relative attitude mapping (v3): the block has a bow
            // (FACING = placer's look direction, see BridgeCoreBlock). Bow/stern
            // faces PITCH (tip toward the powered face, axis = up x faceDir),
            // port/starboard faces YAW (+Y = turn to port), up/down faces ROLL
            // (up = roll to port). v1 (tip-toward-everything) and v2 (hardcoded
            // face pairs) both broke because the cube had no front: with the bow
            // along X, v2's "yaw pair" landed on the pilot's W/S keys.
            Direction bow = getBlockState().getValue(BridgeCoreBlock.FACING);
            double fwd = torqueFaces[bow.ordinal()] / 15.0;
            double aft = torqueFaces[bow.getOpposite().ordinal()] / 15.0;
            double port = torqueFaces[bow.getCounterClockWise().ordinal()] / 15.0;
            double stbd = torqueFaces[bow.getClockWise().ordinal()] / 15.0;
            double up = torqueFaces[Direction.UP.ordinal()] / 15.0;
            double down = torqueFaces[Direction.DOWN.ordinal()] / 15.0;
            double bowX = bow.getStepX();
            double bowZ = bow.getStepZ();
            double pitchD = clampUnit(fwd - aft + pad.pitch());
            double yawD = clampUnit(port - stbd + pad.yaw());
            double rollD = clampUnit(down - up + pad.roll());
            // HUD input-line snapshot: the merged demand, readable by the server thread.
            stickPitch = pitchD;
            stickRoll = rollD;
            if (starshipFamily) {
                // Rate command: same axis composition as the torque mapping below,
                // but each demand channel is scaled by its own full-stick rate limit.
                omegaTargetBody.set(
                        bowZ * pitchD * OMEGA_MAX_PITCH + bowX * rollD * OMEGA_MAX_ROLL,
                        yawD * OMEGA_MAX_YAW,
                        -bowX * pitchD * OMEGA_MAX_PITCH + bowZ * rollD * OMEGA_MAX_ROLL);
                rateCommand = omegaTargetBody.lengthSquared() > 0.0;
            } else {
                angularImpulse.set(
                        bowZ * pitchD + bowX * rollD,   // pitch X + roll X
                        yawD,                           // yaw: +Y = to port
                        -bowX * pitchD + bowZ * rollD   // pitch Z + roll Z
                );
                if (angularImpulse.lengthSquared() > 0.0) {
                    angularImpulse.mul(REDSTONE_ALPHA);          // rad/s^2 demand, body frame
                    inertia.transform(angularImpulse);           // -> torque (N*m)
                    double scale = priceWrite(angularImpulse.length(), timeStep,
                            FE_PER_NM_SECOND * tune.rotPriceMult());
                    if (scale > 0.0) {
                        handle.applyAngularImpulse(angularImpulse.mul(scale * timeStep));
                    }
                }
            }
        }

        // ---- starship translation layer (handoff §8 step 5) ----
        // World-frame force accumulator: the unified target-velocity loop (v6.0).
        // Priced per newton-second like all writes. (OFF returned above.)
        if (starshipFamily) {
            double mass = subLevel.getMassTracker().getMass();
            linForce.set(0.0, 0.0, 0.0);
            if (coupledEff) {
                // Unified target-velocity loop (v6.0), per body axis:
                //   F = m * transKv * (v_target - v),  v_target = stick * topSpeed.
                // One loop subsumes three v5.x mechanisms:
                //  - per-axis release brake = the rx=0/rz=0 case (target 0);
                //  - sideslip suppression   = the lateral axis targets 0 while the
                //    bow axis is driven, so slip is pulled to zero DURING turns;
                //  - soft speed cap         = v_target never exceeds topSpeed, and
                //    overspeed beyond target is gently pulled back by the same loop
                //    (a limiter with restoring force, not the old zero-thrust cap).
                // Y excluded while hoverOn — the hover cascade owns the vertical
                // channel (stacked dampers braked at -8 m/s^2 on release, the
                // "hard yank down" of the 2026-09-01 ground test). The loop's
                // hover branch then damps WORLD-horizontal velocity instead of
                // the body X/Z plane — see the else branch below (v15 drift fix).
                // Y ownership (v6.2, 2026-09-02 pitch-authority log): normally the hover
                // cascade owns the vertical and the loop leaves body-Y alone (stacked
                // dampers braked at -8 m/s^2 on release, the 2026-09-01 "hard yank").
                // But in altitude-follow stick flight the hover degrades to gravity
                // feed-forward ONLY — if the loop still skips body-Y, the hull-up
                // velocity error (the "angle of attack" between track and nose) has NO
                // owner and the velocity vector creeps toward the nose over ~7 s via
                // the X axis alone (log 19:11:34: pitch held -26.5 deg, v.y only
                // reached -5.4 of the expected -10.7 m/s). So while altitude-follow
                // stick flight is active the loop takes body-Y back (target 0 = track
                // follows the nose plane). Vertical stick (verticalInput) still hands
                // the channel to the hover climb loop.
                boolean loopOwnsBodyY = !hoverOn || (altitudeFollow && horizontalInput && !verticalInput);
                double topSpeed = TOP_SPEED_BASE * gearMultSmoothed;
                vTargetBody.set(rx * topSpeed, ry * topSpeed, rz * topSpeed);
                if (loopOwnsBodyY) {
                    pose.orientation().transformInverse(linVel, vBody); // world -> body
                    latBody.set(tune.transKv() * (vTargetBody.x - vBody.x),
                            tune.transKv() * (vTargetBody.y - vBody.y),
                            tune.transKv() * (vTargetBody.z - vBody.z));
                    pose.orientation().transform(latBody);          // body -> world
                    linForce.add(latBody.mul(mass));
                } else {
                    // Hover owns the vertical: damp WORLD-horizontal velocity
                    // toward the horizontal part of the stick target, not the
                    // body X/Z plane (2026-09-24 v15 drift fix). At steep pitch
                    // the body x/z plane is nearly vertical: the loop fought
                    // the hover's channel through the bow axis while the
                    // hull-up direction — horizontal there — had NO owner, so
                    // the reaction to the vertical sink (the documented ~0.13
                    // m/s readback artifact) leaked a constant world-horizontal
                    // force that integrated unchecked (24 s linear backward
                    // drift at pitch -86.3 deg, att=DAMPING, vTB=(0,0,0)).
                    // Level flight is unchanged (upright: body x/z plane IS the
                    // world-horizontal plane); v_y stays untouched, so the
                    // release "hard yank" protection above still holds.
                    latBody.set(rx * topSpeed, 0.0, rz * topSpeed);
                    pose.orientation().transform(latBody); // stick target -> world
                    latBody.y = 0.0;
                    linForce.add(mass * tune.transKv() * (latBody.x - linVel.x),
                            0.0, mass * tune.transKv() * (latBody.z - linVel.z));
                }
            } else {
                vTargetBody.set(Double.NaN, Double.NaN, Double.NaN);
            }
            double fLen = linForce.length();
            if (fLen > 0.0) {
                // Force ceiling scales with the gear's own thrust authority so the
                // loop can actually reach top speed at high gears (G4 = 24 m/s^2 —
                // brakes bite hard there; player-facing tunables land in the UI later).
                double fMax = mass * Math.max(MAX_LIN_ALPHA, THRUST_ACCEL * gearMultSmoothed);
                if (fLen > fMax) {
                    linForce.mul(fMax / fLen);
                }
                double scale = priceWrite(linForce.length(), timeStep, FE_PER_NEWTON_SECOND);
                if (scale > 0.0) {
                    pose.orientation().transformInverse(linForce.mul(scale)); // world -> local
                    handle.applyImpulseAtPoint(subLevel.getMassTracker().getCenterOfMass(),
                            linForce.mul(timeStep));
                }
            }
        }

        // Attitude authority. STARSHIP runs a RATE loop while the attitude stick is
        // held. Release-edge capture splits on the selfBalance switch (M3 revision,
        // 2026-09-02 user ruling): plain STARSHIP holds the FULL attitude (on-demand
        // leveling is the C key); selfBalance auto-levels (yaw-hold, pitch/roll -> 0,
        // near-vertical bow falls back to full capture). No input yet = pure damping
        // (pre-M1 baseline behavior). DAMPING, the only other active mode, is pure
        // KD damping.
        AttitudeController.Mode attMode;
        Quaterniond attTarget;
        if (starshipFamily) {
            if (rateCommand) {
                attMode = AttitudeController.Mode.RATE;
                hadRateInput = true;
                attTarget = null;
            } else {
                if (hadRateInput) {
                    hadRateInput = false;
                    if (selfBalance) {
                        // Self-balance (M3 revision, 2026-09-02 user ruling): release-stick
                        // auto-levels — target = current heading with pitch/roll zeroed.
                        // A near-vertical bow has no defined heading; fall back to a
                        // full-attitude capture there.
                        Direction bowDir = getBlockState().getValue(BridgeCoreBlock.FACING);
                        Vector3d bowLocal = new Vector3d(bowDir.getStepX(), bowDir.getStepY(), bowDir.getStepZ());
                        Quaterniond leveled = new Quaterniond();
                        if (AttitudeController.levelTarget(current, bowLocal, leveled)) {
                            starshipAttitudeTarget.set(leveled);
                        } else {
                            starshipAttitudeTarget.set(new Quaterniond(current));
                        }
                    } else {
                        // Plain STARSHIP: release-stick holds the FULL attitude; on-demand
                        // leveling is the C key (auto-level), not the release edge.
                        starshipAttitudeTarget.set(new Quaterniond(current));
                    }
                }
                attTarget = starshipAttitudeTarget.get();
                attMode = attTarget != null ? AttitudeController.Mode.HOLD : AttitudeController.Mode.DAMPING;
            }
        } else {
            attMode = AttitudeController.Mode.DAMPING;
            attTarget = null;
        }
        AttitudeController.computeTorque(attMode, current, attTarget,
                rateCommand ? omegaTargetBody : null, omegaWorld, inertia, torque,
                tune.attitudeKp(), tune.attitudeKd(), tune.attMaxAlpha());
        if (torque.lengthSquared() > 0.0) {
            double scale = priceWrite(torque.length(), timeStep,
                    FE_PER_NM_SECOND * tune.rotPriceMult());
            if (scale > 0.0) {
                handle.applyAngularImpulse(angularImpulse.set(torque).mul(scale * timeStep));
            }
        }

    }

    // ---- world-side watchdog (server thread, via block ticker) ----

    public static void serverTick(Level level, BlockPos pos, BlockState state, BridgeCoreBlockEntity be) {
        // Energy account (v0.13): physics spends straight out of the structure's
        // distributor pools over CoreDirectory membership, not world adjacency —
        // ship blocks sit at far-out shipyard coordinates. No core-local buffer,
        // no trickle settlement: the distributors are the shipboard capacitor.
        // Rebind the pool snapshot every tick (cheap; the directory lists are tiny),
        // then settle this tick's actual spend into the low-passed draw readout
        // (HUD energy line, per-tick resolution).
        ServerSubLevel sub = Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel s
                ? s : null;
        be.energyAccount.rebind(sub);
        double spent = be.energyAccount.drainSpent();
        be.drawFePerTick += (spent - be.drawFePerTick) * DRAW_SMOOTH_ALPHA;
        // END estimate (CC channel; the HUD screen runs the same estimator): the
        // stock is pools + extractor buffers + the burnable remainder.
        double stock = be.energyAccount.totalStored();
        if (sub != null) {
            for (EnergyExtractorBlockEntity ex : CoreDirectory.extractorsOf(sub)) {
                stock += ex.buffer() + ex.potentialFe();
            }
        }
        be.enduranceSeconds = be.endurance.update(stock, be.drawFePerTick, level.getGameTime());
        // Legacy migration: pre-v0.13 saves stored a core-local buffer under "fe";
        // pour it back into the pools once, void what does not fit.
        if (be.pendingLegacyFe >= 0) {
            if (be.energyAccount.hasPools()) {
                long restored = be.energyAccount.fillExternal(be.pendingLegacyFe);
                long voided = be.pendingLegacyFe - restored;
                BridgeCoreMod.LOGGER.warn("[bridgecore] legacy core buffer at {}: {} FE restored to pools"
                        + (voided > 0 ? ", " + voided + " FE voided (pools full)" : ""), pos, restored);
                be.pendingLegacyFe = -1;
            } else if (++be.legacyFlushWait >= 20) {
                if (be.pendingLegacyFe > 0) {
                    BridgeCoreMod.LOGGER.warn("[bridgecore] legacy core buffer at {}: {} FE voided "
                            + "(no momentum distributor on the structure)", pos, be.pendingLegacyFe);
                }
                be.pendingLegacyFe = -1;
            }
        }
        // Keyboard pilot channel (M3k): poll the Linked Typewriter on the same tick.
        // Pairing metric = the gamepad ruling (ShipPairing): on a structure the
        // typewriter pairs against the structure's SHIPYARD-space bounding box
        // (+PAIR_MARGIN) — the BE sits at shipyard coordinates, so a big hull
        // would push a bridge typewriter past any point radius (same 2026-09-09
        // dist=50.1 failure as the gamepad). Bench core: 32-block point radius.
        if (be.keyboardChannel) {
            TypewriterInput.KeyFaces polled = be.typewriterPoller.poll(level, pos,
                    sub != null ? ShipPairing.shipyardBounds(sub) : null,
                    state.getValue(BridgeCoreBlock.FACING), be.keyMap);
            be.keyFaces.set(polled);
            // §2.29 (2026-10-30 裁决稿②): the category notch is now a
            // STRUCTURE-LEVEL value owned by this core (修订 C) — the screens
            // are pure mirrors. Tab edge cycles the core notch and pushes it
            // to every HUD of the structure.
            if (polled.lockCategoryNow() && sub != null) {
                be.cycleHudLockCategory(sub);
            }
            // suppressHudTargeting (修订): the OFF-forcing moved from the
            // screen tick to here — while any provider suppresses targeting,
            // clamp the structure notch to OFF and keep pushing.
            if (be.hudLockCategory != HudScreenBlockEntity.LOCK_NOTCH_OFF
                    && HudTargetProviders.suppressHudTargeting()) {
                be.hudLockCategory = HudScreenBlockEntity.LOCK_NOTCH_OFF;
                if (sub != null) {
                    be.pushHudLockCategory(sub);
                }
                be.setChanged();
            }
            // V/G/H command channels: V = species cycle (fallback) / precise
            // target cycle (§2.29, ②G), G = short/long-press lock arbitration,
            // H = click/hold/double-click fire. All run through one tick-based
            // state machine that captures the fire-control channel at edge
            // time so a mid-hold X switch cannot misroute the release.
            be.tickCommandInputs(level, sub, polled);
            // Channel cycle (X, 2026-10-26 exchange log): pure forwarding —
            // every fire-control core on the structure (coresOf covers ground
            // cores too, null sub) cycles its weapon channel. No bridgecore
            // copy of the channel state; the fire-control core is the
            // authority and the HUD reads its snapshot.
            if (polled.channelCycleNow()) {
                for (FireControlCoreBlockEntity fc
                        : FireControlCoreBlockEntity.coresOf(level, sub)) {
                    fc.cycleChannel();
                }
            }
        } else if (be.keyFaces.get() != TypewriterInput.KeyFaces.IDLE) {
            be.keyFaces.set(TypewriterInput.KeyFaces.IDLE);
        }
        // Gamepad pilot channel (M3a): always-on, player-centric pairing. Same
        // ShipPairing metric, but the player walks in WORLD space, so the bounds
        // are the structure's real-world bounding box (2026-09-09 modpack:
        // dist=50.1 > 32 with the old point radius, never linked).
        Vector3d logicalPos = be.lastPosition.get();
        be.padState.set(be.gamepadPoller.poll(level,
                logicalPos != null ? new Vec3(logicalPos.x, logicalPos.y, logicalPos.z)
                        : Vec3.atCenterOf(pos),
                sub != null ? ShipPairing.worldBounds(sub) : null,
                be.gamepadMap));

        // Pointing link watchdog (interface v1 §2.3): 500 ms renewal timeout.
        be.tickPointingLink();

        // HUD calibration re-assert (server thread): displays keep their own persisted
        // calib and self-adopt on (re)join, but a display that was already on the
        // structure while the CORE reloaded would keep stale values — re-push once
        // whenever this core's structure membership (re)appears.
        if (Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel subNow) {
            if (subNow != be.calibPushedSub) {
                be.calibPushedSub = subNow;
                for (HudScreenBlockEntity hud : CoreDirectory.hudScreensOf(subNow)) {
                    hud.setCalib(be.hudOffsetX, be.hudOffsetY, be.hudScale, be.hudEyeX,
                            be.hudEyeY, be.hudPitchStep, be.hudAttSize, be.hudAttBorder,
                            be.hudInputLine);
                    // §2.29: the lock notch is structure-level — re-mirror it
                    // alongside the calib so a reloaded core re-asserts both.
                    hud.setHudLockCategory(be.hudLockCategory);
                }
            }
        } else {
            be.calibPushedSub = null;
        }

        if (be.lastPhysicsGameTime == Long.MIN_VALUE) {
            if (!be.wasPhysicalized) {
                return; // never physicalized — nothing to watch
            }
            // BE rebuilt after de-physicalization: runtime state was wiped, but NBT says
            // this block was on a structure before. Re-arm at "now" so the watchdog
            // fires if no new physics frames arrive within the threshold.
            be.lastPhysicsGameTime = level.getGameTime();
        }
        long last = be.lastPhysicsGameTime;
        long gap = level.getGameTime() - last;
        if (gap > WATCHDOG_TICK_THRESHOLD && !be.watchdogTripped) {
            be.watchdogTripped = true;
            if (be.mode != ControlMode.OFF) {
                be.mode = ControlMode.OFF;
                BridgeCoreMod.LOGGER.warn(
                        "[bridgecore] watchdog: no physics frame for {} ticks at {} — structure "
                                + "de-physicalized, mode forced to OFF (safe state)",
                        gap, pos);
            } else {
                BridgeCoreMod.LOGGER.warn(
                        "[bridgecore] watchdog: no physics frame for {} ticks at {} (structure de-physicalized)",
                        gap, pos);
            }
        }
    }

    // ---- proxy redstone input (server thread writes, physics thread consumes) ----

    private static final class ProxyFeed {
        final int[] powers;   // per-face 0..15, Direction.ordinal() indexed
        final long nanos;     // System.nanoTime stamp (level gameTimes differ across
                              // overworld/sub-level clocks — wall clock is unambiguous)
        ProxyFeed(int[] powers, long nanos) {
            this.powers = powers;
            this.nanos = nanos;
        }
    }

    /** Called by a redstone proxy's server ticker (every tick, zeros included). */
    public void submitProxyInput(RedstoneProxyBlock.Kind kind, BlockPos proxyPos, int[] powers) {
        var map = kind == RedstoneProxyBlock.Kind.ATTITUDE ? attitudeProxyFeeds : translationProxyFeeds;
        map.put(proxyPos, new ProxyFeed(powers.clone(), System.nanoTime()));
    }

    /** Sum live proxy feeds per face (clamped 0..15); expired feeds are dropped.
     *  Physics thread only. */
    private int[] collectProxyDemand(java.util.concurrent.ConcurrentHashMap<BlockPos, ProxyFeed> feeds) {
        int[] out = new int[6];
        if (feeds.isEmpty()) {
            return out;
        }
        long now = System.nanoTime();
        for (var it = feeds.entrySet().iterator(); it.hasNext();) {
            ProxyFeed f = it.next().getValue();
            if (now - f.nanos > PROXY_FEED_TTL_NS) {
                it.remove();
                continue;
            }
            for (int i = 0; i < 6; i++) {
                out[i] = Math.min(15, out[i] + f.powers[i]);
            }
        }
        return out;
    }

    @Override
    public void setRemoved() {
        CoreDirectory.deregister(registeredSubLevel, this);
        registeredSubLevel = null;
        super.setRemoved();
    }

    // ---- NBT persistence ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("wasPhysicalized", wasPhysicalized || lastPhysicsGameTime != Long.MIN_VALUE);
        tag.putInt("gear", gear);
        // Interaction state (2026-09-02 "kbd=OFF" lesson): switches that silently reset
        // across a restart look like broken controls — persist all of them.
        tag.putString("mode", mode.name());
        tag.putBoolean("coupled", coupled);
        tag.putBoolean("gravityComp", gravityComp);
        tag.putBoolean("altitudeFollow", altitudeFollow);
        tag.putBoolean("kbd", keyboardChannel);
        tag.putBoolean("selfBalance", selfBalance);
        // Config GUI state (v1.0): gear multipliers x1000 as ints, raw GLFW key codes,
        // HUD calibration as doubles. Missing keys on old saves -> field defaults.
        double[] gm = gearMults;
        int[] gmInt = new int[GEAR_COUNT];
        for (int i = 0; i < GEAR_COUNT; i++) {
            gmInt[i] = (int) Math.round(gm[i] * 1000.0);
        }
        tag.putIntArray("gearMults", gmInt);
        tag.putIntArray("keyMap", keyMap.clone());
        tag.putIntArray("gamepadMap", gamepadMap.clone());
        tag.putDouble("hudX", hudOffsetX);
        tag.putDouble("hudY", hudOffsetY);
        tag.putDouble("hudScale", hudScale);
        tag.putDouble("hudEyeX", hudEyeX);
        tag.putDouble("hudEyeY", hudEyeY);
        // 2026-10-26 v2 calibration model marker (orthogonalized semantics; the
        // hud* keys themselves are unchanged). Guards the one-time migration in
        // loadAdditional.
        tag.putBoolean("hudCalibV2", true);
        tag.putDouble("hudPitchStep", hudPitchStep);
        tag.putDouble("hudAttSize", hudAttSize);
        tag.putDouble("hudAttBorder", hudAttBorder);
        tag.putBoolean("hudInputLine", hudInputLine);
        tag.putBoolean("hudOutline", hudOutline);
        tag.putInt("hudLockRange", hudLockRange);
        tag.putInt("hudLockConeDeg", hudLockConeDeg);
        tag.putInt("hudLockMaxCount", hudLockMaxCount);
        if (hudLockSpecies != null) {
            tag.putString("hudLockSpecies", hudLockSpecies);
        }
        // §2.29: 结构级 Tab 档位（-1=OFF..3），屏不再自持。
        tag.putInt("hudLockCategory", hudLockCategory);
        // NBT has no double-array tag type -> ListTag of DoubleTag under one key.
        ListTag tuneTag = new ListTag();
        for (double v : tunings.toArray()) {
            tuneTag.add(DoubleTag.valueOf(v));
        }
        tag.put("tunings", tuneTag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // Independent of calibration schema. Missing key preserves the
        // current value (new instances default ON; partial loads do not reset it).
        if (tag.contains("hudOutline")) hudOutline = tag.getBoolean("hudOutline");
        wasPhysicalized = tag.getBoolean("wasPhysicalized");
        // Pre-v0.13 saves stored a core-local FE buffer; v0.13 spends straight from
        // the pools, so the leftover is poured back into them on a server tick.
        if (tag.contains("fe")) {
            pendingLegacyFe = tag.getInt("fe");
        }
        // Config state first: the gear snap below reads gearMults.
        if (tag.contains("gearMults")) {
            int[] raw = tag.getIntArray("gearMults");
            if (raw.length == GEAR_COUNT) {
                double[] gm = new double[GEAR_COUNT];
                for (int i = 0; i < GEAR_COUNT; i++) {
                    gm[i] = Math.max(GEAR_MULT_MIN, Math.min(GEAR_MULT_MAX, raw[i] / 1000.0));
                }
                gearMults = gm;
            }
        }
        if (tag.contains("keyMap")) {
            int[] raw = tag.getIntArray("keyMap");
            if (raw.length == TypewriterInput.CHANNEL_COUNT) {
                keyMap = raw;
            } else if (raw.length < TypewriterInput.CHANNEL_COUNT && raw.length >= 15) {
                // Shorter saves predate newer channels (lock_category at 15,
                // lock_species at 16, lock_release at 17, channel_cycle at 18,
                // fire at 19) — adopt the stored binds and take the new
                // channels' factory defaults (Tab, V, G, X, H).
                int[] grown = TypewriterInput.defaultKeyMap();
                System.arraycopy(raw, 0, grown, 0, raw.length);
                keyMap = grown;
            }
        }
        if (tag.contains("gamepadMap")) {
            int[] raw = tag.getIntArray("gamepadMap");
            if (raw.length == GamepadInput.MAP_LENGTH) {
                gamepadMap = raw;
            }
        }
        if (tag.contains("hudScale")) {
            double hx = tag.getDouble("hudX");
            double hy = tag.getDouble("hudY");
            double hs = tag.getDouble("hudScale");
            // Pre-2.14 saves may predate the eye keys entirely: the migration
            // below treats them as the old defaults (2.0 / 0.12).
            double hex = tag.contains("hudEyeX") ? tag.getDouble("hudEyeX") : 2.0;
            double hey = tag.contains("hudEyeY") ? tag.getDouble("hudEyeY") : 0.12;
            if (!tag.getBoolean("hudCalibV2")) {
                // 2026-10-26 v1 -> v2 calibration migration. DIRECT field write,
                // deliberately bypassing the setHudCalib clamps so extreme
                // migrated values survive intact (e.g. eyeY 3.25 from a maxed
                // legacy panel — past the ±3 GUI range). Formulas:
                //   newScale = oldScale x oldEyeX/2        (panel size preserved —
                //     the legacy eyeX/2 size factor folds into the scale)
                //   newY     = oldCenterY - PANEL_ANCHOR_Y (panel position kept)
                //   newEyeX  = oldEyeX                     (eye distance unchanged)
                //   newEyeY  = (1.1 + max(oldHalf,1.25)) - EYE_BASE_Y
                //     (effective eye height kept; default notch lands on 0)
                // with oldCenterY = 1.1 + (oldEyeY-0.12) + max(oldHalf,1.25) + oldY
                // and  oldHalf = 1.25 x screenScale x oldScale x (oldEyeX/2).
                // screenScale is the HUD BLOCK's right-click notch and is NOT in
                // this NBT — assume the factory default 0.6: exact for
                // single-screen and uniform-notch ships; on mixed-notch ships the
                // non-baseline screens carry a bounded height error (not
                // guaranteed). The HUD block's own cache migrates with its REAL
                // notch (HudScreenBlockEntity.loadAdditional), but an online
                // core's push overrides it.
                double oldHalf = 1.25 * 0.6 * hs * (hex / 2.0);
                double oldAnchorHalf = Math.max(oldHalf, 1.25);
                double oldCenterY = 1.1 + (hey - 0.12) + oldAnchorHalf + hy;
                hudOffsetX = hx;
                hudOffsetY = oldCenterY - com.bridgecore.display.HudLockBoxLayer.PANEL_ANCHOR_Y;
                hudScale = hs * hex / 2.0;
                hudEyeX = hex;
                hudEyeY = (1.1 + oldAnchorHalf) - com.bridgecore.display.HudLockBoxLayer.EYE_BASE_Y;
                // The remaining params take the same clamps as setHudCalib.
                hudPitchStep = Math.max(HUD_PITCH_STEP_MIN, Math.min(HUD_PITCH_STEP_MAX,
                        tag.contains("hudPitchStep") ? tag.getDouble("hudPitchStep") : 5.0));
                hudAttSize = Math.max(HUD_ATT_SIZE_MIN, Math.min(HUD_ATT_SIZE_MAX,
                        tag.contains("hudAttSize") ? tag.getDouble("hudAttSize") : 1.0));
                hudAttBorder = Math.max(0.0, Math.min(HUD_ATT_BORDER_MAX,
                        tag.contains("hudAttBorder") ? tag.getDouble("hudAttBorder") : 0.0));
                hudInputLine = !tag.contains("hudInputLine") || tag.getBoolean("hudInputLine");
                hudLockRange = Math.max(HUD_LOCK_RANGE_MIN, Math.min(HUD_LOCK_RANGE_MAX,
                        tag.contains("hudLockRange") ? tag.getInt("hudLockRange")
                                : HUD_LOCK_RANGE_DEFAULT));
                hudLockConeDeg = Math.max(HUD_LOCK_CONE_MIN, Math.min(HUD_LOCK_CONE_MAX,
                        tag.contains("hudLockConeDeg") ? tag.getInt("hudLockConeDeg")
                                : HUD_LOCK_CONE_DEFAULT));
                hudLockMaxCount = Math.max(HUD_LOCK_MAX_COUNT_MIN, Math.min(HUD_LOCK_MAX_COUNT_MAX,
                        tag.contains("hudLockMaxCount") ? tag.getInt("hudLockMaxCount")
                                : HUD_LOCK_MAX_COUNT_DEFAULT));
            } else {
                setHudCalib(hx, hy, hs, hex, hey,
                        tag.contains("hudPitchStep") ? tag.getDouble("hudPitchStep") : 5.0,
                        tag.contains("hudAttSize") ? tag.getDouble("hudAttSize") : 1.0,
                        tag.contains("hudAttBorder") ? tag.getDouble("hudAttBorder") : 0.0,
                        !tag.contains("hudInputLine") || tag.getBoolean("hudInputLine"),
                        tag.contains("hudLockRange") ? tag.getInt("hudLockRange")
                                : HUD_LOCK_RANGE_DEFAULT,
                        tag.contains("hudLockConeDeg") ? tag.getInt("hudLockConeDeg")
                                : HUD_LOCK_CONE_DEFAULT,
                        tag.contains("hudLockMaxCount") ? tag.getInt("hudLockMaxCount")
                                : HUD_LOCK_MAX_COUNT_DEFAULT);
            }
        }
        // §2.29 (2026-10-30): 档位重新上移为核心持有的结构级值（v3.1 曾下放
        // 到各屏，②起屏为纯镜像）。旧存档无此键 → 保持默认 OFF。
        if (tag.contains("hudLockCategory")) {
            hudLockCategory = Math.max(HudScreenBlockEntity.LOCK_NOTCH_OFF,
                    Math.min(3, tag.getInt("hudLockCategory")));
        }
        hudLockSpecies = tag.contains("hudLockSpecies")
                ? tag.getString("hudLockSpecies") : null;
        if (tag.contains("tunings")) {
            ListTag list = tag.getList("tunings", Tag.TAG_DOUBLE);
            // Pre-v0.14 saves carry SIX entries (no attMaxAlpha/rotPriceMult):
            // overlay what the tag has onto the defaults so old saves keep their
            // tuned values AND gain the new defaults, instead of falling back
            // wholesale or crashing on the length check.
            if (list.size() >= 6) {
                double[] raw = Tunings.DEFAULT.toArray();
                for (int i = 0; i < Math.min(list.size(), Tunings.COUNT); i++) {
                    raw[i] = list.getDouble(i);
                }
                tunings = Tunings.fromArray(raw); // clamps per field
            }
        }
        if (tag.contains("gear")) {
            gear = Math.min(tag.getInt("gear"), gearMults.length - 1);
            gearMultSmoothed = gearMults[gear]; // snap on load; no slew across a world reload
        }
        // Merge-style load (handoff §5.6 pattern): missing keys keep the field defaults.
        // Mode migration (2026-09-05 slimming): the deleted HOLD bench lands on
        // DAMPING; the demoted SELF_BALANCE mode lands on STARSHIP with the
        // selfBalance switch ON. A missing "selfBalance" key keeps the field
        // default (false) — so the explicit key, when present, is read AFTER the
        // mode migration and wins.
        if (tag.contains("mode")) {
            String saved = tag.getString("mode");
            switch (saved) {
                case "HOLD" -> mode = ControlMode.DAMPING;
                case "SELF_BALANCE" -> {
                    mode = ControlMode.STARSHIP;
                    selfBalance = true;
                }
                default -> {
                    try {
                        mode = ControlMode.valueOf(saved);
                    } catch (IllegalArgumentException e) {
                        mode = ControlMode.OFF;
                    }
                }
            }
        }
        if (tag.contains("selfBalance")) {
            selfBalance = tag.getBoolean("selfBalance");
        }
        if (tag.contains("coupled")) {
            coupled = tag.getBoolean("coupled");
        }
        if (tag.contains("gravityComp")) {
            gravityComp = tag.getBoolean("gravityComp");
        }
        if (tag.contains("altitudeFollow")) {
            altitudeFollow = tag.getBoolean("altitudeFollow");
        }
        if (tag.contains("kbd")) {
            keyboardChannel = tag.getBoolean("kbd");
        }
    }

    /**
     * Price one physics-frame write: cost = magnitude * dt * rate, consumed from the FE
     * buffer; returns the affordability scale in [0,1]. A zero scale logs a throttled
     * starvation warning — never starve silently (ground-test lesson: a re-placed core
     * has an empty buffer and dead-silent control looks like a broken mod).
     * Application order defines starvation priority: hover first, then redstone input,
     * starship translation, attitude law last. Open item: production may want the
     * control law to outrank raw pilot input.
     */
    /** Clamp into [-1, 1] — combined multi-channel stick demands. */
    private static double clampUnit(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }

    /**
     * Auto-level (M3 revision, 2026-09-02 user ruling): one-shot pitch/roll -> 0,
     * keep heading. A "panic button" for disorientation — unlike the self-balance
     * switch's release-stick leveling this is an on-demand, single-shot action in
     * starship mode. Triggered by the C key (keyboard channel) or the A
     * button (gamepad channel). Physics thread only.
     */
    private void engageAutoLevel(Pose3d pose) {
        Direction bow = getBlockState().getValue(BridgeCoreBlock.FACING);
        Vector3d bowLocal = new Vector3d(bow.getStepX(), bow.getStepY(), bow.getStepZ());
        Quaterniond leveled = new Quaterniond();
        if (AttitudeController.levelTarget(pose.orientation(), bowLocal, leveled)) {
            starshipAttitudeTarget.set(leveled);
            hadRateInput = false;
        }
    }

    private double priceWrite(double magnitude, double timeStep, double rate) {
        if (magnitude <= 0.0) {
            return 0.0;
        }
        double cost = magnitude * timeStep * rate;
        double afforded = energyAccount.trySpend(cost);
        if (afforded >= cost) {
            return 1.0;
        }
        if (afforded <= 0.0) {
            if (physicsFrames - lastStarveLogFrame >= 100) {
                lastStarveLogFrame = physicsFrames;
                BridgeCoreMod.LOGGER.warn("[bridgecore] control starved: structure pools empty at {}", worldPosition);
            }
            return 0.0;
        }
        return afforded / cost;
    }
}
