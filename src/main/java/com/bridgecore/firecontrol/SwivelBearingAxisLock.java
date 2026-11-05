package com.bridgecore.firecontrol;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.mixin.SwivelBearingAccessorMixin;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * AxisLock — kinematic takeover of fire-control-claimed swivel bearings
 * (create-fire-control BearingStabilizer methodology, ship-follow subset).
 *
 * <p>Why: the native rotary constraint couples the turret sub-structure to the
 * hull through the physics solver; every patch route (servo inertia scaling,
 * gravity cancel impulse/velocity-delta) only traded one coupling channel for
 * the next (飞控 2026-09-17 诊断链定案, archive branch swivel-bearing-physics).
 * The only cure is decoupling: delete the native constraint and set the child
 * pose kinematically every physics substep —
 * {@code childPose = parentPose ∘ joint(register)} — so the child never
 * participates in dynamics at all (no gravity account, no inertia account,
 * no servo torque, nothing to oscillate).
 *
 * <p>Scope ruling (vs BearingStabilizer's 1551-line full monty): we do NOT do
 * world-frame stabilization (swing/twist inheritance) — all fire-control aim
 * math is ship-frame, so the child fully inherits parent rotation plus the
 * joint angle. Ground bearings are never claimed (their native servo works
 * fine). Only bearings actively heartbeat-claimed by a paired
 * {@link SwivelTurretActuator} are taken over — a plain shipborne bearing
 * (e.g. a crane) keeps its native behaviour.
 *
 * <p>Lifecycle (patterns proven in the archived gravity-cancel):
 * <ul>
 *   <li>register: mixin on attachConstraints RETURN captures child UUID +
 *       both local anchors (bearing-front centre in parent plot frame; plate
 *       anchor in child plot frame);</li>
 *   <li>claim: SwivelTurretActuator heartbeats every tick while paired, with
 *       the calibrated SIGN; 2-tick tolerance, then the binding lapses and the
 *       native checkPersistence re-attaches the constraint — seamless two-way
 *       handoff;</li>
 *   <li>suppress: native tick re-attaches the constraint whenever handle ==
 *       null (SwivelBearingBlockEntity.checkPersistence), so suppression runs
 *       at three points: attachConstraints RETURN (same call stack as native
 *       creation — zero physics substeps in between), updateServoCoefficients
 *       TAIL, and after every successful enforce;</li>
 *   <li>unregister: removeHandle HEAD (native path only — our own suppression
 *       is flag-guarded) and BlockEntity.remove HEAD; the enforce loop also
 *       drops stale entries (BE gone, child sublevel removed, UUID changed).</li>
 * </ul>
 *
 * <p>Register→joint mapping: qRel0 and register0 are captured together at
 * claim/rebaseline; per frame the target relative orientation is
 * {@code axisAngle(axisLocal, sign·wrap180(R−R0)) ∘ qRel0}. twistDeg measures
 * right-hand-positive about the axis and rotationAxis uses the same
 * convention, so the calibration probe self-identifies SIGN under takeover
 * (provisional +1, probe response is exact). A register jump > JUMP_REBASE_DEG
 * within one tick is treated as an external rewrite (native
 * setTargetAngleFromCurrentOrientation on the lock edge) and rebases instead
 * of slewing the turret.
 *
 * <p>Baseline (2026-10-29 step2, handoff §2.31): the pose baseline itself is
 * NEVER persisted — but it is no longer re-measured raw either. When a trusted
 * assembly zero exists ({@link AxisLockAssemblyZero}, captured at assemble
 * time), the baseline is REBUILT canonically at claim: swing from the as-built
 * datum, twist from the live pose (upstream BearingStabilizer's
 * orientationForJointAngle semantics) — no stillness window, and a sagged or
 * mid-slew pose can no longer be blessed into qRel0. Without a zero (legacy
 * saves — the zero is never silently promoted), the 2026-10-28 gated capture
 * below remains as the fallback. While that fallback capture is pending, the
 * two mechanical degrees of freedom are SPLIT, not abandoned (the upstream
 * invariant: at every instant at least one system holds the joint):
 * <ul>
 *   <li>TRANSLATION is pinned by us every physics substep — the child is
 *       teleported so its pivot sits exactly on the anchor, orientation
 *       unchanged, velocity killed both sides. The native rotary constraint
 *       provably cannot hold a heavy head's translation (that is why this
 *       class exists; recalibration once sagged heads 1~4.7m off-anchor and
 *       the capture gate could never pass — the 飞头 loop), so we never leave
 *       translation to it again;</li>
 *   <li>ROTATION stays with the native constraint (no suppression while
 *       pending): the servo drags the twist onto the register pose, and the
 *       capture gate waits for the twist to be still for
 *       CAPTURE_STILL_TICKS (歪头 guard — a mid-slew capture would bake the
 *       slew and its swing droop into qRel0 forever; enforcement only ever
 *       rotates qRel0 about the axis). The native servo converges exactly on
 *       the register pose, so a still capture is register-consistent by
 *       construction.</li>
 * </ul>
 * The first pin of a pending period re-checks the 64m sanity fuse; on
 * PENDING_TIMEOUT_TICKS without a still sample the claim is refused with a
 * 100t cooldown rather than ever pinning a mechanically impossible pose.
 *
 * <p>First-teleport fuse: if the child's current pivot sits more than 64 m
 * from the computed anchor, takeover is refused and the native constraint is
 * kept (BearingStabilizer's real incident — they blew this up once).
 *
 * <p>Server thread only (physics events fire on it; BE ticks too).
 */
public final class SwivelBearingAxisLock {

    /** Heartbeat tolerance in game ticks (BearingStabilizer同款). */
    private static final long HEARTBEAT_TOLERANCE_TICKS = 2;
    /** Register delta above this within one tick = external rewrite → rebase. */
    private static final double JUMP_REBASE_DEG = 15.0;
    /** First-teleport sanity fuse, metres (BearingStabilizer MAX_INITIAL_LOCK_CORRECTION_BLOCKS). */
    private static final double MAX_INITIAL_CORRECTION = 64.0;
    /** Pending timeout: translation is pinned from the first pending substep,
     *  so a timeout now means the twist NEVER went still (servo never
     *  converged — obstruction, power loss, or a garbage register); refuse
     *  the claim and hand everything back to the native servo (100t cooldown
     *  before the next try). */
    private static final int PENDING_TIMEOUT_TICKS = 200;
    /** First-pin correction beyond this is logged (reload sag / heavy assembly
     *  being pulled back onto the anchor); healthy held joints sit at ~cm, a
     *  sagged assembly at decimetres to metres — 2026-10-06 mangled-turret
     *  incident. */
    private static final double CAPTURE_MAX_JOINT_ERROR = 0.5;
    /** 歪头 fix (2026-10-06): the pivot sits ON the hinge axis, so a mid-slew
     *  head passes the translation gate at ANY twist — the rotational gate
     *  below is what keeps a reload slew out of the baseline. Capture only
     *  once the joint is rotationally still: |Δtwist| ≤ this for
     *  CAPTURE_STILL_TICKS consecutive samples (the native servo satisfies it
     *  exactly when converged to the register pose → the captured baseline is
     *  register-consistent by construction). */
    private static final double CAPTURE_TWIST_STILL_DEG = 0.25;
    private static final int CAPTURE_STILL_TICKS = 2;

    private record Key(ResourceKey<Level> dim, BlockPos pos) {}

    /** Assembly facts + claim state for one bearing. */
    private static final class Binding {
        final ServerLevel level;
        final BlockPos pos;
        final UUID childId;
        final Vector3d axisLocalParent;   // unit hinge axis, parent plot frame
        final Vector3d anchorLocalParent; // bearing-front centre, parent plot frame
        final Vector3d pivotLocalChild;   // plate anchor, child plot frame
        // claim state
        boolean claimed;
        int sign = 1;
        long lastHeartbeat = Long.MIN_VALUE;
        /** Fuse-refusal cooldown: claims are ignored until this game time
         *  (without it a refused binding re-captures and re-refuses every
         *  5 ticks, churning a garbage baseline each cycle). */
        long refuseCooldownUntil;
        // baseline: qRel0 = conj(parentOri)·childOri captured with register0
        final Quaterniond qRel0 = new Quaterniond();
        double register0;
        double lastSeenRegister;
        /** Physical twist at baseline capture (register0 ⇔ this twist). NaN
         *  until a baseline exists — the lapse handback rebase needs the
         *  register⇔twist anchor to write a pose-consistent hold value. */
        double baselineTwist = Double.NaN;
        boolean decoupleReady; // first teleport succeeded, constraint suppressed
        /** Capture deferred while the joint waits for its rotational baseline
         *  (see the capture gate in enforceOne). Pending no longer means
         *  "hands off": translation is pinned by us every substep from the
         *  first pending tick (2026-10-28 飞头 fix), rotation stays with the
         *  native constraint until the baseline is captured. */
        boolean baselinePending;
        long pendingSinceTick;
        /** Rotational-stillness samples for the capture gate (歪头 fix):
         *  twist of the previous pending substep + consecutive still count. */
        double pendingTwist = Double.NaN;
        int pendingStillTicks;
        /** First-pin fuse state: the 64m sanity check runs once per pending
         *  period (continuous pinning afterwards moves sub-mm per substep). */
        boolean pinFuseChecked;

        Binding(ServerLevel level, BlockPos pos, UUID childId,
                Vector3d axisLocalParent, Vector3d anchorLocalParent, Vector3d pivotLocalChild) {
            this.level = level;
            this.pos = pos;
            this.childId = childId;
            this.axisLocalParent = axisLocalParent;
            this.anchorLocalParent = anchorLocalParent;
            this.pivotLocalChild = pivotLocalChild;
        }
    }

    private static final Map<Key, Binding> REGISTRY = new ConcurrentHashMap<>();
    /** True while WE are inside removeHandle — the mixin must not unregister. */
    private static boolean suppressing;
    private static boolean initialized = false;

    private SwivelBearingAxisLock() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        SableEventPlatform.INSTANCE.onPostPhysicsTick(SwivelBearingAxisLock::onPostPhysicsTick);
    }

    /** Server stopping (upstream BearingStabilizer.onServerStopping parity,
     *  2026-10-28): drop every binding. Keys are only (dim, pos) — without
     *  this, a single-player rejoin in the same JVM would find stale bindings
     *  whose level/child references belong to the dead world. */
    public static void onServerStopping() {
        REGISTRY.clear();
        AxisLockAssemblyZero.onServerStopping();
    }

    // ---------------- registration (mixin entry points) ----------------

    /** attachConstraints RETURN, handle non-null (attach actually happened). */
    public static void onAttach(SwivelBearingBlockEntity bearing, ServerSubLevel child, Vector3dc attachPos) {
        try {
            if (!(bearing.getLevel() instanceof ServerLevel level)) return;
            if (!(Sable.HELPER.getContaining(bearing) instanceof ServerSubLevel)) return; // ground: never touch
            UUID childId = bearing.getSubLevelID();
            if (childId == null || child == null || attachPos == null) return;
            Direction facing = bearing.getBlockState().getValue(BlockStateProperties.FACING);
            Vector3d axis = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
            Vector3d anchorLocal = new Vector3d(
                    bearing.getBlockPos().getX() + 0.5 + facing.getStepX(),
                    bearing.getBlockPos().getY() + 0.5 + facing.getStepY(),
                    bearing.getBlockPos().getZ() + 0.5 + facing.getStepZ());
            Key key = new Key(level.dimension(), bearing.getBlockPos().immutable());
            // attachPos = plate anchor in child plot frame (native computes it as
            // platePos + plateFacing centre − 0.001·facing, anti-embedding)
            Binding b = new Binding(level, key.pos(), childId, axis, anchorLocal, new Vector3d(attachPos));
            // Native checkPersistence re-attaches the constraint EVERY TICK while
            // handle == null, which lands here — preserve claim state across the
            // re-registration or the constraint flickers alive each tick and the
            // baseline churns. Only assembly facts (childId/anchors/pivot) refresh.
            Binding prev = REGISTRY.get(key);
            if (prev != null && prev.childId.equals(childId)) {
                b.claimed = prev.claimed;
                b.sign = prev.sign;
                b.lastHeartbeat = prev.lastHeartbeat;
                b.qRel0.set(prev.qRel0);
                b.register0 = prev.register0;
                b.lastSeenRegister = prev.lastSeenRegister;
                b.baselineTwist = prev.baselineTwist;
                b.decoupleReady = prev.decoupleReady;
                b.refuseCooldownUntil = prev.refuseCooldownUntil;
                b.baselinePending = prev.baselinePending;
                b.pendingSinceTick = prev.pendingSinceTick;
                b.pendingTwist = prev.pendingTwist;
                b.pendingStillTicks = prev.pendingStillTicks;
                b.pinFuseChecked = prev.pinFuseChecked;
            }
            REGISTRY.put(key, b);
            // native created a constraint this call; if claimed, kill it in the
            // same call stack (zero physics substeps with the constraint live)
            suppressIfClaimed(bearing, b);
        } catch (Throwable t) {
            BridgeCoreMod.LOGGER.warn("[axislock] register hook failed, attach unaffected", t);
        }
    }

    /** removeHandle HEAD — native detach (disassemble). Our own suppression
     *  calls removeHandle too; the flag keeps us from eating our own tail.
     *  SERVER ONLY: client-side BE callbacks share the same (dim,pos) key and
     *  would kill the server binding (2026-09-18: a Render-thread remove() ate
     *  the registration mid-run — claim heartbeat then no-ops and the native
     *  servo silently takes back over, the "接管要做两遍" bug). */
    public static void onNativeRemoveHandle(SwivelBearingBlockEntity bearing) {
        if (suppressing) return;
        if (!(bearing.getLevel() instanceof ServerLevel)) return;
        unregister(bearing.getLevel(), bearing.getBlockPos());
    }

    public static void onBearingRemoved(SwivelBearingBlockEntity bearing) {
        if (!(bearing.getLevel() instanceof ServerLevel)) return; // client removes must not touch server state
        unregister(bearing.getLevel(), bearing.getBlockPos());
    }

    private static void unregister(@Nullable Level level, @Nullable BlockPos pos) {
        if (level == null || pos == null) return;
        REGISTRY.remove(new Key(level.dimension(), pos));
    }

    // ---------------- claim (actuator heartbeat) ----------------

    /**
     * Heartbeat-claim a bearing for kinematic takeover. Call every tick while
     * paired; stops being called → binding lapses → the register is rebased
     * onto the current physical twist and the native servo resumes (handback
     * continuity — no slew to a stale aim command).
     * No-op for ground bearings (container is not a ServerSubLevel).
     *
     * @param sign calibrated register→joint SIGN (+1 provisional during
     *             calibration — the probe self-identifies under takeover)
     */
    public static void claim(ServerLevel level, BlockPos pos, int sign) {
        Binding b = REGISTRY.get(new Key(level.dimension(), pos));
        if (b == null) {
            b = tryReconstruct(level, pos); // self-heal: a missed/lost registration must not strand the claim
            if (b == null) return; // not assembled / ground — native servo meanwhile
        }
        if (!b.claimed && level.getGameTime() < b.refuseCooldownUntil) return; // fuse cooldown — native servo holds
        b.lastHeartbeat = level.getGameTime();
        int s = sign >= 0 ? 1 : -1;
        if (b.claimed && !b.baselinePending && b.sign != s) {
            rebase(b, currentRegister(b)); // sign flip mid-run: rebaseline, no jump
        }
        b.sign = s;
        if (!b.claimed) {
            b.claimed = true;
            // Capture-at-claim (BearingStabilizer methodology, 2026-10-06
            // redesign; pending-pin added 2026-10-28 after the recalibration
            // 飞头): the baseline is captured at the first rotationally-still
            // pose. During the wait, TRANSLATION is pinned by us every physics
            // substep — the one thing the native constraint provably cannot do
            // for a heavy head — while ROTATION stays with the native servo
            // (constraint kept alive, no suppression), which drags the joint
            // back to the register and thereby satisfies the stillness gate.
            // There is no window in which nobody holds the joint.
            b.baselinePending = true;
            b.pendingSinceTick = level.getGameTime();
            b.pendingTwist = Double.NaN;
            b.pendingStillTicks = 0;
            b.pinFuseChecked = false;
        }
    }

    /** Force a fresh baseline capture (turret unpaired / manual recalibration):
     *  with a trusted assembly zero the next substep rebuilds the baseline
     *  immediately (step2 fast path); without one it re-captures at the first
     *  rotationally-still pose (gated fallback). The claim itself is left
     *  intact, and the joint is NEVER handed back to the native servo alone:
     *  pending keeps translation pinned every substep while the native
     *  constraint keeps rotation — recalibration is a re-measurement, not a
     *  handoff (2026-10-28 飞头 fix: the old code re-entered a hold-nothing
     *  window here and heavy heads sagged metres off-anchor). */
    public static void resetBaseline(ServerLevel level, BlockPos pos) {
        Binding b = REGISTRY.get(new Key(level.dimension(), pos));
        if (b == null) return;
        b.baselinePending = true;
        b.decoupleReady = false; // re-run the first-teleport fuse on the new baseline
        b.pendingSinceTick = level.getGameTime();
        b.pendingTwist = Double.NaN;
        b.pendingStillTicks = 0;
        b.pinFuseChecked = false;
    }

    /** Registration normally happens in the attachConstraints hook, but that
     *  hook only fires on (re)attach — if the binding was lost while the native
     *  constraint stayed alive (the client-side-remove bug class), nothing ever
     *  re-fires it and the claim would no-op forever. Rebuild the registration
     *  from live BE state instead: same anchors the native code computes
     *  (bearing-front centre; plate anchor = plate front centre − 0.001·facing). */
    private static @Nullable Binding tryReconstruct(ServerLevel level, BlockPos pos) {
        try {
            if (!(level.getBlockEntity(pos) instanceof SwivelBearingBlockEntity be)) return null;
            if (!be.isAssembled() || be.getSubLevelID() == null) return null;
            if (!(Sable.HELPER.getContaining(be) instanceof ServerSubLevel)) return null; // ground
            BlockPos platePos = be.getPlatePos();
            if (platePos == null) return null;
            Direction plateFacing = level.getBlockState(platePos).getValue(BlockStateProperties.FACING);
            Vector3d pivotLocal = new Vector3d(
                    platePos.getX() + 0.5 + plateFacing.getStepX() * (1.0 - 0.001),
                    platePos.getY() + 0.5 + plateFacing.getStepY() * (1.0 - 0.001),
                    platePos.getZ() + 0.5 + plateFacing.getStepZ() * (1.0 - 0.001));
            Direction facing = be.getBlockState().getValue(BlockStateProperties.FACING);
            Vector3d axis = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
            Vector3d anchorLocal = new Vector3d(
                    pos.getX() + 0.5 + facing.getStepX(),
                    pos.getY() + 0.5 + facing.getStepY(),
                    pos.getZ() + 0.5 + facing.getStepZ());
            Binding b = new Binding(level, pos.immutable(), be.getSubLevelID(), axis, anchorLocal, pivotLocal);
            REGISTRY.put(new Key(level.dimension(), b.pos), b);
            return b;
        } catch (Throwable t) {
            BridgeCoreMod.LOGGER.warn("[axislock] reconstruct failed for bearing@{}", pos.toShortString(), t);
            return null;
        }
    }

    /** Both bearings of a turret claimed & decoupled → the actuator may use
     *  the crisp (ground) profile: under takeover there is no physics coupling
     *  for the ship-profile to soften against. */
    public static boolean isActive(ServerLevel level, BlockPos pos) {
        Binding b = REGISTRY.get(new Key(level.dimension(), pos));
        return b != null && b.claimed && b.decoupleReady
                && level.getGameTime() - b.lastHeartbeat <= HEARTBEAT_TOLERANCE_TICKS;
    }

    private static double currentRegister(Binding b) {
        if (b.level.getBlockEntity(b.pos) instanceof SwivelBearingBlockEntity be) {
            return ((SwivelBearingAccessorMixin) be).bridgecore$getTargetAngleDegrees();
        }
        return 0;
    }

    /** Capture the CURRENT physical relative orientation + register as the
     *  zero-delta baseline, so takeover never snaps the turret. In-memory only
     *  (2026-09-26): the baseline is re-measured every session — a persisted
     *  zero can be poisoned by a bad capture and then trusted forever. */
    private static void rebase(Binding b, double register) {
        ServerSubLevel parent = parentOf(b);
        ServerSubLevel child = childOf(b);
        if (parent != null && child != null) {
            b.qRel0.set(parent.logicalPose().orientation()).conjugate()
                    .mul(child.logicalPose().orientation());
            b.baselineTwist = liveTwistDeg(b, parent, child);
        } else {
            b.qRel0.identity();
            b.baselineTwist = Double.NaN;
        }
        b.register0 = register;
        b.lastSeenRegister = register;
    }

    /** Pending-capture timeout: refuse the claim (100t cooldown), leaving the
     *  joint to the native servo — never pin a mechanically impossible or
     *  mid-slew pose. */
    private static void refuseIfTimedOut(Binding b, long now, String why) {
        if (now - b.pendingSinceTick >= PENDING_TIMEOUT_TICKS) {
            b.claimed = false;
            b.baselinePending = false;
            b.refuseCooldownUntil = now + 100;
            BridgeCoreMod.LOGGER.warn(
                    "[axislock] capture refused on bearing@{}: {} after {}t — native servo keeps the joint, claim on 100t cooldown",
                    b.pos.toShortString(), why, now - b.pendingSinceTick);
        }
    }

    /** Joint twist of the live relative orientation about the hinge axis —
     *  same swing-twist the actuator's referenceTwist measures. */
    private static double liveTwistDeg(Binding b, ServerSubLevel parent, ServerSubLevel child) {
        Quaterniond qRel = new Quaterniond(parent.logicalPose().orientation()).conjugate()
                .mul(child.logicalPose().orientation());
        return GimbalMath.twistDeg(
                new com.bridgecore.firecontrol.ballistics.Quat(qRel.w, qRel.x, qRel.y, qRel.z),
                new com.bridgecore.firecontrol.ballistics.Vec3(
                        b.axisLocalParent.x, b.axisLocalParent.y, b.axisLocalParent.z));
    }

    private static @Nullable ServerSubLevel parentOf(Binding b) {
        return Sable.HELPER.getContaining(b.level, b.pos) instanceof ServerSubLevel s ? s : null;
    }

    private static @Nullable ServerSubLevel childOf(Binding b) {
        if (SubLevelContainer.getContainer(b.level) instanceof ServerSubLevelContainer sc
                && sc.getSubLevel(b.childId) instanceof ServerSubLevel s && !s.isRemoved()) {
            return s;
        }
        return null;
    }

    // ---------------- constraint suppression ----------------

    private static void suppressIfClaimed(SwivelBearingBlockEntity bearing, Binding b) {
        // baselinePending: the native constraint must STAY ALIVE — the servo
        // owns ROTATION (slewing the joint back to the register) until the
        // stillness gate fires, while we pin TRANSLATION every substep
        // (2026-10-28 飞头 fix). (2026-09-25 incident: this hook killed it on
        // the same call stack as the load-time attach, the turret free-fell
        // 71m.)
        if (!b.claimed || b.baselinePending) return;
        suppress(bearing, b);
    }

    private static void suppress(SwivelBearingBlockEntity bearing, Binding b) {
        SwivelBearingAccessorMixin acc = (SwivelBearingAccessorMixin) bearing;
        if (acc.bridgecore$getHandle() == null) return;
        try {
            suppressing = true;
            acc.bridgecore$invokeRemoveHandle(); // routine: no log, would spam per tick
        } catch (Throwable t) {
            BridgeCoreMod.LOGGER.warn("[axislock] suppress failed on bearing@{}", b.pos.toShortString(), t);
        } finally {
            suppressing = false;
        }
    }

    /** updateServoCoefficients TAIL hook — the native servo refresh re-creates
     *  nothing by itself but validates the handle; suppress if one reappeared. */
    public static void onServoCoefficients(SwivelBearingBlockEntity bearing) {
        try {
            if (!(bearing.getLevel() instanceof ServerLevel level)) return;
            Binding b = REGISTRY.get(new Key(level.dimension(), bearing.getBlockPos()));
            if (b != null) suppressIfClaimed(bearing, b);
        } catch (Throwable t) {
            BridgeCoreMod.LOGGER.warn("[axislock] servo-coefficients hook failed", t);
        }
    }

    // ---------------- enforce (post-physics, per substep) ----------------

    private static void onPostPhysicsTick(SubLevelPhysicsSystem system, double dt) {
        if (REGISTRY.isEmpty()) return;
        try {
            // Parent-first ordering (BearingStabilizer: yaw before pitch): the
            // pitch bearing's parent IS the yaw child sub-level — enforcing the
            // child row first would read the parent's pre-teleport pose and lag
            // the pitch joint one substep behind every yaw motion.
            java.util.List<Binding> mine = new java.util.ArrayList<>();
            java.util.Set<UUID> childIds = new java.util.HashSet<>();
            for (Binding b : REGISTRY.values()) {
                if (SubLevelPhysicsSystem.get(b.level) != system) continue;
                mine.add(b);
                childIds.add(b.childId);
            }
            if (mine.isEmpty()) return;
            mine.sort((a, b2) -> {
                // a binding whose parent is another binding's child enforces later
                boolean aIsNested = parentIdOf(a) != null && childIds.contains(parentIdOf(a));
                boolean bIsNested = parentIdOf(b2) != null && childIds.contains(parentIdOf(b2));
                return Boolean.compare(aIsNested, bIsNested);
            });
            for (Binding b : mine) {
                if (!enforceOne(b)) {
                    REGISTRY.remove(new Key(b.level.dimension(), b.pos), b);
                }
            }
        } catch (Throwable t) {
            BridgeCoreMod.LOGGER.warn("[axislock] enforce pass failed, entries kept", t);
        }
    }

    private static @Nullable UUID parentIdOf(Binding b) {
        ServerSubLevel parent = parentOf(b);
        return parent != null ? parent.getUniqueId() : null;
    }

    /** @return false to drop the binding from the registry */
    private static boolean enforceOne(Binding b) {
        if (!(b.level.getBlockEntity(b.pos) instanceof SwivelBearingBlockEntity be)) return false;
        if (be.isRemoved() || be.getSubLevelID() == null || !be.getSubLevelID().equals(b.childId)) return false;
        ServerSubLevel parent = parentOf(b);
        ServerSubLevel child = childOf(b);
        if (parent == null || child == null) return false;
        if (!b.claimed) return true; // registered, unclaimed: native servo drives
        if (b.level.getGameTime() - b.lastHeartbeat > HEARTBEAT_TOLERANCE_TICKS) {
            b.claimed = false;
            b.decoupleReady = false;
            // Handback continuity (2026-10-30, 翻转事件取证结论): before the
            // native servo resumes, rebase the register onto the CURRENT
            // physical twist — otherwise the re-attached servo slews the joint
            // to the last aim command (or a lock-edge-rebased value), a snap
            // that looks exactly like the 抽风/歪头 class. Only possible once
            // a baseline exists (register⇔twist anchor); while still pending
            // the native servo owned rotation all along, so the register it
            // was already tracking is continuous by construction.
            if (!b.baselinePending && Double.isFinite(b.baselineTwist)) {
                double hold = b.register0
                        + b.sign * GimbalMath.wrapDeg(liveTwistDeg(b, parent, child) - b.baselineTwist);
                ((SwivelBearingAccessorMixin) be).bridgecore$setTargetAngleDegrees(GimbalMath.norm360(hold));
            }
            return true; // keep registration; checkPersistence re-attaches the constraint
        }

        if (b.baselinePending) {
            // 第三批 step2 fast path (§2.31): a trusted assembly zero makes
            // the canonical rebuild immediate — NO stillness window. Swing
            // comes from the as-built datum (mechanically legal by
            // construction), twist from the live pose: qRel0 =
            // axisAngle(axis, liveTwist) ∘ swing(assembly) — a sagged or
            // mid-slew pose at claim time can no longer be blessed into the
            // baseline (the 歪头 class), and the native constraint is
            // suppressed on THIS substep's first teleport (upstream
            // BearingStabilizer's initialize semantics).
            AxisLockAssemblyZero.Zero zero = AxisLockAssemblyZero.get(b.level, b.pos, be);
            if (zero != null) {
                double liveTwist = liveTwistDeg(b, parent, child);
                Quaterniond rebuilt = new Quaterniond().rotationAxis(
                                Math.toRadians(liveTwist),
                                b.axisLocalParent.x, b.axisLocalParent.y, b.axisLocalParent.z)
                        .mul(AxisLockAssemblyZero.swingOf(zero, b.axisLocalParent));
                if (rebuilt.isFinite()) {
                    b.qRel0.set(rebuilt);
                    b.register0 = ((SwivelBearingAccessorMixin) be).bridgecore$getTargetAngleDegrees();
                    b.lastSeenRegister = b.register0;
                    b.baselineTwist = liveTwist;
                    b.baselinePending = false;
                    // fall through: first teleport + 64m fuse THIS substep
                }
                // degenerate rebuild (should not happen — get() validated):
                // fall into the gated path below
            }
        }
        if (b.baselinePending) {
            // Capture gate (2026-10-28 redesign — the upstream
            // BearingStabilizer invariant: at every instant at least ONE
            // system holds the joint):
            //  * TRANSLATION is pinned by US every pending substep: teleport
            //    the child so its pivot sits exactly on the anchor, keeping
            //    the current orientation, velocity killed both sides. The old
            //    code did NOTHING mechanical while pending — the native
            //    constraint (provably unable to hold a heavy head; that is
            //    why AxisLock exists) was the only holder, and heads sagged
            //    1~4.7m off-anchor after every recalibration, failing the
            //    capture gate forever (the 飞头 loop).
            //  * ROTATION stays with the native constraint (NO suppress
            //    here): the servo drags the twist onto the register pose,
            //    which is exactly the convergence the stillness gate below
            //    waits for (歪头 guard: a mid-slew capture would bake the
            //    slew — and its swing droop — into qRel0 forever; enforcement
            //    only ever rotates qRel0 about the axis).
            long now = b.level.getGameTime();
            Pose3dc pendingParentPose = parent.logicalPose();
            Pose3dc pendingChildPose = child.logicalPose();
            Vector3d pendingAnchor = pendingParentPose.transformPosition(b.anchorLocalParent, new Vector3d());
            Vector3d pendingPivot = pendingChildPose.transformPosition(b.pivotLocalChild, new Vector3d());
            double jointError = pendingPivot.distance(pendingAnchor);
            if (!b.pinFuseChecked) {
                // one-shot first-pin sanity fuse (same 64m rule as the
                // first-teleport fuse below): beyond that the pose is
                // garbage, not a sagging joint — refuse and leave the
                // native servo to it
                b.pinFuseChecked = true;
                if (jointError > MAX_INITIAL_CORRECTION) {
                    b.claimed = false;
                    b.baselinePending = false;
                    b.refuseCooldownUntil = now + 100;
                    BridgeCoreMod.LOGGER.error(
                            "[axislock] refused pending-pin on bearing@{}: initial correction {}m > 64m — keeping native constraint (100t cooldown)",
                            b.pos.toShortString(), String.format("%.1f", jointError));
                    return true;
                }
            }
            Vector3d pinOffset = new Vector3d(b.pivotLocalChild)
                    .sub(pendingChildPose.rotationPoint()).mul(pendingChildPose.scale());
            Quaterniond pinOri = new Quaterniond(pendingChildPose.orientation());
            pinOri.transform(pinOffset);
            Vector3d pinPos = new Vector3d(pendingAnchor).sub(pinOffset);
            var pinPipeline = SubLevelPhysicsSystem.get(b.level).getPipeline();
            pinPipeline.resetVelocity(child);
            pinPipeline.teleport(child, pinPos, pinOri);
            pinPipeline.resetVelocity(child);
            double twist = liveTwistDeg(b, parent, child);
            if (!Double.isNaN(b.pendingTwist)
                    && Math.abs(GimbalMath.wrapDeg(twist - b.pendingTwist)) <= CAPTURE_TWIST_STILL_DEG) {
                b.pendingStillTicks++;
            } else {
                b.pendingStillTicks = 0;
            }
            b.pendingTwist = twist;
            if (b.pendingStillTicks < CAPTURE_STILL_TICKS) {
                refuseIfTimedOut(b, now, "joint still slewing (servo never converged)");
                return true; // pinned by us, rotated by the native servo
            }
            b.baselinePending = false;
            rebase(b, ((SwivelBearingAccessorMixin) be).bridgecore$getTargetAngleDegrees());
            // fall through: first full (orientation) teleport happens THIS substep
        }

        double register = ((SwivelBearingAccessorMixin) be).bridgecore$getTargetAngleDegrees();
        double sinceLast = GimbalMath.wrapDeg(register - b.lastSeenRegister);
        if (Math.abs(sinceLast) > JUMP_REBASE_DEG) {
            rebase(b, register); // external rewrite (lock edge etc.) — follow, don't slew
        }
        b.lastSeenRegister = register;

        Pose3dc parentPose = parent.logicalPose();
        Pose3dc childPose = child.logicalPose();

        // target relative orientation: rotate baseline about the hinge axis
        // (parent plot frame) by sign·(register − register0)
        double theta = b.sign * GimbalMath.wrapDeg(register - b.register0);
        Quaterniond qRel = new Quaterniond()
                .rotationAxis(Math.toRadians(theta), b.axisLocalParent.x, b.axisLocalParent.y, b.axisLocalParent.z)
                .mul(b.qRel0);
        Quaterniond childOri = new Quaterniond(parentPose.orientation()).mul(qRel);

        // keep the pivot pinned on the anchor:
        // world = pos + ori·((local − rotationPoint) ⊙ scale)  ⇒ solve pos
        Vector3d worldAnchor = parentPose.transformPosition(b.anchorLocalParent, new Vector3d());
        Vector3d pivotOffset = new Vector3d(b.pivotLocalChild)
                .sub(childPose.rotationPoint()).mul(childPose.scale());
        childOri.transform(pivotOffset);
        Vector3d childPos = new Vector3d(worldAnchor).sub(pivotOffset);

        if (!b.decoupleReady) {
            // first-teleport fuse: current pivot must already sit near the anchor
            Vector3d pivotWorld = childPose.transformPosition(b.pivotLocalChild, new Vector3d());
            double correction = pivotWorld.distance(worldAnchor);
            if (correction > MAX_INITIAL_CORRECTION) {
                b.claimed = false;
                b.refuseCooldownUntil = b.level.getGameTime() + 100;
                // the baseline that led here is garbage (captured while the
                // joint sat somewhere it mechanically cannot be) — the next
                // claim re-arms the settle-gated capture
                BridgeCoreMod.LOGGER.error(
                        "[axislock] refused takeover on bearing@{}: initial correction {}m > 64m — keeping native constraint (baseline re-armed, 100t cooldown)",
                        b.pos.toShortString(), String.format("%.1f", correction));
                return true;
            }
            b.decoupleReady = true;
        }

        var pipeline = SubLevelPhysicsSystem.get(b.level).getPipeline();
        pipeline.resetVelocity(child);
        pipeline.teleport(child, childPos, childOri);
        pipeline.resetVelocity(child); // teleport-to ≠ zero velocity: kill the drift twice
        suppress(be, b); // native may have re-attached since the last substep
        return true;
    }
}
