package com.bridgecore.firecontrol;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.bridgecore.display.HudTargetProvider;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Fire-control HUD target feed — SPI v3 (contract: shared handbook §2.29 频道
 * 任务协议 v1; supersedes the §2.28 single-engagement-slot model).
 *
 * Semantics:
 *  - tracks = every non-whitelisted registry target (whitelist / canSeeSky
 *    filtering are the scan side's job, upstream of the registry). category +
 *    species are tagged at scan time (see TargetRegistry), never guessed here.
 *  - SHIP track pos/vel go through the core's 20 Hz fast path (freshState):
 *    the renderer re-resolves ENTITY positions per tick by uuid but has no
 *    equivalent for structures, so ship tracks must not ride the 4 Hz scan
 *    staircase. Entity tracks report the registry's last-scan pos/vel; the
 *    renderer's per-tick re-resolution supersedes them anyway.
 *  - v3: {@link #channelTasks} exposes every channel's authoritative
 *    ChannelTask (source/mode/rev/promoteSuppressed + the core's ACTUAL
 *    assigned target ids — the only red-box basis); {@link #activeChannelOf}
 *    reports the command channel X cycles.
 *  - v2 兼容桥已整体删除 (修订 I, 飞控侧 2026-10-30 落地②): requestLock/
 *    requestUnlock/requestMultiLock 与 snapshot 的 engagedId 字段均已移除,
 *    飞控侧直调核心 (prelockUpdate/gPress/hPress/cyclePreciseTarget)。
 *  - a HUD whose structure has no fire-control core gets null = "no feed".
 */
public final class FireControlHudTargetProvider implements HudTargetProvider {

    @Override
    public @Nullable HudTargetSnapshot snapshot(Level level, @Nullable ServerSubLevel sub,
                                                BlockPos hudPos, Direction facing) {
        FireControlCoreBlockEntity core = FireControlCoreBlockEntity.findFor(level, sub, hudPos);
        if (core == null) return null;

        TargetRegistry registry = core.targets();
        List<Track> tracks = new ArrayList<>();
        for (TargetRegistry.Target t : registry.targets()) {
            if (t.whitelisted()) continue;
            Vec3 pos;
            Vec3 vel;
            double[] fresh = t.category() == TargetRegistry.Category.SHIP
                    ? core.freshState(level, t) : null;
            if (fresh != null) {
                pos = new Vec3(fresh[0], fresh[1], fresh[2]);
                vel = new Vec3(fresh[3], fresh[4], fresh[5]);
            } else {
                pos = new Vec3(t.x(), t.y(), t.z());
                vel = new Vec3(t.vx() * 20, t.vy() * 20, t.vz() * 20);
            }
            tracks.add(new Track(t.id(), Category.valueOf(t.category().name()), t.species(),
                    pos, vel, t.health(), t.maxHealth()));
        }
        // 修订 I: engagedId 已随 v2 整体删除 (2026-10-30 飞控侧落地②)
        return new HudTargetSnapshot(tracks);
    }

    /** The core that speaks for this structure's v3 views (the channel task
     *  views carry no hudPos — with multiple cores on one structure the FIRST
     *  one answers; 评审 F 的多核心舰队级统一指挥在后续批次). */
    private static @Nullable FireControlCoreBlockEntity firstCore(Level level,
                                                                  @Nullable ServerSubLevel sub) {
        List<FireControlCoreBlockEntity> cores = FireControlCoreBlockEntity.coresOf(level, sub);
        return cores.isEmpty() ? null : cores.get(0);
    }

    @Override
    public List<ChannelTaskView> channelTasks(Level level, @Nullable ServerSubLevel sub) {
        FireControlCoreBlockEntity core = firstCore(level, sub);
        if (core == null) return List.of();
        List<ChannelTaskView> out = new ArrayList<>(FireControlCoreBlockEntity.CHANNEL_COUNT);
        for (int ch = 0; ch < FireControlCoreBlockEntity.CHANNEL_COUNT; ch++) {
            ChannelTask t = core.taskOf(ch);
            out.add(new ChannelTaskView(ch, core.channelName(ch),
                    TaskSource.valueOf(t.source().name()), TaskMode.valueOf(t.mode().name()),
                    t.rev(), t.promoteSuppressed(), core.assignedUniqueTargetIds(ch)));
        }
        return out;
    }

    @Override
    public int activeChannelOf(Level level, @Nullable ServerSubLevel sub) {
        FireControlCoreBlockEntity core = firstCore(level, sub);
        return core != null ? core.activeChannel() : 0;
    }

    /** Same-thread, read-only presentation snapshot. No new gate evaluation
     * or LOS raycast: only the drive loop's revision-stamped verdict is read. */
    @Override
    public @Nullable WeaponStatusView weaponStatus(Level level, @Nullable ServerSubLevel sub,
                                                   BlockPos hudPos, Direction facing) {
        var core = FireControlCoreBlockEntity.findFor(level, sub, hudPos);
        if (core == null) return null;
        int ch = core.activeChannel();
        ChannelTask t = core.taskOf(ch);
        var task = new ChannelTaskView(ch, core.channelName(ch),
                TaskSource.valueOf(t.source().name()), TaskMode.valueOf(t.mode().name()),
                t.rev(), t.promoteSuppressed(), List.copyOf(core.assignedUniqueTargetIds(ch)));
        List<TurretStatusView> lamps = new ArrayList<>();
        Double minimumEnergy = null;
        boolean partialEnergy = false;
        MountLink link = MountLinks.active();
        for (TurretUnit unit : core.turrets()) {
            if (unit.channel() != ch || !unit.actuator().isPaired()) continue;
            var mounts = unit.actuator().mounts();
            var gate = core.currentHudGate(unit.id());
            boolean active = t.mode() != ChannelTask.Mode.IDLE;
            boolean tracking = active && gate != null
                    && gate.gate() != FireDirector.Gate.NO_TARGET
                    && gate.gate() != FireDirector.Gate.NO_CHANNEL;
            TurretLamp lamp;
            int[] codes = core.mountStatusCodesOf(unit.id());
            if (mounts.isEmpty()) lamp = TurretLamp.FAULT;
            else if (!unit.isReady()) lamp = TurretLamp.WAITING;
            else if (codes.length != mounts.size()) lamp = TurretLamp.UNKNOWN;
            else {
                boolean fault = false, waiting = false;
                for (int code : codes) {
                    if (code == MountLink.STATUS_BREECH_BUSY) waiting = true;
                    else if (code != MountLink.STATUS_READY) fault = true;
                }
                if (fault) lamp = TurretLamp.FAULT;
                else if (waiting) lamp = TurretLamp.WAITING;
                else if (!active) lamp = TurretLamp.IDLE;
                else if (gate == null) lamp = TurretLamp.UNKNOWN;
                else lamp = switch (gate.gate()) {
                    case FIRE -> core.channelAutoFire(ch) && !core.channelSalvoWaiting(ch)
                            && t.mode() != ChannelTask.Mode.FOLLOW
                            ? TurretLamp.AUTO_ALLOWED : TurretLamp.TRACKING;
                    case AIMING, OUT_OF_ARC, CADENCE, BLOCKED, NOT_READY -> TurretLamp.WAITING;
                    case AUTO_OFF -> TurretLamp.TRACKING;
                    default -> TurretLamp.IDLE;
                };
            }
            lamps.add(new TurretStatusView(unit.id().toString(), lamp, tracking));
            if (mounts.isEmpty()) partialEnergy = true;
            for (var mount : mounts) {
                var reading = link.energyReading(link.mountAt(level, mount.pos()));
                if (reading == null || reading.capacity() <= 0 || reading.available() < 0) {
                    partialEnergy = true;
                    continue;
                }
                double fraction = Math.min(1.0, (double) reading.available() / reading.capacity());
                minimumEnergy = minimumEnergy == null ? fraction : Math.min(minimumEnergy, fraction);
            }
        }
        return new WeaponStatusView(task, core.channelAutoFire(ch), core.channelSalvoWaiting(ch),
                List.copyOf(lamps), minimumEnergy, partialEnergy);
    }

    @Override
    public boolean suppressHudTargeting() {
        return FireControlCoreBlockEntity.anyCalibrationActive();
    }
}
