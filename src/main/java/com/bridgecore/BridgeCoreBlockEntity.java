package com.bridgecore;

import com.bridgecore.control.AttitudeController;
import com.bridgecore.control.PointingExecution;
import com.bridgecore.firecontrol.ballistics.Quat;
import com.bridgecore.control.AttitudeTrim;
import com.bridgecore.control.CargoFlight;
import com.bridgecore.control.HoverController;
import com.bridgecore.control.PhysicsWatchdog;
import com.bridgecore.control.NavigationController;
import com.bridgecore.control.NudgeDirection;
import com.bridgecore.control.RemoteManeuver;
import com.bridgecore.control.CruiseRouteWatch;
import com.bridgecore.control.CruiseHeading;
import com.bridgecore.nav.NavTerminalData;
import com.bridgecore.nav.NavWakeProbe;
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
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final double DEFAULT_HOVER_KP = 1.0;
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
    /** M2 thrust gears: factory-default force multiplier per gear. Default gear index 2
     *  reproduces the pre-M2 THRUST_ACCEL behavior. */
    public static final double DEFAULT_G4_MULTIPLIER = 2.0;
    private static final double[] DEFAULT_GEAR_MULT = {0.25, 0.5, 1.0, DEFAULT_G4_MULTIPLIER};
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
     *   3 hoverKp     悬停刚度   (0.5..16,  default 1.0)
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
    // ---- interface v1 telemetry (fire-control request #2, 2026-09-11) ----
    /** Latest immutable telemetry snapshot, produced on the physics frame and
     *  volatile-published (runs even in OFF — read-only telemetry is exempt
     *  from the OFF skip). Null until the first physics frame with a valid
     *  handle; consumers poll at 20 Hz and treat null/stale as "static
     *  platform" (their degrade rule, not ours). */
    private volatile TelemetrySnapshot telemetry;
    private volatile long lastManualFlightInputTick = Long.MIN_VALUE;
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
    /** TRACK_ENTITY: server-tick-resolved world center of the target entity
     *  (volatile; the physics thread never touches Level). Null = unresolved. */
    private volatile Vec3 pointingTrackPos;
    /** SETTLED sustain counter (physics frames; PointingExecution thresholds). */
    private int pointingSettleFrames;
    private @Nullable Quat pointingSettleTarget;
    /** 指向上一物理帧是否活跃：用于释放当帧的姿态目标重捕获。 */
    private boolean pointingWasActive;
    private final AtomicBoolean levelAfterPointing = new AtomicBoolean();
    /** 导航终端单一权限（研究稿 v2 §4）：只接受此 UUID 终端的指令。
     *  NBT 持久化；null = 未绑定。 */
    private @Nullable UUID boundTerminalUuid;
    /** CC 通道外设缓存（CCIntegration 管理；Object 类型是为了 CC 缺席时
     *  本类不加载 CC 类型）。生命周期随本 BE。 */
    private Object ccChannelPeripheral;

    public Object ccChannelPeripheral() {
        return ccChannelPeripheral;
    }

    public void setCcChannelPeripheral(Object peripheral) {
        ccChannelPeripheral = peripheral;
    }

    /** Immutable pointing request snapshot (interface v1 §2 payload + limits);
     *  renewed by replacement — every requestPointing call swaps in a fresh
     *  record with a fresh timestamp. */
    private record PointingRequest(UUID requester, PointingMode mode,
                                   @Nullable Vec3 posOrDir, @Nullable UUID targetEntity,
                                   @Nullable Vec3 bodyAxis,
                                   @Nullable Quat attitude,
                                   double maxAngularRate, double maxAngularAccel,
                                   long renewNanos) {
    }

    private long physicsFrames;
    private final PhysicsWatchdog physicsWatchdog = new PhysicsWatchdog();
    private final NavigationController navigation = new NavigationController();
    private final CruiseRouteWatch cruiseWatch = new CruiseRouteWatch();
    private final CruiseHeading cruiseHeading = new CruiseHeading();
    private UUID cruiseWitness;
    private final RemoteManeuver remoteManeuver = new RemoteManeuver();
    private boolean navigationWasActive;
    private boolean precisionHover;
    /** Last physics frame we logged an FE-starvation warning (throttled). */
    private long lastStarveLogFrame = -1000;

    // Scratch objects to avoid per-frame allocation on the physics thread.
    private final Vector3d angularVelocity = new Vector3d();
    private final Vector3d angularAcceleration = new Vector3d();
    private final Vector3d omegaAfterAttitudeWrite = new Vector3d();
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
    private final HoverController hover = new HoverController();
    private final AttitudeTrim attitudeTrim = new AttitudeTrim();
    private final CargoFlight cargoFlight = new CargoFlight();
    private boolean hoverVerticalInput;

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
        if (next != ControlMode.OFF && physicsWatchdog.timedOut()) {
            return "[bridgecore] physics unavailable";
        }
        if (next != mode) {
            // Capture from the next valid physics frame when entering STARSHIP.
            levelAfterPointing.set(false);
            starshipAttitudeTarget.set(null);
            hadRateInput = false;
        }
        mode = next;
        if (next != ControlMode.STARSHIP) {
            navigation.finish(NavigationController.Phase.MODE_CHANGED);
            remoteManeuver.revoke();
        }
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
        if (!coupled) {
            navigation.finish(NavigationController.Phase.MODE_CHANGED);
            remoteManeuver.revoke();
        }
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

    public double enduranceSeconds() {
        return energyStatus().enduranceSeconds();
    }

    public StructureEnergyAccount.EnergyStatus energyStatus() {
        return energyAccount.status();
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
     *  volatile-published, or null while there is no usable physics sample.
     *  Safe to poll from the server thread at 20 Hz. */
    public TelemetrySnapshot telemetrySnapshot() {
        return telemetry;
    }

    public boolean manualFlightInputSince(long tick) {
        return lastManualFlightInputTick >= tick;
    }

    /** Server-side entry point for an already authorized, same-dimension supervised task. */
    public boolean submitNavigation(UUID id, ResourceKey<Level> dimension, Vec3 target, double speed) {
        return requestCruise(id, dimension, target, speed) == NavigationController.Admission.ACCEPTED;
    }

    public NavigationController.Admission requestCruise(UUID id, ResourceKey<Level> dimension, Vec3 target, double speed) {
        return requestCruise(id, dimension, target, speed, CruiseHeading.DEFAULT_RATE);
    }

    public NavigationController.Admission requestCruise(UUID id, ResourceKey<Level> dimension, Vec3 target,
                                                         double speed, double turnRate) {
        return submitNavigation(id, dimension, target, speed, NavigationController.Profile.CRUISE, turnRate);
    }

    public enum NudgeResult { ACCEPTED, NO_SAMPLE, MOVING, NO_HEADING, REJECTED }

    public boolean submitRemoteManeuver(RemoteManeuver.Owner owner, int mask, int speedTenths, boolean changed) {
        if (mask != 0 && (isRemoved() || level == null || mode != ControlMode.STARSHIP || !(coupled || selfBalance)
                || telemetry == null || navigation.active(level.getGameTime()) != null)) return false;
        return remoteManeuver.update(owner, mask, speedTenths, changed, System.nanoTime());
    }

    /** Server-side entry point for one authorized translation step. */
    public NudgeResult submitNudge(NudgeDirection direction) {
        TelemetrySnapshot sample = telemetry;
        if (sample == null || !(level instanceof ServerLevel server)) return NudgeResult.NO_SAMPLE;
        Vec3 velocity = sample.velocity();
        if (Math.hypot(velocity.x, velocity.z) > 0.35 || Math.abs(velocity.y) > 0.4) return NudgeResult.MOVING;
        Direction facing = getBlockState().getValue(BridgeCoreBlock.FACING);
        Vector3d bow = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        sample.orientation().transform(bow);
        var offset = direction.offset(bow.x, bow.z);
        if (offset == null) return NudgeResult.NO_HEADING;
        Vec3 target = sample.position().add(offset.x(), offset.y(), offset.z());
        return submitNavigation(UUID.randomUUID(), server.dimension(), target, 0.6,
                NavigationController.Profile.NUDGE, CruiseHeading.DEFAULT_RATE)
                == NavigationController.Admission.ACCEPTED
                ? NudgeResult.ACCEPTED : NudgeResult.REJECTED;
    }

    private NavigationController.Admission submitNavigation(UUID id, ResourceKey<Level> dimension, Vec3 target,
                                                             double speed, NavigationController.Profile profile,
                                                             double turnRate) {
        if (!(level instanceof ServerLevel server) || isRemoved()
                || mode != ControlMode.STARSHIP || !(coupled || selfBalance)) {
            return NavigationController.Admission.UNAVAILABLE;
        }
        if (!server.dimension().equals(dimension) || id == null || target == null
                || !Double.isFinite(target.x) || !Double.isFinite(target.y) || !Double.isFinite(target.z)
                || !(speed > 0.0 && speed <= NavigationController.MAX_SPEED)
                || !(turnRate >= CruiseHeading.MIN_RATE && turnRate <= CruiseHeading.MAX_RATE)
                || target.y < server.getMinBuildHeight() || target.y >= server.getMaxBuildHeight()
                || !server.getWorldBorder().isWithinBounds(target.x, target.z)) {
            return NavigationController.Admission.INVALID_TARGET;
        }
        TelemetrySnapshot sample = telemetry;
        if (sample == null) return NavigationController.Admission.UNAVAILABLE;
        long tick = server.getGameTime();
        if (remoteManeuver.active(System.nanoTime()) != null) return NavigationController.Admission.BUSY;
        var running = navigation.active(tick);
        var request = new NavigationController.Task(id, target.x, target.y, target.z, speed,
                tick + profile.lifetimeTicks(), profile, turnRate);
        if (running != null) return navigation.start(request)
                ? NavigationController.Admission.ACCEPTED : NavigationController.Admission.BUSY;
        if (profile == NavigationController.Profile.NUDGE) return navigation.start(request)
                ? NavigationController.Admission.ACCEPTED : NavigationController.Admission.BUSY;

        double distance = sample.position().distanceTo(target);
        cruiseWitness = null;
        if (distance > BCConfig.UNSUPERVISED_CRUISE_RANGE.get()
                && !findCruiseWitness(server, Sable.HELPER.getContaining(this) instanceof ServerSubLevel ship ? ship : null)) {
            return NavigationController.Admission.NEEDS_CREW;
        }
        if (!cruiseChunksLoaded(server, request, sample)) return NavigationController.Admission.ROUTE_UNLOADED;
        double horizontal = Math.hypot(target.x - sample.position().x, target.z - sample.position().z);
        double vertical = Math.abs(target.y - sample.position().y);
        double gearedSpeed = Math.min(speed, TOP_SPEED_BASE * gearMults[gear]);
        long turnAllowance = horizontal > 0.5 ? (long) Math.ceil(20.0 * Math.PI / turnRate) : 0;
        long deadline = tick + turnAllowance + Math.max(
                CruiseRouteWatch.cruiseLifetime(horizontal, gearedSpeed, profile.lifetimeTicks()),
                CruiseRouteWatch.cruiseLifetime(vertical, tunings.climbRate(), profile.lifetimeTicks()));
        if (!navigation.start(new NavigationController.Task(id, target.x, target.y, target.z, speed, deadline, profile, turnRate))) {
            return NavigationController.Admission.BUSY;
        }
        cruiseWatch.start(distance, tick);
        return NavigationController.Admission.ACCEPTED;
    }

    private boolean findCruiseWitness(ServerLevel level, @Nullable ServerSubLevel ship) {
        if (ship == null || ship.isRemoved()) return false;
        var players = level.getServer().getPlayerList();
        if (cruiseWitness != null && isCruiseWitness(players.getPlayer(cruiseWitness), level, ship)) return true;
        cruiseWitness = null;
        int checked = 0;
        for (UUID tracked : ship.getTrackingPlayers()) {
            if (++checked > 16) break;
            if (isCruiseWitness(players.getPlayer(tracked), level, ship)) {
                cruiseWitness = tracked;
                return true;
            }
        }
        return false;
    }

    private static boolean isCruiseWitness(@Nullable ServerPlayer player, ServerLevel level, ServerSubLevel ship) {
        return player != null && player.level() == level && player.isAlive() && !player.isSpectator()
                && Sable.HELPER.getTrackingOrVehicleSubLevel(player) == ship;
    }

    private static boolean cruiseChunksLoaded(ServerLevel level, NavigationController.Task task, TelemetrySnapshot sample) {
        Vec3 position = sample.position();
        Vec3 velocity = sample.velocity();
        double speed = Math.max(task.speed(), Math.hypot(velocity.x, velocity.z));
        return CruiseRouteWatch.loadedAhead(position.x, position.z, task.x(), task.z(), speed,
                (x, z) -> level.getChunkSource().getChunkNow(x, z) != null);
    }

    private void checkCruise(ServerLevel level, @Nullable ServerSubLevel ship, NavigationController.Task task, long tick) {
        var sample = telemetry;
        if (sample == null) return;
        double remaining = sample.position().distanceTo(new Vec3(task.x(), task.y(), task.z()));
        if (!cruiseChunksLoaded(level, task, sample)) {
            navigation.finish(task, NavigationController.Phase.ROUTE_UNLOADED);
        } else if (remaining > BCConfig.UNSUPERVISED_CRUISE_RANGE.get()
                && cruiseWatch.lostSupervision(findCruiseWitness(level, ship), tick)) {
            navigation.finish(task, NavigationController.Phase.SUPERVISION_LOST);
        } else {
            double headingError = Double.NaN;
            if (pointingRequest == null) {
                Direction bow = getBlockState().getValue(BridgeCoreBlock.FACING);
                headingError = CruiseHeading.turningError(sample.orientation(), bow.getStepX(), bow.getStepZ(),
                        task.x() - sample.position().x, task.z() - sample.position().z);
            }
            if (cruiseWatch.stalled(remaining, headingError, tick)) {
                navigation.finish(task, NavigationController.Phase.NO_PROGRESS);
            }
        }
    }

    public boolean cancelNavigation(UUID id) { return navigation.cancel(id); }

    public @Nullable NavigationController.Status navigationStatus() { return navigation.status(); }

    private void clearPhysicsSamples() {
        navigation.finish(NavigationController.Phase.PHYSICS_LOST);
        remoteManeuver.revoke();
        telemetry = null;
        lastPosition.set(null);
        lastOrientation.set(null);
        lastLinearVelocity.set(null);
        lastAngularVelocity.set(null);
        hasPrevFrameVelocity = false;
        accelEma.zero();
        resetHover();
    }

    private void expirePhysicsSamples(long gameTime) {
        long gap = physicsWatchdog.pollTimeout(gameTime);
        if (gap < 0) return;

        clearPhysicsSamples();
        boolean wasActive = mode != ControlMode.OFF;
        if (wasActive) setMode(ControlMode.OFF);
        BridgeCoreMod.LOGGER.warn("[bridgecore] watchdog: no valid physics sample for {} ticks at {}{}",
                gap, worldPosition, wasActive ? " (mode forced to OFF)" : "");
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
     *  overrides and is logged (§2.3). mode=OFF releases the active request
     *  but keeps the link CONNECTED; clearPointing() disconnects fully. */
    public void requestPointing(UUID requester, PointingMode mode, @Nullable Vec3 posOrDir,
                                @Nullable UUID targetEntity, double maxAngularRate,
                                double maxAngularAccel) {
        requestPointing(requester, mode, posOrDir, targetEntity, null, null, maxAngularRate, maxAngularAccel);
    }

    public boolean tryRequestAxisPointing(UUID requester, Vec3 bodyAxis, Vec3 direction,
                                          double maxAngularRate, double maxAngularAccel) {
        if (mode != ControlMode.STARSHIP || pointingActiveExcept(requester)) return false;
        requestPointing(requester, PointingMode.POINT_AT_DIR, direction, null, bodyAxis, null, maxAngularRate, maxAngularAccel);
        return true;
    }

    public boolean tryRequestAttitudePointing(UUID requester, Quat attitude, double maxAngularRate, double maxAngularAccel) {
        double norm = attitude.x() * attitude.x() + attitude.y() * attitude.y()
                + attitude.z() * attitude.z() + attitude.w() * attitude.w();
        if (mode != ControlMode.STARSHIP || pointingActiveExcept(requester) || Math.abs(norm - 1.0) > 1e-6
                || !Double.isFinite(norm)) return false;
        requestPointing(requester, PointingMode.HOLD_ATTITUDE, null, null, null, attitude, maxAngularRate, maxAngularAccel);
        return true;
    }

    public boolean attitudePointingSettled(UUID requester) {
        var active = pointingRequest;
        var sample = telemetrySnapshot();
        return active != null && requester.equals(active.requester()) && active.attitude() != null
                && pointingSettled && sample != null
                && PointingExecution.withinSettleWindow(PointingExecution.attitudeErrorRad(sample.orientation(), active.attitude()),
                        sample.angularVelocity().length());
    }

    private void requestPointing(UUID requester, PointingMode mode, @Nullable Vec3 posOrDir,
                                 @Nullable UUID targetEntity, @Nullable Vec3 bodyAxis, @Nullable Quat attitude,
                                 double maxAngularRate, double maxAngularAccel) {
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(mode, "mode");
        if (mode == PointingMode.HOLD_ATTITUDE && attitude == null) throw new IllegalArgumentException("Missing attitude");
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
        // 2026-11-08 续期幂等（火控 3c 建议采纳）：同 requester + 同模式 + 同
        // 参数（目标点逐位相等）的 20 Hz 续期只刷新联系时间——不再重置
        // settled/link，否则 SETTLED 每 tick 被清一次，下游读到的是闪烁。
        // 目标点变了（如 SHIP 活位置刷新）= 新瞄准请求，照常重置。
        PointingRequest cur = pointingRequest;
        if (cur != null && cur.requester().equals(requester) && cur.mode() == mode
                && sameVec(cur.posOrDir(), posOrDir)
                && Objects.equals(cur.targetEntity(), targetEntity)
                && Objects.equals(cur.bodyAxis(), bodyAxis)
                && Objects.equals(cur.attitude(), attitude)
                && cur.maxAngularRate() == maxAngularRate
                && cur.maxAngularAccel() == maxAngularAccel) {
            pointingRequester = requester;
            pointingContactNanos = System.nanoTime();
            return;
        }
        pointingRequester = requester;
        pointingContactNanos = System.nanoTime();
        levelAfterPointing.set(false);
        pointingRequest = new PointingRequest(requester, mode, posOrDir, targetEntity, bodyAxis, attitude,
                maxAngularRate, maxAngularAccel, pointingContactNanos);
        pointingLink = PointingLinkState.REQUESTING;
        pointingSettled = false; // stays false until execution lands
    }

    /** Full disconnect (interface v1 §2.3). No-op for a non-current requester. */
    public void clearPointing(UUID requester) {
        clearPointing(requester, false);
    }

    public void clearPointing(UUID requester, boolean levelOnRelease) {
        if (pointingRequester != null && pointingRequester.equals(requester)) {
            var active = pointingRequest;
            levelAfterPointing.set(levelOnRelease && active != null && (active.bodyAxis() != null || active.attitude() != null)
                    && mode == ControlMode.STARSHIP);
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

    /** Vec3 逐位相等（续期幂等用；不依赖 Vec3.equals 是否存在）。 */
    private static boolean sameVec(@Nullable Vec3 a, @Nullable Vec3 b) {
        if (a == null || b == null) return a == b;
        return a.x == b.x && a.y == b.y && a.z == b.z;
    }

    /** HUD POINT 指示（对表：必做）：有存活指向请求 = 姿态被接管。 */
    public boolean pointingActive() {
        return pointingRequest != null;
    }

    public boolean pointingActiveExcept(@Nullable UUID requester) {
        var current = pointingRequest;
        return current != null && !current.requester().equals(requester);
    }

    /** 轴炮开火闸门读数（对表③）：遥测链现成，这里再给直读入口。 */
    public PointingLinkState pointingLink() {
        return pointingLink;
    }

    /** 指向已就位（SETTLED 持续判定），HUD 指示与遥测用。 */
    public boolean pointingSettled() {
        return pointingSettled;
    }

    /** 导航终端绑定（单一权限集，研究稿 v2 §4）。 */
    public @Nullable UUID boundTerminalUuid() {
        return boundTerminalUuid;
    }

    public void bindTerminal(UUID terminalUuid) {
        if (level instanceof ServerLevel server) {
            if (boundTerminalUuid != null && !boundTerminalUuid.equals(terminalUuid)) {
                NavWakeProbe.untrackBinding(server, getBlockPos(), boundTerminalUuid);
            }
            NavWakeProbe.trackBinding(server, this, terminalUuid);
        }
        boundTerminalUuid = terminalUuid;
        setChanged();
    }

    /** 解绑：仅当请求方就是当前绑定终端时生效。 */
    public void unbindTerminal(UUID terminalUuid) {
        if (terminalUuid.equals(boundTerminalUuid)) {
            clearBoundTerminal();
        }
    }

    /** 指令权限：仅绑定终端通过；null 请求方永不通过。 */
    public boolean terminalAllowed(@Nullable UUID terminalUuid) {
        return terminalUuid != null && terminalUuid.equals(boundTerminalUuid);
    }

    /** 核心 GUI 的解绑按钮（操作员动作）：清空绑定，无需终端在场。 */
    public void clearBoundTerminal() {
        if (boundTerminalUuid != null && level instanceof ServerLevel server) {
            NavWakeProbe.untrackBinding(server, getBlockPos(), boundTerminalUuid);
        }
        boundTerminalUuid = null;
        setChanged();
    }

    /** TRACK_ENTITY 服务端解析（设计 v1.1 §3）：实体中心写入 volatile 缓存；
     *  实体死亡/卸载 → 释放请求，链路保 CONNECTED（等效 mode=OFF）。 */
    private void tickPointingTrack(Level level) {
        PointingRequest req = pointingRequest;
        if (req == null || req.mode() != PointingMode.TRACK_ENTITY) {
            pointingTrackPos = null;
            return;
        }
        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            net.minecraft.world.entity.Entity e = sl.getEntity(req.targetEntity());
            if (e != null) {
                pointingTrackPos = e.position().add(0.0, e.getBbHeight() * 0.5, 0.0);
                return;
            }
        }
        pointingTrackPos = null;
        pointingRequest = null;
        pointingSettled = false;
        pointingSettleFrames = 0;
        pointingLink = PointingLinkState.CONNECTED;
        pointingContactNanos = System.nanoTime();
    }

    /** 期望船头世界方向（物理帧）：DIR 直读；POS/TRACK 用船逻辑位置现算差。
     *  目标与船重合等退化情况返回 null。 */
    private Vector3d pointingDirectionOf(PointingRequest req, Pose3d pose) {
        Vec3 base = switch (req.mode()) {
            case POINT_AT_DIR -> {
                Vec3 d = req.posOrDir();
                yield d; // 纯方向,直接返回
            }
            case POINT_AT_POS -> {
                var c = pose.position();
                Vec3 t = req.posOrDir();
                yield new Vec3(t.x - c.x(), t.y - c.y(), t.z - c.z());
            }
            case TRACK_ENTITY -> {
                Vec3 t = pointingTrackPos;
                if (t == null) yield null;
                var c = pose.position();
                yield new Vec3(t.x - c.x(), t.y - c.y(), t.z - c.z());
            }
            default -> null;
        };
        return base != null ? new Vector3d(base.x, base.y, base.z) : null;
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
        if (telemetry != null) {
            sb.append(" physics=LIVE(frame ").append(physicsFrames).append(')');
        } else {
            sb.append(physicsWatchdog.wasPhysicalized() ? " physics=STALE" : " physics=NEVER");
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
        long gameTime = subLevel.getLevel().getGameTime();
        expirePhysicsSamples(gameTime);
        if (!handle.isValid() || !Double.isFinite(timeStep) || timeStep <= 0.0) {
            physicsWatchdog.recordInvalid(gameTime);
            if (telemetry != null) clearPhysicsSamples();
            return;
        }

        Pose3d pose = subLevel.logicalPose();
        Vector3dc omegaWorld = handle.getAngularVelocity(angularVelocity);
        handle.getLinearVelocity(linVel);
        var massData = subLevel.getMassTracker();
        Matrix3dc inertia = massData == null ? null : massData.getInertiaTensor();
        Vector3dc centerOfMass = massData == null ? null : massData.getCenterOfMass();
        if (!pose.orientation().isFinite() || !pose.position().isFinite() || !omegaWorld.isFinite()
                || !linVel.isFinite() || inertia == null || !inertia.isFinite()
                || centerOfMass == null || !centerOfMass.isFinite() || !Double.isFinite(massData.getMass())) {
            physicsWatchdog.recordInvalid(gameTime);
            if (telemetry != null) clearPhysicsSamples();
            return;
        }

        boolean continuous = physicsWatchdog.recordValid(gameTime);
        if (!continuous || subLevel != registeredSubLevel) {
            clearPhysicsSamples();
            attitudeTrim.reset();
            cargoFlight.reset();
        }

        // Keep the CoreDirectory entry current so redstone proxies on the same
        // structure can find us (re-registration is nearly free; the sub-level
        // object can change identity across reassembly).
        if (subLevel != registeredSubLevel) {
            CoreDirectory.deregister(registeredSubLevel, this);
            CoreDirectory.register(subLevel, this);
            registeredSubLevel = subLevel;
            telemetryStructureId = structureIdOf(subLevel);
        }

        Quaterniond current = new Quaterniond(pose.orientation());
        lastOrientation.set(current);
        lastPosition.set(new Vector3d(pose.position()));
        lastAngularVelocity.set(new Vector3d(omegaWorld));
        lastLinearVelocity.set(new Vector3d(linVel));

        // Gear smoothing (M2): slew the thrust multiplier toward the selected gear.
        // Snapshot the config array once per frame (copy-on-write — the GUI may swap
        // the reference mid-frame; the slew target just follows next frame).
        double[] gm = gearMults;
        // R6-1：物理侧同样夹取下限——不只信载入（data merge 可绕过 loadAdditional）。
        double gearDiff = gm[Math.max(0, Math.min(gear, gm.length - 1))] - gearMultSmoothed;
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
            resetHover();
            attitudeTrim.reset();
            cargoFlight.reset();
            stickPitch = 0.0;
            stickRoll = 0.0;
            navigationWasActive = false;
            cruiseHeading.clear();
            return;
        }
        double phase = SubLevelPhysicsSystem.require(subLevel.getLevel()).getPartialPhysicsTick();
        energyAccount.beginStep(gameTime, Double.doubleToLongBits(phase), timeStep);

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
                engageAutoLevel(pose, false);
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
                engageAutoLevel(pose, false);
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

        // Hover owns world Y except during pitch-follow flight. Vertical stick
        // input always takes precedence over pitch-follow.
        boolean velocityLoopOn = starshipFamily && coupledEff;
        boolean hoverOn = gravityComp || (starshipFamily && coupledEff);
        boolean followPitch = altitudeFollow && ry == 0.0 && (rx != 0.0 || rz != 0.0);
        NavigationController.Task navTask = navigation.active(gameTime);
        RemoteManeuver.Input remoteInput = remoteManeuver.active(System.nanoTime());
        boolean manual = rx != 0.0 || ry != 0.0 || rz != 0.0 || pad.pitch() != 0.0 || pad.yaw() != 0.0
                || pad.roll() != 0.0 || axisDemand(torqueFaces, Direction.UP)
                || axisDemand(torqueFaces, Direction.EAST) || axisDemand(torqueFaces, Direction.SOUTH)
                || keys.levelNow() || pad.levelEdge();
        if (manual || remoteInput != null) lastManualFlightInputTick = gameTime;
        if (navTask != null) {
            if (!velocityLoopOn || massData.getMass() <= 0.0) {
                navigation.finish(NavigationController.Phase.MODE_CHANGED);
                navTask = null;
            } else if (manual) {
                navigation.finish(NavigationController.Phase.PILOT_OVERRIDE);
                navTask = null;
            }
        }
        if (remoteInput != null && (!velocityLoopOn || massData.getMass() <= 0.0 || manual || navTask != null)) {
            remoteManeuver.revoke();
            remoteInput = null;
        }
        RemoteManeuver.Motion remoteMotion = remoteInput == null ? null
                : resolveRemoteMotion(remoteInput, current, pose, subLevel.getLevel(), TOP_SPEED_BASE * gearMultSmoothed);
        boolean leavingNavigation = navigationWasActive && navTask == null;
        if (starshipFamily && leavingNavigation && !keys.levelNow() && !pad.levelEdge()) {
            starshipAttitudeTarget.set(new Quaterniond(current));
        }
        navigationWasActive = navTask != null;
        if (navTask != null || remoteMotion != null) followPitch = false;
        double verticalStick = remoteMotion == null ? ry : Math.signum(remoteMotion.y());
        double hullMass = massData.getMass();
        if (velocityLoopOn) {
            if (cargoFlight.update(subLevel, gameTime, pose, massData, linVel)) {
                resetHover();
                attitudeTrim.reset();
            }
        } else {
            if (cargoFlight.hasDocked()) resetHover();
            cargoFlight.reset();
        }
        boolean dockedCargo = cargoFlight.hasDocked();
        boolean tetheredCargo = cargoFlight.hasTether();
        double controlMass = dockedCargo ? cargoFlight.mass() : hullMass;
        Vector3dc controlCenter = dockedCargo ? cargoFlight.center() : centerOfMass;
        Vector3dc controlVelocity = dockedCargo ? cargoFlight.velocity() : linVel;
        boolean precisionNavigation = navTask != null && navTask.profile() == NavigationController.Profile.NUDGE;
        if (remoteMotion != null) {
            precisionHover = true;
        } else if (navTask != null) {
            precisionHover = precisionNavigation;
        } else if (ry != 0.0 || followPitch || (leavingNavigation
                && navigation.status().phase() != NavigationController.Phase.ARRIVED)) {
            precisionHover = false;
        }
        if (!precisionHover) hover.resetHeightSampling();
        double verticalTolerance = navTask == null ? 0.45 : navTask.profile().verticalTolerance();
        boolean navigationLiftLimited = false;
        double navigationVerticalSpeed = linVel.y();
        if (hoverOn) {
            if (hullMass > 0.0) {
                double y = dockedCargo ? cargoFlight.height() : pose.position().y();
                DimensionPhysicsData.getGravity(subLevel.getLevel(), pose.position(), gravityVec);
                // Hover spends first. This estimate uses current pool stock and
                // the same FE rate as the actual write below.
                double maxThrust = HOVER_MAX_ALPHA * (hullMass / controlMass);
                double availableThrust = Math.min(maxThrust,
                        energyAccount.availableForStep() / (controlMass * timeStep * FE_PER_NEWTON_SECOND));
                double gravity = -gravityVec.y;
                if (tetheredCargo) cargoFlight.observe(controlVelocity, gravity, maxThrust, verticalStick == 0.0 && !followPitch
                        && (navTask == null || Math.abs(navTask.y() - pose.position().y()) <= verticalTolerance));
                double support = tetheredCargo ? gravity + Math.copySign(cargoFlight.verticalSupport(), gravity) : gravity;
                double response = tetheredCargo ? cargoFlight.verticalResponse(gravity) : 1.0;
                double verticalSpeed = dockedCargo || tetheredCargo
                        ? cargoFlight.sampleVerticalSpeed(y, controlVelocity.y(), timeStep) : linVel.y;
                if (precisionHover) verticalSpeed = hover.sampleHeightSpeed(y, verticalSpeed, timeStep);
                navigationVerticalSpeed = dockedCargo && !precisionNavigation ? linVel.y() : verticalSpeed;
                navigationLiftLimited = availableThrust < Math.abs(support)
                        || (navTask != null && Math.abs(navTask.y() - pose.position().y()) > verticalTolerance
                        && availableThrust < Math.abs(support) + 0.05);
                hoverVerticalInput = verticalStick != 0.0;
                if (leavingNavigation && ry == 0.0 && !followPitch) hover.captureHeight(y);
                double externalHeight = navTask == null ? Double.NaN : navTask.y() + (y - pose.position().y());
                double climbRate = precisionNavigation ? Math.min(tune.climbRate(), navTask.speed()) : tune.climbRate();
                if (remoteMotion != null && verticalStick != 0.0) climbRate = Math.abs(remoteMotion.y());
                double acceleration = hover.thrustAcceleration(y, verticalSpeed, verticalStick, followPitch,
                        support, availableThrust, timeStep, tune.hoverKp(), tune.climbKv(), climbRate,
                        response, externalHeight);
                double force = controlMass * Math.clamp(acceleration, -maxThrust, maxThrust);
                double scale = priceWrite(Math.abs(force), timeStep, FE_PER_NEWTON_SECOND);
                if (scale > 0.0) {
                    double impulseY = force * scale * timeStep;
                    if (tetheredCargo) cargoFlight.recordHoverImpulse(impulseY);
                    linForce.set(0.0, impulseY, 0.0);
                    pose.orientation().transformInverse(linForce);
                    handle.applyImpulseAtPoint(controlCenter, linForce);
                }
            } else {
                resetHover();
            }
        } else {
            resetHover();
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
        if (!velocityLoopOn && (rx != 0.0 || ry != 0.0 || rz != 0.0)) {
            double mass = subLevel.getMassTracker().getMass();
            linearImpulse.set(rx, hoverOn ? 0.0 : ry, rz);
            linearImpulse.mul(mass * THRUST_ACCEL * gearMultSmoothed);  // N
            double scale = priceWrite(linearImpulse.length(), timeStep, FE_PER_NEWTON_SECOND);
            if (scale > 0.0) {
                handle.applyImpulseAtPoint(centerOfMass, linearImpulse.mul(scale * timeStep));
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

        PointingRequest preq = pointingRequest;
        if (preq != null && preq.attitude() != null && (manual || remoteInput != null)) {
            preq = null;
            pointingSettled = false;
        }
        if (navTask != null && navTask.profile() == NavigationController.Profile.CRUISE) {
            Direction bow = getBlockState().getValue(BridgeCoreBlock.FACING);
            cruiseHeading.update(navTask, pose.position().x(), pose.position().z(), bow.getStepX(), bow.getStepZ());
        } else {
            cruiseHeading.clear();
        }
        var courseAttitude = preq == null ? cruiseHeading.target() : null;
        boolean cruiseFacing = courseAttitude != null;
        // ---- starship translation layer (handoff §8 step 5) ----
        // World-frame force accumulator: the unified target-velocity loop (v6.0).
        // Priced per newton-second like all writes. (OFF returned above.)
        if (starshipFamily) {
            linForce.set(0.0, 0.0, 0.0);
            double fMax = hullMass * Math.max(MAX_LIN_ALPHA, THRUST_ACCEL * gearMultSmoothed);
            if (navTask != null) {
                double availableForce = Math.min(fMax,
                        energyAccount.availableForStep() / (timeStep * FE_PER_NEWTON_SECOND));
                double brakeMass = controlMass * (tetheredCargo ? 1.0 + cargoFlight.horizontalRatio() : 1.0);
                double alignment = cruiseFacing ? cruiseHeading.speedFactor(current) : 1.0;
                double speedLimit = Math.min(navTask.speed(), TOP_SPEED_BASE * gearMultSmoothed);
                if (navTask.profile() == NavigationController.Profile.CRUISE
                        && speedLimit > CruiseRouteWatch.FAST_SPEED_THRESHOLD) {
                    speedLimit = Math.min(speedLimit, CruiseRouteWatch.fastSpeedLimit(Math.min(24.0, availableForce / brakeMass)));
                }
                NavigationController.Guidance guide = navigation.guide(navTask, timeStep,
                        pose.position().x(), pose.position().y(), pose.position().z(),
                        linVel.x(), navigationVerticalSpeed, linVel.z(), availableForce / brakeMass,
                        speedLimit * alignment, navigationLiftLimited);
                linForce.set(guide.vx() - controlVelocity.x(), 0.0, guide.vz() - controlVelocity.z())
                        .mul(controlMass * tune.transKv());
                pose.orientation().transformInverse(vTargetBody.set(guide.vx(), 0.0, guide.vz()));
            } else if (remoteMotion != null) {
                double vx = navigationLiftLimited ? 0.0 : remoteMotion.x();
                double vz = navigationLiftLimited ? 0.0 : remoteMotion.z();
                linForce.set(vx - controlVelocity.x(), 0.0, vz - controlVelocity.z())
                        .mul(controlMass * tune.transKv());
                pose.orientation().transformInverse(vTargetBody.set(vx, 0.0, vz));
            } else if (coupledEff) {
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
                // follows the nose plane). Vertical stick still hands
                // the channel to the hover climb loop.
                boolean loopOwnsBodyY = !hoverOn || followPitch;
                double topSpeed = TOP_SPEED_BASE * gearMultSmoothed;
                vTargetBody.set(rx * topSpeed, ry * topSpeed, rz * topSpeed);
                if (loopOwnsBodyY) {
                    pose.orientation().transformInverse(controlVelocity, vBody); // world -> body
                    latBody.set(tune.transKv() * (vTargetBody.x - vBody.x),
                            tune.transKv() * (vTargetBody.y - vBody.y),
                            tune.transKv() * (vTargetBody.z - vBody.z));
                    pose.orientation().transform(latBody);          // body -> world
                    linForce.add(latBody.mul(controlMass));
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
                    linForce.add(controlMass * tune.transKv() * (latBody.x - controlVelocity.x()),
                            0.0, controlMass * tune.transKv() * (latBody.z - controlVelocity.z()));
                }
            } else {
                vTargetBody.set(Double.NaN, Double.NaN, Double.NaN);
            }
            if (tetheredCargo) {
                double massScale = 1.0 + cargoFlight.horizontalRatio();
                linForce.mul(massScale, 1.0, massScale);
                cargoFlight.slewHorizontalForce(linForce, hullMass, timeStep);
            }
            double fLen = linForce.length();
            if (fLen > 0.0) {
                // Force ceiling scales with the gear's own thrust authority so the
                // loop can actually reach top speed at high gears (G4 = 24 m/s^2 —
                // brakes bite hard there; player-facing tunables land in the UI later).
                if (fLen > fMax) {
                    linForce.mul(fMax / fLen);
                }
                double scale = priceWrite(linForce.length(), timeStep, FE_PER_NEWTON_SECOND);
                if (scale > 0.0) {
                    if (navTask != null) navigation.recordBrake(navTask, linVel.x(), linVel.z(),
                            linForce.x() * scale * timeStep, linForce.z() * scale * timeStep, timeStep);
                    if (tetheredCargo) cargoFlight.recordVelocityImpulse(linForce, scale * timeStep);
                    pose.orientation().transformInverse(linForce.mul(scale)); // world -> local
                    handle.applyImpulseAtPoint(controlCenter, linForce.mul(timeStep));
                }
            }
        }
        if (tetheredCargo) cargoFlight.finish(controlVelocity, controlMass, timeStep);

        // Attitude authority. STARSHIP runs a RATE loop while the attitude stick is
        // held. Release-edge capture splits on the selfBalance switch (M3 revision,
        // 2026-09-02 user ruling): plain STARSHIP holds the FULL attitude (on-demand
        // leveling is the C key); selfBalance auto-levels (yaw-hold, pitch/roll -> 0,
        // near-vertical bow falls back to full capture). A fresh STARSHIP captures
        // its current attitude on the first valid frame without a rate command.
        // 指向释放当帧：以当前姿态重捕获 HOLD 目标——否则旧目标会把船拉回
        // 指向开始前的姿态（回弹）。
        if (preq == null && starshipFamily) {
            if (pointingWasActive && !keys.levelNow() && !pad.levelEdge()) {
                starshipAttitudeTarget.set(new Quaterniond(current));
            }
            if (levelAfterPointing.get() && levelAfterPointing.getAndSet(false)
                    && !manual && remoteMotion == null && navTask == null) {
                engageAutoLevel(pose, true);
            }
        }
        pointingWasActive = preq != null;
        boolean remoteYaw = remoteMotion != null && remoteMotion.yaw() != 0.0 && preq == null;
        if (remoteYaw) {
            current.transformInverse(omegaTargetBody.set(0.0, remoteMotion.yaw(), 0.0));
            rateCommand = true;
        }
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
                if (attTarget == null) {
                    attTarget = new Quaterniond(current);
                    starshipAttitudeTarget.set(attTarget);
                }
                attMode = AttitudeController.Mode.HOLD;
            }
        } else {
            attMode = AttitudeController.Mode.DAMPING;
            attTarget = null;
        }
        // Pointing execution (design v1.1, 2026-11-08): an active request owns
        // the attitude loop. Per-frame shortest-arc target (roll free), RATE-form
        // command = clamp(kp·|e|, maxAngularRate), accel clamp additionally
        // capped by the request's maxAngularAccel. Pilot stick input does NOT
        // break the link (§5 user ruling). Translation/hover untouched.
        Vector3d pointingOmega = null;
        double attMaxAlphaEff = remoteYaw ? Math.min(tune.attMaxAlpha(), RemoteManeuver.YAW_ACCELERATION)
                : tune.attMaxAlpha();
        if (preq != null && starshipFamily) {
            Vector3d dirW = pointingDirectionOf(preq, pose);
            Direction bowDir = getBlockState().getValue(BridgeCoreBlock.FACING);
            Vec3 bodyAxis = preq.bodyAxis();
            Vector3d bowLocal = bodyAxis == null
                    ? new Vector3d(bowDir.getStepX(), bowDir.getStepY(), bowDir.getStepZ())
                    : new Vector3d(bodyAxis.x, bodyAxis.y, bodyAxis.z);
            Quat attitude = preq.attitude();
            if (!Objects.equals(attitude, pointingSettleTarget)) {
                pointingSettleFrames = 0;
                pointingSettleTarget = attitude;
            }
            Quaterniond qt = attitude != null ? new Quaterniond(attitude.x(), attitude.y(), attitude.z(), attitude.w())
                    : dirW != null ? PointingExecution.shortestArcTarget(current, bowLocal, dirW) : null;
            if (qt != null) {
                pointingOmega = PointingExecution.rateCommand(current, qt,
                        tune.attitudeKp(), preq.maxAngularRate(), new Vector3d());
                attMode = AttitudeController.Mode.RATE;
                attTarget = null;
                attMaxAlphaEff = Math.min(tune.attMaxAlpha(), preq.maxAngularAccel());
                double error = attitude != null ? PointingExecution.attitudeErrorRad(current, attitude)
                        : PointingExecution.bowErrorRad(current, bowLocal, dirW);
                pointingSettleFrames = PointingExecution.nextSettleFrames(pointingSettleFrames, error, omegaWorld.length());
                boolean settledNow = pointingSettleFrames >= PointingExecution.SETTLED_FRAMES;
                pointingSettled = settledNow;
                pointingLink = settledNow ? PointingLinkState.SETTLED : PointingLinkState.REQUESTING;
            } else {
                pointingSettleFrames = 0;
                pointingSettled = false;
            }
        } else {
            pointingSettleFrames = 0;
            pointingSettleTarget = null;
        }
        if (cruiseFacing) {
            PointingExecution.rateCommand(current, courseAttitude, tune.attitudeKp(), navTask.turnRate(), omegaTargetBody);
            attMode = AttitudeController.Mode.RATE;
            attTarget = null;
            attMaxAlphaEff = Math.min(attMaxAlphaEff, CruiseHeading.MAX_ACCELERATION);
        }
        Vector3d omegaCmd = pointingOmega != null ? pointingOmega
                : rateCommand || cruiseFacing ? omegaTargetBody : null;
        AttitudeController.computeAngularAcceleration(attMode, current, attTarget,
                omegaCmd, omegaWorld, angularAcceleration,
                tune.attitudeKp(), tune.attitudeKd());
        boolean trackAttitudeLoad = attTarget != null || omegaCmd != null;
        if (trackAttitudeLoad) {
            attitudeTrim.addCorrection(attTarget, current, omegaWorld, controlMass,
                    attMaxAlphaEff, angularAcceleration);
        }
        if (remoteYaw && pointingOmega == null) {
            current.transform(angularAcceleration);
            angularAcceleration.y = RemoteManeuver.limitYawAcceleration(angularAcceleration.y, omegaWorld.y(), timeStep);
            current.transformInverse(angularAcceleration);
        }
        boolean saturated = AttitudeController.limitAngularAcceleration(angularAcceleration, attMaxAlphaEff);
        (dockedCargo ? cargoFlight.inertia() : inertia).transform(angularAcceleration, torque);
        double scale = 1.0;
        if (torque.lengthSquared() > 0.0) {
            scale = priceWrite(torque.length(), timeStep,
                    FE_PER_NM_SECOND * tune.rotPriceMult());
            if (scale > 0.0) {
                handle.applyAngularImpulse(angularImpulse.set(torque).mul(scale * timeStep));
            }
        }
        if (trackAttitudeLoad && !saturated && scale == 1.0) {
            if (dockedCargo) {
                // The solver shares the root's impulse with the docked bodies.
                // Use the predicted group response as the observer baseline.
                current.transform(angularAcceleration, omegaAfterAttitudeWrite).mul(timeStep).add(omegaWorld);
            } else {
                handle.getAngularVelocity(omegaAfterAttitudeWrite);
            }
            attitudeTrim.recordAfterWrite(omegaAfterAttitudeWrite, timeStep);
        } else {
            attitudeTrim.reset();
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
        // Legacy migration: pre-v0.13 saves stored a core-local buffer under "fe";
        // transfer it once, preserving any balance above the new capacity.
        if (be.pendingLegacyFe >= 0) {
            if (be.energyAccount.hasPools()) {
                long restored = be.energyAccount.restoreLegacy(be.pendingLegacyFe);
                long voided = be.pendingLegacyFe - restored;
                BridgeCoreMod.LOGGER.warn("[bridgecore] legacy core buffer at {}: {} FE restored to pools"
                        + (voided > 0 ? ", " + voided + " FE exceeded storage range" : ""), pos, restored);
                be.pendingLegacyFe = -1;
            } else if (++be.legacyFlushWait >= 20) {
                if (be.pendingLegacyFe > 0) {
                    BridgeCoreMod.LOGGER.warn("[bridgecore] legacy core buffer at {}: {} FE voided "
                            + "(no momentum distributor on the structure)", pos, be.pendingLegacyFe);
                }
                be.pendingLegacyFe = -1;
            }
        }
        be.energyAccount.updateStatus(be.drawFePerTick, level.getGameTime());
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
        be.tickPointingTrack(level);

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

        be.expirePhysicsSamples(level.getGameTime());
        var remote = be.remoteManeuver.active(System.nanoTime());
        if (remote != null && level instanceof ServerLevel server) {
            var owner = remote.owner();
            var pilot = server.getServer().getPlayerList().getPlayer(owner.player());
            var held = pilot == null ? null : NavTerminalData.heldTerminal(pilot, owner.terminal());
            if (held == null || pilot.level() != server || !pilot.isAlive() || pilot.isSpectator()
                    || !be.terminalAllowed(owner.terminal()) || !pos.equals(NavTerminalData.boundCorePos(held))
                    || !server.dimension().location().equals(NavTerminalData.boundCoreDim(held))) {
                be.remoteManeuver.revoke();
            }
        }
        long tick = level.getGameTime();
        var navigationTask = be.navigation.active(tick);
        if (navigationTask != null && navigationTask.profile() == NavigationController.Profile.CRUISE
                && level instanceof ServerLevel server
                && Math.floorMod(tick + pos.hashCode(), CruiseRouteWatch.CHECK_INTERVAL) == 0) {
            be.checkCruise(server, sub, navigationTask, tick);
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
        clearPhysicsSamples();
        super.setRemoved();
    }

    // ---- NBT persistence ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("wasPhysicalized", physicsWatchdog.wasPhysicalized());
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
        if (boundTerminalUuid != null) {
            tag.putUUID("boundTerminal", boundTerminalUuid);
        }
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
        physicsWatchdog.restorePhysicalized(tag.getBoolean("wasPhysicalized"));
        // Pre-v0.13 saves stored a core-local FE buffer; v0.13 spends straight from
        // the pools, so the leftover is poured back into them on a server tick.
        if (tag.contains("fe")) {
            pendingLegacyFe = tag.getInt("fe");
        }
        // Config state first: the gear snap below reads gearMults.
        if (tag.contains("gearMults")) {
            int[] raw = tag.getIntArray("gearMults");
            // R7-3：长度不符不做整组丢弃——前缀覆盖 + 其余保留默认/现值。
            if (raw.length != GEAR_COUNT) {
                BridgeCoreMod.LOGGER.warn("[bridgecore] gearMults length {} != {}, prefix overlay",
                        raw.length, GEAR_COUNT);
            }
            double[] next = gearMults.clone();
            for (int i = 0; i < Math.min(raw.length, GEAR_COUNT); i++) {
                next[i] = Math.max(GEAR_MULT_MIN, Math.min(GEAR_MULT_MAX, raw[i] / 1000.0));
            }
            gearMults = next;
        }
        if (tag.contains("keyMap")) {
            int[] raw = tag.getIntArray("keyMap");
            if (raw.length == TypewriterInput.CHANNEL_COUNT) {
                keyMap = raw;
            } else if (raw.length < TypewriterInput.CHANNEL_COUNT) {
                // Shorter saves predate newer channels (lock_category at 15,
                // lock_species at 16, lock_release at 17, channel_cycle at 18,
                // fire at 19) — adopt the stored binds and take the new
                // channels' factory defaults (Tab, V, G, X, H).
                // R7-3 残留：长度 <15 的旧档也走这条路（此前整组回默认）。
                int[] grown = TypewriterInput.defaultKeyMap();
                System.arraycopy(raw, 0, grown, 0, raw.length);
                keyMap = grown;
                // R7-4：玩法相关的迁移留日志。
                BridgeCoreMod.LOGGER.warn("[bridgecore] keyMap migrated at {}: {} -> {} channels",
                        getBlockPos().toShortString(), raw.length, TypewriterInput.CHANNEL_COUNT);
            } else if (raw.length > TypewriterInput.CHANNEL_COUNT) {
                // R7-3：未来版本的更长 keyMap 也做前缀覆盖（不是整组丢弃）。
                BridgeCoreMod.LOGGER.warn("[bridgecore] keyMap length {} > {}, prefix overlay",
                        raw.length, TypewriterInput.CHANNEL_COUNT);
                int[] next = keyMap.clone();
                System.arraycopy(raw, 0, next, 0, TypewriterInput.CHANNEL_COUNT);
                keyMap = next;
            }
        }
        if (tag.contains("gamepadMap")) {
            int[] raw = tag.getIntArray("gamepadMap");
            // R7-3：同 gearMults——前缀覆盖，不整组丢弃。
            if (raw.length != GamepadInput.MAP_LENGTH) {
                BridgeCoreMod.LOGGER.warn("[bridgecore] gamepadMap length {} != {}, prefix overlay",
                        raw.length, GamepadInput.MAP_LENGTH);
            }
            int[] next = gamepadMap.clone();
            System.arraycopy(raw, 0, next, 0, Math.min(raw.length, next.length));
            gamepadMap = next;
        }
        if (tag.contains("hudScale")) {
            // R7-5：双精度载入统一有限性闸门（Math.min/max 拦不住 NaN/Inf）。
            double hx = NbtGuards.finiteOr(tag.getDouble("hudX"), 0.0);
            double hy = NbtGuards.finiteOr(tag.getDouble("hudY"), 0.0);
            double hs = NbtGuards.finiteOr(tag.getDouble("hudScale"), 1.0);
            // Pre-2.14 saves may predate the eye keys entirely: the migration
            // below treats them as the old defaults (2.0 / 0.12).
            double hex = tag.contains("hudEyeX")
                    ? NbtGuards.finiteOr(tag.getDouble("hudEyeX"), 2.0) : 2.0;
            double hey = tag.contains("hudEyeY")
                    ? NbtGuards.finiteOr(tag.getDouble("hudEyeY"), 0.12) : 0.12;
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
                        tag.contains("hudPitchStep")
                                ? NbtGuards.finiteOr(tag.getDouble("hudPitchStep"), 5.0) : 5.0));
                hudAttSize = Math.max(HUD_ATT_SIZE_MIN, Math.min(HUD_ATT_SIZE_MAX,
                        tag.contains("hudAttSize")
                                ? NbtGuards.finiteOr(tag.getDouble("hudAttSize"), 1.0) : 1.0));
                hudAttBorder = Math.max(0.0, Math.min(HUD_ATT_BORDER_MAX,
                        tag.contains("hudAttBorder")
                                ? NbtGuards.finiteOr(tag.getDouble("hudAttBorder"), 0.0) : 0.0));
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
                // R7-4：v1→v2 校准迁移留日志（影响观感的迁移必须可见）。
                BridgeCoreMod.LOGGER.warn("[bridgecore] HUD calibration migrated v1->v2 at {}",
                        getBlockPos().toShortString());
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
        boundTerminalUuid = NbtGuards.hasUuid4(tag, "boundTerminal") ? tag.getUUID("boundTerminal") : null;
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
            // R6-1：负 gear 也要夹（此前只夹上限，脏 NBT 直接 AIOOBE）。
            gear = Math.max(0, Math.min(tag.getInt("gear"), gearMults.length - 1));
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
                        // R7-3 残留：未知 mode 回退也要留日志。
                        BridgeCoreMod.LOGGER.warn("[bridgecore] unknown control mode '{}' at {} -> OFF",
                                saved, getBlockPos().toShortString());
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

    private static boolean axisDemand(int[] faces, Direction direction) {
        return faces[direction.ordinal()] != faces[direction.getOpposite().ordinal()];
    }

    private RemoteManeuver.Motion resolveRemoteMotion(RemoteManeuver.Input input, Quaterniond orientation,
                                                      Pose3d pose, ServerLevel level, double speedLimit) {
        Direction facing = getBlockState().getValue(BridgeCoreBlock.FACING);
        Vector3d bow = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        orientation.transform(bow);
        var motion = RemoteManeuver.resolve(input, bow.x, bow.z, speedLimit);
        var position = pose.position();
        boolean withinBorder = level.getWorldBorder().isWithinBounds(
                BlockPos.containing(position.x() + motion.x(), position.y(), position.z() + motion.z()));
        double projectedHeight = position.y() + motion.y();
        boolean withinHeight = projectedHeight >= level.getMinBuildHeight() && projectedHeight < level.getMaxBuildHeight();
        return new RemoteManeuver.Motion(withinBorder ? motion.x() : 0.0, withinHeight ? motion.y() : 0.0,
                withinBorder ? motion.z() : 0.0, motion.yaw());
    }

    private void resetHover() {
        hover.reset();
        hover.resetHeightSampling();
        precisionHover = false;
        hoverVerticalInput = false;
    }

    /**
     * Auto-level (M3 revision, 2026-09-02 user ruling): one-shot pitch/roll -> 0,
     * keep heading. A "panic button" for disorientation — unlike the self-balance
     * switch's release-stick leveling this is an on-demand, single-shot action in
     * starship mode. Also used when a completed coordinate strike releases
     * axis pointing. Physics thread only.
     */
    private void engageAutoLevel(Pose3d pose, boolean afterAxisStrike) {
        Direction bow = getBlockState().getValue(BridgeCoreBlock.FACING);
        Vector3d bowLocal = new Vector3d(bow.getStepX(), bow.getStepY(), bow.getStepZ());
        Quaterniond leveled = new Quaterniond();
        // A near-vertical bow has no horizontal heading. Use the ship's side axis for strike recovery.
        if (AttitudeController.levelTarget(pose.orientation(), bowLocal, leveled)
                || (afterAxisStrike && AttitudeController.levelTarget(pose.orientation(),
                        new Vector3d(-bowLocal.z, 0.0, bowLocal.x), leveled))) {
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
        if (physicsFrames - lastStarveLogFrame >= 100) {
            lastStarveLogFrame = physicsFrames;
            long stored = energyAccount.totalStored();
            BridgeCoreMod.LOGGER.warn("[bridgecore] control starved: {} at {} (requested {} FE, granted {} FE, stored {} FE)",
                    stored > 0 ? "distributor output limit" : "distributor energy exhausted",
                    worldPosition, fmt(cost), fmt(afforded), stored);
        }
        return afforded / cost;
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }
}
