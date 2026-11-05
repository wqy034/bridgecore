package com.bridgecore.menu;

import com.bridgecore.BCRegistry;
import com.bridgecore.firecontrol.FireControlUiText;
import com.bridgecore.firecontrol.MountLinks;
import com.bridgecore.network.FireControlIdentityPayload;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import com.bridgecore.firecontrol.FireControlCoreBlockEntity;
import com.bridgecore.firecontrol.SwivelTurretActuator;
import com.bridgecore.firecontrol.TurretPairing;
import com.bridgecore.firecontrol.TurretUnit;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * Fire-control core menu — the internal management GUI (same Menu/Screen
 * pattern as the flight core's BridgeCoreMenu; user ruling 2026-09-26:
 * 内部调控走内部UI，不放显示屏). No machine slots and no player-inventory
 * slots — this is a pure config/management panel.
 *
 * <p>ContainerData (ints only, broadcast every tick):
 * <pre>
 *   0 = turret count (0..MAX_TURRETS, display is capped)
 *   per turret i, base = 1 + i*16:
 *     +0 = status: 0 not-paired, 1 calibrating, 2 ready
 *     +1 = aim error x10 deg, -1 when NaN
 *     +2 = name index N of "炮塔 N" (creation-order number, survives deletions)
 *     +3 = fire-arc yaw min, int deg (-180..180; P2)
 *     +4 = fire-arc yaw max, int deg (P2)
 *     +5 = fire-arc pitch min, int deg (P2)
 *     +6 = fire-arc pitch max, int deg (P2)
 *     +7 = live yaw vs arc zero x10 deg, -9999 when unknown (GUI indicator dot)
 *     +8 = live pitch vs arc zero x10 deg, -9999 when unknown
 *     +9 = auto-fire switch 0/1 (P3)
 *     +10 = aggregate mount status (P3): -1 no mounts, 1 = EVERY mount READY
 *           (the volley gate), otherwise the first non-READY code
 *     +11 = mount count (0..4)
 *     +12..+15 = per-mount packed chip, slot k: (mountNo << 4) | (code + 1),
 *           code = RailgunLink.STATUS_* (-1 no mount / 0 NONE / 1 READY /
 *           2 NOT_ASSEMBLED / 3 NO_AMMO / 4 INSUFFICIENT_ENERGY /
 *           5 OVERHEATED / 6 BREECH_BUSY); -1 = empty slot
 * </pre>
 *
 * <p>Button ids ({@link #clickMenuButton}): 0..MAX_TURRETS-1 = unpair turret
 * row i; {@link #BUTTON_CANCEL_PAIR} = cancel the clicker's pairing session;
 * {@link #BUTTON_FIRE_VOLLEY} = manual volley from every fire-ready mounted
 * turret (P3);
 * {@link #FIRE_TOGGLE_BASE}+row = toggle that row's auto-fire switch (P3);
 * {@link #MOUNT_UNBIND_BASE} + row*16 + slotIdx = unlink that mount chip (P3);
 * {@link #ARC_SET_BASE}.. = absolute arc set from the GUI drag handles:
 * id = ARC_SET_BASE + row*4096 + param*512 + (valueDeg+180), param 0=yawMin /
 * 1=yawMax / 2=pitchMin / 3=pitchMax, valueDeg -180..180.
 * New commands live in {@link FireControlMenuActions}, starting at 65536
 * AFTER the complete legacy arc range. Row order is insertion order.
 *
 * <p>stillValid has no distance check (Sable shipyard vs logical coordinates
 * would always fail); removal is the only invalidation.
 */
public class FireControlMenu extends AbstractContainerMenu {

    public static final int MAX_TURRETS = 8;
    public static final int BUTTON_CANCEL_PAIR = 100;
    /** Toggle the sky-visibility scan filter (隔墙锁 fix, default ON; 2026-09-26
     *  user ruling: player-toggleable per core). */
    public static final int BUTTON_TOGGLE_SKY_FILTER = 101;
    /** Re-run SIGN probes + fresh AxisLock zero captures on all paired turrets. */
    public static final int BUTTON_RECALIBRATE_ALL = 102;
    /** Toggle 视线跟随 (aim-at-look) for the clicking player — 当前频道的
     *  FOLLOW 任务 (裁断稿 §7 特殊任务, CONSOLE 来源). */
    public static final int BUTTON_TOGGLE_FOLLOW = 103;
    /** Manual volley (P3 fix 1): one shot from every fire-ready mounted
     *  turret; the fire DECISION is the operator's, auto-fire stays an
     *  off-by-default auxiliary mode. */
    public static final int BUTTON_FIRE_VOLLEY = 104;

    /** Toggle a row's auto-fire switch (P3): id = FIRE_TOGGLE_BASE + row. */
    public static final int FIRE_TOGGLE_BASE = 200;

    /** Unlink one mount chip (P3 multi-mount): id = MOUNT_UNBIND_BASE +
     *  row*16 + slotIdx (slotIdx = the mount's index in link order). */
    public static final int MOUNT_UNBIND_BASE = 4096;

    /** Base id of the absolute arc-set button encoding (GUI drag handles):
     *  id = ARC_SET_BASE + row*4096 + param*512 + (valueDeg+180);
     *  param 0=yawMin / 1=yawMax / 2=pitchMin / 3=pitchMax, valueDeg -180..180. */
    public static final int ARC_SET_BASE = 32768;

    public static final int STATUS_UNPAIRED = 0;
    public static final int STATUS_CALIBRATING = 1;
    public static final int STATUS_READY = 2;

    /** Per-row ContainerData stride: status / err10 / nameIdx / yawMin /
     *  yawMax / pitchMin / pitchMax / relYaw10 / relPitch10 / autoFire /
     *  mountAggregate / mountCount / 4x packed mount chip. */
    private static final int ROW_STRIDE = 16;
    /** relYaw10/relPitch10 sentinel for "no aim tick yet". */
    public static final int REL_UNKNOWN = -9999;
    /** Packed mount chip slot value for an empty slot. */
    public static final int MOUNT_SLOT_EMPTY = -1;

    private static final int DATA_SKY_FILTER = 1 + MAX_TURRETS * ROW_STRIDE;
    private static final int DATA_FOLLOW = DATA_SKY_FILTER + 1;
    // Append data; the legacy 16-int rows and sky/follow offsets stay intact.
    private static final int DATA_EXTRA = DATA_FOLLOW + 1;
    private static final int EXTRA_STRIDE = 6; // channel, cadence, adjustable, gate, identity, paired
    private static final int DATA_CHANNELS = DATA_EXTRA + MAX_TURRETS * EXTRA_STRIDE;
    private static final int CHANNEL_STRIDE = 5; // auto, promote, mode, source, suppression
    private static final int DATA_ACTIVE_CHANNEL = DATA_CHANNELS + 4 * CHANNEL_STRIDE;
    private static final int DATA_PAIRING = DATA_ACTIVE_CHANNEL + 1;
    private static final int DATA_ROSTER_EPOCH = DATA_PAIRING + 1;
    private static final int DATA_COUNT = DATA_ROSTER_EPOCH + 1;

    private final FireControlCoreBlockEntity be;
    private final SyncData data = new SyncData();
    private final Player viewer;
    private java.util.List<String> operationRoster = java.util.List.of();
    private int operationEpoch;
    private @Nullable FireControlIdentityPayload lastSentIdentity, receivedIdentity;
    private int identityVersion;

    /** Only open-menu viewers receive text. Moving world positions refresh at
     * most twice/second; a changed roster is sent immediately. No forced loads. */
    private void broadcastIdentity() {
        if (!(viewer instanceof ServerPlayer player) || be.getLevel() == null) return;
        if (lastSentIdentity != null && lastSentIdentity.epoch() == operationEpoch
                && be.getLevel().getGameTime() % 10 != 0) return;
        var rows = new java.util.ArrayList<FireControlIdentityPayload.Turret>();
        var level = be.getLevel();
        for (TurretUnit unit : be.turrets()) {
            if (rows.size() >= MAX_TURRETS) break;
            var a = unit.actuator();
            var mounts = new java.util.ArrayList<FireControlIdentityPayload.Mount>();
            var weapons = new java.util.LinkedHashMap<String, Integer>();
            boolean unloaded = false;
            for (var mount : a.mounts()) {
                boolean loaded = level.hasChunkAt(mount.pos());
                unloaded |= !loaded;
                Object handle = loaded ? MountLinks.active().mountAt(level, mount.pos()) : null;
                String weapon = !loaded ? "炮座未加载" : handle == null ? "炮座已移除 / 不支持" :
                        FireControlUiText.weapon(MountLinks.active().gunTypeName(handle));
                if (MountLinks.active().hasGun(handle)) weapons.merge(weapon, 1, Integer::sum);
                mounts.add(new FireControlIdentityPayload.Mount(mount.no(), weapon, worldLocation(mount.pos())));
            }
            String weapon = weapons.entrySet().stream().map(entry -> entry.getKey()
                    + (entry.getValue() > 1 ? "×" + entry.getValue() : "")).collect(java.util.stream.Collectors.joining("+"));
            if (weapon.isEmpty()) weapon = mounts.isEmpty() ? "未挂载炮座" : unloaded ? "炮座未加载" : "未装配武器";
            BlockPos offset = a.yawOffset();
            rows.add(new FireControlIdentityPayload.Turret(unit.id(), unit.name(),
                    weapon, worldLocation(a.yawPos()), worldLocation(a.pitchPos()),
                    offset == null ? "未知" : offset.getX() + ", " + offset.getY() + ", " + offset.getZ(), mounts));
        }
        var names = new java.util.ArrayList<String>(4);
        for (int ch = 0; ch < 4; ch++) names.add(be.channelName(ch));
        var payload = new FireControlIdentityPayload(containerId, operationEpoch, names, rows);
        if (!payload.equals(lastSentIdentity)) {
            PacketDistributor.sendToPlayer(player, payload);
            lastSentIdentity = payload;
        }
    }

    private String worldLocation(@Nullable BlockPos pos) {
        var level = be.getLevel();
        if (pos == null) return "未配对";
        if (level == null || !level.hasChunkAt(pos)) return "未加载";
        var world = new Vector3d(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        if (Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel ship)
            ship.logicalPose().transformPosition(world, world);
        return "X " + (int) Math.floor(world.x) + "  Y " + (int) Math.floor(world.y) + "  Z " + (int) Math.floor(world.z);
    }

    public void acceptIdentity(FireControlIdentityPayload payload) {
        if (payload.menuId() == containerId && !payload.equals(receivedIdentity)) {
            receivedIdentity = payload;
            identityVersion++;
        }
    }
    public int identityVersion() { return identityVersion; }
    public @Nullable FireControlIdentityPayload.Turret turretInfo(int row) {
        if (receivedIdentity == null || receivedIdentity.epoch() != rosterEpoch()
                || row < 0 || row >= turretCount() || row >= receivedIdentity.turrets().size()) return null;
        var info = receivedIdentity.turrets().get(row);
        return (info.id().hashCode() & 0x7fff) == identity(row) ? info : null;
    }
    public String turretName(int row) {
        var info = turretInfo(row);
        return info == null ? "炮塔 " + nameIndex(row) : info.name();
    }
    public String weaponLabel(int row) {
        var info = turretInfo(row);
        return info == null ? "信息同步中" : info.weapon();
    }
    public String channelName(int ch) {
        return ch < 0 || ch >= 4 ? "未编组" : receivedIdentity == null
                ? FireControlUiText.channel(ch) : receivedIdentity.channels().get(ch);
    }
    public @Nullable FireControlIdentityPayload.Mount mountInfo(int row, int slot) {
        var info = turretInfo(row);
        int packed = mountPacked(row, slot);
        if (info == null || slot >= info.mounts().size() || packed == MOUNT_SLOT_EMPTY) return null;
        var mount = info.mounts().get(slot);
        return mount.no() == (packed >> 4) ? mount : null;
    }

    private void refreshOperationRoster() {
        java.util.List<String> next = new java.util.ArrayList<>();
        for (TurretUnit unit : be.turrets()) {
            next.add(unit.id().toString());
            for (var mount : unit.actuator().mounts()) next.add(mount.no() + ":" + mount.pos().asLong());
        }
        if (!next.equals(operationRoster)) {
            operationRoster = java.util.List.copyOf(next);
            operationEpoch = (operationEpoch + 1) & FireControlMenuActions.EPOCH_MASK;
        }
    }

    /** Server-side constructor. */
    public FireControlMenu(int windowId, Inventory playerInv, FireControlCoreBlockEntity be) {
        super(BCRegistry.FIRE_CONTROL_MENU.get(), windowId);
        this.be = be;
        this.viewer = playerInv.player;
        addDataSlots(data);
    }

    /** Client-side constructor (IMenuTypeExtension factory): extra data = BlockPos. */
    public FireControlMenu(int windowId, Inventory playerInv, FriendlyByteBuf buf) {
        this(windowId, playerInv,
                (FireControlCoreBlockEntity) playerInv.player.level().getBlockEntity(buf.readBlockPos()));
    }

    @Override
    public void broadcastChanges() {
        refreshOperationRoster();
        data.set(DATA_ROSTER_EPOCH, operationEpoch);
        int i = 0;
        for (TurretUnit u : be.turrets()) {
            if (i >= MAX_TURRETS) break;
            var a = u.actuator();
            int base = 1 + i * ROW_STRIDE;
            data.set(base, a.isReady() ? STATUS_READY : a.isPaired() ? STATUS_CALIBRATING : STATUS_UNPAIRED);
            double e = u.aimErrorDeg();
            data.set(base + 1, Double.isNaN(e) ? -1 : (int) Math.round(e * 10));
            data.set(base + 2, nameIndexOf(u, i));
            data.set(base + 3, (int) Math.round(a.yawMinDeg()));
            data.set(base + 4, (int) Math.round(a.yawMaxDeg()));
            data.set(base + 5, (int) Math.round(a.pitchMinDeg()));
            data.set(base + 6, (int) Math.round(a.pitchMaxDeg()));
            data.set(base + 7, Double.isNaN(a.relYawDeg()) ? REL_UNKNOWN : (int) Math.round(a.relYawDeg() * 10));
            data.set(base + 8, Double.isNaN(a.relPitchDeg()) ? REL_UNKNOWN : (int) Math.round(a.relPitchDeg() * 10));
            data.set(base + 9, u.channel() >= 0 && be.channelAutoFire(u.channel()) ? 1 : 0);
            int extra = DATA_EXTRA + i * EXTRA_STRIDE;
            data.set(extra, u.channel());
            data.set(extra + 1, a.fireIntervalTicks());
            data.set(extra + 2, be.turretCadenceAdjustable(i) ? 1 : 0);
            var gate = be.gateOf(u.id());
            data.set(extra + 3, gate == null ? -1 : gate.gate().ordinal());
            data.set(extra + 4, u.id().hashCode() & 0x7fff);
            data.set(extra + 5, a.isPaired() ? 1 : 0);
            int mc = a.mountCount();
            data.set(base + 10, be.mountAggregateOf(u.id(), mc));
            data.set(base + 11, mc);
            int[] codes = be.mountStatusCodesOf(u.id());
            var mounts = a.mounts();
            for (int k = 0; k < SwivelTurretActuator.MAX_MOUNTS; k++) {
                // pack (mountNo << 4) | (code + 1) — the chip needs the NUMBER
                // (numbers are never renumbered on unlink) plus the status
                data.set(base + 12 + k, k < mc
                        ? (mounts.get(k).no() << 4) | ((k < codes.length ? codes[k] : -1) + 1)
                        : MOUNT_SLOT_EMPTY);
            }
            i++;
        }
        data.set(0, i);
        data.set(DATA_SKY_FILTER, be.skyFilter() ? 1 : 0);
        data.set(DATA_FOLLOW, be.followActive() ? 1 : 0);
        for (int ch = 0; ch < FireControlCoreBlockEntity.CHANNEL_COUNT; ch++) {
            int base = DATA_CHANNELS + ch * CHANNEL_STRIDE;
            data.set(base, be.channelAutoFire(ch) ? 1 : 0);
            data.set(base + 1, be.channelAutoPromote(ch) ? 1 : 0);
            data.set(base + 2, be.taskOf(ch).mode().ordinal());
            data.set(base + 3, be.taskOf(ch).source().ordinal());
            data.set(base + 4, be.taskOf(ch).promoteSuppressed() ? 1 : 0);
        }
        data.set(DATA_ACTIVE_CHANNEL, be.activeChannel());
        data.set(DATA_PAIRING, TurretPairing.hasSession(viewer.getUUID()) ? 1 : 0);
        super.broadcastChanges();
        broadcastIdentity();
    }

    /** "炮塔 N" -> N (creation-order number baked into the auto name); renamed
     *  units (P2) fall back to the row index so the GUI stays readable. */
    private static int nameIndexOf(TurretUnit u, int row) {
        String n = u.name();
        if (n.startsWith("炮塔 ")) {
            try {
                return Integer.parseInt(n.substring(3).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return row + 1;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (be.isRemoved()) return false;
        if (id >= FireControlMenuActions.GUARDED_BASE && id < FireControlMenuActions.GUARDED_END) {
            refreshOperationRoster();
            int action = FireControlMenuActions.decodeGuarded(id, operationEpoch);
            if (action < 0) {
                player.sendSystemMessage(Component.literal("[火控核心] 炮塔或炮座列表已变化，请确认对象后重试"));
                return true;
            }
            id = action;
        }
        if (id >= FireControlMenuActions.RECALIBRATE_BASE && id < FireControlMenuActions.CHANNEL_SELECT_BASE) {
            TurretUnit u = be.turretByIndex(id - FireControlMenuActions.RECALIBRATE_BASE);
            String name = u == null ? null : be.recalibrateUnit(u.id(), player.level());
            if (name != null) player.sendSystemMessage(Component.literal(
                    "[火控核心] 正在重新校准 " + name + "，其他炮塔不重校；该频道任务按剩余可用成员重算"));
            return true;
        }
        if (id >= FireControlMenuActions.CHANNEL_SELECT_BASE && id < FireControlMenuActions.CHANNEL_AUTO_BASE) {
            be.setActiveChannel(id - FireControlMenuActions.CHANNEL_SELECT_BASE);
            return true;
        }
        if (id >= FireControlMenuActions.CHANNEL_AUTO_BASE && id < FireControlMenuActions.CHANNEL_PROMOTE_BASE) {
            be.toggleChannelAutoFire(id - FireControlMenuActions.CHANNEL_AUTO_BASE);
            return true;
        }
        if (id >= FireControlMenuActions.CHANNEL_PROMOTE_BASE && id < FireControlMenuActions.TURRET_CHANNEL_BASE) {
            be.toggleChannelAutoPromote(id - FireControlMenuActions.CHANNEL_PROMOTE_BASE);
            return true;
        }
        if (id >= FireControlMenuActions.TURRET_CHANNEL_BASE && id < FireControlMenuActions.CADENCE_DOWN_BASE) {
            int rel = id - FireControlMenuActions.TURRET_CHANNEL_BASE;
            be.setTurretChannel(rel / 5, rel % 5 - 1);
            return true;
        }
        if (id >= FireControlMenuActions.CADENCE_DOWN_BASE && id < FireControlMenuActions.END) {
            boolean slower = id < FireControlMenuActions.CADENCE_UP_BASE;
            int row = id - (slower ? FireControlMenuActions.CADENCE_DOWN_BASE : FireControlMenuActions.CADENCE_UP_BASE);
            if (be.turretCadenceAdjustable(row)) be.cycleFireInterval(row, slower ? 1 : -1);
            return true;
        }
        if (id >= 0 && id < MAX_TURRETS) {
            String removed = be.unpairByIndex(id, player.level());
            if (removed != null) {
                player.sendSystemMessage(Component.literal("[火控核心] 已解配 " + removed));
            }
            return true;
        }
        if (id == BUTTON_CANCEL_PAIR && player instanceof ServerPlayer sp) {
            if (TurretPairing.hasSession(sp.getUUID())) {
                TurretPairing.cancel(sp, "");
            } else {
                player.sendSystemMessage(Component.literal("[火控核心] 无进行中的配对"));
            }
            return true;
        }
        if (id == BUTTON_TOGGLE_SKY_FILTER) {
            be.toggleSkyFilter();
            player.sendSystemMessage(Component.literal(
                    "[火控核心] 露天过滤: " + (be.skyFilter() ? "开(不隔墙锁)" : "关(可隔墙锁, 扫描开销略增)")));
            return true;
        }
        if (id == BUTTON_RECALIBRATE_ALL) {
            boolean hadTask = be.hasAnyTask(); // 校全部塔 = 各频道无可用成员 → 任务失效 (修订 E)
            int n = be.recalibrateAll(player.level());
            player.sendSystemMessage(Component.literal(
                    n > 0 ? "[火控核心] 已触发重新校准(" + n + " 座炮塔): 校准期间炮塔会小幅摆动"
                            + (hadTask ? ", 各频道任务按可用成员重新评估" : "")
                          : "[火控核心] 无已配对的炮塔"));
            return true;
        }
        if (id == BUTTON_TOGGLE_FOLLOW) {
            be.toggleFollow(player);
            return true;
        }
        if (id == BUTTON_FIRE_VOLLEY) {
            String msg = be.fireVolley(player.level());
            if (msg != null) {
                player.sendSystemMessage(Component.literal(msg));
            }
            return true;
        }
        if (id >= FIRE_TOGGLE_BASE && id < FIRE_TOGGLE_BASE + MAX_TURRETS) {
            TurretUnit u = be.toggleAutoFire(id - FIRE_TOGGLE_BASE);
            if (u != null) {
                int ch = u.channel();
                player.sendSystemMessage(Component.literal("[火控核心] "
                        + be.channelName(ch) + "(" + u.name() + ") 自动开火: "
                        + (be.channelAutoFire(ch) ? "开" : "关")));
            } else {
                player.sendSystemMessage(Component.literal(
                        "[火控核心] 该炮塔未编入任何频道 — 请到面板「频道」页编组"));
            }
            return true;
        }
        if (id >= MOUNT_UNBIND_BASE && id < MOUNT_UNBIND_BASE + MAX_TURRETS * 16) {
            int rel = id - MOUNT_UNBIND_BASE;
            String msg = be.unbindMount(rel / 16, rel % 16);
            if (msg != null) {
                player.sendSystemMessage(Component.literal("[火控核心] " + msg));
            }
            return true;
        }
        if (id >= ARC_SET_BASE && id < ARC_SET_BASE + MAX_TURRETS * 4096) {
            int rel = id - ARC_SET_BASE;
            int row = rel / 4096, rem = rel % 4096;
            int param = rem / 512, value = rem % 512 - 180;
            if (param < 4) {
                be.setArc(row, param, value); // silent: drag handles fire per frame
            }
            return true;
        }
        return false;
    }

    public boolean skyFilterOn() {
        return data.get(DATA_SKY_FILTER) != 0;
    }

    public boolean followOn() {
        return data.get(DATA_FOLLOW) != 0;
    }

    // ---- client-side accessors for the screen ----

    public int turretCount() {
        return data.get(0);
    }

    public int status(int i) {
        return data.get(1 + i * ROW_STRIDE);
    }

    /** Aim error x10 deg, -1 when NaN. */
    public int aimErr10(int i) {
        return data.get(2 + i * ROW_STRIDE);
    }

    public int nameIndex(int i) {
        return data.get(3 + i * ROW_STRIDE);
    }

    /** Fire-arc yaw min, int deg. */
    public int arcYawMin(int i) {
        return data.get(4 + i * ROW_STRIDE);
    }

    /** Fire-arc yaw max, int deg. */
    public int arcYawMax(int i) {
        return data.get(5 + i * ROW_STRIDE);
    }

    /** Fire-arc pitch min, int deg. */
    public int arcPitchMin(int i) {
        return data.get(6 + i * ROW_STRIDE);
    }

    /** Fire-arc pitch max, int deg. */
    public int arcPitchMax(int i) {
        return data.get(7 + i * ROW_STRIDE);
    }

    /** Live yaw vs arc zero x10 deg, REL_UNKNOWN when no aim tick yet. */
    public int relYaw10(int i) {
        return data.get(8 + i * ROW_STRIDE);
    }

    /** Live pitch vs arc zero x10 deg, REL_UNKNOWN when no aim tick yet. */
    public int relPitch10(int i) {
        return data.get(9 + i * ROW_STRIDE);
    }

    /** Auto-fire switch (P3), 0/1. */
    public int autoFire(int i) {
        return data.get(10 + i * ROW_STRIDE);
    }

    /** Gun-mount aggregate code (P3): -1 no mounts, 1 = all READY. */
    public int mountStatus(int i) {
        return data.get(11 + i * ROW_STRIDE);
    }

    /** Mount count, 0..SwivelTurretActuator.MAX_MOUNTS. */
    public int mountCount(int i) {
        return data.get(12 + i * ROW_STRIDE);
    }

    /** Packed mount chip for slot k: (mountNo << 4) | (code + 1),
     *  MOUNT_SLOT_EMPTY when the slot is unused. */
    public int mountPacked(int i, int k) {
        return data.get(13 + i * ROW_STRIDE + k);
    }

    public int turretChannel(int row) { return data.get(DATA_EXTRA + row * EXTRA_STRIDE); }
    public int fireInterval(int row) { return data.get(DATA_EXTRA + row * EXTRA_STRIDE + 1); }
    public boolean cadenceAdjustable(int row) { return data.get(DATA_EXTRA + row * EXTRA_STRIDE + 2) != 0; }
    public int gate(int row) { return data.get(DATA_EXTRA + row * EXTRA_STRIDE + 3); }
    public int identity(int row) { return data.get(DATA_EXTRA + row * EXTRA_STRIDE + 4); }
    public boolean paired(int row) { return data.get(DATA_EXTRA + row * EXTRA_STRIDE + 5) != 0; }
    public int activeChannel() { return data.get(DATA_ACTIVE_CHANNEL); }
    public boolean channelAuto(int ch) { return data.get(DATA_CHANNELS + ch * CHANNEL_STRIDE) != 0; }
    public boolean channelPromote(int ch) { return data.get(DATA_CHANNELS + ch * CHANNEL_STRIDE + 1) != 0; }
    public int channelMode(int ch) { return data.get(DATA_CHANNELS + ch * CHANNEL_STRIDE + 2); }
    public int channelSource(int ch) { return data.get(DATA_CHANNELS + ch * CHANNEL_STRIDE + 3); }
    public boolean promoteSuppressed(int ch) { return data.get(DATA_CHANNELS + ch * CHANNEL_STRIDE + 4) != 0; }
    public int rosterEpoch() { return data.get(DATA_ROSTER_EPOCH); }
    public boolean pairingActive() { return data.get(DATA_PAIRING) != 0; }

    @Override
    public boolean stillValid(Player player) {
        return !be.isRemoved();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY; // no slots at all
    }

    private static final class SyncData implements ContainerData {
        private final int[] values = new int[DATA_COUNT];

        @Override
        public int get(int index) {
            return values[index];
        }

        @Override
        public void set(int index, int value) {
            values[index] = value;
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    }
}
