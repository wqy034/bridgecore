package com.bridgecore.firecontrol;

import com.bridgecore.BridgeCoreBlockEntity;
import com.bridgecore.firecontrol.railgun.RailgunLink;
import com.bridgecore.menu.FireControlMenu;
import com.bridgecore.nav.NavTerminalData;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Runtime-only authorization for one volley at a world coordinate. */
final class MarkedStrike {
    private static final int AIM_TIMEOUT_TICKS = 200;
    private static final int POSE_TIMEOUT_TICKS = 400;
    private static final int REFRESH_TICKS = 5;

    record Shots(SwivelTurretActuator turret, List<SwivelTurretActuator.Mount> mounts, Object[] handles) {}

    sealed interface Origin permits Terminal, Console {}
    record Terminal(UUID id, NavTerminalData.Mark mark, boolean quick) implements Origin {}
    record Console(FireControlMenu menu) implements Origin {}

    final UUID player;
    final Origin origin;
    final BlockPos target;
    final int channel;
    final Set<UUID> selected;
    final StrikePreview.Mode weaponMode;
    private final BridgeCoreBlockEntity flight;
    private final BridgeCoreBlockEntity.ControlMode mode;
    private final ServerSubLevel ship;
    private final int revision;
    private final long startTick;
    private final Map<UUID, Shots> settled = new LinkedHashMap<>();
    private Map<UUID, StrikePreview.TurretAim> aims;
    private final UUID pointingRequester;
    private final @Nullable StrikePreview.PosePlan posePlan;
    private boolean poseReady;
    private long nextPoseRefresh;
    private @Nullable StrikePreview.AxisAim axisAim;
    private Map<UUID, Object> axisShots = Map.of();
    private final List<Object> axisAccepted = new ArrayList<>();
    private long axisWarmupDeadline;

    MarkedStrike(ServerPlayer player, Origin origin, BridgeCoreBlockEntity flight, ServerSubLevel ship,
                 BlockPos target, int channel, int revision, StrikePreview.Mode weaponMode, Set<UUID> selected,
                 @Nullable StrikePreview.Assessment assessment, @Nullable StrikePreview.PosePlan posePlan) {
        this.player = player.getUUID();
        this.origin = origin;
        this.flight = flight;
        mode = flight.mode();
        this.ship = ship;
        this.target = target;
        this.channel = channel;
        this.revision = revision;
        this.weaponMode = weaponMode;
        aims = assessment == null ? Map.of() : assessment.turrets();
        axisAim = assessment == null ? null : assessment.axisAim();
        this.posePlan = posePlan;
        pointingRequester = isAxis() || isPose() ? UUID.randomUUID() : null;
        this.selected = Set.copyOf(selected);
        startTick = player.serverLevel().getGameTime();
    }

    String targetLabel() {
        return origin instanceof Terminal terminal ? terminal.mark().name() : target.toShortString();
    }

    @Nullable String beginTick(ServerLevel level, FireControlCoreBlockEntity core) {
        settled.clear();
        long elapsed = level.getGameTime() - startTick;
        if (!axisSubmitted() && elapsed >= (isPose() ? POSE_TIMEOUT_TICKS : AIM_TIMEOUT_TICKS)) return "瞄准超时";
        if (flight.manualFlightInputSince(startTick) || flight.mode() != mode) return "驾驶员已接管";
        if (!core.markedChannelAvailable(channel, selected, weaponMode)) return "火控频道已接管";
        if ((isAxis() || isPose()) && core.axisStrikeBusy(flight, pointingRequester)) return "舰体正在执行指向或导航任务";
        if (isPose()) return beginPoseTick(level, core);
        if (elapsed % REFRESH_TICKS == 0) {
            var reason = axisSubmitted() ? validate(level, core, true) : refresh(level, core, StrikePreview.SightCheck.SKIP);
            if (reason != null) return reason;
        }
        if (isAxis() && (axisAim == null || !flight.tryRequestAxisPointing(pointingRequester, axisAim.bodyAxis(), axisAim.direction(),
                FireControlCoreBlockEntity.POINTING_MAX_RATE, FireControlCoreBlockEntity.POINTING_MAX_ACCEL))) {
            return "舰体指向不可用";
        }
        return null;
    }

    private @Nullable String beginPoseTick(ServerLevel level, FireControlCoreBlockEntity core) {
        long now = level.getGameTime();
        boolean verifyConfiguration = (now - startTick) % REFRESH_TICKS == 0;
        var reason = validate(level, core, verifyConfiguration);
        if (reason != null) return reason;
        if (!flight.tryRequestAttitudePointing(pointingRequester, posePlan.orientation(),
                FireControlCoreBlockEntity.POINTING_MAX_RATE, FireControlCoreBlockEntity.POINTING_MAX_ACCEL)) {
            return "舰体指向不可用";
        }
        poseReady = flight.attitudePointingSettled(pointingRequester);
        if (!poseReady) {
            if (verifyConfiguration && readRoster(level, core) == null) {
                return "火控配置已变化";
            }
            aims = Map.of();
            return null;
        }
        if (!aims.isEmpty() && now < nextPoseRefresh) return null;
        nextPoseRefresh = now + REFRESH_TICKS;
        return refresh(level, core, StrikePreview.SightCheck.SKIP);
    }

    boolean controls(TurretUnit unit) {
        return !isAxis() && selected.contains(unit.id());
    }

    void drive(ServerLevel level, TurretUnit unit, List<SwivelTurretActuator.Mount> mounts,
               Object[] handles, boolean allReady) {
        var aim = aims.get(unit.id());
        var turret = unit.actuator();
        if (aim == null) {
            turret.holdForHullTurn(level);
            return;
        }
        turret.tick(level, aim.direction());
        var actual = turret.currentDirWorld();
        var result = FireDirector.evaluate(new FireDirector.Input(FireDirector.Mode.VOLLEY,
                actual != null && Double.isFinite(turret.getAimingErrorDeg()), true, true, allReady, "炮座未就绪",
                turret.getAimingErrorDeg(), turret.isInArc(), true), () -> false);
        if (result.fires() && actual != null && aim.agreesWith(actual)) {
            settled.put(unit.id(), new Shots(turret, mounts, handles));
        }
    }

    boolean isAxis() { return weaponMode == StrikePreview.Mode.AXIS_BEAM; }

    boolean isPose() { return weaponMode == StrikePreview.Mode.TURRET_POSE; }

    boolean allSettled() {
        if (isAxis()) return !axisSubmitted() && flight.pointingSettled();
        return (!isPose() || poseReady) && settled.size() == selected.size();
    }

    @Nullable String checkBeforeFire(ServerLevel level, FireControlCoreBlockEntity core) {
        if (isAxis()) {
            axisShots = Map.of();
            return refresh(level, core, StrikePreview.SightCheck.ACTUAL);
        }
        String reason = refresh(level, core, StrikePreview.SightCheck.SKIP);
        if (reason != null || !stillAimed()) return reason;
        return refresh(level, core, StrikePreview.SightCheck.ACTUAL);
    }

    boolean stillAimed() {
        if (isAxis()) return axisShots.size() == selected.size();
        if (isPose() && !flight.attitudePointingSettled(pointingRequester)) return false;
        for (var entry : settled.entrySet()) {
            var actual = entry.getValue().turret().currentDirWorld();
            if (actual == null || !entry.getValue().turret().isInArc() || !aims.get(entry.getKey()).agreesWith(actual)) return false;
        }
        return true;
    }

    Iterable<Shots> shots() { return settled.values(); }

    boolean axisSubmitted() { return !axisAccepted.isEmpty(); }

    int submitAxis(long now) {
        for (var handle : axisShots.values()) {
            if (RailgunLink.macroFire(handle) != null) continue;
            axisAccepted.add(handle);
            axisWarmupDeadline = Math.max(axisWarmupDeadline, now + RailgunLink.macroTiming(handle).warmupTicks() + 20L);
        }
        return axisAccepted.size();
    }

    boolean axisWarmingUp() {
        return axisAccepted.stream().anyMatch(handle -> RailgunLink.macroTiming(handle).phase() == MacroReadout.Phase.WARMUP);
    }

    boolean axisFired() {
        return axisAccepted.stream().anyMatch(handle -> {
            var phase = RailgunLink.macroTiming(handle).phase();
            return phase == MacroReadout.Phase.FIRING || phase == MacroReadout.Phase.COOLDOWN;
        });
    }

    boolean axisWarmupTimedOut(long now) { return now >= axisWarmupDeadline; }

    void releasePointing(boolean levelAfterRelease) {
        if (pointingRequester != null) flight.clearPointing(pointingRequester, levelAfterRelease);
    }

    private @Nullable String refresh(ServerLevel level, FireControlCoreBlockEntity core, StrikePreview.SightCheck sight) {
        var reason = validate(level, core, sight == StrikePreview.SightCheck.ACTUAL);
        if (reason != null) return reason;
        var roster = readRoster(level, core);
        if (roster == null) return "火控配置已变化";
        var assessment = StrikePreview.assess(level, ship, roster, channel, weaponMode,
                target, selected, sight);
        if (!assessment.executableWeapons().equals(selected)) {
            if (isAxis() && sight == StrikePreview.SightCheck.ACTUAL && assessment.rows().stream()
                    .filter(row -> !row.eligible()).allMatch(row -> row.reason().equals(StrikePreview.AXIS_ALIGNING))) return null;
            return unavailable(assessment);
        }
        aims = assessment.turrets();
        if (isAxis()) {
            if (sight == StrikePreview.SightCheck.ACTUAL) axisShots = assessment.axisShots();
            else axisAim = assessment.axisAim();
        }
        return null;
    }

    private @Nullable StrikePreview.Roster readRoster(ServerLevel level, FireControlCoreBlockEntity core) {
        var roster = origin instanceof Terminal ? StrikePreview.roster(level, ship, flight.getBlockPos()) : StrikePreview.roster(core);
        return roster != null && roster.core() == core && roster.revision() == revision ? roster : null;
    }

    private @Nullable String validate(ServerLevel level, FireControlCoreBlockEntity core, boolean checkMark) {
        var owner = level.getServer().getPlayerList().getPlayer(player);
        if (owner == null || owner.level() != level || !owner.isAlive() || owner.isSpectator()) return "请求者已离开";
        if (flight.isRemoved() || ship.isRemoved() || flight.telemetrySnapshot() == null || core.registeredSubLevel() != ship
                || Sable.HELPER.getContaining(flight) != ship) return "船体或飞控采样已失效";
        if (origin instanceof Terminal terminal) {
            var stack = NavTerminalData.heldTerminal(owner, terminal.id());
            if (stack == null || !flight.terminalAllowed(terminal.id())
                    || !flight.getBlockPos().equals(NavTerminalData.boundCorePos(stack))
                    || !level.dimension().location().equals(NavTerminalData.boundCoreDim(stack))) {
                return "终端绑定或持有状态已变化";
            }
            if (checkMark && !terminal.quick() && !NavTerminalData.marks(stack).contains(terminal.mark())) {
                return "打击标定点已变化";
            }
        } else {
            var console = (Console) origin;
            if (console.menu().strikeCore(owner) != core) return "火控界面已关闭";
        }
        return null;
    }

    static String unavailable(StrikePreview.Assessment assessment) {
        return assessment.rows().stream().filter(row -> !row.eligible()).findFirst()
                .map(row -> row.reason()).orElse("炮座无法共用瞄准方向");
    }
}
