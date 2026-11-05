package com.bridgecore.firecontrol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Turret pairing sessions (phase 3, user ruling: manual pairing like the
 * gamepad's bounding-box pairing). Flow, all via chat prompts:
 * <ol>
 *   <li>compass + right-click the fire-control core -> session starts;</li>
 *   <li>right-click the yaw (base) swivel bearing -> recorded;</li>
 *   <li>right-click the pitch bearing (on the turret head) -> recorded;</li>
 *   <li>OPTIONAL (2026-09-17): compass + right-click the barrel ROOT block,
 *       then the barrel TIP block -> geometric barrel-axis reference
 *       (tip - base in the gun yard frame; exact, and independent of where
 *       the pitch bearing actually sits — it is typically one block beside
 *       the barrel or behind a fixed cannon mount, NOT on the barrel axis);</li>
 *   <li>OPTIONAL (P3, fe_railgun link): compass + right-click a cannon MOUNT
 *       block -> linked to the turret (multiple mounts supported, numbered in
 *       click order; re-click a linked mount = unlink it);</li>
 *   <li>back to the core, compass + right-click -> muzzle reference resolved
 *       (geometric if a tip was marked, else the player's look), SIGN
 *       auto-calibration starts.</li>
 * </ol>
 * Sneak + compass on the core cancels the session / unpairs.
 *
 * <p>Session rework (2026-09-25, user report "配完了提示缺俯仰轴 / 配俯仰轴
 * 又提示不能配同一个轴承 / 想重来都不行"):
 * <ul>
 *   <li>pitch lives IN the session now — it used to be stashed on the core
 *       block entity (pendingPairPitch), which died silently on a core BE
 *       reload ("还差俯仰轴" right after clicking it) and went stale across
 *       cancel/unpair (a new session could complete against an old pitch);</li>
 *   <li>clicking the recorded yaw bearing again RE-RECORDS it and clears the
 *       later steps — an in-session restart. The old behaviour was a dead-end
 *       error ("两台轴承不能是同一个方块") with no recovery short of
 *       unpairing;</li>
 *   <li>the barrel-marking branch only arms AFTER pitch is recorded — before
 *       that, a click that failed to resolve to a bearing gets a guidance
 *       message instead of silently becoming a "barrel root" mark.</li>
 * </ul>
 * Server side only; sessions are per-player and die with the player
 * disconnecting (map entry is harmless to keep — completion/cancel clears it).
 */
public final class TurretPairing {

    private TurretPairing() {}

    private record Session(BlockPos corePos,
                           @org.jetbrains.annotations.Nullable BlockPos yawPos,
                           @org.jetbrains.annotations.Nullable BlockPos pitchPos,
                           @org.jetbrains.annotations.Nullable BlockPos barrelBase,
                           @org.jetbrains.annotations.Nullable BlockPos muzzleTip,
                           List<BlockPos> mounts) {}

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    /** Pairing/mount-link trigger item (2026-10-02 user ruling: the compass is
     *  a DEV-ONLY stand-in — when the mod ships its own tool item, this one
     *  method is the ONLY place to change; FireControlCoreBlock's session
     *  start/complete check routes through here too). */
    static boolean isPairingTool(net.minecraft.world.item.ItemStack stack) {
        return stack.is(Items.COMPASS);
    }

    public static boolean hasSession(UUID playerId) {
        return SESSIONS.containsKey(playerId);
    }

    public static void begin(ServerPlayer player, BlockPos corePos) {
        SESSIONS.put(player.getUUID(), new Session(corePos, null, null, null, null, List.of()));
        player.sendSystemMessage(Component.literal(
                "[火控核心] 配对开始: 请右键【偏航轴承】(炮塔基座) (未选轴承时再点核心=取消)"));
    }

    public static void cancel(ServerPlayer player, String reason) {
        SESSIONS.remove(player.getUUID());
        player.sendSystemMessage(Component.literal("[火控核心] 配对取消" + (reason.isEmpty() ? "" : ": " + reason)));
    }

    /**
     * Compass + right-click on the core: completes the session when both
     * bearings are recorded (muzzle captured from the player's look), otherwise
     * reports progress. Returns true when a pairing was completed.
     */
    public static boolean tryComplete(ServerPlayer player, FireControlCoreBlockEntity core) {
        Session s = SESSIONS.get(player.getUUID());
        if (s == null || !s.corePos().equals(core.getBlockPos())) return false;
        if (s.yawPos() == null) {
            // second tap on the core before any bearing is recorded = cancel
            // (sneak+compass never reaches the block: sneaking skips block
            // interaction in the vanilla pipeline)
            SESSIONS.remove(player.getUUID());
            player.sendSystemMessage(Component.literal("[火控核心] 配对取消"));
            return true;
        }
        if (s.pitchPos() == null) {
            player.sendSystemMessage(Component.literal(
                    "[火控核心] 配对中: 偏航@" + s.yawPos().toShortString() + " 已录, 还差【俯仰轴承】(右键它)"));
            return true;
        }
        String replaced = core.completePairing(s.yawPos(), s.pitchPos(), player.getLookAngle(),
                s.barrelBase(), s.muzzleTip(), s.mounts());
        SESSIONS.remove(player.getUUID());
        if (replaced != null) {
            player.sendSystemMessage(Component.literal("[火控核心] " + replaced + " 占用同一轴承, 已被本次配对替换"));
        }
        boolean geometric = s.barrelBase() != null && s.muzzleTip() != null;
        player.sendSystemMessage(Component.literal((geometric
                ? "[火控核心] 配对完成, 炮口几何参考已记录, 符号自校准开始(保持轴承恒锁)"
                : "[火控核心] 配对完成, 使用视线参考(精度有限), 符号自校准开始(保持轴承恒锁)")
                + (s.mounts().isEmpty() ? ", 未挂炮座(纯瞄准)" : ", 已挂炮座×" + s.mounts().size())
                + ", 目标锁定已解除"));
        return true;
    }

    /** Right-click on a swivel bearing while in a session: record yaw/pitch. */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        // MAIN_HAND only: RightClickBlock fires once per hand and this flow is
        // compass-driven — the empty-offhand second fire otherwise advances the
        // session a second time in the same click (2026-09-18: one yaw click
        // recorded yaw AND printed 两台轴承不能是同一个方块).
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!isPairingTool(event.getItemStack())) return; // pairing is tool-driven
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Session s = SESSIONS.get(player.getUUID());
        if (s == null) {
            // P3 fix 2 (session-less mount link/unlink, no re-pairing needed):
            // tool + right-click a gun mount toggles it on the OWNING turret —
            // the mount's containing sub-level is matched against each paired
            // turret's bearing-child sub-level ids (unassembled turrets have no
            // child id and simply never match).
            if (MountLinks.active().mountAt(event.getLevel(), event.getPos()) != null) {
                event.setCanceled(true);
                String msg = FireControlCoreBlockEntity.toggleMountAt(event.getLevel(), event.getPos());
                player.sendSystemMessage(Component.literal("[火控核心] " + (msg != null
                        ? msg : "该炮座不属于任何已配对炮塔(不在炮管子结构上)")));
            }
            return;
        }
        BlockEntity clicked = event.getLevel().getBlockEntity(event.getPos());
        if (!(clicked instanceof SwivelBearingBlockEntity)) {
            // P3 gun-mount hookup (fe_railgun): once both bearings are recorded,
            // compass + right-click a cannon mount to link it (re-click the same
            // mount = unlink; multiple mounts supported — 多联装主炮, numbered
            // in click order at completion). Checked by block type BEFORE the
            // barrel-marking branch below — a mount block is never a barrel
            // root/tip mark.
            if (s.yawPos() != null && s.pitchPos() != null && !event.getPos().equals(s.corePos())
                    && MountLinks.active().mountAt(event.getLevel(), event.getPos()) != null) {
                event.setCanceled(true);
                List<BlockPos> mounts = new ArrayList<>(s.mounts());
                if (mounts.remove(event.getPos())) {
                    SESSIONS.put(player.getUUID(), new Session(s.corePos(), s.yawPos(), s.pitchPos(),
                            s.barrelBase(), s.muzzleTip(), List.copyOf(mounts)));
                    player.sendSystemMessage(Component.literal(
                            "[火控核心] 炮座挂载已取消 @ " + event.getPos().toShortString()));
                } else if (mounts.size() >= SwivelTurretActuator.MAX_MOUNTS) {
                    player.sendSystemMessage(Component.literal(
                            "[火控核心] 炮座已达上限(" + SwivelTurretActuator.MAX_MOUNTS + ")"));
                } else {
                    mounts.add(event.getPos().immutable());
                    SESSIONS.put(player.getUUID(), new Session(s.corePos(), s.yawPos(), s.pitchPos(),
                            s.barrelBase(), s.muzzleTip(), List.copyOf(mounts)));
                    player.sendSystemMessage(Component.literal(
                            "[火控核心] 炮座已记录 @ " + event.getPos().toShortString()
                                    + " (第 " + mounts.size() + " 座)"));
                }
                return;
            }
            // optional barrel-axis marking (2026-09-17, two points — the pitch
            // bearing is NOT on the barrel axis: it sits one block beside the
            // barrel or behind a fixed cannon mount, so a single tip point
            // referenced to the bearing is a diagonal). Compass + right-click
            // the barrel ROOT block (a fixed cannon mount is a natural root),
            // then the barrel TIP: (tip - base) is the true barrel axis in the
            // gun yard frame, pose-free and bearing-position-free.
            //
            // Armed only AFTER pitch is recorded: before that, a click that
            // didn't resolve to a bearing is far more likely a missed pitch
            // click than an intentional barrel mark (2026-09-25: such clicks
            // were silently swallowed as barrel roots and the user then got
            // "还差俯仰轴" at completion with no idea why).
            if (s.pitchPos() != null && !event.getPos().equals(s.corePos())) {
                event.setCanceled(true);
                if (s.barrelBase() == null) {
                    SESSIONS.put(player.getUUID(), new Session(s.corePos(), s.yawPos(), s.pitchPos(),
                            event.getPos().immutable(), null, s.mounts()));
                    player.sendSystemMessage(Component.literal(
                            "[火控核心] 炮管根部已记录 @ " + event.getPos().toShortString()
                                    + " , 再指南针右键【炮口前端方块】(或回核心直接完成)"));
                } else {
                    SESSIONS.put(player.getUUID(), new Session(s.corePos(), s.yawPos(), s.pitchPos(),
                            s.barrelBase(), event.getPos().immutable(), s.mounts()));
                    player.sendSystemMessage(Component.literal(
                            "[火控核心] 炮口前端已记录 @ " + event.getPos().toShortString()
                                    + " , 回到核心指南针右键完成"));
                }
            } else if (s.yawPos() != null && s.pitchPos() == null && !event.getPos().equals(s.corePos())) {
                player.sendSystemMessage(Component.literal(
                        "[火控核心] 这里没识别到轴承: 请右键【俯仰轴承】(炮塔头上; 结构在动就等停稳再点)"));
            }
            return; // not a bearing: let other clicks through (core clicks handled by the block)
        }
        event.setCanceled(true); // swallow the bearing's own UI while pairing
        if (s.yawPos() == null) {
            SESSIONS.put(player.getUUID(), new Session(s.corePos(), event.getPos().immutable(), null, null, null, List.of()));
            player.sendSystemMessage(Component.literal(
                    "[火控核心] 偏航轴承已记录 @ " + event.getPos().toShortString()
                            + " , 请右键【俯仰轴承】(炮塔头上)"));
        } else if (event.getPos().equals(s.yawPos())) {
            // in-session restart: re-record yaw, clear the later steps
            SESSIONS.put(player.getUUID(), new Session(s.corePos(), event.getPos().immutable(), null, null, null, List.of()));
            player.sendSystemMessage(Component.literal(
                    "[火控核心] 偏航轴承已重录 @ " + event.getPos().toShortString()
                            + " , 俯仰/炮管/炮座标记已清空, 请右键【俯仰轴承】"));
        } else {
            SESSIONS.put(player.getUUID(), new Session(s.corePos(), s.yawPos(),
                    event.getPos().immutable(), null, null, List.of()));
            player.sendSystemMessage(Component.literal(
                    "[火控核心] 俯仰轴承已记录 @ " + event.getPos().toShortString()
                            + " , 可选: 指南针右键【炮管根部方块】再右键【炮口前端方块】取精确炮管基准"
                            + " , 可选: 指南针右键【炮座】挂火炮"
                            + " , 然后回核心指南针右键完成 (重点偏航轴承=重录)"));
        }
    }
}
