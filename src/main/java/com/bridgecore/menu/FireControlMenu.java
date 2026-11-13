package com.bridgecore.menu;

import com.bridgecore.BCRegistry;
import com.bridgecore.firecontrol.FireControlUiText;
import com.bridgecore.firecontrol.WeaponGroups;
import com.bridgecore.firecontrol.SectorArc;
import com.bridgecore.firecontrol.MacroReadout;
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
 * <p>stillValid checks the loaded block entity's identity. Menu distance
 * needs a shared ship-aware policy because shipyard positions are local.
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
    // Axis guns (3b): per-gun channel / status / idHash / energy / cap / heat /
    // maxHeat, then the gun count. Appended AFTER every legacy slot.
    private static final int FIXED_STRIDE = 7;
    private static final int DATA_FIXED = DATA_ROSTER_EPOCH + 1;
    private static final int DATA_FIXED_COUNT = DATA_FIXED + FireControlMenuActions.FIXED_GUNS * FIXED_STRIDE;
    private static final int DATA_GROUP_COUNT = DATA_FIXED_COUNT + 1;
    private static final int DATA_EXTRA_GROUPS = DATA_GROUP_COUNT + 1;
    private static final int DATA_MEMBERSHIPS = DATA_EXTRA_GROUPS + (WeaponGroups.MAX - 4) * CHANNEL_STRIDE;
    private static final int DATA_MACRO_DETAIL = DATA_MEMBERSHIPS + (MAX_TURRETS + FireControlMenuActions.FIXED_GUNS) * 2;
    private static final int MACRO_DETAIL_STRIDE = 10;
    private static final int DATA_COUNT = DATA_MACRO_DETAIL + FireControlMenuActions.FIXED_GUNS * MACRO_DETAIL_STRIDE;

    private static int groupBase(int group) {
        return group < 4 ? DATA_CHANNELS + group * CHANNEL_STRIDE
                : DATA_EXTRA_GROUPS + (group - 4) * CHANNEL_STRIDE;
    }

    private void setMembership(int row, int mask) {
        int base = DATA_MEMBERSHIPS + row * 2;
        data.set(base, (short) mask);
        data.set(base + 1, (short) (mask >>> 16));
    }

    public int membership(int row, boolean macro) {
        int base = DATA_MEMBERSHIPS + (macro ? MAX_TURRETS + row : row) * 2;
        return (data.get(base) & 0xffff) | (data.get(base + 1) << 16);
    }

    public boolean belongsTo(int row, boolean macro, int group) {
        return (membership(row, macro) & WeaponGroups.bit(group)) != 0;
    }

    public int channelCount() { return Math.max(1, data.get(DATA_GROUP_COUNT)); }


    private final @Nullable FireControlCoreBlockEntity be;
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
                mounts.add(new FireControlIdentityPayload.Mount(mount.no(), weapon, worldLocation(mount.pos()),
                        handle == null ? null : mount.pos()));
            }
            String weapon = weapons.entrySet().stream().map(entry -> entry.getKey()
                    + (entry.getValue() > 1 ? "×" + entry.getValue() : "")).collect(java.util.stream.Collectors.joining("+"));
            if (weapon.isEmpty()) weapon = mounts.isEmpty() ? "未挂载炮座" : unloaded ? "炮座未加载" : "未装配武器";
            BlockPos offset = a.yawOffset();
            BlockPos yawBlock = a.yawPos();
            int rightSign = -1;
            if (yawBlock != null && level.hasChunkAt(yawBlock)) {
                var state = level.getBlockState(yawBlock);
                var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING;
                if (state.hasProperty(facing) && state.getValue(facing) == net.minecraft.core.Direction.DOWN) rightSign = 1;
            }
            rows.add(new FireControlIdentityPayload.Turret(unit.id(), unit.name(),
                    weapon, worldLocation(a.yawPos()), worldLocation(a.pitchPos()),
                    offset == null ? "未知" : offset.getX() + ", " + offset.getY() + ", " + offset.getZ(), mounts,
                    a.sectorArc(), yawBlock, rightSign));
        }
        var names = new java.util.ArrayList<String>(4);
        for (int ch = 0; ch < be.channelCount(); ch++) names.add(be.channelName(ch));
        var guns = new java.util.ArrayList<FireControlIdentityPayload.AxisGun>();
        for (var gun : be.fixedGuns()) {
            guns.add(new FireControlIdentityPayload.AxisGun(gun.id(), gun.name(), worldLocation(gun.gunPos())));
        }
        var payload = new FireControlIdentityPayload(containerId, operationEpoch, names, rows, guns);
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
        return ch < 0 ? "未编组" : receivedIdentity == null || ch >= receivedIdentity.channels().size()
                ? FireControlUiText.channel(ch) : receivedIdentity.channels().get(ch);
    }
    public @Nullable FireControlIdentityPayload.Mount mountInfo(int row, int slot) {
        var info = turretInfo(row);
        int packed = mountPacked(row, slot);
        if (info == null || slot >= info.mounts().size() || packed == MOUNT_SLOT_EMPTY) return null;
        var mount = info.mounts().get(slot);
        return mount.no() == (packed >> 4) ? mount : null;
    }

    /** Axis-gun identity row: same epoch + id-hash cross-check as turrets. */
    public @Nullable FireControlIdentityPayload.AxisGun fixedGunInfo(int row) {
        if (receivedIdentity == null || receivedIdentity.epoch() != rosterEpoch()
                || row < 0 || row >= fixedGunCount() || row >= receivedIdentity.guns().size()) return null;
        var info = receivedIdentity.guns().get(row);
        return (info.id().hashCode() & 0x7fff) == fixedGunIdentity(row) ? info : null;
    }
    public String fixedGunName(int row) {
        var info = fixedGunInfo(row);
        return info == null ? "轴炮(同步中)" : info.name();
    }

    private void refreshOperationRoster() {
        java.util.List<String> next = new java.util.ArrayList<>();
        next.add(be.activeChannel() + ":" + be.channelCount());
        for (TurretUnit unit : be.turrets()) {
            next.add(unit.id() + ":" + unit.groups());
            for (var mount : unit.actuator().mounts()) next.add(mount.no() + ":" + mount.pos().asLong());
        }
        for (var gun : be.fixedGuns()) next.add(gun.id() + ":" + gun.gunPos().asLong() + ":" + gun.groups());
        if (!next.equals(operationRoster)) {
            operationRoster = java.util.List.copyOf(next);
            operationEpoch = (operationEpoch + 1) & FireControlMenuActions.EPOCH_MASK;
        }
    }

    /** Server-side constructor. */
    public FireControlMenu(int windowId, Inventory playerInv, @Nullable FireControlCoreBlockEntity be) {
        super(BCRegistry.FIRE_CONTROL_MENU.get(), windowId);
        this.be = be;
        this.viewer = playerInv.player;
        addDataSlots(data);
    }

    /** Client-side constructor (IMenuTypeExtension factory): extra data = BlockPos. */
    public FireControlMenu(int windowId, Inventory playerInv, FriendlyByteBuf buf) {
        this(windowId, playerInv, clientCore(playerInv, buf.readBlockPos()));
    }

    private static @Nullable FireControlCoreBlockEntity clientCore(Inventory inv, BlockPos pos) {
        return inv.player.level().getBlockEntity(pos) instanceof FireControlCoreBlockEntity core ? core : null;
    }

    @Override
    public void broadcastChanges() {
        if (be == null) return;
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
            data.set(base + 9, be.commandGroup(u.groups()) >= 0 && be.channelAutoFire(be.commandGroup(u.groups())) ? 1 : 0);
            int extra = DATA_EXTRA + i * EXTRA_STRIDE;
            data.set(extra, be.commandGroup(u.groups()));
            setMembership(i, u.groups());
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
        data.set(DATA_GROUP_COUNT, be.channelCount());
        for (int ch = 0; ch < be.channelCount(); ch++) {
            int base = groupBase(ch);
            data.set(base, be.channelAutoFire(ch) ? 1 : 0);
            data.set(base + 1, be.channelAutoPromote(ch) ? 1 : 0);
            data.set(base + 2, be.taskOf(ch).mode().ordinal());
            data.set(base + 3, be.taskOf(ch).source().ordinal());
            data.set(base + 4, be.taskOf(ch).promoteSuppressed() ? 1 : 0);
        }
        data.set(DATA_ACTIVE_CHANNEL, be.activeChannel());
        data.set(DATA_PAIRING, TurretPairing.hasSession(viewer.getUUID()) ? 1 : 0);
        int gunRow = 0;
        for (var gun : be.fixedGuns()) {
            if (gunRow >= FireControlMenuActions.FIXED_GUNS) break;
            MacroReadout sample = be.macroReadout(gun);
            int base = DATA_FIXED + gunRow * FIXED_STRIDE;
            int detail = DATA_MACRO_DETAIL + gunRow * MACRO_DETAIL_STRIDE;
            setMembership(MAX_TURRETS + gunRow, gun.groups());
            data.set(base, be.commandGroup(gun));
            data.set(base + 1, sample == null ? -1 : sample.hardware());
            data.set(base + 2, gun.id().hashCode() & 0x7fff);
            long energy = sample == null ? 0 : sample.energy();
            long capacity = sample == null ? 0 : sample.capacity();
            data.set(base + 3, (int) energy);
            data.set(base + 4, (int) capacity);
            data.set(base + 5, sample == null ? 0 : sample.heat());
            data.set(base + 6, sample == null ? 0 : sample.maxHeat());
            data.set(detail, (int) (energy >>> 32));
            data.set(detail + 1, (int) (capacity >>> 32));
            data.set(detail + 2, sample == null ? 0 : sample.aim().gate().ordinal());
            data.set(detail + 3, sample == null ? -1 : quantize(sample.aim().degrees(), 10));
            data.set(detail + 4, sample == null ? -1 : quantize(sample.aim().distance(), 1));
            data.set(detail + 5, sample == null ? -1 : quantize(sample.aim().range(), 1));
            data.set(detail + 6, sample == null ? 0 : sample.shotCost());
            data.set(detail + 7, sample == null ? 0 : sample.warmup());
            data.set(detail + 8, sample == null ? 0 : sample.cooldown());
            data.set(detail + 9, sample == null ? 0 : sample.phase().ordinal());
            gunRow++;
        }
        data.set(DATA_FIXED_COUNT, gunRow);
        super.broadcastChanges();
        broadcastIdentity();
    }

    private static int quantize(double value, int scale) {
        return value < 0 || !Double.isFinite(value) ? -1
                : (int) Math.min(Integer.MAX_VALUE, Math.round(value * scale));
    }

    public void renameGroup(Player player, int group, String name) {
        if (strikeCore(player) == null) return;
        if (be.renameChannel(group, name)) broadcastIdentityNow();
    }

    public @Nullable FireControlCoreBlockEntity strikeCore(Player player) {
        return player == viewer && player.containerMenu == this && player.isAlive()
                && !player.isSpectator() && stillValid(player) ? be : null;
    }

    public void replaceSectorArc(Player player, java.util.UUID turret, SectorArc expected, SectorArc replacement) {
        if (strikeCore(player) == null) return;
        for (var unit : be.turrets()) {
            if (!unit.id().equals(turret) || !unit.actuator().isPaired()) continue;
            var actuator = unit.actuator();
            if (actuator.sectorArc().equals(expected)) actuator.setSectorArc(replacement);
            else player.displayClientMessage(Component.literal("射界已更新，请按最新分区重新编辑"), true);
            broadcastIdentityNow();
            return;
        }
    }

    public void renameTurret(Player player, java.util.UUID turret, String name) {
        if (strikeCore(player) == null) return;
        if (be.renameTurret(turret, name)) broadcastIdentityNow();
        else player.displayClientMessage(Component.literal("名称无效或本核心已有同名炮塔"), true);
    }

    private void broadcastIdentityNow() {
        lastSentIdentity = null;
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
        if (strikeCore(player) == null) return false;
        if (id >= FireControlMenuActions.GUARDED_BASE && id < FireControlMenuActions.GUARDED_END) {
            refreshOperationRoster();
            int action = FireControlMenuActions.decodeGuarded(id, operationEpoch);
            if (action < 0) {
                player.sendSystemMessage(Component.literal("[火控核心] 炮塔或炮座列表已变化，请确认对象后重试"));
                return true;
            }
            id = action;
        }
        if (id == FireControlMenuActions.GROUP_ADD) {
            if (be.addChannel()) broadcastChanges();
            return true;
        }
        if (id >= FireControlMenuActions.SECTOR_RESUME_BASE
                && id < FireControlMenuActions.SECTOR_RESUME_BASE + MAX_TURRETS) {
            TurretUnit unit = be.turretByIndex(id - FireControlMenuActions.SECTOR_RESUME_BASE);
            if (unit != null && unit.actuator().isPaired()) {
                var actuator = unit.actuator();
                actuator.resumeSectorArc(SectorArc.start((int) Math.round(actuator.pitchMinDeg()),
                        (int) Math.round(actuator.pitchMaxDeg())));
                broadcastIdentityNow();
            }
            return true;
        }
        if (id >= FireControlMenuActions.GROUP_SELECT && id < FireControlMenuActions.GROUP_AUTO) {
            be.setActiveChannel(id - FireControlMenuActions.GROUP_SELECT);
            return true;
        }
        if (id >= FireControlMenuActions.GROUP_AUTO && id < FireControlMenuActions.GROUP_PROMOTE) {
            be.toggleChannelAutoFire(id - FireControlMenuActions.GROUP_AUTO);
            return true;
        }
        if (id >= FireControlMenuActions.GROUP_PROMOTE && id < FireControlMenuActions.GROUP_MEMBERSHIP) {
            be.toggleChannelAutoPromote(id - FireControlMenuActions.GROUP_PROMOTE);
            return true;
        }
        if (id >= FireControlMenuActions.GROUP_MEMBERSHIP && id < FireControlMenuActions.GROUP_END) {
            int action = id - FireControlMenuActions.GROUP_MEMBERSHIP;
            int row = action / (WeaponGroups.MAX * 2);
            int group = (action / 2) % WeaponGroups.MAX;
            boolean join = (action & 1) != 0;
            // N2: 互斥拒绝给回执(轴炮与炮塔不能同频道), 不再静默丢弃
            boolean ok = row < MAX_TURRETS ? be.editTurretGroup(row, group, join)
                    : be.editFixedGunGroup(row - MAX_TURRETS, group, join);
            if (!ok) player.sendSystemMessage(Component.literal(
                    "[火控核心] 编组未生效:轴炮与炮塔不能编入同一频道(打击方式不同)"));
            return true;
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
            if (!be.setTurretChannel(rel / 5, rel % 5 - 1)) {
                player.sendSystemMessage(Component.literal(
                        "[火控核心] 编组未生效:轴炮与炮塔不能编入同一频道(打击方式不同)"));
            }
            return true;
        }
        if (id >= FireControlMenuActions.CADENCE_DOWN_BASE && id < FireControlMenuActions.CADENCE_END) {
            boolean slower = id < FireControlMenuActions.CADENCE_UP_BASE;
            int row = id - (slower ? FireControlMenuActions.CADENCE_DOWN_BASE : FireControlMenuActions.CADENCE_UP_BASE);
            if (be.turretCadenceAdjustable(row)) be.cycleFireInterval(row, slower ? 1 : -1);
            return true;
        }
        if (id >= FireControlMenuActions.FIXED_CHANNEL_BASE && id < FireControlMenuActions.END) {
            int rel = id - FireControlMenuActions.FIXED_CHANNEL_BASE;
            if (!be.setFixedGunChannel(rel / 5, rel % 5 - 1)) {
                player.sendSystemMessage(Component.literal(
                        "[火控核心] 编组未生效:轴炮与炮塔不能编入同一频道(打击方式不同)"));
            }
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
                int ch = be.commandGroup(u.groups());
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
    public boolean channelAuto(int ch) { return data.get(groupBase(ch)) != 0; }
    public boolean channelPromote(int ch) { return data.get(groupBase(ch) + 1) != 0; }
    public int channelMode(int ch) { return data.get(groupBase(ch) + 2); }
    public int channelSource(int ch) { return data.get(groupBase(ch) + 3); }
    public boolean promoteSuppressed(int ch) { return data.get(groupBase(ch) + 4) != 0; }
    public int rosterEpoch() { return data.get(DATA_ROSTER_EPOCH); }
    public boolean pairingActive() { return data.get(DATA_PAIRING) != 0; }

    public int fixedGunCount() { return data.get(DATA_FIXED_COUNT); }
    public int fixedGunChannel(int row) { return data.get(DATA_FIXED + row * FIXED_STRIDE); }
    public int fixedGunStatus(int row) { return data.get(DATA_FIXED + row * FIXED_STRIDE + 1); }
    public int fixedGunIdentity(int row) { return data.get(DATA_FIXED + row * FIXED_STRIDE + 2); }
    private int macroDetail(int row, int offset) {
        return data.get(DATA_MACRO_DETAIL + row * MACRO_DETAIL_STRIDE + offset);
    }
    public long fixedGunEnergy(int row) {
        return ((long) macroDetail(row, 0) << 32)
                | Integer.toUnsignedLong(data.get(DATA_FIXED + row * FIXED_STRIDE + 3));
    }
    public long fixedGunEnergyCap(int row) {
        return ((long) macroDetail(row, 1) << 32)
                | Integer.toUnsignedLong(data.get(DATA_FIXED + row * FIXED_STRIDE + 4));
    }
    public MacroReadout.Aim fixedGunAim(int row) {
        var gates = MacroReadout.Gate.values();
        int state = macroDetail(row, 2);
        int angle = macroDetail(row, 3);
        return new MacroReadout.Aim(state >= 0 && state < gates.length ? gates[state] : MacroReadout.Gate.UNKNOWN,
                angle < 0 ? -1 : angle / 10.0, macroDetail(row, 4), macroDetail(row, 5));
    }
    public MacroReadout.Phase fixedGunPhase(int row) {
        var phases = MacroReadout.Phase.values();
        int state = macroDetail(row, 9);
        return state >= 0 && state < phases.length ? phases[state] : MacroReadout.Phase.UNKNOWN;
    }
    public String fixedGunReason(int row) {
        return fixedGunAim(row).label(fixedGunStatus(row), fixedGunPhase(row));
    }
    public int fixedGunShotCost(int row) { return macroDetail(row, 6); }
    public int fixedGunWarmup(int row) { return macroDetail(row, 7); }
    public int fixedGunCooldown(int row) { return macroDetail(row, 8); }
    public int fixedGunHeat(int row) { return data.get(DATA_FIXED + row * FIXED_STRIDE + 5); }
    public int fixedGunMaxHeat(int row) { return data.get(DATA_FIXED + row * FIXED_STRIDE + 6); }

    @Override
    public boolean stillValid(Player player) {
        var level = be == null ? null : be.getLevel();
        return level != null && level == player.level() && !be.isRemoved()
                && level.hasChunkAt(be.getBlockPos()) && level.getBlockEntity(be.getBlockPos()) == be;
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
