package com.bridgecore.display;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * HUD lock target feed — SPI v3 (contract: shared handbook §2.29 频道任务协议
 * v1, 2026-10-27; supersedes the §2.28 single-engagement-slot model).
 * Implemented by the fire-control side and registered via
 * {@link HudTargetProviders#register}; the holo HUD pulls one
 * snapshot per server render tick. Pure bridgecore API surface — this package
 * must NOT reference the firecontrol package.
 *
 * Model: the provider is a SCANNER — it reports every track (with category +
 * species tags) plus the authoritative per-channel task views and the command
 * channel. The HUD owns the filter chain (cone -> category -> species ->
 * nearest-to-crosshair auto-select, sticky with a grace window) and draws
 * YELLOW pre-lock boxes from its own candidate construction; RED boxes come
 * ONLY from the core's assignedTargetIds ("HUD 发出请求" ≠ "炮塔已实锁").
 * Uplink commands (PrelockUpdate/GPress/HPress/V-target-cycle) do NOT pass
 * through this SPI — the flight core calls the fire-control core directly
 * (修订 I, 2026-10-28: the v2 requestLock/requestUnlock/requestMultiLock
 * bridge and the engagedId slot were deleted outright, no compat layer).
 *
 * Pull semantics: called on the server thread during HUD canvas rendering.
 * Freshness rule: the renderer re-resolves entity positions per tick via
 * ServerLevel.getEntity(id-as-uuid); the snapshot pos is the fallback for SHIP
 * tracks and unloaded entities. A snapshot with an empty track list actively
 * clears the HUD layer — providers must push it when targets disappear, the
 * HUD does no timeout guessing.
 */
public interface HudTargetProvider {

    /** @return the current snapshot for the HUD block at {@code hudPos} riding
     *  {@code sub} (null = on the ground); null = this provider has no feed for
     *  that HUD (the next registered provider is asked). */
    @Nullable HudTargetSnapshot snapshot(Level level, @Nullable ServerSubLevel sub,
                                         BlockPos hudPos, Direction facing);

    /** Fire-control direct override (v3.1, 2026-10-01): while this returns
     *  true the HUD force-holds its Tab notch at "no-lock" (target layer
     *  off) — intended for turret rebuild / recalibration windows where the
     *  HUD must stand down. The pilot pin release rides the EXISTING
     *  slot-cleared linkage (fire control clears the slot on those actions,
     *  the HUD follows silently) — the HUD sends no packets for this path.
     *  Returning to false does NOT restore the previous notch: the HUD stays
     *  in the no-lock notch until the pilot Tabs out. Polled every tick on
     *  the FIRST registered provider that overrides this method (same
     *  routing as the write-backs). Default false — providers that never
     *  override it see no behavior change. Server thread. */
    default boolean suppressHudTargeting() {
        return false;
    }

    // ---------------- SPI v3 (频道任务协议 v1, shared handbook §2.29 §4) ----------------

    /** Task origin — the authority that issued a channel's task. */
    enum TaskSource { NONE, HUD, CONSOLE }

    /** Task mode (裁断稿 §1 名词表): IDLE = 待命; MULTI_LOCK = 多目标实锁
     *  (per-turret distributed assignment); PRECISE_LOCK = 精确锁定/锚定
     *  (whole channel converges on one target); FOLLOW = 视线跟随/展示. */
    enum TaskMode { IDLE, MULTI_LOCK, PRECISE_LOCK, FOLLOW }

    /** Per-channel authoritative task view (协议 §4 下行显示, 服务端渲染直读,
     *  零新增网络通道). {@code taskRev} is the core's monotone revision — the
     *  HUD must show a task ONLY while the view's rev matches the core's
     *  current value; on mismatch show "同步中", never a stale frame
     *  (评审 D). {@code assignedTargetIds} = the unique target ids the core
     *  ACTUALLY assigned in this channel — the ONLY basis for red boxes
     *  (黄框预锁来自 HUD 自己的候选构造; "HUD 发出请求" ≠ "炮塔已实锁").
     *  OFF 档照常显示 CONSOLE 来源任务与武器状态 (裁断稿 §3 末段). */
    record ChannelTaskView(int channel, String name, TaskSource source, TaskMode mode,
                           long taskRev, boolean promoteSuppressed,
                           List<String> assignedTargetIds) {
    }

    /** Read-only weapon indicators. AUTO_ALLOWED means the most recent AUTO
     * gate passed, never proof of a shot or a manual-fire permission. */
    enum TurretLamp { IDLE, UNKNOWN, TRACKING, WAITING, FAULT, AUTO_ALLOWED }

    /** Stable pairing order; one indicator per turret UUID, not per barrel. */
    record TurretStatusView(String id, TurretLamp lamp, boolean tracking) {}

    /** One server-thread view of the command channel. Energy is the minimum
     * valid mount fraction, NOT a pooled FE total. null means unknown;
     * partialEnergy marks mounts with no usable reading. */
    record WeaponStatusView(ChannelTaskView task, boolean autoFire,
                            boolean salvoWaiting, List<TurretStatusView> turrets,
                            @Nullable Double minimumEnergyRatio, boolean partialEnergy) {}

    /** HUD presentation only: no scanning, assignments, gate evaluation or
     * commands may be performed by this query. Null clears the weapon block. */
    default @Nullable WeaponStatusView weaponStatus(Level level,
            @Nullable ServerSubLevel sub, BlockPos hudPos, Direction facing) {
        return null;
    }

    /** v3: every channel's authoritative task view (index = channel number).
     *  Default empty — old (v2) providers compile and run untouched. Server
     *  thread, HUD render tick. */
    default List<ChannelTaskView> channelTasks(Level level, @Nullable ServerSubLevel sub) {
        return List.of();
    }

    /** v3: the core's current command channel (X 循环的目标; G/H 仲裁与显示
     *  高亮都以它为准). Default 0 — old providers see no behavior change.
     *  Server thread. */
    default int activeChannelOf(Level level, @Nullable ServerSubLevel sub) {
        return 0;
    }

    /** Coarse target class for the HUD category filter (Tab cycles:
     *  all -> PLAYER -> CREATURE -> MONSTER -> SHIP). MONSTER is the
     *  vanilla instanceof Monster ruling; SHIP = physical structures
     *  (non-LivingEntity, listed separately). */
    enum Category {PLAYER, CREATURE, MONSTER, SHIP}

    /** One tracked contact. id: entity uuid string for PLAYER/CREATURE/MONSTER,
     *  SubLevel uuid for SHIP — stable identity either way. species: entity
     *  type registry path (e.g. "zombie") for the species filter (V cycles the
     *  species actually present in the current cone+category set; null species
     *  filter = all). vel/health/maxHealth nullable (SHIPs carry no health). */
    record Track(String id, Category category, String species, Vec3 pos,
                 @Nullable Vec3 vel, @Nullable Double health, @Nullable Double maxHealth) {
    }

    /** @param tracks every reportable contact (scan side owns whitelist /
     *  sky-exposure filtering — the HUD paints what it gets). v3 (修订 I):
     *  the v2 engagedId slot is gone — task state arrives via channelTasks. */
    record HudTargetSnapshot(List<Track> tracks) {
        public static final HudTargetSnapshot EMPTY = new HudTargetSnapshot(List.of());
    }
}
