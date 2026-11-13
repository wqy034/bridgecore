package com.bridgecore.firecontrol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;

import com.bridgecore.BCRegistry;
import com.bridgecore.BridgeCoreBlockEntity;
import com.bridgecore.BridgeCoreMod;
import com.bridgecore.CoreDirectory;
import com.bridgecore.PointingMode;
import com.bridgecore.control.NavigationController;
import com.bridgecore.firecontrol.TargetRegistry.Category;
import com.bridgecore.firecontrol.TargetRegistry.Sighting;
import com.bridgecore.firecontrol.TargetRegistry.Target;
import com.bridgecore.firecontrol.ballistics.Quat;
import com.bridgecore.firecontrol.ballistics.SolveChain;
import com.bridgecore.firecontrol.ballistics.Vec3;
import com.bridgecore.firecontrol.railgun.RailgunLink;
import com.bridgecore.menu.FireControlMenu;
import com.bridgecore.nav.NavTerminalData;
import com.bridgecore.network.FireControlStrikeResult;
import com.bridgecore.network.TerminalReplyPayload;

import org.joml.Vector3d;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Fire-control core block entity: the 20 Hz game-tick slow loop — target scan,
 * per-channel task state machine, whitelist. Never touches the physics thread
 * (双核分立: a fire-control crash must not drop the ship).
 *
 * <p>频道任务协议 v1 (shared handbook §2.29, 2026-10-27/28 双方基线): this core
 * is the ONLY authority on engagement. Each of the four weapon channels holds at
 * most one {@link ChannelTask} (source HUD/CONSOLE × mode MULTI_LOCK/PRECISE_LOCK/
 * FOLLOW); the HUD prelock pool is a shared tactical picture with seq/ttl
 * freshness, never an engagement by itself (无候选 ≠ 授权自主搜敌). The old
 * core-global lockedId / followPlayerUuid / hudPrelockIds engagement paths are
 * retired (NBT-migrated into the active channel's task).
 *
 * <p>Scan semantics ported from control_center.lua:
 * <ul>
 *   <li>living entities (players included) within scanRadius, world frame;
 *       players on the OWN structure are excluded (§2.29 §8 — the
 *       "解锁后锁到自己" class);</li>
 *   <li>Sable ships enumerated via SubLevelContainer, own structure excluded by
 *       identity (uuid-exact — name matching breaks on rename/duplicates);</li>
 *   <li>structures hosting a registered fireControlCore are implicitly
 *       whitelisted (注册即白名单) — this is what kills the "locked our own
 *       turret" bug class;</li>
 *   <li>velocities derived from consecutive sightings (see TargetRegistry).</li>
 * </ul>
 */
public class FireControlCoreBlockEntity extends BlockEntity implements MenuProvider {

    /** control_center.lua properties.raycastRange default. */
    private static final double DEFAULT_SCAN_RADIUS = 256.0;
    /** Scan every N ticks — a full radius query per tick is wasted; velocity
     *  derivation divides by the real interval so 4 Hz costs no accuracy. */
    private static final int SCAN_INTERVAL = 5;
    /** Rows printed by the chat status (radar stand-in until the display system). */
    private static final int CHAT_ROWS = 10;
    /** Precise-lock watchdog (2026-09-30, carried into the §2.29 task model): a
     *  precise target that dies/despawns while the 4 Hz scan still lists a stale
     *  snapshot keeps the channel aiming at its ghost. When the 20 Hz fast path
     *  cannot resolve a PRECISE_LOCK target for this many consecutive ticks, the
     *  task ENDS (rev+1, 协议 §5: 不自动改锁). Ships get the same grace — their
     *  sublevel lookup can flap on chunk reload. */
    private static final int LOCK_LOST_GRACE_TICKS = 60;
    private final int[] preciseLostTicks = new int[WeaponGroups.MAX];

    private final TargetRegistry registry = new TargetRegistry();
    /** Sky-visibility scan filter (the 2026-09-23 隔墙锁 fix): ON by default,
     *  player-toggleable per core (2026-09-26 user ruling — the heightmap
     *  lookup is cheap, but big-scan-radius users may want it off). */
    private boolean skyFilter = true;
    /** Paired turrets, insertion-ordered (pairing order = display order). */
    private final Map<java.util.UUID, TurretUnit> turrets = new java.util.LinkedHashMap<>();
    /** Hull-fixed guns (轴炮, docs/axis-gun-design.md): fe_railgun macro-cannon
     *  cores (宏炮核心 — own core block, NOT CBC mounts) on the SAME main
     *  structure as this core, no bearings — the ship is the carriage and the
     *  flight core's pointing service aims them. Separate from turrets on
     *  purpose: TurretUnit hard-binds SwivelTurretActuator. */
    private final Map<java.util.UUID, FixedGun> fixedGuns = new java.util.LinkedHashMap<>();
    /** Pointing requester identity (3c): persisted so the flight core's
     *  override logs stay meaningful across reloads. */
    private java.util.UUID coreUuid;
    /** Channel currently owning our pointing request (-1 = none). Runtime only. */
    private int pointingChannel = -1;
    private final Map<java.util.UUID, MacroReadout> macroReadouts = new java.util.HashMap<>();
    /** Axis-gun fire gate: actual beam axis vs target direction, degrees. */
    static final double AXIS_GUN_FIRE_TOLERANCE_DEG = 1.5;
    /** Pointing rate/accel defaults (pointing-execution-design §6.4; the
     *  flight core clamps further by its own attMaxAlpha). */
    static final double POINTING_MAX_RATE = 0.6, POINTING_MAX_ACCEL = 2.0;
    public static final int MAX_FIXED_GUNS = 4;
    private double scanRadius = DEFAULT_SCAN_RADIUS;
    private ServerSubLevel registeredSubLevel;
    private long tickCount;

    // ---------------- 武器频道 + 频道任务 (§2.29 频道任务协议 v1) ----------------
    private int channelCount = WeaponGroups.DEFAULT_COUNT;
    private final String[] channelNames = new String[WeaponGroups.MAX];
    private final boolean[] channelAutoFire = new boolean[WeaponGroups.MAX];
    /** 自动升格 preference (裁断稿 §5): auto-enter HUD MULTI_LOCK when the channel
     *  is IDLE and the prelock pool is valid+non-empty. ONLY into multi-lock —
     *  never precise, never firing. Manual release pauses it via the task's
     *  promoteSuppressed; recovery = another G or re-enabling this switch. */
    private final boolean[] channelAutoPromote = new boolean[WeaponGroups.MAX];
    /** The authoritative per-channel tasks (协议 §1: 每频道至多一份, NBT 持久化). */
    private final ChannelTask[] tasks = new ChannelTask[WeaponGroups.MAX];
    /** Per-turret execution feedback (协议 §1): unit id → assignment stamped with
     *  the taskRev it was computed from — a rev move invalidates it IMMEDIATELY
     *  (评审 H 即时失效原则), the drive loop refuses stale-rev assignments. */
    private final Map<java.util.UUID, Assignment> turretAssignments = new java.util.HashMap<>();
    private record Assignment(String targetId, int channel, long taskRev) {}
    /** HUD prelock receive state (协议 §3 PrelockUpdate): the SHIP-WIDE candidate
     *  picture pushed by the command HUD — not per channel, not an engagement.
     *  seq is monotone (stale pushes dropped); expiry is a gameTime deadline,
     *  heartbeats must re-arm it (修订 A). Explicit OFF revokes HUD tasks;
     *  an enabled but empty cone only empties multi-lock assignments.
     *  NOT persisted: a silent HUD (ttl timeout) is treated as disconnected. */
    private List<String> prelockIds = List.of();
    private long prelockSeq;
    private long prelockExpiryTick = Long.MIN_VALUE;
    /** Per-channel fire scheduling input state (协议 §1/§6): salvoWaiting = 双击齐射
     *  等待中 (driveTurret 全组就位即击发并清除), hHeld = 手动持续扳机 (driveTurret
     *  AUTO 闸门链中顶替频道自动开火开关, 仍按炮塔自身装填节奏). Pure runtime;
     *  cancelled on task release / source switch / channel switch. */
    private final boolean[] salvoWaiting = new boolean[WeaponGroups.MAX];
    private final long[] salvoRevision = new long[WeaponGroups.MAX];
    /** P1-5: 齐射排队快照 + 超时。快照以双击时刻在册成员为准——等待期间
     *  新配对/新挂载/新登记不纳入旧请求; 超过 10s 未就位自动取消。 */
    private final long[] salvoStartTick = new long[WeaponGroups.MAX];
    private final java.util.Map<Integer, java.util.Set<String>> salvoSnapshots = new java.util.HashMap<>();
    private static final int SALVO_TIMEOUT_TICKS = 200;
    private final boolean[] hHeld = new boolean[WeaponGroups.MAX];
    /** The channel the G/H arbitration, the volley and the panel edits target. */
    private int activeChannel;

    /** Flat registry of every loaded core per level (server thread only), so
     *  display apps can find a core zero-link — including GROUND cores, which
     *  CoreDirectory never holds (null-sub registration is a no-op there). */
    private static final Map<Level, List<FireControlCoreBlockEntity>> REGISTRY = new WeakHashMap<>();

    /** All loaded cores riding the given structure in this level (null==null
     *  matches ground). Server thread only. */
    public static List<FireControlCoreBlockEntity> coresOf(Level level,
                                                           @Nullable ServerSubLevel sub) {
        List<FireControlCoreBlockEntity> cores = REGISTRY.get(level);
        if (cores == null) return List.of();
        List<FireControlCoreBlockEntity> out = new ArrayList<>();
        for (FireControlCoreBlockEntity c : cores) {
            if (c.registeredSubLevel == sub) out.add(c);
        }
        return out;
    }

    /** v3.1 HUD suppression source (2026-10-01): true while ANY turret on any
     *  loaded core is actively calibrating (pairing/recalibration window —
     *  the core has just cleared the engagement slot, so the HUD must stand
     *  down and not re-pin mid-swing). Server thread only. */
    public static boolean anyCalibrationActive() {
        for (List<FireControlCoreBlockEntity> cores : REGISTRY.values()) {
            for (FireControlCoreBlockEntity c : cores) {
                if (!(c.getLevel() instanceof ServerLevel sl)) continue;
                for (TurretUnit u : c.turrets.values()) {
                    if (u.actuator().calibrationActive(sl)) return true;
                }
            }
        }
        return false;
    }

    /** 20 Hz fast-path re-resolve of a tracked target (wraps the drive loop's
     *  refreshLockedFast): {x,y,z,vx,vy,vz} in world m and m/s, or null ->
     *  fall back to the registry's last-scan snapshot. The HUD provider uses
     *  this so SHIP primaries don't ride the 4 Hz scan staircase. */
    public double @Nullable [] freshState(Level level, Target t) {
        return level instanceof ServerLevel sl ? refreshLockedFast(sl, t) : null;
    }

    /** Nearest core riding the same structure as `sub` (null==null matches two
     *  ground blocks). Never crosses structures: a screen on ship A will not
     *  read the core of ship B. Server thread only. */
    public static @Nullable FireControlCoreBlockEntity findFor(Level level,
                                                               @Nullable ServerSubLevel sub,
                                                               BlockPos near) {
        List<FireControlCoreBlockEntity> cores = REGISTRY.get(level);
        if (cores == null) return null;
        FireControlCoreBlockEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (FireControlCoreBlockEntity c : cores) {
            if (c.registeredSubLevel != sub) continue;
            double d = c.getBlockPos().distSqr(near);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    public FireControlCoreBlockEntity(BlockPos pos, BlockState state) {
        super(BCRegistry.FIRE_CONTROL_CORE_BE.get(), pos, state);
        for (int i = 0; i < WeaponGroups.MAX; i++) {
            tasks[i] = new ChannelTask();
            channelNames[i] = FireControlUiText.channel(i);
            channelAutoPromote[i] = true;
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  FireControlCoreBlockEntity be) {
        be.syncDirectory(level, pos);
        ++be.tickCount;
        if (level instanceof ServerLevel sl) {
            be.tickPrelock(sl);
            be.maybeAutoPromote(sl);
        }
        if (be.tickCount % SCAN_INTERVAL == 0) {
            be.scan(level, pos);
        }
        if (level instanceof ServerLevel sl) {
            if (be.assignmentsDirty) be.recomputeAssignments(sl);
            be.driveTurret(sl);
            be.tickAxisGuns(sl);
            be.tickSalvoTimeouts(sl);
        }
    }

    // ---------------- phase 3: aim-only turret drive ----------------

    /** Solve parameters, upstream OnCannon.lua defaults EXCEPT the field-of-fire:
     *  lockYawRange 170° / pitch [-15°,60°] were servo-mount-era physical
     *  limits; a swivel turret slews freely, and the gate made the turret
     *  STALL in a fixed world-frame wedge (target in the ±170°..180° sector ->
     *  src="out-of-range" -> no writes -> "bearing dead zone" illusion reported
     *  2026-09-17). Now wide open: ±180° yaw, ±90° pitch (lockZoneDir stays
     *  "none"); keep the params only for the solver's numeric needs.
     *  forecastMov/forecastRot 0.8s were servo-era (Lua): the shell-exit pose
     *  forecast had to cover a ~16-tick actuator delay. Direct bearing control
     *  (AxisLock) closes the loop in 1-3 ticks, and the target side is already
     *  led by TRACK_LEAD_TICKS — an 0.8s ship-side forecast over-leads by
     *  ~0.6s×shipVel, which is exactly the "accurate parked, hopeless while
     *  maneuvering" report of 2026-10-06. 0.1s ≈ the real solve+actuate
     *  pipeline delay. */
    static final SolveChain.Params DEFAULT_PARAMS = new SolveChain.Params(
            0.99, 0.05, 160, 0, -90, 90, 180, "none", 30, 0.1, 0.1, 0.05,
            false, false, 0, "-X");
    /** Tracking-loop delay lead (2026-09-17, stutter fix ③): pure position
     *  feedback lags a moving target by ω×loop-delay (fast-path read + solve +
     *  PD servo ≈ 3-4 ticks, Lua prototype measured the servo at 2-3). Lead
     *  the target by this many ticks — 先进火控同款 (ConnectedDisplayBlockEntity
     *  .solveAim: predicted = target + velocity×ticks). The chain's own
     *  ballistic flight-time lead (mode 3, cTime>5) stacks on top for far
     *  targets. Tune up if the turret still trails, down if it overshoots. */
    private static final double TRACK_LEAD_TICKS = 4.0;

    // ---- P3 fe_railgun link: per-turret solver-params cache + GUI status ----
    /** Refreshed every 20 ticks or when the mount ballistic signature changes. */
    private final Map<java.util.UUID, SolveChain.Params> turretParamsCache = new java.util.HashMap<>();
    private final Map<java.util.UUID, String> turretParamsSig = new java.util.HashMap<>();
    /** Last per-mount status codes per turret (RailgunLink.STATUS_*, parallel
     *  to SwivelTurretActuator.mounts() slot order; the menu reads this cache
     *  instead of re-querying the mounts). */
    private final Map<java.util.UUID, int[]> turretMountStatus = new java.util.HashMap<>();
    /** FireDirector verdict per turret from the latest drive tick (auto-mode
     *  gates) — the GUI status page narrates which gate each turret is
     *  parked at (2026-10-08 火力指挥层). */
    private final Map<java.util.UUID, FireDirector.GateResult> turretGate = new java.util.HashMap<>();

    /** Display-only stamps: prevent an old task's cached green lamp from
     * surviving a rev change, calibration, or a stopped drive loop. */
    private record HudGateSample(int channel, long taskRev, long tick, FireDirector.GateResult gate) {}
    private final Map<java.util.UUID, HudGateSample> hudGateSamples = new java.util.HashMap<>();

    /** 双击齐射候选 (协议 §6): one channel turret + its VOLLEY-gate verdict
     *  from this drive tick; the salvo fires only when every entry passes. */
    private record SalvoEntry(SwivelTurretActuator turret,
                              List<SwivelTurretActuator.Mount> mounts,
                              Object[] handles, FireDirector.GateResult gate) {}

    /** 20 Hz: resolve each turret's aim from the task of ITS OWN channel
     *  (§2.29: ch<0 无任务不瞄准 — 未编组不指挥; PRECISE_LOCK → the anchored id;
     *  MULTI_LOCK → this turret's rev-stamped assignment; FOLLOW → the look
     *  direction of the follow player; IDLE → hold). The old core-global
     *  pin/follow priority is gone. Each unit solves from its OWN muzzle
     *  position; the actuator also needs the no-target ticks for calibration,
     *  so it runs whenever paired. */
    private int salvoFiredGroups;
    private @Nullable MarkedStrike markedStrike;

    /** Queues a confirmed preview without changing the ordinary channel task. */
    public @Nullable String startMarkedStrike(ServerPlayer player, UUID terminal, BridgeCoreBlockEntity flight,
            ServerSubLevel ship, NavTerminalData.Mark mark, StrikePreview.Roster roster, int channel,
            StrikePreview.Mode weaponMode, Set<UUID> selected, @Nullable StrikePreview.PosePlan posePlan, boolean quick) {
        return startCoordinateStrike(player, new MarkedStrike.Terminal(terminal, mark, quick), flight, ship, mark.pos(), roster,
                channel, weaponMode, selected, posePlan);
    }

    public @Nullable String startCoordinateStrike(ServerPlayer player, FireControlMenu menu, ServerSubLevel ship,
            BlockPos target, StrikePreview.Roster roster, int channel, StrikePreview.Mode weaponMode, Set<UUID> selected, @Nullable StrikePreview.PosePlan posePlan) {
        if (menu.strikeCore(player) != this) return "火控界面已关闭";
        BridgeCoreBlockEntity flight = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (var candidate : CoreDirectory.coresOf(ship)) {
            if (candidate.isRemoved() || candidate.getLevel() != player.serverLevel()) continue;
            double distance = candidate.getBlockPos().distSqr(worldPosition);
            if (distance < nearest) {
                flight = candidate;
                nearest = distance;
            }
        }
        if (flight == null) return "本船没有可用的飞控核心";
        return startCoordinateStrike(player, new MarkedStrike.Console(menu), flight, ship, target, roster, channel,
                weaponMode, selected, posePlan);
    }

    private @Nullable String startCoordinateStrike(ServerPlayer player, MarkedStrike.Origin origin,
            BridgeCoreBlockEntity flight, ServerSubLevel ship, BlockPos target, StrikePreview.Roster roster,
            int channel, StrikePreview.Mode weaponMode, Set<UUID> selected, @Nullable StrikePreview.PosePlan posePlan) {
        ServerLevel level = player.serverLevel();
        if (markedStrike != null) return "已有标定打击正在瞄准";
        if (flight.telemetrySnapshot() == null) return "飞控采样不可用";
        if (flight.manualFlightInputSince(level.getGameTime() - 1)) return "驾驶员正在操纵飞船";
        if (!markedChannelAvailable(channel, selected, weaponMode)) return "炮位或频道已被其他火控任务占用";
        boolean poseStrike = weaponMode == StrikePreview.Mode.TURRET_POSE;
        if (poseStrike || weaponMode == StrikePreview.Mode.AXIS_BEAM) {
            if (flight.mode() != BridgeCoreBlockEntity.ControlMode.STARSHIP) return "请先启用 STARSHIP 舰体指向";
            if (axisStrikeBusy(flight, null)) return "舰体正在执行指向或导航任务";
        }
        StrikePreview.Assessment assessment = null;
        if (poseStrike) {
            if (posePlan == null || !ship.getUniqueId().equals(posePlan.ship()) || !selected.equals(posePlan.turrets())) {
                return "确认的舰姿或炮位已失效，请重新试算";
            }
        } else {
            assessment = StrikePreview.assess(level, ship, roster, channel, weaponMode,
                    target, selected, StrikePreview.SightCheck.ESTIMATED);
            if (!assessment.executableWeapons().equals(selected)) return MarkedStrike.unavailable(assessment);
        }
        markedStrike = new MarkedStrike(player, origin, flight, ship, target, channel, roster.revision(),
                weaponMode, selected, assessment, posePlan);
        for (var id : selected) {
            turretGate.remove(id);
            hudGateSamples.remove(id);
        }
        return null;
    }

    boolean markedChannelAvailable(int channel, Set<UUID> selected, StrikePreview.Mode weaponMode) {
        if (channel < 0 || channel >= channelCount || selected.isEmpty()) return false;
        if (markedGroupBusy(channel)) return false;
        for (var id : selected) {
            int groups;
            if (weaponMode == StrikePreview.Mode.AXIS_BEAM) {
                FixedGun gun = fixedGuns.get(id);
                if (gun == null || !gun.belongsTo(channel)) return false;
                groups = gun.groups();
            } else {
                TurretUnit unit = turrets.get(id);
                if (unit == null || !unit.belongsTo(channel) || !unit.actuator().isPaired()) return false;
                groups = unit.groups();
            }
            for (int members = groups & WeaponGroups.validMask(channelCount); members != 0; members &= members - 1) {
                int member = Integer.numberOfTrailingZeros(members);
                if (markedGroupBusy(member)) return false;
            }
        }
        return true;
    }

    private boolean markedGroupBusy(int channel) {
        return tasks[channel].mode() != ChannelTask.Mode.IDLE || salvoWaiting[channel] || hHeld[channel];
    }

    boolean axisStrikeBusy(BridgeCoreBlockEntity flight, @Nullable UUID requester) {
        var navigation = flight.navigationStatus();
        if (flight.pointingActiveExcept(requester) || (navigation != null && navigation.phase() == NavigationController.Phase.ACTIVE)) return true;
        for (var gun : fixedGuns.values()) {
            int group = commandGroup(gun);
            if (group >= 0 && tasks[group].mode() == ChannelTask.Mode.PRECISE_LOCK) return true;
        }
        return false;
    }

    public @Nullable String cancelMarkedStrike(ServerPlayer player, UUID terminal) {
        var strike = markedStrike;
        if (strike == null || !(strike.origin instanceof MarkedStrike.Terminal origin)
                || !origin.id().equals(terminal) || !strike.player.equals(player.getUUID())) return null;
        return abortMarkedStrike(player.serverLevel(), "标定打击已取消");
    }

    public @Nullable String cancelCoordinateStrike(ServerPlayer player, FireControlMenu menu) {
        var strike = markedStrike;
        if (strike == null || !strike.origin.equals(new MarkedStrike.Console(menu))
                || !strike.player.equals(player.getUUID())) return null;
        return abortMarkedStrike(player.serverLevel(), "标定打击已取消");
    }

    private String abortMarkedStrike(ServerLevel level, String message) {
        if (markedStrike != null && markedStrike.axisSubmitted() && markedStrike.axisWarmingUp()) {
            message += "，已接受开火的轴炮继续原生预热";
        }
        finishMarkedStrike(level, message);
        return message;
    }

    private void finishMarkedStrike(ServerLevel level, String message) {
        finishMarkedStrike(level, message, false);
    }

    private void finishMarkedStrike(ServerLevel level, String message, boolean levelAfterRelease) {
        var strike = markedStrike;
        if (strike == null) return;
        markedStrike = null;
        strike.releasePointing(levelAfterRelease);
        reportMarkedStrike(level, strike, message);
    }

    private void reportMarkedStrike(ServerLevel level, MarkedStrike strike, String message) {
        sendMarkedStrikeReport(level, strike, message);
    }

    private static void sendMarkedStrikeReport(ServerLevel level, MarkedStrike strike, String message) {
        var player = level.getServer().getPlayerList().getPlayer(strike.player);
        if (player == null) return;
        if (strike.origin instanceof MarkedStrike.Terminal terminal) {
            TerminalReplyPayload.send(player, terminal.id(), message);
        } else {
            var console = (MarkedStrike.Console) strike.origin;
            FireControlStrikeResult.finished(player, console.menu(), message);
        }
    }

    private void fireMarkedStrike(ServerLevel level, MarkedStrike strike) {
        if (strike.isAxis()) {
            fireMarkedAxisStrike(level, strike);
            return;
        }
        markedStrike = null; // Consume the authorization before invoking any mount.
        int fired = 0;
        int total = 0;
        for (var shots : strike.shots()) total += shots.mounts().size();
        try {
            for (var shots : strike.shots()) fired += fireMounts(level, shots.turret(), shots.mounts(), shots.handles());
            String result = fired == 0 ? "标定打击中止，武器拒绝开火"
                    : "标定打击已执行，炮座接受 " + fired + "/" + total + " 发" + (strike.isPose() ? "，舰体开始找平" : "");
            reportMarkedStrike(level, strike, result);
        } catch (RuntimeException failure) {
            BridgeCoreMod.LOGGER.warn("[fire-control] marked strike weapon call failed at {}", worldPosition, failure);
            reportMarkedStrike(level, strike, "标定打击结束，武器接口异常，请查看服务器日志");
        } finally {
            strike.releasePointing(strike.isPose() && fired > 0);
        }
    }

    private void fireMarkedAxisStrike(ServerLevel level, MarkedStrike strike) {
        try {
            int accepted = strike.submitAxis(level.getGameTime());
            if (accepted == 0) {
                finishMarkedStrike(level, "标定打击中止，轴炮拒绝开火");
                return;
            }
            String message = "轴炮已接受 " + accepted + "/" + strike.selected.size() + " 发，预热期间保持舰体指向";
            sendMarkedStrikeReport(level, strike, message);
        } catch (RuntimeException failure) {
            BridgeCoreMod.LOGGER.warn("[fire-control] marked axis strike weapon call failed at {}", worldPosition, failure);
            abortMarkedStrike(level, "标定打击结束，武器接口异常，请查看服务器日志");
        }
    }

    private void driveTurret(ServerLevel level) {
        salvoFiredGroups = 0;
        tickTaskWatchdogs(level);
        for (int group = 0; group < channelCount; group++) {
            if (salvoWaiting[group] && salvoRevision[group] != tasks[group].rev()) salvoWaiting[group] = false;
        }
        var strike = markedStrike;
        if (strike != null) {
            String reason = strike.beginTick(level, this);
            if (reason != null) {
                abortMarkedStrike(level, "标定打击中止，" + reason);
                strike = null;
            } else if (strike.axisSubmitted() && !strike.axisWarmingUp()) {
                boolean fired = strike.axisFired();
                finishMarkedStrike(level, fired ? "轴炮打击结束，舰体开始找平" : "轴炮预热结束，未发射，舰体指向已交还", fired);
                strike = null;
            } else if (strike.axisSubmitted() && strike.axisWarmupTimedOut(level.getGameTime())) {
                abortMarkedStrike(level, "轴炮预热等待超时，舰体指向已交还");
                strike = null;
            }
        }
        Map<Integer, List<SalvoEntry>> salvoCandidates = null; // lazy: most ticks have no pending salvo
        for (TurretUnit unit : turrets.values()) {
            SwivelTurretActuator turret = unit.actuator();
            if (!turret.isPaired()) continue;
            // P3: resolve the linked fe gun mounts (opaque handles; null entries
            // when fe_railgun is absent or a mount block is gone)
            List<SwivelTurretActuator.Mount> mounts = turret.mounts();
            Object[] handles = new Object[mounts.size()];
            int[] codes = new int[mounts.size()];
            boolean allReady = !mounts.isEmpty();
            for (int m = 0; m < mounts.size(); m++) {
                handles[m] = MountLinks.active().mountAt(level, mounts.get(m).pos());
                codes[m] = MountLinks.active().mountStatusCode(handles[m]);
                if (codes[m] != RailgunLink.STATUS_READY) allReady = false;
            }
            turretMountStatus.put(unit.id(), codes);
            if (strike != null && strike.controls(unit)) {
                strike.drive(level, unit, mounts, handles, allReady);
                continue;
            }
            // Per-channel task resolution (§2.29 驱动回路)
            int ch = commandGroup(unit.groups());
            ChannelTask task = ch >= 0 ? tasks[ch] : null;
            Vec3 des = null;
            Target aimTarget = null;
            if (task != null) {
                switch (task.mode()) {
                    case PRECISE_LOCK -> aimTarget = resolveTaskTarget(task.preciseId());
                    case MULTI_LOCK -> {
                        Assignment a = turretAssignments.get(unit.id());
                        // rev 不匹配 = 任务已变更而分配未重算 — 作废,不瞄准
                        if (a != null && a.channel() == ch && a.taskRev() == task.rev()) {
                            aimTarget = resolveTaskTarget(a.targetId());
                        }
                    }
                    case FOLLOW -> {
                        // 视线跟随: pure direction, no target entity, no lead,
                        // no drop compensation (裁断稿 §7: 展示用低精度模式)
                        Player fp = task.followPlayer() != null
                                ? level.getPlayerByUUID(task.followPlayer()) : null;
                        if (fp != null) {
                            var l = fp.getLookAngle();
                            des = new Vec3(l.x, l.y, l.z);
                        }
                    }
                    case IDLE -> {}
                }
            }
            if (des == null && aimTarget != null) {
                Vec3 cannon = cannonWorldPos(level, turret);
                if (cannon != null) {
                    SolveChain.SolveOutcome out = SolveChain.solveCore(
                            paramsForUnit(unit, turret, mounts, handles),
                            buildInput(level, aimTarget, cannon));
                    if ("track".equals(out.src())) {
                        des = out.desWorld();
                    }
                }
            }
            turret.tick(level, des);
            // Fire interlock (P3) — the FireDirector gate chain, AUTO mode
            // (same gates as the volley, plus the channel switch + cadence;
            // 任务模型下无任务 ⇒ des==null ⇒ NO_TARGET 闸门自然拦住).
            // NaN aim error counts as no-target (the
            // actuator has not produced a solution yet); the LOF raycast is
            // lazy and only runs once every cheaper gate passed.
            double err = turret.getAimingErrorDeg();
            final Vec3 desF = des; // lambda capture (des is assigned above)
            FireDirector.GateResult gate = FireDirector.evaluate(
                    new FireDirector.Input(FireDirector.Mode.AUTO,
                            des != null && !Double.isNaN(err),
                            ch >= 0, ch >= 0 && !salvoWaiting[ch] && (channelAutoFire[ch] || hHeld[ch]),
                            allReady, firstBadDetail(mounts, codes),
                            err, turret.isInArc(),
                            level.getGameTime() - turret.lastFireTick() >= turret.fireIntervalTicks()),
                    () -> {
                        Vec3 muzzle = cannonWorldPos(level, turret);
                        return muzzle != null && lineOfFireBlocked(level, turret, muzzle, desF);
                    });
            turretGate.put(unit.id(), gate);
            hudGateSamples.put(unit.id(), new HudGateSample(ch, task == null ? -1 : task.rev(),
                    level.getGameTime(), gate));
            if (gate.fires()) {
                fireMounts(level, turret, mounts, handles);
            } else if (gate.gate() == FireDirector.Gate.BLOCKED) {
                turret.noteFireRejected("火线遮挡(本船结构)");
            }
            // 双击齐射 (协议 §6): collect the channel's turrets under the
            // VOLLEY gate chain (operator's explicit request — no cadence /
            // channel-switch gates, LOF along the barrel's ACTUAL direction).
            // Mountless turrets can't fire and don't veto the salvo.
            // P1-5 排队快照: 只收双击时刻在册的炮塔与炮座; 快照外的新成员
            // 既不参与也不否决; 快照内的炮塔但无快照炮座 = 不参与不否决。
            java.util.Set<String> snapshot = ch >= 0 ? salvoSnapshots.get(ch) : null;
            if (ch >= 0 && salvoWaiting[ch] && !mounts.isEmpty()
                    && snapshot != null && snapshot.contains(unit.id().toString())) {
                List<SwivelTurretActuator.Mount> sm = new ArrayList<>();
                List<Object> sh = new ArrayList<>();
                List<Integer> sc = new ArrayList<>();
                for (int m = 0; m < mounts.size(); m++) {
                    if (!snapshot.contains(unit.id() + "#" + mounts.get(m).no())) continue;
                    sm.add(mounts.get(m)); sh.add(handles[m]); sc.add(codes[m]);
                }
                if (sm.isEmpty()) continue;
                int[] scodes = new int[sc.size()];
                boolean allReadyS = true;
                Object[] shandles = sh.toArray();
                for (int m = 0; m < sc.size(); m++) {
                    scodes[m] = sc.get(m);
                    if (scodes[m] != RailgunLink.STATUS_READY) allReadyS = false;
                }
                FireDirector.GateResult vgate = FireDirector.evaluate(
                        new FireDirector.Input(FireDirector.Mode.VOLLEY,
                                !Double.isNaN(err), true, true,
                                allReadyS, firstBadDetail(sm, scodes),
                                err, turret.isInArc(), true),
                        () -> {
                            Vec3 muzzle = cannonWorldPos(level, turret);
                            Vec3 dir = turret.currentDirWorld();
                            return muzzle != null && dir != null
                                    && lineOfFireBlocked(level, turret, muzzle, dir);
                        });
                if (salvoCandidates == null) salvoCandidates = new LinkedHashMap<>();
                salvoCandidates.computeIfAbsent(ch, k -> new ArrayList<>())
                        .add(new SalvoEntry(turret, sm, shandles, vgate));
            }
        }
        if (strike != null && strike.allSettled()) {
            String reason = strike.checkBeforeFire(level, this);
            if (reason != null) finishMarkedStrike(level, "标定打击中止，" + reason);
            else if (strike.stillAimed()) fireMarkedStrike(level, strike);
        }
        for (int group = 0; group < channelCount; group++) {
            if (!salvoWaiting[group]) continue;
            List<SalvoEntry> entries = salvoCandidates == null ? List.of()
                    : salvoCandidates.getOrDefault(group, List.of());
            if (entries.stream().anyMatch(entry -> !entry.gate().fires())) continue;
            List<Object> macros = new ArrayList<>();
            boolean ready = true;
            for (FixedGun gun : fixedGuns.values()) {
                if (tasks[group].mode() != ChannelTask.Mode.PRECISE_LOCK || commandGroup(gun) != group) continue;
                // P1-5: 快照外新登记的轴炮不纳入本次请求, 也不否决
                java.util.Set<String> snap = salvoSnapshots.get(group);
                if (snap != null && !snap.contains(gun.id().toString())) continue;
                Object handle = RailgunLink.macroAt(level, gun.gunPos());
                int hardware = RailgunLink.macroStatusCode(handle);
                if (!macroAim(level, group, handle, hardware).ready()) {
                    ready = false;
                    break;
                }
                macros.add(handle);
            }
            if (!ready) continue;
            salvoWaiting[group] = false;
            salvoFiredGroups |= WeaponGroups.bit(group);
            if (entries.isEmpty() && macros.isEmpty()) {
                notifyNearby(level, "[火控核心] " + channelNames[group] + "：齐射取消，当前组没有可指挥的武器");
            } else {
                for (SalvoEntry entry : entries) fireMounts(level, entry.turret(), entry.mounts(), entry.handles());
                int fired = 0;
                for (Object macro : macros) if (RailgunLink.macroFire(macro) == null) fired++;
                notifyNearby(level, "[火控核心] " + channelNames[group] + "：齐射已执行，炮塔 "
                        + entries.size() + "，宏炮 " + fired + "/" + macros.size() + " 接受开火请求");
            }
            setChanged();
        }
    }

    // ---------------- 频道任务编排 (§2.29) ----------------

    /** A task target is aimable only while the registry still tracks it and it
     *  is not whitelisted (whitelist = 永不交战). */
    private @Nullable Target resolveTaskTarget(@Nullable String id) {
        if (id == null) return null;
        Target t = registry.targetById(id);
        return t != null && !t.whitelisted() ? t : null;
    }

    /** The prelock pool is valid while non-empty and not past its gameTime
     *  deadline (协议 §3: 心跳式连推, 超时视为候选源断开). */
    private boolean prelockValid(ServerLevel level) {
        return !prelockIds.isEmpty() && level.getGameTime() <= prelockExpiryTick;
    }

    /** Release a channel's task (rev+1 inside ChannelTask.release) and cancel
     *  the channel's pending fire requests (协议 §5/§6: 任务撤销即取消等待齐射
     *  与手动持续扳机). Drops the channel's assignments and marks dirty. */
    private void releaseTask(int ch) {
        tasks[ch].release();
        salvoWaiting[ch] = false;
        hHeld[ch] = false;
        preciseLostTicks[ch] = 0;
        turretAssignments.values().removeIf(a -> a.channel() == ch);
        assignmentsDirty = true;
        setChanged();
    }

    /** Chat feedback to every player within 64m of the core (same半径 as the
     *  old lock-lost notice — the operator pressing G is flying nearby). */
    private void notifyNearby(ServerLevel level, String msg) {
        for (Player p : level.players()) {
            if (p.distanceToSqr(worldPosition.getX() + 0.5,
                    worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) < 64 * 64) {
                p.sendSystemMessage(Component.literal(msg));
            }
        }
    }

    /** Prelock heartbeat bookkeeping (协议 §3/§5): ttl 超时 = 候选源断开 →
     *  所有 HUD 来源任务失效 (rev+1, promoteSuppressed 不动). Tab OFF 的刻意
     *  静默与源断开同效果 (协议 §5). */
    private void tickPrelock(ServerLevel level) {
        if (prelockExpiryTick != Long.MIN_VALUE && level.getGameTime() > prelockExpiryTick) {
            prelockExpiryTick = Long.MIN_VALUE;
            prelockIds = List.of();
            for (int ch = 0; ch < channelCount; ch++) {
                if (tasks[ch].source() == ChannelTask.Source.HUD) {
                    releaseTask(ch);
                }
            }
            recomputeAssignments(level);
        }
    }

    /** 自动升格 (裁断稿 §5): 升格开 && 未被人工暂停 && 任务 IDLE && prelock
     *  有效非空 → HUD MULTI_LOCK. 升格只进多实锁,永不进精确锁,也不授权开火. */
    private void maybeAutoPromote(ServerLevel level) {
        if (!prelockValid(level)) return;
        for (int ch = 0; ch < channelCount; ch++) {
            ChannelTask task = tasks[ch];
            if (channelAutoPromote[ch] && !task.promoteSuppressed()
                    && task.mode() == ChannelTask.Mode.IDLE
                    && hasReadyTurretMember(ch)) {
                task.toMulti(ChannelTask.Source.HUD);
                setChanged();
                recomputeAssignments(level); // rev 变化 → 立即重算 (评审 H)
            }
        }
    }

    private boolean hasReadyTurretMember(int group) {
        for (TurretUnit unit : turrets.values()) if (unit.belongsTo(group) && unit.isReady()) return true;
        return false;
    }

    /** Precise-lock watchdogs, one per channel (the old single-lock watchdog
     *  generalized): a PRECISE_LOCK target that stays unresolvable for
     *  LOCK_LOST_GRACE_TICKS ends the task — 不自动改锁 (协议 §5). */
    private void tickTaskWatchdogs(ServerLevel level) {
        for (int ch = 0; ch < channelCount; ch++) {
            ChannelTask task = tasks[ch];
            if (task.mode() != ChannelTask.Mode.PRECISE_LOCK) {
                preciseLostTicks[ch] = 0;
                continue;
            }
            Target t = resolveTaskTarget(task.preciseId());
            if (t != null && refreshLockedFast(level, t) != null) {
                preciseLostTicks[ch] = 0;
                continue;
            }
            if (++preciseLostTicks[ch] >= LOCK_LOST_GRACE_TICKS) {
                releaseTask(ch);
                notifyNearby(level, "[火控核心] " + channelNames[ch]
                        + ": 精确锁目标丢失(死亡/消失),任务结束");
            }
        }
    }

    // ---------------- 上行事件接收 (协议 §3, public SPI/console entries) ----------------

    /** PrelockUpdate receiver (协议 §3): the command HUD's roaming candidate
     *  picture, heartbeat-pushed. seq monotone (stale pushes dropped); expiry
     *  re-armed to gameTime+ttl even for an empty active cone. hudEnabled=false
     *  explicitly revokes all HUD tasks; empty active candidates leave precise
     *  anchors intact. Candidate presence itself never creates a task. */
    public void prelockUpdate(Level level, List<String> ids, long seq, int ttlTicks, boolean hudEnabled) {
        if (!(level instanceof ServerLevel sl)) return;
        if (seq <= prelockSeq) return; // 乱序/重复丢弃
        prelockSeq = seq;
        prelockIds = hudEnabled ? List.copyOf(ids) : List.of();
        prelockExpiryTick = hudEnabled ? sl.getGameTime() + Math.max(1, ttlTicks) : Long.MIN_VALUE;
        if (!hudEnabled) {
            for (int ch = 0; ch < channelCount; ch++) {
                if (tasks[ch].source() == ChannelTask.Source.HUD) {
                    releaseTask(ch);
                }
            }
        }
        recomputeAssignments(sl); // 候选集变化 → 立即重算
        setChanged();
    }

    /** Is this id an engageable target right now (tracked + not whitelisted)? */
    private boolean resolvable(@Nullable String id) {
        return resolveTaskTarget(id) != null;
    }

    /** GPress receiver (协议 §3 + 裁断稿 §4 仲裁表, 修订 G: 命令携带频道号,
     *  按下时刻捕获): 对**指定频道**仲裁 — 先查该频道任务是否 CONSOLE(FCS)
     *  来源,再查 HUD 候选。焦点取准星突出候选(飞控侧给 focusId); 长按无焦点
     *  不动作。channel 越界直接丢弃。Server thread (飞控侧直调核心). */
    public void gPress(boolean longPress, @Nullable String focusId, int channel) {
        if (channel < 0 || channel >= channelCount) return; // 修订 G: 越界丢弃
        if (!(getLevel() instanceof ServerLevel sl)) return;
        if (markedStrike != null && markedStrike.channel == channel) abortMarkedStrike(sl, "火控手动接管，标定打击已取消");
        int ch = channel;
        ChannelTask task = tasks[ch];
        String chName = channelNames[ch];
        if (task.source() == ChannelTask.Source.CONSOLE) {
            if (!longPress) {
                // FCS 短按 = 解除: 任务撤销 + 升格暂停 + 取消等待齐射/持续扳机
                // (releaseTask 已取消 salvoWaiting/hHeld)
                releaseTask(ch);
                tasks[ch].setPromoteSuppressed(true);
                notifyNearby(sl, "[火控核心] " + chName + ": FCS 已解除,待命(自动升格已暂停)");
            } else {
                // FCS 长按 = 候选有效且焦点可解析 → 接管为 HUD 精确锁;
                // 否则不动作(不解除原任务)
                if (prelockValid(sl) && resolvable(focusId)) {
                    task.toPrecise(ChannelTask.Source.HUD, focusId);
                    setChanged();
                    notifyNearby(sl, "[火控核心] " + chName + ": 已接管为 HUD 精确锁定 -> "
                            + labelOf(focusId));
                    recomputeAssignments(sl);
                } else {
                    notifyNearby(sl, "[火控核心] " + chName + ": 无有效候选,未接管(FCS 任务保留)");
                }
            }
            return;
        }
        // 非 FCS: prelock 无效(过期或空) → 长短按均无操作
        if (!longPress && task.mode() == ChannelTask.Mode.PRECISE_LOCK) {
            releaseTask(ch);
            task.setPromoteSuppressed(true);
            notifyNearby(sl, "[火控核心] " + chName + ": 精确锁已解除,回预锁(自动升格已暂停)");
            return;
        }
        if (!prelockValid(sl)) return;
        if (!longPress) {
            switch (task.mode()) {
                case IDLE -> {
                    task.toMulti(ChannelTask.Source.HUD);
                    task.setPromoteSuppressed(false); // 再次按 G = 升格恢复入口 (裁断稿 §5)
                    setChanged();
                    notifyNearby(sl, "[火控核心] " + chName + ": 进入多目标实锁");
                    recomputeAssignments(sl);
                }
                case MULTI_LOCK -> {
                    if (resolvable(focusId)) {
                        task.toPrecise(ChannelTask.Source.HUD, focusId);
                        setChanged();
                        notifyNearby(sl, "[火控核心] " + chName + ": 精确锁定 -> " + labelOf(focusId));
                    } else {
                        notifyNearby(sl, "[火控核心] " + chName + ": 无明确焦点目标,保持多实锁");
                    }
                }
                case PRECISE_LOCK -> {
                    releaseTask(ch);
                    tasks[ch].setPromoteSuppressed(true);
                    notifyNearby(sl, "[火控核心] " + chName + ": 精确锁已解除,回预锁(自动升格已暂停)");
                }
                case FOLLOW -> {} // FOLLOW 恒为 CONSOLE 来源,上面已处理
            }
        } else {
            // 长按 = 直接精确单锁(任何 HUD 态)
            if (resolvable(focusId)) {
                task.toPrecise(ChannelTask.Source.HUD, focusId);
                setChanged();
                notifyNearby(sl, "[火控核心] " + chName + ": 精确锁定 -> " + labelOf(focusId));
            } else {
                notifyNearby(sl, "[火控核心] " + chName + ": 无有效焦点,未建立精确锁");
            }
        }
    }

    /** V precise-target cycle (协议 §3 增补, 2026-10-30 GPT 设计 / 飞控代写,
     *  交流日志已报备): V 在精确锁定态 = 换锚定目标。窄入口,刻意不做成长 G
     *  的全权限——只在"仍是原来的 HUD 精确任务"时换目标: 校验 source==HUD、
     *  mode==PRECISE_LOCK、taskRev 与 HUD 快照一致(中途被改派/解除则不动作)、
     *  nextId 可解析。不隐式接管 FCS 任务(接管仍由长 G 完成),不创建任务。
     *  Server thread (飞控侧直调核心). */
    public void cyclePreciseTarget(int channel, String nextId, long expectedTaskRev) {
        if (channel < 0 || channel >= channelCount) return;
        if (!(getLevel() instanceof ServerLevel sl)) return;
        ChannelTask task = tasks[channel];
        if (task.source() != ChannelTask.Source.HUD
                || task.mode() != ChannelTask.Mode.PRECISE_LOCK
                || task.rev() != expectedTaskRev
                || !resolvable(nextId)) {
            return; // 非 HUD 精确态 / 快照过期 / 目标失效 — 静默不动作
        }
        task.toPrecise(ChannelTask.Source.HUD, nextId);
        setChanged();
        notifyNearby(sl, "[火控核心] " + channelNames[channel]
                + ": 精确锁定 -> " + labelOf(nextId));
        recomputeAssignments(sl);
    }

    /** HPress receiver (协议 §3/§6, 修订 G: 命令携带频道号,按下时刻捕获;
     *  HOLD 中途切组 HOLD_END 仍送达原频道 — 状态按频道分桶,天然满足).
     *  CLICK=本组当前任务一轮射击(立即,无视装填节奏); HOLD_START/END=手动
     *  持续扳机(驱动回路按炮塔自身节奏开火); DOUBLE=登记 salvoWaiting,
     *  全组就位即齐射(等待中由 HUD 经 channelSalvoWaiting 叙述). 任务撤销/
     *  换源/切组时 salvoWaiting/hHeld 被取消 (releaseTask/setActiveChannel). */
    public enum HPressKind { CLICK, HOLD_START, HOLD_END, DOUBLE }

    public void hPress(HPressKind kind, int channel) {
        if (channel < 0 || channel >= channelCount) return; // 修订 G: 越界丢弃
        if (!(getLevel() instanceof ServerLevel sl)) return;
        if (markedStrike != null && markedStrike.channel == channel && kind != HPressKind.HOLD_END) {
            abortMarkedStrike(sl, "火控手动接管，标定打击已取消");
        }
        switch (kind) {
            case CLICK -> {
                String msg = fireVolley(sl, channel);
                if (msg != null) notifyNearby(sl, msg);
            }
            case HOLD_START -> hHeld[channel] = true;
            case HOLD_END -> hHeld[channel] = false;
            case DOUBLE -> {
                ChannelTask.Mode m = tasks[channel].mode();
                if (m != ChannelTask.Mode.MULTI_LOCK && m != ChannelTask.Mode.PRECISE_LOCK) {
                    notifyNearby(sl, "[火控核心] " + channelNames[channel]
                            + " 无任务 — 双击齐射请求未登记(H 是扳机,不搜敌、不改任务)");
                } else if (!salvoWaiting[channel]) {
                    salvoWaiting[channel] = true;
                    salvoRevision[channel] = tasks[channel].rev();
                    salvoStartTick[channel] = sl.getGameTime();
                    salvoSnapshots.put(channel, snapshotSalvoMembers(channel));
                    notifyNearby(sl, "[火控核心] " + channelNames[channel]
                            + ": 齐射请求已登记,全组就位即击发(10s 未就位自动取消)");
                }
            }
        }
        setChanged();
    }

    /** P1-5 排队快照: 双击时刻在册的频道成员(炮塔 id + 炮座号 + 轴炮 id)。
     *  等待期间新配对/新挂载/新登记不纳入本次请求。 */
    private java.util.Set<String> snapshotSalvoMembers(int ch) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (TurretUnit u : turrets.values()) {
            if (commandGroup(u.groups()) != ch) continue;
            out.add(u.id().toString());
            for (var m : u.actuator().mounts()) out.add(u.id() + "#" + m.no());
        }
        for (FixedGun g : fixedGuns.values()) {
            if (commandGroup(g) != ch) continue;
            out.add(g.id().toString());
        }
        return out;
    }

    /** P1-5: 齐射等待 10s 超时——有成员但长期不就位不能永久挂单。 */
    private void tickSalvoTimeouts(ServerLevel sl) {
        for (int ch = 0; ch < channelCount; ch++) {
            if (salvoWaiting[ch] && sl.getGameTime() - salvoStartTick[ch] > SALVO_TIMEOUT_TICKS) {
                salvoWaiting[ch] = false;
                salvoSnapshots.remove(ch);
                notifyNearby(sl, "[火控核心] " + channelNames[ch]
                        + ": 齐射等待超时(10s),已取消——请重新双击");
                setChanged();
            }
        }
    }
    /** Display label for chat feedback (id → label, falls back to the id). */
    private String labelOf(String id) {
        Target t = registry.targetById(id);
        return t != null ? t.label() : id;
    }

    /** Per-turret solver params (P3): a linked fe gun mount contributes its
     *  LIVE muzzle velocity / slug gravity / drag and the pairing-time barrel
     *  length; no mount (or mounts without an fe gun) keeps DEFAULT_PARAMS.
     *  Multi-mount (多联装) conservative policy: the mount with the LOWEST
     *  muzzle velocity builds the params — the shared firing solution then
     *  carries the most drop margin, so faster barrels over-hit rather than
     *  the slow ones falling short. Cached per unit and refreshed every
     *  20 ticks or on any change of the mounts' ballistic signature. */
    private SolveChain.Params paramsForUnit(TurretUnit unit, SwivelTurretActuator turret,
                                            List<SwivelTurretActuator.Mount> mounts, Object[] handles) {
        StringBuilder sig = new StringBuilder();
        Object slowest = null;
        double minV = Double.MAX_VALUE;
        for (int m = 0; m < mounts.size(); m++) {
            sig.append('|').append(mounts.get(m).no()).append(':').append(MountLinks.active().signature(handles[m]));
            if (MountLinks.active().hasGun(handles[m])) {
                double v = MountLinks.active().muzzleVelocity(handles[m]);
                if (v > 0 && v < minV) {
                    minV = v;
                    slowest = handles[m];
                }
            }
        }
        String sigStr = sig.toString();
        SolveChain.Params cached = turretParamsCache.get(unit.id());
        if (cached == null || tickCount % 20 == 0 || !sigStr.equals(turretParamsSig.get(unit.id()))) {
            cached = MountLinks.active().paramsFor(slowest, DEFAULT_PARAMS, turret.barrelLengthBlocks());
            turretParamsCache.put(unit.id(), cached);
            turretParamsSig.put(unit.id(), sigStr);
        }
        return cached;
    }

    /** Auto-fire volley (P3): one shot per mount — the caller has already
     *  gated on every mount being READY. Any mount rejecting the shot lands
     *  in lastFireNote; a clean turret-wide volley clears it and paces the
     *  cooldown. */
    private int fireMounts(ServerLevel level, SwivelTurretActuator turret,
                            List<SwivelTurretActuator.Mount> mounts, Object[] handles) {
        StringBuilder rejected = null;
        int fired = 0;
        for (int m = 0; m < mounts.size(); m++) {
            String r = MountLinks.active().fire(handles[m]); // atomic: readiness re-run inside
            if (r != null) {
                if (rejected == null) rejected = new StringBuilder();
                else rejected.append('/');
                rejected.append("炮座").append(mounts.get(m).no()).append(' ').append(r);
            } else fired++;
        }
        if (rejected == null) {
            turret.noteFired(level.getGameTime());
        } else {
            turret.noteFireRejected(rejected.toString());
        }
        return fired;
    }

    /** 20 Hz fast path for the LOCKED target (2026-09-17, moving-target stutter
     *  fix ①): the 4 Hz scan only maintains the candidate list — a locked
     *  entity/ship is re-resolved directly every tick, so the solve chain
     *  tracks a smooth trajectory instead of a 4 Hz staircase (the staircase
     *  was ported straight from control_center.lua; direct control only fixed
     *  the actuator side).
     * @return {x,y,z,vx,vy,vz} in m and m/s, or null -> registry snapshot */
    private double @Nullable [] refreshLockedFast(ServerLevel level, Target t) {
        java.util.UUID id;
        try {
            id = java.util.UUID.fromString(t.id());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (t.category() == Category.SHIP) {
            ServerSubLevelContainer c = SubLevelContainer.getContainer(level);
            if (c != null && c.getSubLevel(id) instanceof ServerSubLevel sub) {
                var p = sub.logicalPose().position();
                var v = sub.latestLinearVelocity; // world, m/s
                return new double[]{p.x(), p.y(), p.z(), v.x(), v.y(), v.z()};
            }
            return null;
        }
        if (level.getEntity(id) instanceof LivingEntity e && e.isAlive()) {
            var dv = e.getDeltaMovement(); // blocks/tick
            return new double[]{e.getX(), e.getY(), e.getZ(), dv.x * 20, dv.y * 20, dv.z * 20};
        }
        return null;
    }

    private SolveChain.ChainInput buildInput(ServerLevel level, Target t, Vec3 cannon) {
        boolean ground = registeredSubLevel == null;
        Quat shipQuat = Quat.IDENTITY;
        Vec3 shipOmega = Vec3.ZERO, shipVel = Vec3.ZERO;
        Vec3 shipPos = new Vec3(worldPosition.getX() + 0.5,
                worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5);
        if (!ground) {
            var pose = registeredSubLevel.logicalPose();
            var o = pose.orientation();
            shipQuat = new Quat(o.w(), o.x(), o.y(), o.z());
            var lv = registeredSubLevel.latestLinearVelocity;  // world, m/s
            shipVel = new Vec3(lv.x(), lv.y(), lv.z());
            var av = registeredSubLevel.latestAngularVelocity; // body frame, rad/s
            shipOmega = new Vec3(av.x(), av.y(), av.z());
            var pp = pose.position();
            shipPos = new Vec3(pp.x(), pp.y(), pp.z());
        }
        Vec3 tgPos, tgVel; // the chain expects m/s (tgVelScale=0.05 folds it back)
        double[] fast = refreshLockedFast(level, t);
        if (fast != null) {
            tgPos = new Vec3(fast[0], fast[1], fast[2]);
            tgVel = new Vec3(fast[3], fast[4], fast[5]);
        } else {
            // registry snapshot: position of the last 4 Hz scan, velocity blocks/tick
            tgPos = new Vec3(t.x(), t.y(), t.z());
            tgVel = new Vec3(t.vx() * 20, t.vy() * 20, t.vz() * 20);
        }
        // loop-delay lead (tgVel is m/s; /20 -> per tick)
        tgPos = new Vec3(
                tgPos.x() + tgVel.x() / 20 * TRACK_LEAD_TICKS,
                tgPos.y() + tgVel.y() / 20 * TRACK_LEAD_TICKS,
                tgPos.z() + tgVel.z() / 20 * TRACK_LEAD_TICKS);
        return new SolveChain.ChainInput(
                tgPos, tgVel,
                3, false, ground,
                shipQuat, shipOmega, shipVel, shipPos, cannon,
                false, true);
    }

    /** Ballistic origin = the marked barrel-root block when present (on-axis —
     *  2026-09-17 topology correction: the pitch bearing sits one block BESIDE
     *  the barrel or behind a fixed cannon mount, it is not on the barrel
     *  axis); falls back to the pitch bearing (user ruling 2026-09-16, residual
     *  error along the axis is the least harmful). Follows the turret as it
     *  slews either way. */
    private @Nullable Vec3 cannonWorldPos(ServerLevel level, SwivelTurretActuator turret) {
        BlockPos base = turret.barrelBase() != null ? turret.barrelBase() : turret.pitchPos();
        if (base == null) return null;
        if (Sable.HELPER.getContaining(level, base) instanceof ServerSubLevel sub) {
            Vector3d dest = new Vector3d();
            sub.logicalPose().transformPosition(
                    new Vector3d(base.getX() + 0.5, base.getY() + 0.5, base.getZ() + 0.5), dest);
            return new Vec3(dest.x, dest.y, dest.z);
        }
        return new Vec3(base.getX() + 0.5, base.getY() + 0.5, base.getZ() + 0.5);
    }

    /** Line-of-fire occlusion (2026-10-06 self-hit incident: auto-fire tracked
     *  a mob under the fast-moving hull and the main gun shot straight THROUGH
     *  the ship — the gates checked READY/aim/arc but never what the shell
     *  would cross). Cast from just past the muzzle tip (skipping the barrel
     *  and any activation mount between base and tip) along the firing
     *  direction; any non-air block of the OWN hull or of this core's OTHER
     *  turrets vetoes the shot. The firing turret's own substructures are
     *  exempt (the ray starts inside them); foreign structures never veto —
     *  hitting them is the point. Ground batteries have no hull, so only
     *  sibling turrets count. */
    private boolean lineOfFireBlocked(ServerLevel level, SwivelTurretActuator firing, Vec3 muzzle, Vec3 dir) {
        List<ServerSubLevel> self = new ArrayList<>();
        if (registeredSubLevel != null && !registeredSubLevel.isRemoved()) {
            self.add(registeredSubLevel);
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        for (TurretUnit u : turrets.values()) {
            SwivelTurretActuator a = u.actuator();
            if (a == firing || !a.isPaired()) continue;
            for (java.util.UUID id : a.ownChildSubLevelIds(level)) {
                if (container != null && container.getSubLevel(id) instanceof ServerSubLevel s
                        && !s.isRemoved()) {
                    self.add(s);
                }
            }
        }
        if (self.isEmpty()) return false;
        Vec3 start = muzzle.add(dir.scale(firing.barrelLengthBlocks() + 1.0));
        for (ServerSubLevel s : self) {
            var bb = s.boundingBox();
            double[] tt = rayAabb(start, dir,
                    bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());
            if (tt == null) continue;
            var pose = s.logicalPose();
            for (double t = tt[0]; t <= tt[1]; t += 0.5) {
                Vec3 p = start.add(dir.scale(t));
                net.minecraft.world.phys.Vec3 local = pose.transformPositionInverse(
                        new net.minecraft.world.phys.Vec3(p.x(), p.y(), p.z()));
                if (!level.getBlockState(BlockPos.containing(local.x, local.y, local.z)).isAir()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Slab-test a ray against a world AABB; {tEnter, tExit} (tEnter >= 0) or null. */
    static double @Nullable [] rayAabb(Vec3 o, Vec3 d, double minX, double minY, double minZ,
                                               double maxX, double maxY, double maxZ) {
        double[] oa = {o.x(), o.y(), o.z()};
        double[] da = {d.x(), d.y(), d.z()};
        double[] mn = {minX, minY, minZ};
        double[] mx = {maxX, maxY, maxZ};
        double t0 = 0.0, t1 = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(da[i]) < 1e-9) {
                if (oa[i] < mn[i] || oa[i] > mx[i]) return null;
            } else {
                double ta = (mn[i] - oa[i]) / da[i];
                double tb = (mx[i] - oa[i]) / da[i];
                if (ta > tb) { double tmp = ta; ta = tb; tb = tmp; }
                t0 = Math.max(t0, ta);
                t1 = Math.min(t1, tb);
                if (t0 > t1) return null;
            }
        }
        return new double[]{t0, t1};
    }

    // ---------------- turret pairing (TurretPairing session endpoints) ----------------

    /** A completed pairing session creates a NEW turret unit. Duplicate-bearing
     *  guard (multi-turret v1 follow-up): re-pairing an existing unit = unpair
     *  it first, then pair again — but any unit already driving EITHER of these
     *  bearings is silently replaced (its AxisLock zero captures are re-armed
     *  so the new unit measures fresh). Two actuators writing the same bearing
     *  fight into a persistent offset, which presents as "precision lost".
     *  @return the replaced unit's name, or null when nothing was replaced */
    public @Nullable String completePairing(BlockPos yaw, BlockPos pitch, net.minecraft.world.phys.Vec3 look,
                                            @Nullable BlockPos barrelBase, @Nullable BlockPos muzzleTip,
                                            List<BlockPos> mounts) {
        BlockPos newYawOff = yaw.subtract(worldPosition);
        String replaced = null;
        for (java.util.Iterator<TurretUnit> it = turrets.values().iterator(); it.hasNext();) {
            TurretUnit u = it.next();
            SwivelTurretActuator a = u.actuator();
            // offset comparison survives reassembly plot moves (the absolute
            // BlockPos of a bearing changes; core-relative does not); the
            // absolute checks catch same-session duplicates on legacy units
            boolean same = newYawOff.equals(a.yawOffset())
                    || yaw.equals(a.yawPos()) || yaw.equals(a.pitchPos())
                    || pitch.equals(a.yawPos()) || pitch.equals(a.pitchPos());
            if (same) {
                replaced = u.name();
                a.unpair(getLevel()); // re-arms the AxisLock zero captures on those bearings
                it.remove();
            }
        }
        TurretUnit unit = new TurretUnit(java.util.UUID.randomUUID(), nextTurretName(), this::setChanged, this::getBlockPos);
        unit.actuator().pair(yaw, pitch, look, barrelBase, muzzleTip, mounts);
        turrets.put(unit.id(), unit);
        // 修订 E (2026-10-28): 配对/重建 = 该塔退出分配 + 任务立即重算;
        // 仅当频道因此无可用成员或无目标才失效任务(多塔频道不因单塔配对整组
        // 阵亡)。新塔校准完成前 isReady=false,天然领不到分配。
        if (getLevel() instanceof ServerLevel sl) recomputeAssignments(sl);
        setChanged();
        return replaced;
    }

    public boolean renameTurret(java.util.UUID id, String requested) {
        var unit = turrets.get(id);
        String name = WeaponGroups.cleanName(requested);
        if (unit == null || name.isEmpty()) return false;
        for (var other : turrets.values()) {
            if (other != unit && other.name().equals(name)) return false;
        }
        if (!unit.name().equals(name)) {
            unit.rename(name);
            setChanged();
        }
        return true;
    }

    private String nextTurretName() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (TurretUnit u : turrets.values()) names.add(u.name());
        for (int i = 1;; i++) {
            String n = "炮塔 " + i;
            if (!names.contains(n)) return n;
        }
    }

    /** Panel management: unpair one unit by id (修订 E: 该塔退出分配 + 任务
     *  立即重算; 仅当频道无可用成员或无目标才失效任务).
     *  @return the removed unit's name, or null when the id is unknown */
    public @Nullable String unpairUnit(java.util.UUID id, @Nullable Level level) {
        TurretUnit u = turrets.remove(id);
        if (u == null) return null;
        u.actuator().unpair(level);
        turretParamsCache.remove(id); // P3 caches die with the unit
        turretParamsSig.remove(id);
        turretMountStatus.remove(id);
        hudGateSamples.remove(id);
        turretAssignments.remove(id); // 退出分配
        if (level instanceof ServerLevel sl) recomputeAssignments(sl); // 立即重算
        setChanged();
        return u.name();
    }

    /** Management UI: recalibrate exactly this unit. Existing channel-task
     *  validity rules still apply when the last ready member leaves service. */
    public @Nullable String recalibrateUnit(java.util.UUID id, @Nullable Level level) {
        TurretUnit u = turrets.get(id);
        if (u == null || !u.actuator().isPaired()) return null;
        u.actuator().startRecalibration(level);
        turretAssignments.remove(id);
        hudGateSamples.remove(id);
        if (level instanceof ServerLevel sl) recomputeAssignments(sl);
        setChanged();
        return u.name();
    }

    /** Current insertion-order row. Menu commands never choose another row
     *  when an invalid index is supplied. */
    public @Nullable TurretUnit turretByIndex(int row) {
        if (row < 0) return null;
        int index = 0;
        for (TurretUnit u : turrets.values()) if (index++ == row) return u;
        return null;
    }

    /** Explicit membership edit; selecting a channel does not change the
     *  operator's active channel or any channel-wide fire preference.
     *  N2: returns false when the mutual-exclusion rule (轴炮/炮塔不同频道)
     *  rejected the edit — callers must give the player a receipt. */
    public boolean setTurretChannel(int row, int channel) {
        if (channel < -1 || channel >= channelCount) return false;
        TurretUnit unit = turretByIndex(row);
        return unit != null && setTurretGroups(unit, WeaponGroups.bit(channel));
    }

    public boolean editTurretGroup(int row, int group, boolean join) {
        if (group < 0 || group >= channelCount) return false;
        TurretUnit unit = turretByIndex(row);
        if (unit == null) return false;
        int bit = WeaponGroups.bit(group);
        return setTurretGroups(unit, join ? unit.groups() | bit : unit.groups() & ~bit);
    }

    private boolean setTurretGroups(TurretUnit unit, int groups) {
        if (unit.groups() == groups) return true;
        if ((groups & ~unit.groups() & fixedGunGroups()) != 0) return false; // N2: 互斥拒绝要回执
        unit.setGroups(groups);
        turretAssignments.remove(unit.id());
        hudGateSamples.remove(unit.id());
        if (getLevel() instanceof ServerLevel sl) recomputeAssignments(sl);
        setChanged();
        return true;
    }

    /** All paired turrets in pairing order (panel/chat status, scan exclusion). */
    public java.util.Collection<TurretUnit> turrets() {
        return turrets.values();
    }

    /** GUI unpair button: resolve the row index (insertion order — the menu
     *  broadcasts rows in the same order, so indices agree) to a unit id. */
    public @Nullable String unpairByIndex(int index, @Nullable Level level) {
        int i = 0;
        for (TurretUnit u : turrets.values()) {
            if (i++ == index) return unpairUnit(u.id(), level);
        }
        return null;
    }

    /** GUI arc drag handle (P2): resolve the row index (insertion order, same
     *  as the menu broadcast) and set one fire-arc bound to an absolute value. */
    public void setArc(int row, int param, double valueDeg) {
        int i = 0;
        for (TurretUnit u : turrets.values()) {
            if (i++ == row) {
                u.actuator().setArc(param, valueDeg);
                return;
            }
        }
    }

    /** GUI fire toggle (P3 武器频道): flip the auto-fire switch of the CHANNEL
     *  the row turret belongs to. @return the unit, null when the row is out
     *  of range OR the turret is ungrouped (编组即指挥 — an ungrouped turret
     *  has no switch to flip). */
    public @Nullable TurretUnit toggleAutoFire(int row) {
        int i = 0;
        for (TurretUnit u : turrets.values()) {
            if (i++ == row) {
                if (commandGroup(u.groups()) < 0) return null;
                channelAutoFire[commandGroup(u.groups())] = !channelAutoFire[commandGroup(u.groups())];
                setChanged();
                return u;
            }
        }
        return null;
    }

    // ---------------- 武器频道: panel/menu-facing state ----------------

    public int channelCount() { return channelCount; }

    public int commandGroup(int groups) {
        return commandGroup(groups, false);
    }

    public int commandGroup(FixedGun gun) {
        return commandGroup(gun.groups(), true);
    }

    private int commandGroup(int groups, boolean macro) {
        groups &= WeaponGroups.validMask(channelCount);
        int engaged = 0;
        for (int remaining = groups; remaining != 0; remaining &= remaining - 1) {
            int group = Integer.numberOfTrailingZeros(remaining);
            ChannelTask.Mode mode = tasks[group].mode();
            if (macro ? mode == ChannelTask.Mode.PRECISE_LOCK : mode != ChannelTask.Mode.IDLE)
                engaged |= WeaponGroups.bit(group);
        }
        return WeaponGroups.owner(groups, activeChannel, engaged);
    }

    public boolean addChannel() {
        if (channelCount == WeaponGroups.MAX) return false;
        setActiveChannel(channelCount++);
        setChanged();
        return true;
    }

    public boolean renameChannel(int channel, String requested) {
        String name = WeaponGroups.cleanName(requested);
        if (channel < 0 || channel >= channelCount || name.isEmpty()) return false;
        channelNames[channel] = name;
        setChanged();
        return true;
    }

    public int activeChannel() {
        return activeChannel;
    }

    /** 切组即取消**旧频道**未了的射击请求 (协议 §6 + 修订 G: CycleChannel 取消
     *  旧组手动扳机与齐射等待; 任务撤销/换源/切组即取消等待)。任务本身跨切组
     *  保留 — X 只是选择指挥对象。实际取消到东西时给 64m 通报。 */
    public void setActiveChannel(int ch) {
        if (ch >= 0 && ch < channelCount && ch != activeChannel) {
            if ((salvoWaiting[activeChannel] || hHeld[activeChannel])
                    && getLevel() instanceof ServerLevel sl) {
                notifyNearby(sl, "[火控核心] " + channelNames[activeChannel]
                        + ": 切组已取消等待齐射/持续扳机");
            }
            salvoWaiting[activeChannel] = false;
            hHeld[activeChannel] = false;
            activeChannel = ch;
            macroReadouts.clear();
            if (getLevel() instanceof ServerLevel sl) recomputeAssignments(sl);
            setChanged();
        }
    }

    /** X 键切频道入口(飞控键位通道 19, 2026-10-26 交流日志): 循环
     *  一→二→三→四→一。飞控侧经 {@link #coresOf} 枚举同结构火控核心后对
     *  每座核心调本方法(多核心船各核心同步换频道, 评审 F), 飞控不维护
     *  频道状态副本, 显示以快照为准。 */
    public void cycleChannel() {
        setActiveChannel((activeChannel + 1) % channelCount);
    }

    /** The authoritative task of a channel (协议 §1). */
    public ChannelTask taskOf(int ch) {
        return tasks[ch];
    }

    /** Channel display name (NBT-persisted; rename UI 下批). */
    public String channelName(int ch) {
        return ch >= 0 && ch < channelCount ? channelNames[ch] : "?";
    }

    /** Read-only scheduler flag for HUD narration (no request is issued). */
    public boolean channelSalvoWaiting(int ch) {
        return ch >= 0 && ch < channelCount && salvoWaiting[ch];
    }

    public boolean channelAutoFire(int ch) {
        return ch >= 0 && ch < channelCount && channelAutoFire[ch];
    }

    public void toggleChannelAutoFire(int ch) {
        if (ch >= 0 && ch < channelCount) {
            channelAutoFire[ch] = !channelAutoFire[ch];
            setChanged();
        }
    }

    /** 自动升格 preference (裁断稿 §5): 开 = IDLE 且 prelock 有效时自动进
     *  HUD 多实锁。偏好翻转不 bump rev,也不撤销既有任务 — 升格只是
     *  PRELOCK→多实锁 的省键入口,已建立的任务(无论来源)不受影响。 */
    public boolean channelAutoPromote(int ch) {
        return ch >= 0 && ch < channelCount && channelAutoPromote[ch];
    }

    public void toggleChannelAutoPromote(int ch) {
        if (ch >= 0 && ch < channelCount) {
            channelAutoPromote[ch] = !channelAutoPromote[ch];
            if (channelAutoPromote[ch]) {
                // 重新启用升格 = 人工暂停的恢复入口 (裁断稿 §5; 不 bump rev)
                tasks[ch].setPromoteSuppressed(false);
            }
            setChanged();
        }
    }

    /** 频道内已配对炮塔数 — 控制台点选容量 N (协议 §1: 按成员资格计,
     *  不随临时未就绪收缩)。 */
    public int pairedCountOf(int ch) {
        int n = 0;
        for (TurretUnit u : turrets.values()) {
            if (u.belongsTo(ch) && u.actuator().isPaired()) n++;
        }
        return n;
    }

    /** Multi-lock engagement count of a channel (panel 跟踪行: "交战 n 目标"
     *  的塔数口径 — 分配到任务的塔数). */
    public int assignedTargetCount(int ch) {
        int n = 0;
        ChannelTask task = tasks[ch];
        for (Map.Entry<java.util.UUID, Assignment> e : turretAssignments.entrySet()) {
            TurretUnit u = turrets.get(e.getKey());
            if (u != null && commandGroup(u.groups()) == ch && e.getValue().channel() == ch && e.getValue().taskRev() == task.rev()) n++;
        }
        return n;
    }

    /** 唯一交战目标数 (GPT 复审 I): 多塔集火同一目标时塔数口径虚高,
     *  面板"交战 n 目标"与 HUD 红框应数唯一目标。 */
    public int assignedUniqueTargetCount(int ch) {
        ChannelTask task = tasks[ch];
        java.util.Set<String> uniq = new java.util.HashSet<>();
        for (Map.Entry<java.util.UUID, Assignment> e : turretAssignments.entrySet()) {
            TurretUnit u = turrets.get(e.getKey());
            if (u != null && commandGroup(u.groups()) == ch && e.getValue().channel() == ch && e.getValue().taskRev() == task.rev()) {
                uniq.add(e.getValue().targetId());
            }
        }
        return uniq.size();
    }

    /** HUD 红框依据 (协议 §4 ChannelTaskView.assignedTargetIds): 该频道核心
     *  实际分配的唯一目标 id — PRECISE_LOCK = 锚定 id(可解析时);
     *  MULTI_LOCK = 当前 rev 下各塔分配的去重集合; 其余 = 空。 */
    public List<String> assignedUniqueTargetIds(int ch) {
        ChannelTask task = tasks[ch];
        if (task.mode() == ChannelTask.Mode.PRECISE_LOCK) {
            return resolvable(task.preciseId()) ? List.of(task.preciseId()) : List.of();
        }
        if (task.mode() != ChannelTask.Mode.MULTI_LOCK) return List.of();
        java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();
        for (Map.Entry<java.util.UUID, Assignment> e : turretAssignments.entrySet()) {
            TurretUnit u = turrets.get(e.getKey());
            if (u != null && commandGroup(u.groups()) == ch && e.getValue().channel() == ch && e.getValue().taskRev() == task.rev()) {
                uniq.add(e.getValue().targetId());
            }
        }
        return List.copyOf(uniq);
    }

    /** The target id this unit is currently assigned to (频道 MULTI_LOCK),
     *  null = none. Stale-rev assignments read as none (评审 H 即时失效).
     *  For the panel/HUD narration; the id resolves via TargetRegistry.targetById. */
    public @Nullable String assignedTargetId(java.util.UUID unitId) {
        Assignment a = turretAssignments.get(unitId);
        if (a == null) return null;
        TurretUnit u = turrets.get(unitId);
        if (u == null || commandGroup(u.groups()) < 0) return null;
        return a.channel() == commandGroup(u.groups()) && a.taskRev() == tasks[a.channel()].rev() ? a.targetId() : null;
    }

    public @Nullable TurretUnit toggleTurretChannel(int row) {
        TurretUnit unit = turretByIndex(row);
        if (unit != null) editTurretGroup(row, activeChannel, !unit.belongsTo(activeChannel));
        return unit;
    }

    /** Status-page stepper: cycle the ROW TURRET's own cadence through
     *  SwivelTurretActuator.FIRE_INTERVAL_GEARS — a weapon property, editable
     *  whenever the turret is paired (机炮按到 4 = 300 rpm, the breech's own
     *  BREECH_BUSY throttles the excess for free). */
    public void cycleFireInterval(int row, int dir) {
        int i = 0;
        for (TurretUnit u : turrets.values()) {
            if (i++ == row) {
                u.actuator().cycleFireInterval(dir);
                setChanged();
                return;
            }
        }
    }

    /** Panel settings label (P3/LASER): gun-type summary of the row turret's
     *  mounts ("激光机炮×2", "磁轨炮", mixed "激光机炮+磁轨炮"); null = no usable
     *  gun. Resolves handles live — cheap BE lookups, and the MountLink
     *  no-ops when no gun mod is present (label is just null then). */
    public @Nullable String turretGunLabel(int row) {
        Level level = getLevel();
        if (level == null) return null;
        int i = 0;
        for (TurretUnit u : turrets.values()) {
            if (i++ != row) continue;
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (SwivelTurretActuator.Mount m : u.actuator().mounts()) {
                Object h = MountLinks.active().mountAt(level, m.pos());
                if (MountLinks.active().hasGun(h)) {
                    counts.merge(MountLinks.active().gunTypeName(h), 1, Integer::sum);
                }
            }
            if (counts.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (sb.length() > 0) sb.append('+');
                sb.append(gunNameZh(e.getKey()));
                if (e.getValue() > 1) sb.append('×').append(e.getValue());
            }
            return sb.toString();
        }
        return null;
    }

    private static String gunNameZh(String gunType) {
        // 按对面 zh_cn.json+contraption 源码核实(2026-10-06):RAILGUN 的炮管是
        // 粒子加速炮管(纯FE无弹),PARTICLE 的炮管是磁轨加速炮管(吃CBC炮弹)
        // ——对面交接文档的括号注把这两个写反了,以源码为准
        return FireControlUiText.weapon(gunType);
    }

    /** Status-page stepper visibility: paired AND at least one breech-less gun
     *  mounted (粒子炮/激光机炮 — 磁轨炮/线圈炮 pace at their quickfiring breech,
     *  a user cadence below that is meaningless). */
    public boolean turretCadenceAdjustable(int row) {
        Level level = getLevel();
        if (level == null) return false;
        int i = 0;
        for (TurretUnit u : turrets.values()) {
            if (i++ != row) continue;
            if (!u.actuator().isPaired()) return false;
            for (SwivelTurretActuator.Mount m : u.actuator().mounts()) {
                if (MountLinks.active().breechless(MountLinks.active().mountAt(level, m.pos()))) return true;
            }
            return false;
        }
        return false;
    }

    /** GUI mount-status cells (P3): the drive loop's last per-mount readiness
     *  codes for this unit (MountLink.STATUS_*), parallel to the actuator's
     *  mounts() slot order; empty until the first tick / no mounts. */
    public int[] mountStatusCodesOf(java.util.UUID unitId) {
        return turretMountStatus.getOrDefault(unitId, new int[0]);
    }

    /** Aggregate row status (ContainerData +10): -1 no mounts, 1 when EVERY
     *  mount is READY (the volley gate), otherwise the first non-READY code. */
    public int mountAggregateOf(java.util.UUID unitId, int mountCount) {
        if (mountCount <= 0) return RailgunLink.STATUS_NO_MOUNT;
        int[] codes = mountStatusCodesOf(unitId);
        for (int c : codes) {
            if (c != RailgunLink.STATUS_READY) return c;
        }
        return codes.length == 0 ? RailgunLink.STATUS_NO_MOUNT : RailgunLink.STATUS_READY;
    }

    /** GUI chip click (P3): unlink mount slot {@code slotIdx} of the turret at
     *  the broadcast row index. @return the chat message body, or null when
     *  the row/slot is out of range */
    public @Nullable String unbindMount(int row, int slotIdx) {
        int i = 0;
        for (TurretUnit u : turrets.values()) {
            if (i++ == row) {
                SwivelTurretActuator a = u.actuator();
                List<SwivelTurretActuator.Mount> mounts = a.mounts();
                if (slotIdx < 0 || slotIdx >= mounts.size()) return null;
                int no = mounts.get(slotIdx).no();
                a.removeMount(no);
                setChanged();
                return u.name() + " · 炮座 " + no + " 已卸下";
            }
        }
        return null;
    }

    /** Session-less mount link/unlink (P3 fix 2, compass + right-click a mount
     *  outside a pairing session): the owning turret is identified by matching
     *  the mount's containing sub-level against each paired turret's bearing-
     *  child sub-level ids (the gun plot). Clicking an already-linked mount
     *  unlinks THAT mount; clicking a new mount links it with the next issued
     *  number (numbers are never renumbered on unlink). Unassembled turrets
     *  have no child id and never match.
     *  @return the chat message body (without the "[火控核心] " prefix), or
     *          null when no paired turret owns this mount */
    public static @Nullable String toggleMountAt(Level level, BlockPos pos) {
        if (!(Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel sub)) return null;
        java.util.UUID subId = sub.getUniqueId();
        List<FireControlCoreBlockEntity> cores = REGISTRY.get(level);
        if (cores == null) return null;
        for (FireControlCoreBlockEntity core : cores) {
            for (TurretUnit u : core.turrets.values()) {
                SwivelTurretActuator a = u.actuator();
                if (!a.isPaired() || !a.ownChildSubLevelIds(level).contains(subId)) continue;
                int existing = a.mountNoAt(pos);
                if (existing > 0) {
                    a.removeMount(existing);
                    core.setChanged();
                    return u.name() + " · 炮座 " + existing + " 已卸下";
                }
                int no = a.addMount(pos);
                if (no < 0) {
                    return u.name() + " 炮座已达上限(" + SwivelTurretActuator.MAX_MOUNTS + ")";
                }
                core.setChanged();
                return u.name() + " · 炮座 " + no + " 已挂载";
            }
        }
        return null;
    }

    /** Axis-gun (轴炮) registration — session-less compass click on an
     *  fe_railgun MACRO cannon core (axis-gun-design.md §2; detected via
     *  RailgunLink.macroAt by the caller, NOT a cannon mount). The macro core
     *  must sit on the SAME main structure as a fire-control core: hull-fixed
     *  guns aim by pointing the ship, so ground guns and guns on other
     *  sub-assemblies are rejected. Re-click unregisters.
     *  @return the chat message body, or null when no core shares the
     *          gun's main structure (caller keeps its generic hint) */
    public static @Nullable String toggleFixedGunAt(Level level, BlockPos pos) {
        if (!(Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel sub)) return null;
        java.util.UUID subId = sub.getUniqueId();
        List<FireControlCoreBlockEntity> cores = REGISTRY.get(level);
        if (cores == null) return null;
        for (FireControlCoreBlockEntity core : cores) {
            if (core.isRemoved() || core.getLevel() == null) continue;
            if (!(Sable.HELPER.getContaining(core.getLevel(), core.getBlockPos())
                    instanceof ServerSubLevel coreSub)) continue;
            if (!coreSub.getUniqueId().equals(subId)) continue;
            FixedGun existing = null;
            for (FixedGun g : core.fixedGuns.values()) {
                if (g.gunPos().equals(pos)) { existing = g; break; }
            }
            if (existing != null) {
                core.fixedGuns.remove(existing.id());
                core.setChanged();
                return existing.name() + " 已注销";
            }
            if (core.fixedGuns.size() >= MAX_FIXED_GUNS) {
                return "轴炮已达上限(" + MAX_FIXED_GUNS + ")";
            }
            FixedGun gun = new FixedGun(java.util.UUID.randomUUID(),
                    FixedGun.nextName(core.fixedGuns.values()), pos);
            core.fixedGuns.put(gun.id(), gun);
            core.setChanged();
            return gun.name() + " 已登记(未编组 — 请到核心「武器频道」页编入频道)";
        }
        return null;
    }

    /** All registered axis guns in registration order (3b GUI / 3c pointing). */
    public java.util.Collection<FixedGun> fixedGuns() {
        return fixedGuns.values();
    }

    private int turretGroups() {
        int mask = 0;
        for (var unit : turrets.values()) mask |= unit.groups();
        return mask;
    }

    private int fixedGunGroups() {
        int mask = 0;
        for (var gun : fixedGuns.values()) mask |= gun.groups();
        return mask;
    }

    public int axialStrikeChannels() {
        return WeaponGroups.axialOnly(fixedGunGroups(), turretGroups()) & WeaponGroups.validMask(channelCount);
    }

    /** Current registration-order row. Never chooses another row on an
     *  invalid index (turretByIndex rule). */
    public @Nullable FixedGun fixedGunByIndex(int row) {
        if (row < 0) return null;
        int index = 0;
        for (FixedGun g : fixedGuns.values()) if (index++ == row) return g;
        return null;
    }

    /** Explicit membership edit (axis-gun-design.md §4): exclusive channel,
     *  -1 = ungrouped; does not touch the operator's active channel or any
     *  channel-wide preference. Axis guns get no TargetAssigner assignments
     *  in v1 (MULTI_LOCK 不参战), so there is no assignment table to drop. */
    public boolean setFixedGunChannel(int row, int channel) {
        if (channel < -1 || channel >= channelCount) return false;
        FixedGun gun = fixedGunByIndex(row);
        if (gun == null) return false;
        return setFixedGunGroups(gun, WeaponGroups.bit(channel));
    }

    public boolean editFixedGunGroup(int row, int group, boolean join) {
        if (group < 0 || group >= channelCount) return false;
        FixedGun gun = fixedGunByIndex(row);
        if (gun == null) return false;
        int bit = WeaponGroups.bit(group);
        return setFixedGunGroups(gun, join ? gun.groups() | bit : gun.groups() & ~bit);
    }

    private boolean setFixedGunGroups(FixedGun gun, int groups) {
        if (gun.groups() == groups) return true;
        if ((groups & ~gun.groups() & turretGroups()) != 0) return false; // N2: 互斥拒绝要回执
        gun.setGroups(groups);
        macroReadouts.remove(gun.id());
        setChanged();
        return true;
    }

    // ---------------- 轴炮 3c: 指向触发 + 开火闸门 ----------------

    /** Persistent requester id for the flight core's pointing service. */
    public java.util.UUID coreUuid() {
        if (coreUuid == null) {
            coreUuid = java.util.UUID.randomUUID();
            setChanged();
        }
        return coreUuid;
    }

    /** The bridge core on our OWN structure (修订 H 一船一核 → first live
     *  core). Null on ground installations: no hull, no pointing. */
    private @Nullable BridgeCoreBlockEntity flightCoreOnOwnStructure(ServerLevel sl) {
        if (!(Sable.HELPER.getContaining(sl, worldPosition) instanceof ServerSubLevel sub)) return null;
        for (BridgeCoreBlockEntity core : CoreDirectory.coresOf(sub)) {
            if (!core.isRemoved()) return core;
        }
        return null;
    }

    /** 20 Hz (axis-gun-design.md §5): the FIRST channel holding a resolvable
     *  PRECISE_LOCK with ≥1 axis-gun member drives the ship's pointing;
     *  anything else releases. MULTI_LOCK/FOLLOW never point. Renewal rides
     *  the flight core's 500 ms watchdog; target death/unload ends the task
     *  via the existing precise watchdog, which lands here as a release. */
    private void tickAxisGuns(ServerLevel sl) {
        macroReadouts.clear();
        if (fixedGuns.isEmpty()) {
            releaseAxisPointing(sl);
            return;
        }
        int want = -1;
        double[] target = null;
        double[] origin = null;
        for (int i = 0; i < channelCount; i++) {
            int ch = i == 0 ? activeChannel : i <= activeChannel ? i - 1 : i;
            ChannelTask task = tasks[ch];
            if (task.mode() != ChannelTask.Mode.PRECISE_LOCK) continue;
            Target contact = resolveTaskTarget(task.preciseId());
            if (contact == null) continue;
            double[] fresh = refreshLockedFast(sl, contact);
            if (fresh == null) continue;
            for (FixedGun gun : fixedGuns.values()) {
                if (commandGroup(gun) != ch) continue;
                origin = RailgunLink.macroBeamAxis(RailgunLink.macroAt(sl, gun.gunPos()));
                if (origin != null) break;
            }
            if (origin == null) continue;
            want = ch;
            target = fresh;
            break;
        }
        BridgeCoreBlockEntity flight = want < 0 ? null : flightCoreOnOwnStructure(sl);
        if (flight == null) {
            releaseAxisPointing(sl);
        } else {
            // POINT_AT_DIR (2026-11-08 实机视差修复, 飞控 36f38fe 建议):
            // TRACK_ENTITY/POINT_AT_POS 从**船逻辑中心**对准, 与射束闸门的
            // **炮位原点**对准差一个固定视差(≈炮位偏移/距离, 实测 3.5°)。
            // 改发方向向量: dir = 目标 − 该炮射束原点, 两侧基准一致。
            net.minecraft.world.phys.Vec3 dir = new net.minecraft.world.phys.Vec3(
                    target[0] - origin[0], target[1] - origin[1], target[2] - origin[2]).normalize();
            flight.requestPointing(coreUuid(), PointingMode.POINT_AT_DIR, dir,
                    null, POINTING_MAX_RATE, POINTING_MAX_ACCEL);
            pointingChannel = want;
        }

        for (FixedGun gun : fixedGuns.values()) {
            int group = commandGroup(gun);
            Object handle = RailgunLink.macroAt(sl, gun.gunPos());
            int hardware = RailgunLink.macroStatusCode(handle);
            var aim = macroAim(sl, group, handle, hardware);
            if (aim.ready() && !salvoWaiting[group] && (salvoFiredGroups & WeaponGroups.bit(group)) == 0
                    && (channelAutoFire[group] || hHeld[group])) {
                RailgunLink.macroFire(handle);
                hardware = RailgunLink.macroStatusCode(handle);
                if (hardware != RailgunLink.STATUS_READY) aim = new MacroReadout.Aim(
                        MacroReadout.Gate.HARDWARE, aim.degrees(), aim.distance(), aim.range());
            }
            var timing = RailgunLink.macroTiming(handle);
            macroReadouts.put(gun.id(), new MacroReadout(group, group < 0 ? -1 : tasks[group].rev(),
                    sl.getGameTime(), hardware, RailgunLink.macroEnergyStored(handle),
                    RailgunLink.macroEnergyCapacity(handle), RailgunLink.macroHeat(handle),
                    RailgunLink.macroMaxHeat(handle), timing.shotCost(), timing.warmupTicks(),
                    timing.cooldownTicks(), timing.phase(), aim));
        }
    }

    public @Nullable MacroReadout macroReadout(FixedGun gun) {
        MacroReadout sample = macroReadouts.get(gun.id());
        if (sample == null || getLevel() == null) return null;
        int group = commandGroup(gun);
        long age = getLevel().getGameTime() - sample.tick();
        return age >= 0 && age <= 1 && sample.channel() == group
                && (group < 0 || tasks[group].rev() == sample.taskRev()) ? sample : null;
    }

    /** Release the pointing request (OFF keeps the link CONNECTED). No-op when
     *  we own no active request; a foreign requester is never touched. */
    private void releaseAxisPointing(ServerLevel sl) {
        if (pointingChannel < 0) return;
        pointingChannel = -1;
        BridgeCoreBlockEntity flight = flightCoreOnOwnStructure(sl);
        if (flight != null) {
            flight.requestPointing(coreUuid(), PointingMode.OFF, null, null,
                    POINTING_MAX_RATE, POINTING_MAX_ACCEL);
        }
    }

    /** Shared beam verdict for the instrument, single shot, auto and queued salvo. */
    private MacroReadout.Aim macroAim(ServerLevel level, int group, @Nullable Object handle, int hardware) {
        var beam = RailgunLink.macroBeamAxis(handle);
        double range = beam == null ? -1 : beam[6];
        if (group < 0 || tasks[group].mode() != ChannelTask.Mode.PRECISE_LOCK) {
            var gate = hardware != RailgunLink.STATUS_READY ? MacroReadout.Gate.HARDWARE
                    : group < 0 ? MacroReadout.Gate.UNGROUPED : MacroReadout.Gate.NEED_LOCK;
            return new MacroReadout.Aim(gate, -1, -1, range);
        }
        Target target = resolveTaskTarget(tasks[group].preciseId());
        if (target == null || beam == null) {
            var gate = hardware != RailgunLink.STATUS_READY ? MacroReadout.Gate.HARDWARE
                    : target == null ? MacroReadout.Gate.NO_TARGET : MacroReadout.Gate.NO_BEAM;
            return new MacroReadout.Aim(gate, -1, -1, range);
        }
        double[] fresh = refreshLockedFast(level, target);
        if (fresh == null) return new MacroReadout.Aim(MacroReadout.Gate.NO_TARGET, -1, -1, range);
        double dx = fresh[0] - beam[0];
        double dy = fresh[1] - beam[1];
        double dz = fresh[2] - beam[2];
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double dot = distance < 1e-6 ? 1 : (dx * beam[3] + dy * beam[4] + dz * beam[5]) / distance;
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
        var gate = hardware != RailgunLink.STATUS_READY ? MacroReadout.Gate.HARDWARE
                : !Double.isFinite(angle) || !Double.isFinite(range) || range <= 0 ? MacroReadout.Gate.NO_BEAM
                : distance > range ? MacroReadout.Gate.OUT_OF_RANGE
                : angle > AXIS_GUN_FIRE_TOLERANCE_DEG ? MacroReadout.Gate.ALIGNING : MacroReadout.Gate.READY;
        return new MacroReadout.Aim(gate, angle, distance, range);
    }

    /** Manual volley (P3 — the fire DECISION belongs to the operator; the
     *  mounts' fire() is just the execution channel). 频道任务协议 v1: H 单击
     *  只消费当前频道的**有效实锁任务**(MULTI_LOCK/PRECISE_LOCK;FOLLOW 是
     *  展示模式不可射,IDLE/无任务回执"无任务"——H 不搜敌、不改任务),
     *  其余闸门链(FireDirector)不动。Turret-wide readiness semantics (user
     *  ruling "几个炮管同时就位后齐射"): a turret fires ONLY
     *  when EVERY linked mount is READY — one unready mount silences the
     *  whole turret for the volley. A cleared turret fires one shot per
     *  mount; fire() rejections are reported per mount. Ignores
     *  the turret's fire interval (a manual order waits no cooldown) but
     *  still honors READY (it covers the breech cycle).
     *  @return the chat message (prefix included), or null off-server */
    public @Nullable String fireVolley(@Nullable Level level) {
        return fireVolley(level, activeChannel);
    }

    /** Channel-parameterized volley (修订 G: H 载荷携带按下时刻捕获的频道号,
     *  不读执行时的 activeChannel). */
    public @Nullable String fireVolley(@Nullable Level level, int channel) {
        if (!(level instanceof ServerLevel sl)) return null;
        if (channel < 0 || channel >= channelCount) return null;
        if (markedStrike != null && markedStrike.channel == channel) abortMarkedStrike(sl, "火控手动接管，标定打击已取消");
        ChannelTask.Mode taskMode = tasks[channel].mode();
        if (taskMode != ChannelTask.Mode.MULTI_LOCK && taskMode != ChannelTask.Mode.PRECISE_LOCK) {
            return "[火控核心] " + channelNames[channel]
                    + " 无任务 — 先由控制台点选或 HUD 锁定(H 是扳机,不搜敌)";
        }
        int mounted = 0;
        List<String> okParts = new ArrayList<>();   // "炮塔 1×2发"
        List<String> failParts = new ArrayList<>(); // "炮塔 2未齐射(炮座2过热)"
        for (TurretUnit u : turrets.values()) {
            if (commandGroup(u.groups()) != channel) continue; // 武器频道: only the commanded channel fires
            SwivelTurretActuator a = u.actuator();
            List<SwivelTurretActuator.Mount> mounts = a.mounts();
            if (!a.isPaired() || mounts.isEmpty()) continue;
            mounted++;
            if (currentHudGate(u.id()) == null) {
                failParts.add(u.name() + "未齐射(等待本组瞄准更新)");
                continue;
            }
            Object[] handles = new Object[mounts.size()];
            int[] codes = new int[mounts.size()];
            boolean allReady = true;
            for (int m = 0; m < mounts.size(); m++) {
                handles[m] = MountLinks.active().mountAt(sl, mounts.get(m).pos());
                codes[m] = MountLinks.active().mountStatusCode(handles[m]);
                if (codes[m] != RailgunLink.STATUS_READY) {
                    allReady = false;
                }
            }
            // FireDirector gate chain, VOLLEY mode (2026-10-08 收口): no
            // channel-switch / cadence gates, LOF cast along the barrel's
            // ACTUAL direction (the AIMING gate guarantees cur≈des).
            double err = a.getAimingErrorDeg();
            FireDirector.GateResult gate = FireDirector.evaluate(
                    new FireDirector.Input(FireDirector.Mode.VOLLEY,
                            !Double.isNaN(err), true, true,
                            allReady, firstBadDetail(mounts, codes),
                            err, a.isInArc(), true),
                    () -> {
                        Vec3 muzzle = cannonWorldPos(sl, a);
                        Vec3 dir = a.currentDirWorld();
                        return muzzle != null && dir != null
                                && lineOfFireBlocked(sl, a, muzzle, dir);
                    });
            if (!gate.fires()) {
                failParts.add(u.name() + "未齐射(" + gate.detail() + ")");
                continue;
            }
            int fired = 0;
            StringBuilder rej = null;
            for (int m = 0; m < mounts.size(); m++) {
                String r = MountLinks.active().fire(handles[m]); // atomic: readiness re-run inside
                if (r == null) {
                    fired++;
                } else {
                    if (rej == null) rej = new StringBuilder();
                    else rej.append(' ');
                    rej.append("炮座").append(mounts.get(m).no()).append(volleyReason(r));
                }
            }
            okParts.add(u.name() + "×" + fired + "发" + (rej == null ? "" : "(" + rej + ")"));
            if (rej == null) {
                a.noteFired(sl.getGameTime()); // also paces the auto-fire cooldown
            } else {
                a.noteFireRejected(rej.toString());
            }
        }
        // 轴炮参战 (3c): PRECISE_LOCK only, beam-axis gate (axisGunGate);
        // 手动齐射不等冷却但 readiness 仍覆盖炮闩/热量/能量。
        for (FixedGun g : fixedGuns.values()) {
            if (commandGroup(g) != channel) continue;
            mounted++;
            Object handle = RailgunLink.macroAt(sl, g.gunPos());
            int code = RailgunLink.macroStatusCode(handle);
            var aim = macroAim(sl, channel, handle, code);
            if (!aim.ready()) {
                failParts.add(g.name() + "未射(" + aim.label(code, RailgunLink.macroTiming(handle).phase()) + ")");
                continue;
            }
            String r = RailgunLink.macroFire(handle);
            if (r == null) okParts.add(g.name() + "已接受开火请求");
            else failParts.add(g.name() + "未射(" + r + ")");
        }
        if (mounted == 0) {
            return "[火控核心] " + channelNames[channel] + " 无可齐射成员(未编组或未挂武器)";
        }
        StringBuilder sb = new StringBuilder("[火控核心] 齐射(" + channelNames[channel] + "): ");
        sb.append(okParts.isEmpty() ? "无武器击发" : String.join(" ", okParts));
        for (String f : failParts) {
            sb.append(", ").append(f);
        }
        return sb.toString();
    }

    private static String volleyReason(int statusCode) {
        return switch (statusCode) {
            case RailgunLink.STATUS_NOT_ASSEMBLED -> "未装配";
            case RailgunLink.STATUS_NO_AMMO -> "无弹";
            case RailgunLink.STATUS_INSUFFICIENT_ENERGY -> "缺电";
            case RailgunLink.STATUS_OVERHEATED -> "过热";
            case RailgunLink.STATUS_BREECH_BUSY -> "炮闩忙";
            default -> "未就绪";
        };
    }

    /** NOT_READY gate detail: name the first non-READY mount ("炮座2过热"). */
    private static String firstBadDetail(List<SwivelTurretActuator.Mount> mounts, int[] codes) {
        for (int m = 0; m < mounts.size(); m++) {
            if (codes[m] != RailgunLink.STATUS_READY) {
                return "炮座" + mounts.get(m).no() + volleyReason(codes[m]);
            }
        }
        return "";
    }

    /** The gate the given turret is parked at (latest drive tick, AUTO-mode
     *  evaluation) — the panel's per-turret status narration. Null when the
     *  turret was not driven this tick (unpaired). */
    public FireDirector.@Nullable GateResult gateOf(java.util.UUID unitId) {
        return turretGate.get(unitId);
    }

    /** Current-task AUTO verdict for HUD only. One tick tolerates BE tick
     * order; unknown/older samples must never be painted green. */
    public FireDirector.@Nullable GateResult currentHudGate(java.util.UUID id) {
        TurretUnit unit = turrets.get(id);
        HudGateSample sample = hudGateSamples.get(id);
        if (unit == null || sample == null || getLevel() == null
                || commandGroup(unit.groups()) < 0 || !unit.isReady()) return null;
        long age = getLevel().getGameTime() - sample.tick();
        return age >= 0 && age <= 1 && sample.channel() == commandGroup(unit.groups()) && sample.taskRev() == tasks[commandGroup(unit.groups())].rev()
                ? sample.gate() : null;
    }

    private static String volleyReason(String fireStatusName) {
        return switch (fireStatusName) {
            case "NOT_ASSEMBLED" -> "未装配";
            case "NO_AMMO" -> "无弹";
            case "INSUFFICIENT_ENERGY" -> "缺电";
            case "OVERHEATED" -> "过热";
            case "BREECH_BUSY" -> "炮闩忙";
            default -> fireStatusName;
        };
    }

    // ---------------- keybind volley (P3: the operator is FLYING — the GUI
    // button is unreachable). 2026-10-30 落地②: FireVolleyPayload 已删, H 改
    // 走打字机第 20 通道; 本方法暂无调用方, 留给 hPress CLICK 实装复用。 ----

    /** Keybind entry: find the core this player commands and volley it. */
    public static String volleyCommand(net.minecraft.server.level.ServerPlayer player) {
        FireControlCoreBlockEntity core = findCommandingCore(player);
        if (core == null) {
            return "[火控核心] 附近没有可指挥的火控核心";
        }
        String msg = core.fireVolley(player.level());
        return msg != null ? msg : "[火控核心] 附近没有可指挥的火控核心";
    }

    /** The core the player commands: a core riding the structure whose WORLD
     *  bounds contain the player (ShipPairing.worldBounds semantics — players
     *  live in world space, blocks in yard space; 8m deck margin), else the
     *  nearest ground core within 64m, else null. */
    private static @Nullable FireControlCoreBlockEntity findCommandingCore(
            net.minecraft.server.level.ServerPlayer player) {
        List<FireControlCoreBlockEntity> cores = REGISTRY.get(player.level());
        if (cores == null) return null;
        net.minecraft.world.phys.Vec3 p = player.position();
        FireControlCoreBlockEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (FireControlCoreBlockEntity c : cores) {
            if (c.registeredSubLevel == null) continue;
            var bb = c.registeredSubLevel.boundingBox(); // world-space structure bounds
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());
            // point-to-AABB distance with an 8m deck margin (ShipPairing.PAIR_MARGIN)
            double dx = Math.max(box.minX - p.x, Math.max(0.0, p.x - box.maxX));
            double dy = Math.max(box.minY - p.y, Math.max(0.0, p.y - box.maxY));
            double dz = Math.max(box.minZ - p.z, Math.max(0.0, p.z - box.maxZ));
            if (dx * dx + dy * dy + dz * dz <= 8.0 * 8.0) {
                double d = c.getBlockPos().distSqr(player.blockPosition());
                if (d < bestD) {
                    bestD = d;
                    best = c;
                }
            }
        }
        if (best != null) return best;
        bestD = Double.MAX_VALUE;
        for (FireControlCoreBlockEntity c : cores) {
            if (c.registeredSubLevel != null) continue; // ground fallback only
            double d = c.getBlockPos().distSqr(player.blockPosition());
            if (d <= 64.0 * 64.0 && d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    /** Global maintenance action: re-run the SIGN probes on all paired
     *  turrets. Assembly zeros follow AxisLock's current persistence rules.
     *  修订 E: 校准中的塔退出分配 + 任务立即重算; 校全部塔 = 各频道无可用
     *  成员 → 任务失效 (与旧"calibration = disengage"裁定一致).
     *  @return how many turrets were re-armed */
    public int recalibrateAll(@Nullable Level level) {
        int n = 0;
        for (TurretUnit u : turrets.values()) {
            if (u.actuator().isPaired()) {
                u.actuator().startRecalibration(level);
                n++;
            }
        }
        if (n > 0 && level instanceof ServerLevel sl) {
            recomputeAssignments(sl); // 立即重算 (评审 H / 修订 E)
        }
        return n;
    }

    /** Any channel holding a live task (menu recalibration message). */
    public boolean hasAnyTask() {
        for (ChannelTask t : tasks) {
            if (t.mode() != ChannelTask.Mode.IDLE) return true;
        }
        return false;
    }

    // ---------------- MenuProvider (internal management GUI; opened by
    // empty-hand right-click — user ruling 2026-09-26: 内部调控走内部UI) ----------------

    public boolean skyFilter() {
        return skyFilter;
    }

    public void toggleSkyFilter() {
        skyFilter = !skyFilter;
        setChanged();
    }

    /** 视线跟随 (裁断稿 §7): 当前频道任务为 FOLLOW 态。菜单 DATA_FOLLOW 的
     *  语义随之变为"当前频道是否跟随中"。 */
    public boolean followActive() {
        return tasks[activeChannel].mode() == ChannelTask.Mode.FOLLOW;
    }

    /** GUI 视线跟随 toggle (看哪打哪): 当前频道的特殊任务 (CONSOLE 来源) —
     *  显式进出,退出不静默恢复旧锁 (裁断稿 §7)。the clicking player becomes
     *  the eyes; clicking again (same or other player) re-binds or turns it off. */
    public void toggleFollow(Player player) {
        ChannelTask task = tasks[activeChannel];
        if (task.mode() == ChannelTask.Mode.FOLLOW
                && player.getUUID().equals(task.followPlayer())) {
            releaseTask(activeChannel);
            player.sendSystemMessage(Component.literal(
                    "[火控核心] " + channelNames[activeChannel] + " 视线跟随: 关"));
        } else {
            task.toFollow(player.getUUID());
            if (player.level() instanceof ServerLevel sl) recomputeAssignments(sl);
            player.sendSystemMessage(Component.literal(
                    "[火控核心] " + channelNames[activeChannel] + " 视线跟随: 开 — 看哪打哪("
                            + player.getName().getString() + "), 再点关闭"));
        }
        setChanged();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.bridgecore.fire_control_core");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new FireControlMenu(containerId, playerInventory, this);
    }


    // ---------------- CoreDirectory (MomentumDistributor pattern) ----------------

    private void syncDirectory(Level level, BlockPos pos) {
        var containing = Sable.HELPER.getContaining(level, pos);
        ServerSubLevel sub = containing instanceof ServerSubLevel s ? s : null;
        if (sub != registeredSubLevel) {
            CoreDirectory.deregister(CoreDirectory.BUCKET_FIRE_CONTROL, registeredSubLevel, this);
            CoreDirectory.register(CoreDirectory.BUCKET_FIRE_CONTROL, sub, this);
            registeredSubLevel = sub;
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            syncDirectory(level, getBlockPos());
            REGISTRY.computeIfAbsent(level, k -> new ArrayList<>()).add(this);
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel sl) abortMarkedStrike(sl, "火控核心已卸载，标定打击已取消");
        CoreDirectory.deregister(CoreDirectory.BUCKET_FIRE_CONTROL, registeredSubLevel, this);
        registeredSubLevel = null;
        if (level != null && !level.isClientSide) {
            List<FireControlCoreBlockEntity> cores = REGISTRY.get(level);
            if (cores != null) {
                cores.remove(this);
                if (cores.isEmpty()) REGISTRY.remove(level);
            }
        }
        super.setRemoved();
    }

    // ---------------- scan ----------------

    /** World position used for distances: the structure's logical center on a
     *  ship (plot coordinates are untracked far-away yard space), the block
     *  center on the ground. Ship-sized error is fine for a radar list. */
    private double[] scanOrigin(Level level, BlockPos pos) {
        if (registeredSubLevel != null) {
            var c = registeredSubLevel.logicalPose().position();
            return new double[]{c.x(), c.y(), c.z()};
        }
        return new double[]{pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5};
    }

    private void scan(Level level, BlockPos pos) {
        double[] o = scanOrigin(level, pos);
        List<Sighting> sightings = new ArrayList<>();

        // Living entities in the world frame (players included, spectators out).
        // Sky-visibility filter (2026-09-23, user ruling): mirrors astrocompass's
        // getEntities ("露天实体", a canSeeSky heightmap check — NOT a raycast).
        // The legacy control_center used getEntitiesAll and listed underground
        // mobs; harmless when targets were hand-picked from a list, unacceptable
        // now that auto-acquire/Tab-cycling locks them. Caveat: canSeeSky is a
        // heightmap lookup, so a mob directly under our own hovering hull also
        // reads "not sky-visible" — accepted (same false-negative the
        // astrocompass era had).
        AABB box = new AABB(o[0] - scanRadius, o[1] - scanRadius, o[2] - scanRadius,
                o[0] + scanRadius, o[1] + scanRadius, o[2] + scanRadius);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && !e.isSpectator()
                        && (!skyFilter
                                || level.canSeeSky(BlockPos.containing(e.getX(), e.getEyeY(), e.getZ()))))) {
            // §2.29 §8: 排除乘坐在本结构上的玩家 (与排除自家船/炮塔子结构同类 —
            // 治"解锁后锁到玩家自己"; Sable HELPER 判实体所在结构, standing 与
            // 乘坐都被 getContaining(Entity) 覆盖)
            if (registeredSubLevel != null && e instanceof Player
                    && Sable.HELPER.getContaining(e) == registeredSubLevel) {
                continue;
            }
            double dx = e.getX() - o[0], dy = e.getY() - o[1], dz = e.getZ() - o[2];
            if (dx * dx + dy * dy + dz * dz > scanRadius * scanRadius) continue;
            boolean isPlayer = e instanceof Player;
            String path = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(); // type id, no namespace (中文名不可靠)
            String label = isPlayer ? e.getName().getString() : path;
            // §2.28 category tagged at scan time (the Monster check needs the
            // entity instance — consumers must not guess).
            Category cat = isPlayer ? Category.PLAYER
                    : e instanceof Monster ? Category.MONSTER : Category.CREATURE;
            String uuid = e.getUUID().toString();
            sightings.add(new Sighting(uuid, uuid, label, cat, label,
                    e.getX(), e.getY(), e.getZ(), false,
                    (double) e.getHealth(), (double) e.getMaxHealth(), path));
        }

        // Sable ships, own structure excluded by identity.
        if (level instanceof ServerLevel sl) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(sl);
            if (container != null) {
                java.util.Set<java.util.UUID> ownTurretChildren = new java.util.HashSet<>();
                for (TurretUnit u : turrets.values()) {
                    ownTurretChildren.addAll(u.actuator().ownChildSubLevelIds(level));
                }
                for (ServerSubLevel sub : container.getAllSubLevels()) {
                    if (sub == registeredSubLevel) continue; // 锁定敌舰必须按 uuid/identity 排自身
                    if (ownTurretChildren.contains(sub.getUniqueId())) continue; // 自家炮塔子结构不是目标
                    var c = sub.logicalPose().position();
                    double dx = c.x() - o[0], dy = c.y() - o[1], dz = c.z() - o[2];
                    if (dx * dx + dy * dy + dz * dz > scanRadius * scanRadius) continue;
                    String id = sub.getUniqueId().toString();
                    String slug = sub.getName();
                    String label = slug != null ? slug : "ship:" + id.substring(0, 6);
                    boolean implicitWl = !CoreDirectory
                            .componentsOf(CoreDirectory.BUCKET_FIRE_CONTROL, sub).isEmpty();
                    sightings.add(new Sighting(id, id, slug, Category.SHIP, label,
                            c.x(), c.y(), c.z(), implicitWl, null, null,
                            slug != null ? slug : "ship"));
                }
            }
        }

        registry.updateScan(sightings, tickCount, o[0], o[1], o[2]);
        if (level instanceof ServerLevel sl) {
            pruneTasksAfterScan();
            recomputeAssignments(sl);
        }
    }

    /** Scan pruning: known whitelist changes end precise tasks immediately;
     *  missing precise contacts use the 60-tick watchdog (no automatic retarget). CONSOLE 点选集目标出扫描 → 剔除(rev+1), 集合空 →
     *  任务失效待命。HUD 多实锁的锥裁剪由 HUD 侧推新 prelock 实现,这里只
     *  管扫描半径口径。 */
    private void pruneTasksAfterScan() {
        for (int ch = 0; ch < channelCount; ch++) {
            ChannelTask task = tasks[ch];
            if (task.mode() == ChannelTask.Mode.PRECISE_LOCK) {
                Target tracked = task.preciseId() == null ? null : registry.targetById(task.preciseId());
                if (tracked != null && tracked.whitelisted()) releaseTask(ch);
                // A single missed scan is not a release command. The existing
                // 60-tick precise watchdog owns sustained loss; aiming/fire
                // already stop immediately while the target is unresolvable.
            } else if (task.mode() == ChannelTask.Mode.MULTI_LOCK
                    && task.source() == ChannelTask.Source.CONSOLE) {
                task.pruneSelection(id -> resolvable(id));
                if (task.selection().isEmpty()) {
                    releaseTask(ch);
                }
            }
        }
    }

    /** 分配重算 (§2.29 + 评审 H): 每个 MULTI_LOCK 频道独立分配 — 候选集 =
     *  HUD 来源 ? (prelock ∩ registry) : (selection ∩ registry);频道优先覆盖
     *  不同可达目标，再允许余塔集火，保留稳定分配并按炮口距离选候选。触发点 = 扫描拍
     *  + 任务 rev 变化的当场 (prelockUpdate/gPress/控制台点选/配对解配都直接
     *  调), 分配表带 taskRev, rev 变即作废。修订 E: 重算后频道无可用成员
     *  (无 isReady 塔)或无目标 → 任务失效(rev+1); 多塔频道不因单塔校准
     *  整组阵亡。 */
    private boolean assignmentsDirty;

    private void recomputeAssignments(ServerLevel level) {
        assignmentsDirty = false;
        Map<java.util.UUID, Assignment> prev = new java.util.HashMap<>(turretAssignments);
        turretAssignments.clear();
        for (int ch = 0; ch < channelCount; ch++) {
            ChannelTask task = tasks[ch];
            if (task.mode() != ChannelTask.Mode.MULTI_LOCK) continue;
            java.util.Set<String> pool = task.source() == ChannelTask.Source.HUD
                    ? (prelockValid(level) ? new java.util.HashSet<>(prelockIds) : java.util.Set.of())
                    : task.selection();
            if (pool.isEmpty()) {
                // A live but empty cone ends MULTI only. Precise anchors are
                // outside this loop and survive looking away.
                releaseTask(ch);
                continue;
            }
            List<Target> candidates = new ArrayList<>();
            for (String id : new java.util.TreeSet<>(pool)) {
                Target t = resolveTaskTarget(id);
                if (t != null) candidates.add(t);
            }
            if (candidates.isEmpty()) {
                // 无目标 → 任务失效待命 (协议 §5)
                releaseTask(ch);
                continue;
            }
            boolean anyMember = false;
            List<ChannelTargetAllocator.Turret> allocation = new ArrayList<>();
            for (TurretUnit unit : turrets.values()) {
                if (!unit.belongsTo(ch) || !unit.isReady()) continue;
                anyMember = true;
                if (commandGroup(unit.groups()) != ch) continue;
                SwivelTurretActuator turret = unit.actuator();
                Vec3 cannon = cannonWorldPos(level, turret);
                if (cannon == null) continue;
                List<TargetAssigner.Candidate> view = new ArrayList<>(candidates.size());
                for (Target t : candidates) {
                    double dx = t.x() - cannon.x(), dy = t.y() - cannon.y(), dz = t.z() - cannon.z();
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    Vec3 dir = new Vec3(dx, dy, dz).normalized();
                    view.add(new TargetAssigner.Candidate(t.id(), dist,
                            dir != null && turret.isDirectionInArc(level, dir)));
                }
                Assignment pa = prev.get(unit.id());
                String prevId = pa != null && pa.channel() == ch && pa.taskRev() == task.rev() ? pa.targetId() : null;
                allocation.add(new ChannelTargetAllocator.Turret(unit.id().toString(), prevId, view));
            }
            for (var assigned : ChannelTargetAllocator.assign(allocation).entrySet()) {
                turretAssignments.put(java.util.UUID.fromString(assigned.getKey()),
                        new Assignment(assigned.getValue(), ch, task.rev()));
            }
            if (!anyMember) {
                // 修订 E: 无可用成员 → 任务失效
                releaseTask(ch);
            }
        }
    }

    // ---------------- chat interactions (phase-2 player verification channel) ----------------

    private static String fmt(Target t) {
        double speed = Math.sqrt(t.vx() * t.vx() + t.vy() * t.vy() + t.vz() * t.vz()) * 20; // m/s
        String kindTag = switch (t.category()) {
            case SHIP -> "[船]";
            case PLAYER -> "[玩家]";
            default -> "";
        };
        return String.format("%s%s %.1fm %.1fm/s%s%s", kindTag, t.label(), t.dist(), speed,
                t.whitelisted() ? " [白名单]" : "", "");
    }

    /** Right-click: print the radar list to chat. */
    public void printStatus(Player player) {
        List<Target> ts = registry.targets();
        ChannelTask task = tasks[activeChannel];
        player.sendSystemMessage(Component.literal(String.format(
                "[火控核心] 扫描 %d 个目标 (半径 %.0fm) | %s: %s | 白名单 %d 条",
                ts.size(), scanRadius, channelNames[activeChannel], taskSummary(task),
                registry.whiteListEntries().size())));
        int n = Math.min(ts.size(), CHAT_ROWS);
        for (int i = 0; i < n; i++) {
            Target t = ts.get(i);
            String tag = task.mode() == ChannelTask.Mode.PRECISE_LOCK && t.id().equals(task.preciseId())
                    ? " <== 精确锁"
                    : task.selection().contains(t.id()) ? " [已选]" : "";
            player.sendSystemMessage(Component.literal("  #" + (i + 1) + " " + fmt(t) + tag));
        }
        if (ts.size() > CHAT_ROWS) {
            player.sendSystemMessage(Component.literal("  ... 其余 " + (ts.size() - CHAT_ROWS) + " 个略"));
        }
        if (ts.isEmpty()) {
            player.sendSystemMessage(Component.literal("  (半径内无目标)"));
        }
        if (turrets.isEmpty()) {
            player.sendSystemMessage(Component.literal("  炮塔: 无"));
        } else {
            for (TurretUnit u : turrets.values()) {
                player.sendSystemMessage(Component.literal("  " + u.name() + ": " + u.statusLine()));
            }
        }
        player.sendSystemMessage(Component.literal("  潜行+右键=循环精确锁(当前频道) | 木棍+右键=白名单 | 指南针+右键=配对炮塔"));
    }

    /** One-line task summary for chat status. */
    private String taskSummary(ChannelTask task) {
        return switch (task.mode()) {
            case IDLE -> "无任务" + (task.promoteSuppressed() ? "(升格已暂停)" : "");
            case MULTI_LOCK -> (task.source() == ChannelTask.Source.HUD ? "HUD" : "控制台")
                    + "多实锁" + (task.source() == ChannelTask.Source.CONSOLE
                            ? "(已选 " + task.selection().size() + ")" : "");
            case PRECISE_LOCK -> (task.source() == ChannelTask.Source.HUD ? "HUD" : "控制台")
                    + "精确锁 -> " + labelOf(task.preciseId());
            case FOLLOW -> "视线跟随";
        };
    }

    /** Sneak + right-click: cycle the ACTIVE channel's CONSOLE precise lock
     *  through the scan candidates, wrapping to released (频道化后的聊天栏
     *  验证通道 — 旧的全核心单锁已退役). */
    public void cycleLock(Player player) {
        ChannelTask task = tasks[activeChannel];
        List<Target> cands = registry.lockCandidates();
        String cur = task.mode() == ChannelTask.Mode.PRECISE_LOCK
                && task.source() == ChannelTask.Source.CONSOLE ? task.preciseId() : null;
        int idx = -1;
        if (cur != null) {
            for (int i = 0; i < cands.size(); i++) {
                if (cands.get(i).id().equals(cur)) { idx = i; break; }
            }
        }
        idx++;
        if (idx >= cands.size()) {
            if (cur != null) releaseTask(activeChannel);
            player.sendSystemMessage(Component.literal(
                    "[火控核心] " + channelNames[activeChannel] + " 取消锁定"));
        } else {
            Target t = cands.get(idx);
            task.toPrecise(ChannelTask.Source.CONSOLE, t.id());
            if (player.level() instanceof ServerLevel sl) recomputeAssignments(sl);
            player.sendSystemMessage(Component.literal(
                    "[火控核心] " + channelNames[activeChannel] + " 精确锁 -> " + fmt(t)));
        }
        setChanged();
    }

    /** 控制台点选 (协议 §3 控制台命令 + 裁断稿 §3): 切换**当前频道**的选择集
     *  (m/N, N = 频道已配对炮塔数; 满则提示不覆盖)。选集非空即形成/更新
     *  CONSOLE MULTI_LOCK 任务, 清空即解除。面板雷达页点击走这里。 */
    public void toggleSelection(Player player, String targetId) {
        if (!(player.level() instanceof ServerLevel sl)) return;
        Target t = registry.targetById(targetId);
        if (t == null || t.whitelisted()) {
            player.sendSystemMessage(Component.literal("[火控核心] 目标已失效,无法选择"));
            return;
        }
        int ch = activeChannel;
        ChannelTask task = tasks[ch];
        int capacity = pairedCountOf(ch);
        // 轴炮频道 (2026-11-08 实机"火控台锁不上"修复): 无炮塔成员的频道
        // 点选容量=0 且多实锁会被修订 E 立即失效 —— 轴炮只打精确锁,
        // 所以点选直接形成 CONSOLE PRECISE_LOCK; 再点同一目标 = 解除。
        if (capacity == 0) {
            boolean gunMember = false;
            for (FixedGun g : fixedGuns.values()) if (g.belongsTo(ch)) { gunMember = true; break; }
            if (gunMember) {
                if (task.mode() == ChannelTask.Mode.PRECISE_LOCK
                        && task.source() == ChannelTask.Source.CONSOLE
                        && targetId.equals(task.preciseId())) {
                    releaseTask(ch);
                    player.sendSystemMessage(Component.literal(
                            "[火控核心] " + channelNames[ch] + " 取消锁定"));
                } else {
                    task.toPrecise(ChannelTask.Source.CONSOLE, targetId);
                    recomputeAssignments(sl);
                    player.sendSystemMessage(Component.literal(
                            "[火控核心] " + channelNames[ch] + " 精确锁 -> " + fmt(t)));
                }
                setChanged();
                return;
            }
        }
        if (task.mode() != ChannelTask.Mode.MULTI_LOCK
                || task.source() != ChannelTask.Source.CONSOLE) {
            task.toMulti(ChannelTask.Source.CONSOLE); // 形成 CONSOLE 多实锁任务
        }
        ChannelTask.SelectResult r = task.toggleSelection(targetId, capacity);
        switch (r) {
            case FULL -> player.sendSystemMessage(Component.literal("[火控核心] "
                    + channelNames[ch] + " 已选满 " + task.selection().size() + "/" + capacity
                    + ",未覆盖"));
            case ADDED -> player.sendSystemMessage(Component.literal("[火控核心] "
                    + channelNames[ch] + " 已选 " + task.selection().size() + "/" + capacity
                    + ": +" + fmt(t)));
            case REMOVED -> {
                if (task.selection().isEmpty()) {
                    releaseTask(ch); // 清空即解除
                    player.sendSystemMessage(Component.literal(
                            "[火控核心] " + channelNames[ch] + " 选择已清空,任务解除"));
                } else {
                    player.sendSystemMessage(Component.literal("[火控核心] "
                            + channelNames[ch] + " 已选 " + task.selection().size() + "/" + capacity
                            + ": -" + fmt(t)));
                }
            }
        }
        if (r != ChannelTask.SelectResult.FULL) {
            recomputeAssignments(sl); // rev 变化 → 立即重算
            setChanged();
        }
    }

    /** Panel radar tap on empty space: clear the active channel's console
     *  selection (老版点空取消选中 → 点空清空选择集; 清空即解除任务). */
    public void unlockFromPanel(Player player) {
        ChannelTask task = tasks[activeChannel];
        if (task.source() == ChannelTask.Source.CONSOLE
                && task.mode() == ChannelTask.Mode.MULTI_LOCK) {
            releaseTask(activeChannel);
            player.sendSystemMessage(Component.literal(
                    "[火控核心] " + channelNames[activeChannel] + " 选择已清空,任务解除"));
            if (player.level() instanceof ServerLevel sl) recomputeAssignments(sl);
        } else if (task.clearSelection()) {
            setChanged();
        }
    }

    /** Stick + right-click: toggle whitelist on the active channel's precise
     *  target (无精确锁时提示 — 白名单操作需要一个明确目标). */
    public void toggleWhitelistOfLocked(Player player) {
        ChannelTask task = tasks[activeChannel];
        Target locked = task.mode() == ChannelTask.Mode.PRECISE_LOCK
                ? registry.targetById(task.preciseId()) : null;
        if (locked == null) {
            player.sendSystemMessage(Component.literal(
                    "[火控核心] 当前频道无精确锁目标，白名单操作需要先精确锁定"));
            return;
        }
        toggleWhitelistOf(player, locked);
    }

    /** Panel radar sneak-tap: whitelist toggle by target id. */
    public void toggleWhitelistById(Player player, String targetId) {
        Target t = registry.targetById(targetId);
        if (t != null) toggleWhitelistOf(player, t);
    }

    /** Whitelist toggle + 白名单即永不交战: 从所有频道任务剔除该目标
     *  (精确锁 → 任务结束; 点选集 → 剔除, 空 → 解除)。 */
    private void toggleWhitelistOf(Player player, Target t) {
        Sighting s = new Sighting(t.id(), t.id(), t.label(), t.category(), t.label(),
                t.x(), t.y(), t.z(), false, t.health(), t.maxHealth(), t.species());
        boolean added = registry.toggleWhitelist(s);
        if (added) {
            evictTargetFromAllTasks(t.id());
            player.sendSystemMessage(Component.literal(
                    "[火控核心] 已加白名单: " + t.label() + " (已从所有任务剔除)"));
        } else {
            player.sendSystemMessage(Component.literal(
                    "[火控核心] 已移出白名单: " + t.label()));
        }
        if (player.level() instanceof ServerLevel sl) recomputeAssignments(sl);
        setChanged();
    }

    /** Evict a target id from every channel task (whitelist / external veto). */
    private void evictTargetFromAllTasks(String targetId) {
        for (int ch = 0; ch < channelCount; ch++) {
            ChannelTask task = tasks[ch];
            if (task.mode() == ChannelTask.Mode.PRECISE_LOCK
                    && targetId.equals(task.preciseId())) {
                releaseTask(ch);
            } else if (task.selection().contains(targetId)) {
                task.pruneSelection(id -> !id.equals(targetId));
                if (task.mode() == ChannelTask.Mode.MULTI_LOCK
                        && task.source() == ChannelTask.Source.CONSOLE
                        && task.selection().isEmpty()) {
                    releaseTask(ch);
                }
            }
        }
    }

    // ---------------- persistence (merge-style: missing keys keep defaults) ----------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("scanRadius", scanRadius);
        tag.putBoolean("skyFilter", skyFilter);
        tag.putInt("groupCount", channelCount);
        tag.putInt("activeChannel", activeChannel);
        for (int c = 0; c < channelCount; c++) {
            tag.putBoolean("chAutoFire" + c, channelAutoFire[c]);
            tag.putBoolean("chAutoPromote" + c, channelAutoPromote[c]);
            tag.putString("chName" + c, channelNames[c]);
            // 频道任务 (协议 §1: 持久化 — source/mode/rev/payload/promoteSuppressed)
            ChannelTask t = tasks[c];
            CompoundTag tt = new CompoundTag();
            tt.putString("source", t.source().name());
            tt.putString("mode", t.mode().name());
            tt.putLong("rev", t.rev());
            tt.putBoolean("promoteSuppressed", t.promoteSuppressed());
            if (!t.selection().isEmpty()) {
                ListTag sel = new ListTag();
                for (String id : t.selection()) sel.add(StringTag.valueOf(id));
                tt.put("selection", sel);
            }
            if (t.preciseId() != null) tt.putString("preciseId", t.preciseId());
            if (t.followPlayer() != null) tt.putUUID("followPlayer", t.followPlayer());
            tag.put("chTask" + c, tt);
        }
        ListTag wl = new ListTag();
        for (String w : registry.whiteListEntries()) {
            wl.add(StringTag.valueOf(w));
        }
        tag.put("whiteList", wl);
        ListTag units = new ListTag();
        for (TurretUnit u : turrets.values()) {
            CompoundTag ut = new CompoundTag();
            u.save(ut);
            units.add(ut);
        }
        tag.put("turretUnits", units);
        if (coreUuid != null) tag.putUUID("coreUuid", coreUuid);
        ListTag guns = new ListTag();
        for (FixedGun g : fixedGuns.values()) {
            CompoundTag gt = new CompoundTag();
            g.save(gt);
            guns.add(gt);
        }
        tag.put("fixedGuns", guns);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("scanRadius")) {
            // R7-5: Math.min/max pass NaN straight through — gate on finite first.
            double r = tag.getDouble("scanRadius");
            scanRadius = Double.isFinite(r) ? Math.max(16, Math.min(1024, r)) : DEFAULT_SCAN_RADIUS;
        }
        if (tag.contains("skyFilter")) {
            skyFilter = tag.getBoolean("skyFilter");
        }
        channelCount = tag.contains("groupCount")
                ? Math.max(1, Math.min(WeaponGroups.MAX, tag.getInt("groupCount"))) : WeaponGroups.DEFAULT_COUNT;
        activeChannel = Math.max(0, Math.min(channelCount - 1, tag.getInt("activeChannel")));
        for (int c = 0; c < channelCount; c++) {
            if (tag.contains("chAutoFire" + c)) channelAutoFire[c] = tag.getBoolean("chAutoFire" + c);
            // chAutoPromote* absent on pre-④ saves -> keep the ON default
            if (tag.contains("chAutoPromote" + c)) {
                channelAutoPromote[c] = tag.getBoolean("chAutoPromote" + c);
            }
            if (tag.contains("chName" + c)) {
                String n = tag.getString("chName" + c);
                if (!n.isEmpty()) channelNames[c] = n;
            }
            if (tag.contains("chTask" + c)) {
                CompoundTag tt = tag.getCompound("chTask" + c);
                List<String> sel = new ArrayList<>();
                for (Tag st : tt.getList("selection", Tag.TAG_STRING)) sel.add(st.getAsString());
                ChannelTask.Source src = parseEnum(ChannelTask.Source.class, tt.getString("source"),
                        ChannelTask.Source.NONE);
                ChannelTask.Mode mode = parseEnum(ChannelTask.Mode.class, tt.getString("mode"),
                        ChannelTask.Mode.IDLE);
                if (src == ChannelTask.Source.NONE || mode == ChannelTask.Mode.IDLE) {
                    src = ChannelTask.Source.NONE;
                    mode = ChannelTask.Mode.IDLE; // 防御: 无来源不存任务
                }
                tasks[c].restore(src, mode, tt.getLong("rev"), sel,
                        tt.contains("preciseId") ? tt.getString("preciseId") : null,
                        hasUuid4(tt, "followPlayer") ? tt.getUUID("followPlayer") : null,
                        tt.getBoolean("promoteSuppressed"));
            }
        }
        if (tag.contains("whiteList")) {
            List<String> entries = new ArrayList<>();
            for (Tag t : tag.getList("whiteList", Tag.TAG_STRING)) {
                entries.add(t.getAsString());
            }
            registry.loadWhiteList(entries);
        }
        // 旧键迁移 (协议 §7): lockedId → activeChannel 的 CONSOLE PRECISE_LOCK;
        // followPlayerUuid → activeChannel FOLLOW; 各打一条迁移日志。
        // hudPrelockIds 从未持久化,无需迁移。读到即忽略的旧键不再写出。
        if (tag.contains("lockedId") && !tag.contains("chTask" + activeChannel)) {
            String old = tag.getString("lockedId");
            if (!old.isEmpty()) {
                tasks[activeChannel].toPrecise(ChannelTask.Source.CONSOLE, old);
                com.bridgecore.BridgeCoreMod.LOGGER.warn(
                        "[fire-control] migrated legacy lockedId -> channel {} CONSOLE precise lock",
                        activeChannel);
            }
        }
        if (tag.contains("followPlayerUuid") && !tag.contains("chTask" + activeChannel)) {
            try {
                tasks[activeChannel].toFollow(java.util.UUID.fromString(tag.getString("followPlayerUuid")));
                com.bridgecore.BridgeCoreMod.LOGGER.warn(
                        "[fire-control] migrated legacy followPlayerUuid -> channel {} FOLLOW task",
                        activeChannel);
            } catch (IllegalArgumentException ignored) {
            }
        }
        turrets.clear();
        hudGateSamples.clear();
        if (tag.contains("turretUnits")) {
            for (Tag t : tag.getList("turretUnits", Tag.TAG_COMPOUND)) {
                TurretUnit u = TurretUnit.load((CompoundTag) t, this::setChanged, this::getBlockPos);
                turrets.put(u.id(), u);
            }
        } else if (tag.contains("turretYawPos")) {
            // Migration from the pre-multi-turret save format: the legacy single
            // actuator wrote its keys into the root tag — wrap them as unit #1.
            // R7-1: the pre-channel unit had no group mask; without this it
            // would load with groups=0 and never aim/fire again. Group 0 only —
            // auto-fire preferences stay OFF (no preference migration).
            TurretUnit u = new TurretUnit(java.util.UUID.randomUUID(), "炮塔 1", this::setChanged, this::getBlockPos);
            u.actuator().load(tag);
            u.setGroups(WeaponGroups.bit(0));
            turrets.put(u.id(), u);
            com.bridgecore.BridgeCoreMod.LOGGER.warn(
                    "[fire-control] migrated legacy single-turret root keys -> 炮塔 1, group 0 (auto-fire stays off)");
        }
        for (TurretUnit unit : turrets.values()) unit.setGroups(unit.groups() & WeaponGroups.validMask(channelCount));
        assignmentsDirty = true;
        fixedGuns.clear();
        if (hasUuid4(tag, "coreUuid")) coreUuid = tag.getUUID("coreUuid");
        if (tag.contains("fixedGuns")) {
            int turretGroups = turretGroups();
            for (Tag t : tag.getList("fixedGuns", Tag.TAG_COMPOUND)) {
                FixedGun g = FixedGun.load((CompoundTag) t);
                // N2: 互斥/越界裁剪必须有日志——老档同频道升级后轴炮脱组
                // 不能静默(否则玩家只会看到"轴炮不参战"却无任何提示)
                int before = g.groups();
                g.setGroups(WeaponGroups.axialOnly(g.groups(), turretGroups) & WeaponGroups.validMask(channelCount));
                if (g.groups() != before) {
                    com.bridgecore.BridgeCoreMod.LOGGER.warn(
                            "[fire-control] 轴炮 {} 载入编组被互斥/越界裁剪: {} -> {}",
                            g.name(), before, g.groups());
                }
                fixedGuns.put(g.id(), g);
            }
        }
    }

    /** R7-2: CompoundTag.hasUUID only checks the tag TYPE — a wrong-length
     *  int array makes getUUID throw and fails the whole chunk load. Treat
     *  malformed UUIDs as missing. */
    static boolean hasUuid4(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_INT_ARRAY) && tag.getIntArray(key).length == 4;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Read-only view for future phases (actuator / CC bridge). */
    public TargetRegistry targets() {
        return registry;
    }

    public @Nullable ServerSubLevel registeredSubLevel() {
        return registeredSubLevel;
    }

    public double scanRadius() {
        return scanRadius;
    }

    /** Panel range-slider write (老火控中心量程滑条): clamped 16..1024, silent. */
    public void setScanRadius(double r) {
        scanRadius = Math.max(16, Math.min(1024, r));
        setChanged();
    }

    /** Panel status header (老火控中心状态页 SHIP 行): ship name, null on the ground. */
    public @Nullable String shipName() {
        return registeredSubLevel != null ? registeredSubLevel.getName() : null;
    }

    /** Own-ship speed for the panel header, m/s (0 on the ground). */
    public double shipSpeedMps() {
        if (registeredSubLevel == null) return 0;
        var v = registeredSubLevel.latestLinearVelocity;
        return Math.sqrt(v.x() * v.x() + v.y() * v.y() + v.z() * v.z());
    }

    // ---------------- panel radar frame (2026-09-27: 老火控中心俯视雷达图移植) ----------------

    /** World-space radar center (the core's yard position transformed when
     *  shipborne; plain block center on the ground). */
    public double[] radarCenter() {
        if (registeredSubLevel != null) {
            org.joml.Vector3d dest = new org.joml.Vector3d();
            registeredSubLevel.logicalPose().transformPosition(new org.joml.Vector3d(
                    worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5), dest);
            return new double[]{dest.x, dest.y, dest.z};
        }
        return new double[]{worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5};
    }

    /** World → "bow-up" radar frame rotation: conj(ship orientation) when
     *  shipborne (bow = ship-forward at the top), identity = north-up on the
     *  ground (the Lua prototype's properties.face, standardized). */
    public Quat radarRotation() {
        if (registeredSubLevel != null) {
            var o = registeredSubLevel.logicalPose().orientation();
            return new Quat(o.w(), o.x(), o.y(), o.z()).negate();
        }
        return Quat.IDENTITY;
    }
}
