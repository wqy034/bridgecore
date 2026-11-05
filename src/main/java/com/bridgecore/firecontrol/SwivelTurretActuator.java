package com.bridgecore.firecontrol;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import com.bridgecore.firecontrol.ballistics.Quat;
import com.bridgecore.firecontrol.ballistics.Vec3;
import com.bridgecore.mixin.SwivelBearingAccessorMixin;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Swivel-bearing turret actuator (roadmap §3 backend 1, phase 3 aim-only):
 * angle direct-write to the aeronautics swivel bearings; the aeronautics
 * inertia-adaptive PD does the servo work — we never touch the constraint or
 * the physics thread.
 *
 * <p>Outer control law (2026-09-26 self-calibrating revision — the loop
 * re-measures its zero every tick instead of trusting persisted calibration):
 * <ul>
 *   <li>joint command = register readback + SIGN*err at FULL gain — the
 *       readback IS the zero (the old persisted C = register↔twist offset
 *       could go stale across reloads and bake in a fixed bias);</li>
 *   <li>dual-threshold hysteresis (create-fire-control BearingStabilizer
 *       双稳同款): settle/stop writing at |err| &le; 0.5°, resume only above
 *       0.85°; while settled the bearing's own PD holds — writing inside its
 *       ~0.5° hysteresis floor only dithers;</li>
 *   <li>rate-limited to ±3°/tick, norm360 on write; after each write
 *       updateServoCoefficients() (the official write path's PD refresh our
 *       accessor would bypass) and both sub-levels are woken (handoff 坑2);</li>
 *   <li>SIGN per joint auto-identified at calibration by a +5° probe
 *       (雷点4: 轴约定必须实测, never configured by hand), plus an online
 *       write-response check on the native-servo path that flips a stale
 *       SIGN after two contradictory responses;</li>
 *   <li>actual joint angle via swing-twist of the relative pose
 *       (qRel = conj(qContainer)*qTurret) around the hinge axis.</li>
 * </ul>
 *
 * <p>Discipline (handoff §3.2): bearings must be always_locked — an unlocked
 * bearing swallows target writes on the lock edge. Calibration stalls with a
 * status message if a bearing is unpowered/unassembled; auto-assemble is
 * attempted once per calibration run.
 *
 * <p>Server thread only. Attached structures are re-resolved from the bearing's
 * getSubLevelID() every tick (reassembly swaps ServerSubLevel identity — the
 * UUID is stable, the Lua prototype's enumeration gap does not apply here).
 */
public final class SwivelTurretActuator implements TurretActuator {

    /** Lua prototype MAX_STEP_DEG: 3°/tick at 20 Hz = 60°/s slew cap. */
    private static final double MAX_STEP_DEG = 3.0;
    /** Lua prototype CAL_STEP_DEG: sign-identification probe step. */
    private static final double CAL_STEP_DEG = 5.0;
    /** Ticks to let the PD settle between probe write and measurement. */
    private static final int CAL_SETTLE_TICKS = 20;
    /** Stable-tick count the return-to-rest gate requires before zero sampling. */
    private static final int CAL_REST_SETTLE_TICKS = 5;
    /** Probe response below this = something is wrong (lock/assembly/wake). */
    private static final double CAL_MIN_RESPONSE_DEG = 0.5;
    /** Retry a failed calibration after this many ticks. */
    private static final int CAL_RETRY_TICKS = 100;
    /** 脱战收炮延时 (2026-10-06 user ruling): continuous no-target ticks
     *  before both joints drive back to the arc zero — 5s at 20 Hz. */
    private static final int REST_RETURN_DELAY_TICKS = 100;
    /** Dual-threshold hysteresis (create-fire-control BearingStabilizer 双稳同款):
     *  command zero / enter hold at |err| <= HOLD, resume only above RELEASE.
     *  The bearing's own PD has a ~0.5° hysteresis floor — writing inside it
     *  only dithers. While active the loop runs at FULL gain (err applied 1:1,
     *  Lua swivel_closed_loop_test.lua parity; the measured-base revision of
     *  2026-09-16 keeps the anti-windup property, gain 0.5 was a mis-port). */
    private static final double AIM_HOLD_DEG = 0.5;
    private static final double AIM_RELEASE_DEG = 0.85;

    /** Ship-mount loop profile. The 2026-09-17 gentle profile (halved step,
     *  gain 0.5, wide hold band) defended against a turret↔ship torque
     *  coupling that lived in the FORCE-driven servo path. AxisLock drives
     *  shipborne bearings kinematically (teleport + velocity reset, no
     *  reaction torque into the hull), so that coupling channel is closed and
     *  the ship profile returns to ground parity (2026-10-06, "can't track
     *  while my own ship maneuvers"): fractional gain had a built-in
     *  steady-state trail of ω/0.5 deg, and the 0.8° hold band alone capped
     *  accuracy at ~0.8°. If a wobble limit-cycle ever reappears on a moving
     *  hull, revisit these before touching the solve chain. */
    private static final double SHIP_MAX_STEP_DEG = 3.0;
    private static final double SHIP_AIM_GAIN = 1.0;
    private static final double SHIP_AIM_HOLD_DEG = 0.5;
    private static final double SHIP_AIM_RELEASE_DEG = 0.85;

    // ---------------- persisted pairing data ----------------
    private BlockPos yawPos;      // yaw (base) bearing, yard coords of its container
    private BlockPos pitchPos;    // pitch bearing, yard coords ON the yaw structure
    /** Yaw bearing position relative to the CORE block — the reassembly-
     *  invariant identity (2026-09-26): Sable reassembly/projection moves the
     *  ship to a fresh yard plot by a PURE translation (AssemblyTransform,
     *  Rotation.NONE), so absolute positions die but this offset survives.
     *  Null on legacy saves until lazily migrated on first resolve. */
    private BlockPos yawOffset;
    private int signYaw = 1, signPitch = 1; // probe-identified axis conventions (persisted: mechanical properties)
    private Vec3 muzzleLocal;     // muzzle direction in the gun structure's yard frame
    private boolean calibrated;

    // ---- per-turret fire-arc limits (P2, 2026-09-26; GUI drag handles, persisted) ----
    // Twist space relative to the rest pose sampled at calibration (the zero
    // fields below). 压边+禁射语义: a target outside the arc is tracked to the
    // arc edge and held there, and the fire gate reads 禁射 while pinned.
    // Independent yaw min/max (asymmetric arcs for broadside mounts);
    // defaults = unrestricted (legacy behaviour).
    private double yawMinDeg = -180.0, yawMaxDeg = 180.0; // yaw arc bounds, -180..180
    private double pitchMinDeg = -90.0, pitchMaxDeg = 90.0;
    /** Rest-pose twists sampled at calibration start (pre-probe) = arc centers.
     *  NaN on legacy saves → lazily adopted from the first measured twist. */
    private double yawZeroTwist = Double.NaN, pitchZeroTwist = Double.NaN;
    /** Set while the arc clamp pinned a joint this tick (fire gate: 禁射). */
    private boolean arcYawLimited, arcPitchLimited;
    /** Last twist-space angles relative to the arc zeros (GUI live indicator). */
    private double lastRelYaw = Double.NaN, lastRelPitch = Double.NaN;
    private int pitchUpSense = 1; // operator elevation vs mechanical twist, NOT register sign

    // ---- fe_railgun gun-mount link (P3; persisted except the runtime note/tick) ----
    /** Max mounts per turret (multi-mount main-gun adaption, P3 fix 2nd round:
     *  多联装主炮). The GUI row carries exactly this many status slots. */
    public static final int MAX_MOUNTS = 4;
    /** One linked gun mount: {@code no} is the per-turret mount number, issued
     *  in link order and NEVER reissued/renumbered on unlink (stable identity
     *  for GUI chips and chat receipts). */
    public record Mount(int no, BlockPos pos) {}
    /** Linked cannon mounts (yard coords, same resolution rule as the
     *  bearings), in link order. Empty = aim-only turret. */
    private final List<Mount> mounts = new ArrayList<>();
    /** Auto-fire cadence gears (tick/shot), channel-level (P3 武器频道):
     *  4 = 300 rpm (the LASER autocannon's own breech pace — rejections are
     *  free, so the breech is the real throttle), 10 = 120, 20 = 60 (default),
     *  40 = 30. The channel stepper cycles these via {@link #cycleGear}. */
    public static final int[] FIRE_INTERVAL_GEARS = {4, 10, 20, 40};

    /** Cycle {@code cur} through FIRE_INTERVAL_GEARS (dir ±1, wraps around).
     *  A value outside the table snaps to the nearest gear in dir first. */
    public static int cycleGear(int cur, int dir) {
        int[] g = FIRE_INTERVAL_GEARS;
        int next = dir > 0 ? g[0] : g[g.length - 1];
        if (dir > 0) {
            for (int v : g) {
                if (v > cur) { next = v; break; }
            }
        } else {
            for (int i = g.length - 1; i >= 0; i--) {
                if (g[i] < cur) { next = g[i]; break; }
            }
        }
        return next;
    }
    /** Auto-fire cadence (tick/shot), PER TURRET — a weapon property, not a
     *  channel one (用户裁决 2026-10-06: 配对在就能调,编组不该卡它); the
     *  channel layer only owns the auto-fire SWITCH and the volley scope. */
    private int fireIntervalTicks = 20;
    /** Barrel length in blocks, |muzzleTip - barrelBase| captured at pair time
     *  (0 = unmarked). Feeds SolveChain.Params.barrelLength via RailgunLink. */
    private double barrelLengthBlocks;
    /** Game time of the last successful auto-fire (runtime). */
    private long lastFireTick = Long.MIN_VALUE / 2;
    /** Last fire-gate rejection note (runtime, surfaced on the GUI/状态). */
    private String lastFireNote;

    // ---------------- runtime (not persisted) ----------------
    private enum Cal { IDLE, YAW_PROBE, PITCH_PROBE, DONE }
    private Cal calState = Cal.IDLE;
    private int calTimer;
    private int restStableTicks;  // return-to-rest gate stability counter
    private double restLastYaw = Double.NaN, restLastPitch = Double.NaN; // measured-stillness samples
    private double restDrivePrevRel = Double.NaN; // return-to-rest divergence guard
    private int restDriveBadTicks;
    private double calT0, calB0;      // probe samples: reference twist / target register
    private int retryTimer;
    private double pendingLookX = Double.NaN, pendingLookY, pendingLookZ; // muzzle capture
    private BlockPos pendingBarrelBase, pendingMuzzleTip; // geometric reference (gun-structure coords)
    /** Persisted barrel-root position — also the ballistic origin (on-axis,
     *  beats the pitch bearing center which sits beside the barrel). */
    private BlockPos barrelBase;
    /** Assembly-relocation guards (runtime): pairing may record positions while
     *  the turret is NOT yet assembled; the calibration auto-assemble then cuts
     *  the turret head / barrel into child plots, translating every block —
     *  recorded coordinates go stale and resolve() fails forever (the 2026-09-18
     *  "接管要做两遍" bug: pair dead → no claims → native PD servo, exactly the
     *  old feel; re-pairing on the now-assembled turret recorded fresh coords).
     *  Relocation re-derives the translation from the plate position and only
     *  applies it when the candidate verifies (bearing/block actually there). */
    private boolean yawRelocated, pitchRelocated;
    private Double lastYawWritten, lastPitchWritten;
    /** Debug telemetry (2026-09-26 fixed-bias hunt): last measured/desired
     *  muzzle directions and per-axis errors, echoed by [turret-watch]. */
    private Vec3 lastCur, lastDes;
    private Double lastYawErr, lastPitchErr;
    /** Frozen-cmd hunt (2026-09-26, turret 2 walked to twist=90° then never
     *  wrote again despite err≈-90°): per-joint aimJoint exit path
     *  (0=axisNull 1=errNull 2=hold 3=write) + last commanded delta + tick/aim
     *  liveness counters, all echoed by [turret-watch] at 5s cadence. */
    private int yawPath = -1, pitchPath = -1;
    private double yawCmdDelta, pitchCmdDelta;
    private int tickCount, aimCount;
    /** Continuous no-target ticks, paces the 脱战收炮 delay. */
    private int noTargetTicks;
    private boolean yawSettled, pitchSettled; // dual-stable hold flags (runtime)
    private double aimErrorDeg = Double.NaN;
    /** Online SIGN verification state (per joint): after a native-servo write
     *  the twist response is checked against the commanded delta — two
     *  contradictory responses flip a stale SIGN (ground path only; under
     *  AxisLock the register→twist mapping is definitional). */
    private final SignVerify verifyYaw = new SignVerify(), verifyPitch = new SignVerify();
    private String status = "未配对";
    private final Runnable markDirty;
    private final java.util.function.Supplier<BlockPos> corePos;

    public SwivelTurretActuator(Runnable markDirty, java.util.function.Supplier<BlockPos> corePos) {
        this.markDirty = markDirty;
        this.corePos = corePos;
    }

    // ---------------- pairing ----------------

    public boolean isPaired() {
        return yawPos != null && pitchPos != null;
    }

    public BlockPos yawPos() { return yawPos; }
    public BlockPos pitchPos() { return pitchPos; }
    /** Core-relative yaw bearing offset (reassembly-invariant identity), null
     *  on legacy saves until migrated. Used by the core's duplicate guard. */
    public @Nullable BlockPos yawOffset() { return yawOffset; }
    /** Marked barrel-root position (on-axis ballistic origin), null when the
     *  pairing used the look fallback. */
    public @Nullable BlockPos barrelBase() { return barrelBase; }

    // ---- fe_railgun mount link (P3) ----
    /** Linked mounts in link order (read-only view; slot index = GUI chip index). */
    public List<Mount> mounts() { return java.util.Collections.unmodifiableList(mounts); }
    public int mountCount() { return mounts.size(); }
    /** Mount number at a position, -1 when not linked. */
    public int mountNoAt(BlockPos pos) {
        for (Mount m : mounts) if (m.pos().equals(pos)) return m.no();
        return -1;
    }
    /** Link a mount; the number is the LOWEST free slot in 1..MAX_MOUNTS —
     *  existing mounts are never renumbered (operator labels stay stable),
     *  and a re-linked mount reuses the gap instead of climbing forever
     *  (2026-10-06: the old monotonic counter made bind/unbind cycles issue
     *  ever-higher numbers).
     *  @return the issued number, or -1 when at MAX_MOUNTS */
    public int addMount(BlockPos pos) {
        if (mounts.size() >= MAX_MOUNTS) return -1;
        if (mountNoAt(pos) > 0) return mountNoAt(pos);
        int no = 1;
        while (mountNoAtNo(no) && no <= MAX_MOUNTS) no++;
        mounts.add(new Mount(no, pos.immutable()));
        lastFireNote = null;
        markDirty.run();
        return no;
    }
    private boolean mountNoAtNo(int no) {
        for (Mount m : mounts) if (m.no() == no) return true;
        return false;
    }
    /** Unlink a mount by number (session-less unlink / GUI chip click). */
    public boolean removeMount(int no) {
        boolean removed = mounts.removeIf(m -> m.no() == no);
        if (removed) {
            lastFireNote = null;
            markDirty.run();
        }
        return removed;
    }
    public long lastFireTick() { return lastFireTick; }
    public int fireIntervalTicks() { return fireIntervalTicks; }
    /** Pairing-time |muzzleTip - barrelBase| in blocks (0 = unmarked). */
    public double barrelLengthBlocks() { return barrelLengthBlocks; }

    /** Status-page stepper: cycle the cadence through FIRE_INTERVAL_GEARS. */
    public void cycleFireInterval(int dir) {
        fireIntervalTicks = cycleGear(fireIntervalTicks, dir);
        markDirty.run();
    }
    /** Last auto-fire rejection note, null after a successful shot / fresh pair. */
    public @Nullable String lastFireNote() { return lastFireNote; }

    /** Core fire gate: a shot left the mount. */
    public void noteFired(long gameTime) {
        lastFireTick = gameTime;
        lastFireNote = null;
    }

    /** Core fire gate: the mount rejected the shot (handle.fire() != FIRED). */
    public void noteFireRejected(String reason) {
        lastFireNote = "开火被拒: " + reason;
    }

    // ---- fire-arc limits (P2; GUI drag handles, degrees) ----
    public double yawMinDeg() { return yawMinDeg; }
    public double yawMaxDeg() { return yawMaxDeg; }
    public double pitchMinDeg() { return pitchMinDeg; }
    public double pitchMaxDeg() { return pitchMaxDeg; }
    /** Measured angles vs arc zeros, refreshed every actuator tick including
     * idle/return-to-rest. Pitch is up-positive for either mounting side.
     * NaN means the pose or zero is unavailable, never a stale last aim. */
    public double relYawDeg() { return lastRelYaw; }
    public double relPitchDeg() { return lastRelPitch; }
    /** False while the arc clamp is pinning a joint (target outside the fire
     *  arc) — the fire gate must read 禁射. */
    public boolean isInArc() { return !arcYawLimited && !arcPitchLimited; }

    /**
     * Distributed-lock reachability test (火力指挥层④, 2026-10-08): would
     * aiming at this world direction keep BOTH joints inside the fire-arc
     * limits? Mirrors the aimJoint twist-space clamp as a one-shot estimate —
     * current measured twist + the axis-projection error to the target,
     * compared against [min,max] with a half-degree edge tolerance. The
     * allocator only needs the gross answer (a broadside mount asked to shoot
     * backwards); the aim loop still does the exact clamping tick by tick.
     * False when unpaired/uncalibrated or the structure does not resolve.
     */
    public boolean isDirectionInArc(ServerLevel level, Vec3 desWorld) {
        if (!isPaired() || !calibrated || muzzleLocal == null) return false;
        Joints j = resolve(level);
        if (j == null || j.gun() == null || j.yawAttached() == null) return false;
        Vec3 cur = poseQuat(j.gun()).rotate(muzzleLocal).normalized();
        Vec3 des = desWorld.normalized();
        if (cur == null || des == null) return false;
        yawAxisLocalCached = axisOf(level, yawPos);
        pitchAxisLocalCached = axisOf(level, pitchPos);
        updateJointAngles(j);
        return jointInArc(j, cur, des, true) && jointInArc(j, cur, des, false);
    }

    private boolean jointInArc(Joints j, Vec3 cur, Vec3 des, boolean yawJoint) {
        Vec3 axisW = poseQuat(yawJoint ? j.yawContainer() : j.yawAttached())
                .rotate(yawJoint ? yawAxisLocalCached : pitchAxisLocalCached).normalized();
        if (axisW == null) return true; // no axis info: don't veto
        Double err = GimbalMath.axisErrorDeg(axisW, cur, des);
        if (err == null) return true; // singular (target along the axis): this joint can't help, don't veto
        double measured = referenceTwist(j, yawJoint);
        double zero = yawJoint ? yawZeroTwist : pitchZeroTwist;
        if (Double.isNaN(zero)) zero = measured;
        double required = (yawJoint ? 1 : pitchUpSense) * (GimbalMath.wrapDeg(measured - zero) + err);
        double limMin = yawJoint ? yawMinDeg : pitchMinDeg;
        double limMax = yawJoint ? yawMaxDeg : pitchMaxDeg;
        return required >= limMin - 0.5 && required <= limMax + 0.5;
    }

    /** GUI drag handle: param 0=yaw min, 1=yaw max, 2=pitch min, 3=pitch max;
     *  absolute set, clamped against the opposite bound. */
    public void setArc(int param, double valueDeg) {
        switch (param) {
            case 0 -> yawMinDeg = Math.max(-180.0, Math.min(yawMaxDeg, valueDeg));
            case 1 -> yawMaxDeg = Math.max(yawMinDeg, Math.min(180.0, valueDeg));
            case 2 -> pitchMinDeg = Math.max(-90.0, Math.min(pitchMaxDeg, valueDeg));
            case 3 -> pitchMaxDeg = Math.max(pitchMinDeg, Math.min(90.0, valueDeg));
            default -> { return; }
        }
        markDirty.run();
    }

    /** Child sub-level UUIDs of the paired bearings = own turret substructures.
     *  The radar must never list them as lockable ships (2026-09-18 AxisLock
     *  shakeout: the scanner offered the yaw/pitch bearing children as
     *  "[船]ship:xxxxxx" targets 5m away — locking one is the aim loop chasing
     *  its own gun barrel). Empty set when unpaired/unassembled. */
    public java.util.Set<java.util.UUID> ownChildSubLevelIds(Level level) {
        java.util.Set<java.util.UUID> out = new java.util.HashSet<>();
        if (yawPos != null) {
            SwivelBearingBlockEntity be = bearingAt(level, yawPos);
            if (be != null && be.getSubLevelID() != null) out.add(be.getSubLevelID());
        }
        if (pitchPos != null) {
            SwivelBearingBlockEntity be = bearingAt(level, pitchPos);
            if (be != null && be.getSubLevelID() != null) out.add(be.getSubLevelID());
        }
        return out;
    }

    /**
     * Complete a pairing session. The muzzle reference is resolved lazily once
     * the gun structure exists: a marked barrel base+tip pair wins (geometric,
     * exact — the pitch bearing is not on the barrel axis), else the player's
     * look direction (world) is used. Calibration (re)starts on the next ticks.
     */
    public void pair(BlockPos yaw, BlockPos pitch, net.minecraft.world.phys.Vec3 lookWorld,
                     @Nullable BlockPos barrelBase, @Nullable BlockPos muzzleTip,
                     List<BlockPos> initialMounts) {
        yawPos = yaw;
        pitchPos = pitch;
        yawOffset = yaw.subtract(corePos.get()); // reassembly-invariant identity
        calibrated = false;
        muzzleLocal = null;
        pendingLookX = lookWorld.x;
        pendingLookY = lookWorld.y;
        pendingLookZ = lookWorld.z;
        pendingBarrelBase = barrelBase;
        pendingMuzzleTip = muzzleTip;
        if (barrelBase != null) {
            this.barrelBase = barrelBase; // on-axis ballistic origin, persisted
        }
        // P3 mount link: a fresh pairing starts SAFE (no fire note);
        // session-linked mounts are numbered 1..n in click order
        mounts.clear();
        for (BlockPos mp : initialMounts) {
            addMount(mp);
        }
        lastFireNote = null;
        lastFireTick = Long.MIN_VALUE / 2;
        barrelLengthBlocks = barrelBase != null && muzzleTip != null
                ? Math.sqrt(barrelBase.distSqr(muzzleTip)) : 0;
        calState = Cal.IDLE;
        retryTimer = 0;
        restStableTicks = 0;
        yawRelocated = pitchRelocated = false;
        lastYawWritten = lastPitchWritten = null;
        yawSettled = pitchSettled = false;
        verifyYaw.reset();
        verifyPitch.reset();
        // a fresh pairing is a fresh turret: arc limits back to unrestricted,
        // arc centers re-sampled by the upcoming calibration
        yawMinDeg = -180.0;
        yawMaxDeg = 180.0;
        pitchMinDeg = -90.0;
        pitchMaxDeg = 90.0;
        yawZeroTwist = pitchZeroTwist = Double.NaN;
        arcYawLimited = arcPitchLimited = false;
        status = "已配对，待校准";
        markDirty.run();
    }

    public void unpair(@Nullable Level level) {
        // force fresh AxisLock captures: a future pairing on these bearings
        // re-measures its zero from the live pose behind the settle gate
        if (level instanceof ServerLevel sl) {
            if (yawPos != null) SwivelBearingAxisLock.resetBaseline(sl, yawPos);
            if (pitchPos != null) SwivelBearingAxisLock.resetBaseline(sl, pitchPos);
        }
        yawPos = pitchPos = null;
        yawOffset = null;
        calibrated = false;
        muzzleLocal = null;
        pendingMuzzleTip = pendingBarrelBase = null;
        barrelBase = null;
        mounts.clear(); // P3: drop the gun-mount links with the pairing
        barrelLengthBlocks = 0;
        lastFireNote = null;
        calState = Cal.IDLE;
        restStableTicks = 0;
        noTargetTicks = 0;
        aimErrorDeg = Double.NaN;
        status = "未配对";
        markDirty.run();
    }

    /** Manual recalibration (GUI button): re-run the SIGN probes and force a
     *  fresh AxisLock zero capture on both bearings. Pairing data (positions,
     *  muzzle reference) is kept — only the measured conventions are redone.
     *  Safe while locked: the calibration first drives both joints back to the
     *  recorded arc zero (return-to-rest gate) before re-sampling it. */
    public void startRecalibration(@Nullable Level level) {
        if (!isPaired()) return;
        calibrated = false;
        calState = Cal.IDLE;
        retryTimer = 0;
        restStableTicks = 0;
        yawSettled = pitchSettled = false;
        lastYawWritten = lastPitchWritten = null;
        verifyYaw.reset();
        verifyPitch.reset();
        if (level instanceof ServerLevel sl) {
            SwivelBearingAxisLock.resetBaseline(sl, yawPos);
            SwivelBearingAxisLock.resetBaseline(sl, pitchPos);
        }
        status = "已配对，待校准";
        markDirty.run();
    }

    // ---------------- NBT (merge-style: caller applies contains-guards) ----------------

    public void save(CompoundTag tag) {
        if (yawPos != null) tag.putLong("turretYawPos", yawPos.asLong());
        if (pitchPos != null) tag.putLong("turretPitchPos", pitchPos.asLong());
        if (yawOffset != null) tag.putLong("turretYawOff", yawOffset.asLong());
        if (barrelBase != null) tag.putLong("turretBarrelBase", barrelBase.asLong());
        if (!mounts.isEmpty()) {
            net.minecraft.nbt.ListTag ml = new net.minecraft.nbt.ListTag();
            for (Mount m : mounts) {
                CompoundTag mt = new CompoundTag();
                mt.putInt("no", m.no());
                mt.putLong("pos", m.pos().asLong());
                ml.add(mt);
            }
            tag.put("turretMounts", ml);
        }
        tag.putInt("turretFireInterval", fireIntervalTicks);
        tag.putDouble("turretBarrelLen", barrelLengthBlocks);
        tag.putInt("turretSignYaw", signYaw);
        tag.putInt("turretSignPitch", signPitch);
        tag.putBoolean("turretCalibrated", calibrated);
        tag.putDouble("turretArcYawMin", yawMinDeg);
        tag.putDouble("turretArcYawMax", yawMaxDeg);
        tag.putDouble("turretArcPitchMin", pitchMinDeg);
        tag.putDouble("turretArcPitchMax", pitchMaxDeg);
        if (!Double.isNaN(yawZeroTwist)) tag.putDouble("turretYawZero", yawZeroTwist);
        if (!Double.isNaN(pitchZeroTwist)) tag.putDouble("turretPitchZero", pitchZeroTwist);
        if (muzzleLocal != null) {
            tag.putDouble("turretMuzzleX", muzzleLocal.x());
            tag.putDouble("turretMuzzleY", muzzleLocal.y());
            tag.putDouble("turretMuzzleZ", muzzleLocal.z());
        }
    }

    public void load(CompoundTag tag) {
        if (tag.contains("turretYawPos")) yawPos = BlockPos.of(tag.getLong("turretYawPos"));
        if (tag.contains("turretPitchPos")) pitchPos = BlockPos.of(tag.getLong("turretPitchPos"));
        if (tag.contains("turretYawOff")) yawOffset = BlockPos.of(tag.getLong("turretYawOff"));
        if (tag.contains("turretBarrelBase")) barrelBase = BlockPos.of(tag.getLong("turretBarrelBase"));
        if (tag.contains("turretMounts")) {
            mounts.clear();
            for (net.minecraft.nbt.Tag t : tag.getList("turretMounts", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                CompoundTag mt = (CompoundTag) t;
                mounts.add(new Mount(mt.getInt("no"), BlockPos.of(mt.getLong("pos"))));
            }
        } else if (tag.contains("turretMountPos")) {
            // migrate the single-mount P3 save format: the old mount becomes no.1
            mounts.clear();
            mounts.add(new Mount(1, BlockPos.of(tag.getLong("turretMountPos"))));
        }
        // turretAutoFire (pre-channel saves) retired: the auto-fire switch
        // moved to the core's channel layer (武器频道); the cadence stayed
        // per-turret (turretFireInterval below)
        if (tag.contains("turretFireInterval")) {
            fireIntervalTicks = Math.max(FIRE_INTERVAL_GEARS[0],
                    Math.min(FIRE_INTERVAL_GEARS[FIRE_INTERVAL_GEARS.length - 1],
                            tag.getInt("turretFireInterval")));
        }
        if (tag.contains("turretBarrelLen")) barrelLengthBlocks = tag.getDouble("turretBarrelLen");
        if (tag.contains("turretSignYaw")) signYaw = tag.getInt("turretSignYaw") >= 0 ? 1 : -1;
        if (tag.contains("turretSignPitch")) signPitch = tag.getInt("turretSignPitch") >= 0 ? 1 : -1;
        if (tag.contains("turretCalibrated")) calibrated = tag.getBoolean("turretCalibrated");
        if (tag.contains("turretArcYawMin")) {
            yawMinDeg = tag.getDouble("turretArcYawMin");
            yawMaxDeg = tag.getDouble("turretArcYawMax");
        } else if (tag.contains("turretArcYawHalf")) {
            // migrate the short-lived symmetric-half model (2a7e29c)
            double h = tag.getDouble("turretArcYawHalf");
            yawMinDeg = -h;
            yawMaxDeg = h;
        }
        if (tag.contains("turretArcPitchMin")) pitchMinDeg = tag.getDouble("turretArcPitchMin");
        if (tag.contains("turretArcPitchMax")) pitchMaxDeg = tag.getDouble("turretArcPitchMax");
        if (tag.contains("turretYawZero")) yawZeroTwist = tag.getDouble("turretYawZero");
        if (tag.contains("turretPitchZero")) pitchZeroTwist = tag.getDouble("turretPitchZero");
        if (tag.contains("turretMuzzleX")) {
            muzzleLocal = new Vec3(tag.getDouble("turretMuzzleX"),
                    tag.getDouble("turretMuzzleY"), tag.getDouble("turretMuzzleZ"));
        }
        if (isPaired()) status = calibrated ? "就绪" : "已配对，待校准";
    }

    // ---------------- structure resolution ----------------

    private static SwivelBearingBlockEntity bearingAt(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof SwivelBearingBlockEntity be ? be : null;
    }

    private static SwivelBearingAccessorMixin acc(SwivelBearingBlockEntity be) {
        return (SwivelBearingAccessorMixin) be;
    }

    private static Quat poseQuat(@Nullable ServerSubLevel sub) {
        if (sub == null) return Quat.IDENTITY;
        var o = sub.logicalPose().orientation();
        return new Quat(o.w(), o.x(), o.y(), o.z());
    }

    private static Vec3 axisOf(Level level, BlockPos pos) {
        Direction d = level.getBlockState(pos).getValue(BlockStateProperties.FACING);
        return new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
    }

    private record Joints(SwivelBearingBlockEntity yawBe, SwivelBearingBlockEntity pitchBe,
                          ServerSubLevel yawContainer, ServerSubLevel yawAttached, ServerSubLevel gun) {}

    private @Nullable Joints resolve(ServerLevel level) {
        SwivelBearingBlockEntity yawBe = bearingAt(level, yawPos);
        SwivelBearingBlockEntity pitchBe = bearingAt(level, pitchPos);
        if (yawBe == null || pitchBe == null) return null;
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        ServerSubLevel yawContainer =
                Sable.HELPER.getContaining(level, yawPos) instanceof ServerSubLevel s ? s : null;
        ServerSubLevel yawAttached = null, gun = null;
        if (container != null) {
            if (yawBe.getSubLevelID() != null
                    && container.getSubLevel(yawBe.getSubLevelID()) instanceof ServerSubLevel a) {
                yawAttached = a;
            }
            if (pitchBe.getSubLevelID() != null
                    && container.getSubLevel(pitchBe.getSubLevelID()) instanceof ServerSubLevel g) {
                gun = g;
            }
        }
        return new Joints(yawBe, pitchBe, yawContainer, yawAttached, gun);
    }

    /** Handoff 坑2: after each target-angle write, wake both physics islands or a
     *  sleeping island never refreshes the servo goal (the "turns only when blocks
     *  change" bug). assemble/disassemble wake internally; writes must not. */
    private static void wakeAfterWrite(ServerLevel level, SwivelBearingBlockEntity bearing) {
        if (!(SubLevelContainer.getContainer(level) instanceof ServerSubLevelContainer sc)) return;
        var pipeline = sc.physicsSystem().getPipeline();
        if (Sable.HELPER.getContaining(bearing) instanceof ServerSubLevel s) pipeline.wakeUp(s);
        if (bearing.getSubLevelID() != null
                && sc.getSubLevel(bearing.getSubLevelID()) instanceof ServerSubLevel s2) {
            pipeline.wakeUp(s2);
        }
    }

    private static void writeAngle(ServerLevel level, SwivelBearingBlockEntity bearing, double deg) {
        acc(bearing).bridgecore$setTargetAngleDegrees(GimbalMath.norm360(deg));
        // the official write path recomputes the PD coefficients per target —
        // our accessor bypasses it (create-fire-control does the same pairing)
        bearing.updateServoCoefficients();
        bearing.setChanged();
        wakeAfterWrite(level, bearing);
    }

    /** Core-relative reanchor (2026-09-26): when the stored yawPos stops
     *  resolving (ship reassembled / projected to a fresh yard plot — a pure
     *  translation that moves core and yaw stator together), try core+offset.
     *  On adoption, re-arm the relocation chain so the pitch bearing and
     *  barrel references (child plots, translated DIFFERENTLY) re-derive from
     *  the plate. Also lazily migrates legacy saves (offset unknown): computed
     *  on first successful resolve, requiring the assembled state so a ghost
     *  bearing at a stale plot position never cements a wrong offset. */
    private void reanchorToCore(Level level) {
        if (yawOffset == null) {
            SwivelBearingBlockEntity yawBe = bearingAt(level, yawPos);
            if (yawBe != null && yawBe.isAssembled()) {
                yawOffset = yawPos.subtract(corePos.get());
                markDirty.run();
            }
            return;
        }
        if (bearingAt(level, yawPos) != null) return;
        BlockPos candidate = corePos.get().offset(yawOffset);
        if (bearingAt(level, candidate) == null) return;
        yawPos = candidate.immutable();
        yawRelocated = pitchRelocated = false; // child plots moved too
        markDirty.run();
    }

    /** Assembly relocation: pairing may run on an UNASSEMBLED turret, then the
     *  calibration auto-assemble cuts the turret head (incl. the pitch bearing
     *  block) and later the barrel into child plots — every recorded coordinate
     *  translates. Re-derive the translation from the plate position and adopt
     *  only verified candidates (bearing/block actually found there), so pairing
     *  on an already-assembled turret is untouched. Runs before resolve(). */
    private void relocateAfterAssembly(Level level) {
        if (!yawRelocated) {
            SwivelBearingBlockEntity yawBe = bearingAt(level, yawPos);
            if (yawBe != null && yawBe.isAssembled() && yawBe.getPlatePos() != null) {
                Direction f = yawBe.getBlockState().getValue(BlockStateProperties.FACING);
                if (tryAdoptPitch(level, yawBe.getPlatePos().subtract(yawPos.relative(f)))
                        || tryAdoptPitch(level, yawBe.getPlatePos().subtract(yawPos))
                        || bearingAt(level, pitchPos) != null) {
                    yawRelocated = true;
                }
            }
        }
        if (yawRelocated && !pitchRelocated) {
            SwivelBearingBlockEntity pitchBe = bearingAt(level, pitchPos);
            if (pitchBe != null && pitchBe.isAssembled() && pitchBe.getPlatePos() != null) {
                Direction f = pitchBe.getBlockState().getValue(BlockStateProperties.FACING);
                shiftBarrelReference(level, pitchBe, pitchBe.getPlatePos().subtract(pitchPos.relative(f)));
                shiftBarrelReference(level, pitchBe, pitchBe.getPlatePos().subtract(pitchPos));
                pitchRelocated = true; // both candidates tried; verified ones adopted
            }
        }
    }

    /** Adopt the yaw-assembly translation if a swivel bearing actually sits at
     *  pitchPos+delta. The yaw cut moves the WHOLE turret head rigidly, so the
     *  barrel reference points translate by the same verified delta. */
    private boolean tryAdoptPitch(Level level, BlockPos delta) {
        if (delta.equals(BlockPos.ZERO)) return true;
        BlockPos candidate = pitchPos.offset(delta);
        if (bearingAt(level, candidate) == null) return false;
        pitchPos = candidate.immutable();
        if (barrelBase != null) barrelBase = barrelBase.offset(delta);
        shiftMounts(delta);
        if (pendingBarrelBase != null) pendingBarrelBase = pendingBarrelBase.offset(delta);
        if (pendingMuzzleTip != null) pendingMuzzleTip = pendingMuzzleTip.offset(delta);
        markDirty.run();
        return true;
    }

    /** Shift the barrel reference points into the gun plot after pitch assembly.
     *  Verified against the gun sub-level; a no-op when using the look fallback. */
    private void shiftBarrelReference(Level level, SwivelBearingBlockEntity pitchBe, BlockPos delta) {
        if (delta.equals(BlockPos.ZERO)) return;
        java.util.UUID gunId = pitchBe.getSubLevelID();
        if (gunId == null) return;
        if (barrelBase != null) {
            // already in gun-plot coords (paired on an assembled turret)? then no shift
            if (!(Sable.HELPER.getContaining(level, barrelBase) instanceof ServerSubLevel cur
                    && gunId.equals(cur.getUniqueId()))) {
                BlockPos candidate = barrelBase.offset(delta);
                if (Sable.HELPER.getContaining(level, candidate) instanceof ServerSubLevel s
                        && gunId.equals(s.getUniqueId())) {
                    barrelBase = candidate.immutable();
                    if (pendingBarrelBase != null) pendingBarrelBase = pendingBarrelBase.offset(delta);
                    if (pendingMuzzleTip != null) pendingMuzzleTip = pendingMuzzleTip.offset(delta);
                    markDirty.run();
                }
            }
        }
        // P3: the mount blocks typically ride the turret head — shift each by
        // the same verified delta. A mount that stayed on the yaw structure
        // fails the gun-plot containment check and keeps its (already
        // yaw-shifted) pos.
        boolean shifted = false;
        for (int i = 0; i < mounts.size(); i++) {
            Mount m = mounts.get(i);
            if (Sable.HELPER.getContaining(level, m.pos()) instanceof ServerSubLevel cur
                    && gunId.equals(cur.getUniqueId())) continue; // already in gun-plot coords
            BlockPos candidate = m.pos().offset(delta);
            if (Sable.HELPER.getContaining(level, candidate) instanceof ServerSubLevel s
                    && gunId.equals(s.getUniqueId())) {
                mounts.set(i, new Mount(m.no(), candidate.immutable()));
                shifted = true;
            }
        }
        if (shifted) {
            markDirty.run();
        }
    }

    /** Rigidly translate every linked mount (yaw-assembly cut moves the whole
     *  turret head, mounts included). */
    private void shiftMounts(BlockPos delta) {
        for (int i = 0; i < mounts.size(); i++) {
            Mount m = mounts.get(i);
            mounts.set(i, new Mount(m.no(), m.pos().offset(delta).immutable()));
        }
    }

    // ---------------- per-tick drive (called by the core BE) ----------------

    /**
     * @param desWorld desired muzzle direction in world (unit), or null to hold
     *                 the current joints (no solution / out of range)
     */
    public void tick(ServerLevel level, @Nullable Vec3 desWorld) {
        lastRelYaw = lastRelPitch = Double.NaN;
        if (!isPaired()) {
            status = "未配对";
            return;
        }
        tickCount++;
        reanchorToCore(level);
        relocateAfterAssembly(level);
        Joints j = resolve(level);
        if (j == null) {
            status = "轴承丢失(方块被拆?)";
            aimErrorDeg = Double.NaN;
            return;
        }
        yawAxisLocalCached = axisOf(level, yawPos);
        pitchAxisLocalCached = axisOf(level, pitchPos);
        updateJointAngles(j);
        // AxisLock heartbeat (kinematic takeover of shipborne bearings): claim
        // every tick while paired — during calibration too, with the provisional
        // sign (+1); the probe self-identifies the true SIGN under takeover.
        // Ground bearings: claim() no-ops, native servo keeps driving.
        SwivelBearingAxisLock.claim(level, yawPos, signYaw);
        SwivelBearingAxisLock.claim(level, pitchPos, signPitch);
        if (!calibrated) {
            tickCalibration(level, j);
            return;
        }
        // lazy muzzle capture is guaranteed done by calibration; guard anyway
        if (muzzleLocal == null || j.gun() == null || j.yawAttached() == null) {
            status = "炮塔结构未就绪(未装配?)";
            aimErrorDeg = Double.NaN;
            return;
        }
        if (desWorld == null) {
            aimErrorDeg = Double.NaN;
            // 脱战延时收炮 (user ruling 2026-10-06: 5s): after the grace
            // window, drive both joints back to the recorded arc zero through
            // the calibration rest mechanism; a new target cancels instantly
            if (++noTargetTicks >= REST_RETURN_DELAY_TICKS) {
                status = driveToRest(level, j) ? "就绪(无目标)" : "脱战回位中...";
            } else {
                status = "就绪(无目标)";
            }
            return;
        }
        noTargetTicks = 0;
        aim(level, j, desWorld);
    }

    private void aim(ServerLevel level, Joints j, Vec3 desWorld) {
        aimCount++;
        Quat qGun = poseQuat(j.gun());
        Vec3 cur = qGun.rotate(muzzleLocal).normalized();
        Vec3 des = desWorld.normalized();
        if (cur == null || des == null) {
            status = "解算奇异,本 tick 跳过";
            return;
        }
        // fresh axes for referenceTwist (blockstates may have changed since pairing)
        yawAxisLocalCached = axisOf(level, yawPos);
        pitchAxisLocalCached = axisOf(level, pitchPos);

        // platform-dependent profile: gentler loop on a moving base (see
        // SHIP_MAX_STEP_DEG) — the yaw bearing's container being a sub-level
        // means the turret is ship-mounted. Under AxisLock kinematic takeover
        // there is no physics coupling left for the soft profile to damp, so
        // ship turrets get the crisp ground profile back.
        boolean onShip = j.yawContainer() != null
                && !(SwivelBearingAxisLock.isActive(level, yawPos)
                        && SwivelBearingAxisLock.isActive(level, pitchPos));
        double maxStep = onShip ? SHIP_MAX_STEP_DEG : MAX_STEP_DEG;
        double gain = onShip ? SHIP_AIM_GAIN : 1.0;
        double hold = onShip ? SHIP_AIM_HOLD_DEG : AIM_HOLD_DEG;
        double release = onShip ? SHIP_AIM_RELEASE_DEG : AIM_RELEASE_DEG;

        // yaw joint: axis fixed in the yaw bearing's container frame
        Vec3 yawAxisW = poseQuat(j.yawContainer()).rotate(yawAxisLocalCached).normalized();
        yawSettled = aimJoint(level, j.yawBe(), yawAxisW, cur, des,
                signYaw, referenceTwist(j, true), yawSettled, true,
                maxStep, gain, hold, release);

        // pitch joint: axis fixed in the yaw structure's frame
        Vec3 pitchAxisW = poseQuat(j.yawAttached()).rotate(pitchAxisLocalCached).normalized();
        pitchSettled = aimJoint(level, j.pitchBe(), pitchAxisW, cur, des,
                signPitch, referenceTwist(j, false), pitchSettled, false,
                maxStep, gain, hold, release);

        aimErrorDeg = GimbalMath.angleBetweenDeg(cur, des);
        lastCur = cur;
        lastDes = des;
        lastYawErr = GimbalMath.axisErrorDeg(yawAxisW, cur, des);
        lastPitchErr = GimbalMath.axisErrorDeg(pitchAxisW, cur, des);
        boolean limited = arcYawLimited || arcPitchLimited;
        status = String.format("跟踪中 误差%.1f°%s", aimErrorDeg,
                limited ? " [射界外·禁射]" : aimErrorDeg < 1.5 ? " [可开火]" : "");
    }

    /**
     * One joint per tick: dual-threshold hysteresis on top of the REGISTER
     * READBACK (the readback is the zero — no persisted offset can go stale),
     * with the per-turret fire-arc clamp in twist space (P2, 压边+禁射):
     * the desired twist-space delta (err·gain — the register sign cancels,
     * Δtwist = sign·Δregister) is clamped into [limMin, limMax] relative to
     * the calibration-time rest twist; a pinned joint reports 禁射 via
     * arcYawLimited/arcPitchLimited.
     *
     * @return the new settled state
     */
    private boolean aimJoint(ServerLevel level, SwivelBearingBlockEntity bearing, @Nullable Vec3 axisWorld,
                             Vec3 cur, Vec3 des, int sign, double measuredTwist,
                             boolean settled, boolean yawJoint,
                             double maxStepDeg, double gain, double holdDeg, double releaseDeg) {
        if (axisWorld == null) { recordPath(yawJoint, 0, 0); return settled; }
        Double err = GimbalMath.axisErrorDeg(axisWorld, cur, des);
        if (err == null) { recordPath(yawJoint, 1, 0); return settled; } // singular (muzzle parallel to axis): skip, same as the Lua
        SignVerify sv = yawJoint ? verifyYaw : verifyPitch;
        evalSignVerify(level, bearing, measuredTwist, sv, yawJoint);

        // fire-arc clamp (twist space)
        double zero = yawJoint ? yawZeroTwist : pitchZeroTwist;
        if (Double.isNaN(zero)) { // legacy save: adopt the first measured twist as the arc center
            zero = measuredTwist;
            if (yawJoint) yawZeroTwist = zero; else pitchZeroTwist = zero;
            markDirty.run();
        }
        double limMin = yawJoint ? yawMinDeg : pitchMinDeg;
        double limMax = yawJoint ? yawMaxDeg : pitchMaxDeg;
        double absErr = Math.abs(err);
        double rel = GimbalMath.wrapDeg(measuredTwist - zero);
        double desiredDelta = absErr <= holdDeg ? 0.0 : err * gain;
        double clampedDelta = GimbalMath.clampJointDelta(rel, desiredDelta, limMin, limMax,
                yawJoint ? 1 : pitchUpSense);
        boolean limited = Math.abs(clampedDelta - desiredDelta) > 1e-6;
        if (yawJoint) arcYawLimited = limited; else arcPitchLimited = limited;

        if (settled && absErr <= releaseDeg && !limited) { recordPath(yawJoint, 2, 0); return true; } // hold
        if (Math.abs(clampedDelta) <= 1e-9) {
            // hold band, or pinned at the arc edge: no write (the readback
            // base already equals the physical target — nothing to sync)
            recordPath(yawJoint, 2, 0);
            return true;
        }
        double base = acc(bearing).bridgecore$getTargetAngleDegrees();
        double commanded = sign * clampedDelta;
        double w = writeRateLimited(level, bearing, base + commanded,
                yawJoint ? lastYawWritten : lastPitchWritten, maxStepDeg);
        if (yawJoint) lastYawWritten = w; else lastPitchWritten = w;
        armSignVerify(level, bearing, sign, measuredTwist, GimbalMath.wrapDeg(w - base), sv);
        recordPath(yawJoint, 3, commanded);
        return false;
    }

    private void recordPath(boolean yawJoint, int path, double cmdDelta) {
        if (yawJoint) { yawPath = path; yawCmdDelta = cmdDelta; }
        else { pitchPath = path; pitchCmdDelta = cmdDelta; }
    }

    // ---------------- online SIGN verification (native-servo path only) ----------------

    /** One in-flight write-response check per joint. */
    private static final class SignVerify {
        double baseTwist;
        double expected; // commanded twist delta (sign·applied register delta)
        int timer;
        int strikes;
        boolean flipped; // one auto-flip per session; further anomalies need re-pairing

        void reset() {
            timer = 0;
            strikes = 0;
            flipped = false;
        }
    }

    /** Progress an armed check. Runs every aim tick (even while holding) so a
     *  pending evaluation never stalls behind the hysteresis. */
    private void evalSignVerify(ServerLevel level, SwivelBearingBlockEntity bearing,
                                double measuredTwist, SignVerify sv, boolean yawJoint) {
        if (sv.timer <= 0 || --sv.timer > 0) return;
        double dT = GimbalMath.wrapDeg(measuredTwist - sv.baseTwist);
        if (Math.abs(dT) < 1.5) return; // no meaningful response — obstruction/lag, not evidence
        if (dT * sv.expected > 0) {
            sv.strikes = 0; // good response clears the count
            return;
        }
        sv.strikes++;
        if (sv.strikes >= 2 && !sv.flipped) {
            sv.flipped = true;
            if (yawJoint) signYaw = -signYaw; else signPitch = -signPitch;
            yawSettled = pitchSettled = false;
            markDirty.run();
            com.bridgecore.BridgeCoreMod.LOGGER.warn(
                    "[firecontrol] {}轴 SIGN 在线校验连续两次反向响应, 已自动翻转 (轴承@{}) — 如仍异常请重新配对",
                    yawJoint ? "偏航" : "俯仰", bearing.getBlockPos().toShortString());
        }
    }

    /** Arm a check after a real write. Skipped under AxisLock takeover: there
     *  the register→twist mapping is our own definition and cannot disagree. */
    private void armSignVerify(ServerLevel level, SwivelBearingBlockEntity bearing, int sign,
                               double measuredTwist, double appliedDelta, SignVerify sv) {
        if (sv.timer > 0 || sv.flipped || Math.abs(appliedDelta) < 2.0) return;
        if (SwivelBearingAxisLock.isActive(level, bearing.getBlockPos())) return;
        sv.baseTwist = measuredTwist;
        sv.expected = sign * appliedDelta;
        sv.timer = 15; // native PD needs a few ticks to answer a ≤3° step
    }

    private double writeRateLimited(ServerLevel level, SwivelBearingBlockEntity bearing,
                                    double desiredDeg, Double lastWritten, double maxStepDeg) {
        double d = GimbalMath.norm360(desiredDeg);
        if (lastWritten != null) {
            double delta = GimbalMath.wrapDeg(d - lastWritten);
            delta = Math.max(-maxStepDeg, Math.min(maxStepDeg, delta));
            d = GimbalMath.norm360(lastWritten + delta);
        }
        writeAngle(level, bearing, d);
        return d;
    }

    // ---------------- calibration: SIGN auto-identification per joint ----------------

    private void tickCalibration(ServerLevel level, Joints j) {
        aimErrorDeg = Double.NaN;
        if (retryTimer > 0) {
            retryTimer--;
            status = "校准待重试(" + retryTimer / 20 + "s)";
            return;
        }
        // preconditions: assembled + locked (always_locked discipline)
        if (!ensureAssembledAndLocked(level, j)) return;
        if (j.yawAttached() == null || j.gun() == null) {
            status = "等待炮塔子结构...";
            return;
        }
        // lazy muzzle capture: geometric (barrel base+tip pair) preferred,
        // player look as fallback, mapped into the gun yard frame
        if (muzzleLocal == null) {
            Vec3 m = null;
            if (pendingBarrelBase != null && pendingMuzzleTip != null) {
                // (tip - base) in the gun structure's own coordinates IS the
                // barrel axis — no pose math, and independent of where the
                // pitch bearing sits (it is NOT on the barrel axis: one block
                // beside the barrel or behind a fixed cannon mount)
                if (Sable.HELPER.getContaining(level, pendingBarrelBase) == j.gun()
                        && Sable.HELPER.getContaining(level, pendingMuzzleTip) == j.gun()
                        && !pendingBarrelBase.equals(pendingMuzzleTip)) {
                    m = new Vec3(
                            pendingMuzzleTip.getX() - pendingBarrelBase.getX(),
                            pendingMuzzleTip.getY() - pendingBarrelBase.getY(),
                            pendingMuzzleTip.getZ() - pendingBarrelBase.getZ()).normalized();
                }
                pendingBarrelBase = pendingMuzzleTip = null;
            }
            if (m == null && !Double.isNaN(pendingLookX)) {
                Vec3 look = new Vec3(pendingLookX, pendingLookY, pendingLookZ);
                m = poseQuat(j.gun()).negate().rotate(look).normalized();
            }
            muzzleLocal = m != null ? m : new Vec3(0, 0, 1); // re-pair after reload without a saved muzzle
            pendingLookX = Double.NaN;
            markDirty.run();
        }

        switch (calState) {
            case IDLE -> {
                // return-to-rest + measured-stillness gate (2026-09-27):
                // recalibrating while LOCKED used to sample the arc zero at
                // the AIM pose (arc sat deflected, limits "randomly failed"),
                // and a blind 5-tick wait sampled while the structure was
                // still settling from assembly/claim (crooked rest). Drive
                // both joints back to the recorded arc zero first (fresh
                // pairing: pitch drives to twist 0 = assembly level, yaw just
                // waits), then require BOTH twists measured-still for a few
                // ticks before sampling.
                if (!driveToRest(level, j)) {
                    restStableTicks = 0;
                    status = "校准中(回正)...";
                    return;
                }
                double ty = referenceTwist(j, true);
                double tp = referenceTwist(j, false);
                if (!Double.isNaN(restLastYaw)
                        && Math.abs(GimbalMath.wrapDeg(ty - restLastYaw)) < 0.05
                        && Math.abs(GimbalMath.wrapDeg(tp - restLastPitch)) < 0.05) {
                    restStableTicks++;
                } else {
                    restStableTicks = 0;
                }
                restLastYaw = ty;
                restLastPitch = tp;
                if (restStableTicks < CAL_REST_SETTLE_TICKS) {
                    status = "校准中(稳定)...";
                    return;
                }
                restStableTicks = 0;
                restLastYaw = restLastPitch = Double.NaN;
                // 第三批 step3 (§2.31 专门建立流程): the return-to-rest gate
                // just passed at a user-triggered recalibration — a legacy
                // save gets its trusted zero from THIS confirmed rest pose.
                // Assembly-captured zeros are never overwritten.
                AxisLockAssemblyZero.establishIfAbsent(j.yawBe(), level);
                AxisLockAssemblyZero.establishIfAbsent(j.pitchBe(), level);
                calT0 = ty;
                calB0 = acc(j.yawBe()).bridgecore$getTargetAngleDegrees();
                // arc centers = the rest pose AFTER the return-to-rest gate
                yawZeroTwist = ty;
                pitchZeroTwist = tp;
                writeAngle(level, j.yawBe(), calB0 + CAL_STEP_DEG);
                calTimer = CAL_SETTLE_TICKS;
                calState = Cal.YAW_PROBE;
                status = "校准中(偏航轴)...";
            }
            case YAW_PROBE -> {
                if (--calTimer > 0) return;
                double dT = GimbalMath.wrapDeg(referenceTwist(j, true) - calT0);
                writeAngle(level, j.yawBe(), calB0); // restore
                if (Math.abs(dT) < CAL_MIN_RESPONSE_DEG) {
                    failCalibration("偏航", dT);
                    return;
                }
                signYaw = dT > 0 ? 1 : -1;
                calT0 = referenceTwist(j, false);
                calB0 = acc(j.pitchBe()).bridgecore$getTargetAngleDegrees();
                writeAngle(level, j.pitchBe(), calB0 + CAL_STEP_DEG);
                calTimer = CAL_SETTLE_TICKS;
                calState = Cal.PITCH_PROBE;
                status = "校准中(俯仰轴)...";
            }
            case PITCH_PROBE -> {
                if (--calTimer > 0) return;
                double dT = GimbalMath.wrapDeg(referenceTwist(j, false) - calT0);
                writeAngle(level, j.pitchBe(), calB0);
                if (Math.abs(dT) < CAL_MIN_RESPONSE_DEG) {
                    failCalibration("俯仰", dT);
                    return;
                }
                signPitch = dT > 0 ? 1 : -1;
                calState = Cal.DONE;
                calibrated = true;
                yawSettled = pitchSettled = false;
                lastYawWritten = (double) acc(j.yawBe()).bridgecore$getTargetAngleDegrees();
                lastPitchWritten = (double) acc(j.pitchBe()).bridgecore$getTargetAngleDegrees();
                status = "就绪";
                markDirty.run();
            }
            case DONE -> { /* calibrated flag reset means we shouldn't be here */ calState = Cal.IDLE; }
        }
    }

    /** Observe actual relative poses independently of the command/aim branch. */
    private void updateJointAngles(Joints j) {
        lastRelYaw = lastRelPitch = Double.NaN;
        if (j.yawAttached() == null) return;
        if (Double.isFinite(yawZeroTwist))
            lastRelYaw = GimbalMath.wrapDeg(referenceTwist(j, true) - yawZeroTwist);
        if (j.gun() == null || muzzleLocal == null || !Double.isFinite(pitchZeroTwist)) return;
        double rawPitch = GimbalMath.wrapDeg(referenceTwist(j, false) - pitchZeroTwist);
        Quat relative = poseQuat(j.yawAttached()).negate().multiply(poseQuat(j.gun()));
        int sense = GimbalMath.pitchUpSense(pitchAxisLocalCached, relative.rotate(muzzleLocal), rawPitch);
        if (sense != 0) pitchUpSense = sense;
        lastRelPitch = pitchUpSense * rawPitch;
    }

    /** Relative twist of the joint around its hinge axis (swing-twist), degrees. */
    private double referenceTwist(Joints j, boolean yawJoint) {
        if (yawJoint) {
            Quat qRel = poseQuat(j.yawContainer()).negate().multiply(poseQuat(j.yawAttached()));
            return GimbalMath.twistDeg(qRel, yawAxisLocalCached);
        }
        Quat qRel = poseQuat(j.yawAttached()).negate().multiply(poseQuat(j.gun()));
        return GimbalMath.twistDeg(qRel, pitchAxisLocalCached);
    }

    /** Calibration helper: drive both joints back to the recorded arc zero.
     *  @return true when both are within 0.5° (or no zero recorded yet). */
    private boolean driveToRest(ServerLevel level, Joints j) {
        return driveJointToRest(level, j, true) & driveJointToRest(level, j, false);
    }

    private boolean driveJointToRest(ServerLevel level, Joints j, boolean yawJoint) {
        double zero = yawJoint ? yawZeroTwist : pitchZeroTwist;
        SwivelBearingBlockEntity be = yawJoint ? j.yawBe() : j.pitchBe();
        // 第三批 step3 (§2.31): a trusted assembly zero is the authoritative
        // rest pose (mechanical zero = the as-built attitude), ahead of the
        // calibration-sampled arc center
        AxisLockAssemblyZero.Zero az = AxisLockAssemblyZero.get(level,
                yawJoint ? yawPos : pitchPos, be);
        if (az != null) {
            zero = az.twistAssemblyDeg();
        } else if (Double.isNaN(zero)) {
            // fresh pairing / re-pair: yaw twist 0 is not proven to equal the
            // build pose and yaw limits default to ±180 (harmless), so just
            // wait for stillness; pitch twist 0 IS the assembly level pose
            // (telemetry: settled turrets rest at twistPitch≈0), so drive there
            if (yawJoint) return true;
            zero = 0;
        }
        double rel = GimbalMath.wrapDeg(referenceTwist(j, yawJoint) - zero);
        if (Math.abs(rel) < 0.5) {
            restDrivePrevRel = Double.NaN;
            restDriveBadTicks = 0;
            return true;
        }
        // fresh-turret guard: a defaulted sign may point the drive the wrong
        // way; if the error keeps growing, flip the axis convention
        if (!Double.isNaN(restDrivePrevRel) && Math.abs(rel) > Math.abs(restDrivePrevRel) + 0.01) {
            if (++restDriveBadTicks >= 8) {
                if (yawJoint) signYaw = -signYaw; else signPitch = -signPitch;
                restDriveBadTicks = 0;
            }
        } else {
            restDriveBadTicks = 0;
        }
        restDrivePrevRel = rel;
        int sign = yawJoint ? signYaw : signPitch;
        double delta = Math.max(-MAX_STEP_DEG, Math.min(MAX_STEP_DEG, -rel));
        writeAngle(level, be, acc(be).bridgecore$getTargetAngleDegrees() + sign * delta);
        return false;
    }

    // axis locals are re-read on level access; cached per calibration/aim tick via ensure
    private Vec3 yawAxisLocalCached = new Vec3(0, 1, 0);
    private Vec3 pitchAxisLocalCached = new Vec3(0, 0, 1);

    private boolean ensureAssembledAndLocked(ServerLevel level, Joints j) {
        yawAxisLocalCached = axisOf(level, yawPos);
        pitchAxisLocalCached = axisOf(level, pitchPos);
        if (!j.yawBe().isAssembled()) {
            j.yawBe().assemble(); // manual port: no kinetic net to trigger it
            status = "偏航轴承装配中...";
            return false;
        }
        if (!j.pitchBe().isAssembled()) {
            j.pitchBe().assemble();
            status = "俯仰轴承装配中...";
            return false;
        }
        if (!level.getBlockState(yawPos).getValue(BlockStateProperties.POWERED)
                || !level.getBlockState(pitchPos).getValue(BlockStateProperties.POWERED)) {
            // Handoff §3.2: unlocked writes get swallowed on the lock edge; the
            // scroll option (always_locked) is the authoritative lock source.
            status = "轴承未锁定:请在轴承 UI 设'恒锁'(always_locked)";
            return false;
        }
        return true;
    }

    private void failCalibration(String joint, double dT) {
        calState = Cal.IDLE;
        retryTimer = CAL_RETRY_TICKS;
        status = String.format("%s轴校准无响应(dT=%.2f°):检查锁定/装配/结构", joint, dT);
    }

    // ---------------- TurretActuator ----------------

    @Override
    public boolean isReady() {
        return isPaired() && calibrated;
    }

    /** HUD suppression query (v3.1, 2026-10-01): true while the calibration
     *  state machine is actively running — paired, not yet calibrated, and
     *  the bearings resolve. A dead turret (bearings gone) never suppresses:
     *  HUD targeting is harmless to it and must not be held down forever. */
    public boolean calibrationActive(ServerLevel level) {
        return isPaired() && !calibrated && resolve(level) != null;
    }

    @Override
    public double getAimingErrorDeg() {
        return aimErrorDeg;
    }

    /** Last measured barrel direction (world frame); null before the first
     *  aim tick — the manual-volley occlusion check casts along the barrel's
     *  ACTUAL direction, not the solve target. */
    public @Nullable Vec3 currentDirWorld() {
        return lastCur;
    }

    @Override
    public String statusLine() {
        return status;
    }
}
