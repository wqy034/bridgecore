package com.bridgecore.network;

import com.bridgecore.BridgeCoreBlockEntity;
import com.bridgecore.BridgeCoreMod;
import com.bridgecore.BCConfig;
import com.bridgecore.control.NavigationController;
import com.bridgecore.control.CruiseHeading;
import com.bridgecore.nav.NavTerminalData;
import com.bridgecore.firecontrol.StrikePreview;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import com.bridgecore.nav.NavWakeProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Terminal ↔ core command link (auto-navigation-research.md v2 §4).
 * ONE payload type for all terminal commands: version + command enum + params.
 * New commands append enum values; unknown versions/commands are ignored
 * safely. Replies are sent only to the requesting player.
 *
 * Permission: the core accepts ONLY its bound terminal (single-permission
 * set); the terminal must be in the sender's hands and its NBT uuid must
 * match both the payload and the core's boundTerminalUuid.
 *
 * DISPATCH uses supervised N1. RECALL uses the short unattended route.
 */
public record TerminalLinkPayload(int version, int command, UUID terminalUuid,
                                  String name, String extra, long pos, String dim,
                                  boolean strike) implements CustomPacketPayload {
    public static final int PROTOCOL_VERSION = 1;
    private static final Map<ServerPlayer, Integer> LAST_STATUS = new WeakHashMap<>();

    public enum Cmd {
        MARK_ADD, MARK_REMOVE, MARK_RENAME, DISPATCH, RECALL, STRIKE_MARK,
        /** Reserved: low-speed remote movement (user: 待考虑). Not implemented. */
        SIMPLE_MOVE, STATUS, CANCEL, WAKE, KEY_MAP, SAVE_SETTINGS, SHIP_STATUS, SAVE_QUICK_STRIKE
    }

    public static final Type<TerminalLinkPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "terminal_link"));

    public static final net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, TerminalLinkPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalLinkPayload::write, TerminalLinkPayload::new);

    public TerminalLinkPayload(FriendlyByteBuf buf) {
        this(buf.readByte(), buf.readByte(), buf.readUUID(),
                buf.readUtf(24), buf.readUtf(24), buf.readLong(),
                buf.readUtf(64), buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeByte(version);
        buf.writeByte(command);
        buf.writeUUID(terminalUuid);
        buf.writeUtf(name, 24);
        buf.writeUtf(extra, 24);
        buf.writeLong(pos);
        buf.writeUtf(dim, 64);
        buf.writeBoolean(strike);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TerminalLinkPayload payload, IPayloadContext context) {
        if (payload.version != PROTOCOL_VERSION) return; // 未知版本安全忽略
        if (payload.command < 0 || payload.command >= Cmd.values().length) return;
        if (!(context.player() instanceof ServerPlayer player)) return;
        // R8-1：旁观者/死亡玩家不得远程派船（与同族 maneuver/cruise/strike 包同口径）。
        if (!player.isAlive() || player.isSpectator()) return;
        // 终端必须在发送者手中且 uuid 匹配。
        ItemStack stack = findHeldTerminal(player, payload.terminalUuid);
        if (stack == null) return;
        Cmd cmd = Cmd.values()[payload.command];
        switch (cmd) {
            case MARK_ADD, STRIKE_MARK -> {
                if (payload.name.isBlank()) return;
                ResourceLocation dim = player.level().dimension().location();
                BlockPos pos = player.blockPosition();
                if (cmd == Cmd.STRIKE_MARK) {
                    pos = BlockPos.of(payload.pos);
                    // The packet stores a block, while the client ray hits a point on its face.
                    double limit = NavTerminalData.STRIKE_RANGE + Math.sqrt(3.0) / 2.0;
                    if (!dim.toString().equals(payload.dim) || !player.level().isInWorldBounds(pos)
                            || player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > limit * limit) {
                        TerminalReplyPayload.send(player, payload.terminalUuid, "打击标定点超出当前维度或标定距离");
                        return;
                    }
                }
                String name = payload.name;
                // 打击标定允许同名自动加后缀（开镜连打不改名）；普通标定重名拒绝。
                boolean ok = false;
                for (int i = 0; i < 9 && !ok; i++) {
                    String candidate = i == 0 ? name : name + "_" + i;
                    if (candidate.length() > 24) break;
                    ok = NavTerminalData.addMark(stack, new NavTerminalData.Mark(
                            candidate, pos, dim, cmd == Cmd.STRIKE_MARK));
                    if (ok) name = candidate;
                }
                TerminalReplyPayload.send(player, payload.terminalUuid, ok ? "已标定 " + name : "同名标定点已存在");
            }
            case MARK_REMOVE -> {
                boolean ok = NavTerminalData.removeMark(stack, payload.name);
                TerminalReplyPayload.send(player, payload.terminalUuid, ok ? "已删除 " + payload.name : "无此标定点");
            }
            case MARK_RENAME -> {
                if (payload.extra.isBlank()) return;
                boolean ok = NavTerminalData.renameMark(stack, payload.name, payload.extra);
                TerminalReplyPayload.send(player, payload.terminalUuid, ok ? "已改名" : "改名失败");
            }
            case WAKE -> TerminalReplyPayload.send(player, payload.terminalUuid,
                    NavWakeProbe.request(player, stack, payload.terminalUuid));
            case RECALL -> {
                String reply;
                try {
                    double speed = Double.parseDouble(payload.name);
                    double turnRate = Math.toRadians(Double.parseDouble(payload.extra));
                    reply = NavWakeProbe.recall(player, stack, payload.terminalUuid, speed, turnRate);
                } catch (NumberFormatException ignored) {
                    reply = "请在巡航设置中填写有效的速度和转向率";
                }
                TerminalReplyPayload.send(player, payload.terminalUuid, reply);
            }
            case DISPATCH, STATUS, CANCEL -> {
                String wakeReply = switch (cmd) {
                    case STATUS -> NavWakeProbe.status(player, payload.terminalUuid);
                    case CANCEL -> NavWakeProbe.cancel(player, payload.terminalUuid);
                    default -> null;
                };
                if (wakeReply != null) {
                    TerminalReplyPayload.send(player, payload.terminalUuid, wakeReply);
                    return;
                }
                BridgeCoreBlockEntity core = boundCoreOf(player, stack, payload.terminalUuid);
                if (core == null) return;
                String reply = switch (cmd) {
                    case DISPATCH -> dispatch(player, stack, core, payload.name,
                            NavigationController.DEFAULT_CRUISE_SPEED, CruiseHeading.DEFAULT_RATE);
                    case STATUS -> describe(core.navigationStatus());
                    case CANCEL -> {
                        var status = core.navigationStatus();
                        yield status != null && core.cancelNavigation(status.id())
                                ? describe(core.navigationStatus()) : "当前没有可取消的巡航";
                    }
                    default -> throw new IllegalStateException("Unexpected navigation command " + cmd);
                };
                TerminalReplyPayload.send(player, payload.terminalUuid, reply);
            }
            case SAVE_SETTINGS -> {
                boolean saved;
                try {
                    saved = NavTerminalData.saveSettings(stack, new NavTerminalData.Settings(
                            Double.parseDouble(payload.name), Double.parseDouble(payload.extra), Math.toIntExact(payload.pos)));
                } catch (NumberFormatException | ArithmeticException invalid) {
                    saved = false;
                }
                TerminalReplyPayload.send(player, payload.terminalUuid, saved ? "终端设置已保存" : "参数超出允许范围");
            }
            case SHIP_STATUS -> {
                int tick = player.serverLevel().getServer().getTickCount();
                Integer last = LAST_STATUS.get(player);
                if (last != null && tick - last < 20) return;
                LAST_STATUS.put(player, tick);
                String status = shipStatus(player, stack, payload.terminalUuid);
                PacketDistributor.sendToPlayer(player, new TerminalReplyPayload(payload.terminalUuid, status, true));
            }
            case SAVE_QUICK_STRIKE -> {
                if (payload.pos == -1) {
                    NavTerminalData.saveQuickStrike(stack, null);
                    TerminalReplyPayload.send(player, payload.terminalUuid, "已清除快捷默认频道");
                    return;
                }
                var flight = boundCoreOf(player, stack, payload.terminalUuid);
                if (flight == null) return;
                var roster = Sable.HELPER.getContaining(flight) instanceof ServerSubLevel ship && !ship.isRemoved()
                        ? StrikePreview.roster(player.serverLevel(), ship, flight.getBlockPos()) : null;
                if (roster == null || payload.pos < 0 || payload.pos >= roster.channels().size()
                        || !roster.channels().get((int) payload.pos).equals(payload.name)) {
                    TerminalReplyPayload.send(player, payload.terminalUuid, "武器频道已变化，请刷新后再保存");
                    return;
                }
                var mode = roster.channelMode((int) payload.pos, payload.extra.equals("turn"));
                NavTerminalData.saveQuickStrike(stack, new NavTerminalData.QuickStrike((int) payload.pos, payload.name, mode.wireId));
                TerminalReplyPayload.send(player, payload.terminalUuid, "快捷打击已保存 · " + payload.name);
            }
            case KEY_MAP -> {
                BridgeCoreBlockEntity core = boundCoreOf(player, stack, payload.terminalUuid);
                if (core != null) {
                    PacketDistributor.sendToPlayer(player,
                            TerminalKeyMapPayload.fromCore(payload.terminalUuid, payload.pos, core.keyMap()));
                }
            }
            case SIMPLE_MOVE -> {
                // 预留：低速遥控暂不实装（用户：待考虑）。
            }
        }
    }

    private static String shipStatus(ServerPlayer player, ItemStack stack, UUID terminal) {
        var operation = NavWakeProbe.status(player, terminal);
        if (operation != null) return operation;
        var position = NavTerminalData.boundCorePos(stack);
        var dimension = NavTerminalData.boundCoreDim(stack);
        if (position == null || dimension == null) return "未绑定飞控";
        if (!player.level().dimension().location().equals(dimension)) return "飞船在其他维度";
        var core = boundCoreOf(player, stack, terminal, false);
        if (core == null) {
            return player.level().hasChunkAt(position) && player.level().getBlockEntity(position) instanceof BridgeCoreBlockEntity
                    ? "飞控绑定已失效" : "飞控未加载";
        }
        if (core.mode() == BridgeCoreBlockEntity.ControlMode.OFF) return "飞控关闭";
        if (core.telemetrySnapshot() == null) return "等待飞控采样";
        if (core.pointingActive()) return "舰体指向中";
        var navigation = core.navigationStatus();
        if (navigation != null) return describe(navigation);
        return core.mode() == BridgeCoreBlockEntity.ControlMode.STARSHIP ? "星舰模式 · 待命" : "阻尼模式 · 待命";
    }

    static String dispatch(ServerPlayer player, ItemStack stack, BridgeCoreBlockEntity core, String markName,
                           double speed, double turnRate) {
        var matches = NavTerminalData.marks(stack).stream().filter(candidate -> candidate.name().equals(markName))
                .limit(2).toList();
        if (matches.size() != 1 || matches.getFirst().strike()) return "请选择终端内名称唯一的普通标定点";
        var mark = matches.getFirst();
        var level = player.serverLevel();
        if (!level.dimension().location().equals(mark.dim())) return "巡航目标须与飞船处于同一维度";
        var sample = core.telemetrySnapshot();
        if (sample == null) return "飞控尚无有效物理采样";
        // N1 dispatch flies over the mark at the ship's present altitude.
        Vec3 target = new Vec3(mark.pos().getX() + 0.5, sample.position().y, mark.pos().getZ() + 0.5);
        return cruiseReply(core.requestCruise(UUID.randomUUID(), level.dimension(), target, speed, turnRate),
                "已接受平飞任务 · " + mark.name());
    }

    public static String cruiseReply(NavigationController.Admission admission, String accepted) {
        return switch (admission) {
            case ACCEPTED -> accepted;
            case UNAVAILABLE -> "请启用耦合星舰模式并确认飞控有有效采样";
            case BUSY -> "飞船已有在途任务或正在接受遥控";
            case INVALID_TARGET -> "目标超出本维度边界";
            case NEEDS_CREW -> "无人随船航段上限为 " + BCConfig.UNSUPERVISED_CRUISE_RANGE.get() + " 格";
            case ROUTE_UNLOADED -> "前方航段有未加载区块，派遣未接受";
        };
    }

    public static String describe(NavigationController.Status status) {
        if (status == null) return "暂无巡航任务";
        return switch (status.phase()) {
            case ACTIVE -> "巡航中";
            case ARRIVED -> "已到位，已交还悬停";
            case CANCELLED -> "已取消";
            case PILOT_OVERRIDE -> "驾驶员已接管";
            case MODE_CHANGED -> "飞控模式改变，巡航已结束";
            case PHYSICS_LOST -> "物理采样中断，巡航已结束";
            case POWER_LIMITED -> "供能受限，巡航已结束";
            case TIMED_OUT -> "巡航超时";
            case ROUTE_UNLOADED -> "前方航段有未加载区块，巡航已结束";
            case SUPERVISION_LOST -> "随船乘员离开，长航段巡航已结束";
            case NO_PROGRESS -> "航段长期没有进展，巡航已结束";
        };
    }

    static ItemStack findHeldTerminal(ServerPlayer player, UUID uuid) {
        return NavTerminalData.heldTerminal(player, uuid);
    }

    /** 绑定校验 + 核心定位（同维度 v1）。失败已提示，返回 null。 */
    static BridgeCoreBlockEntity boundCoreOf(ServerPlayer player, ItemStack stack, UUID uuid) {
        return boundCoreOf(player, stack, uuid, true);
    }

    static BridgeCoreBlockEntity boundCoreOf(ServerPlayer player, ItemStack stack, UUID uuid, boolean report) {
        BlockPos pos = NavTerminalData.boundCorePos(stack);
        ResourceLocation dim = NavTerminalData.boundCoreDim(stack);
        if (pos == null || dim == null) {
            if (report) TerminalReplyPayload.send(player, uuid, "未绑定飞控核心");
            return null;
        }
        if (!player.level().dimension().location().equals(dim)) {
            if (report) TerminalReplyPayload.send(player, uuid, "核心在其他维度");
            return null;
        }
        if (!player.level().hasChunkAt(pos) || !(player.level().getBlockEntity(pos) instanceof BridgeCoreBlockEntity core)) {
            if (report) TerminalReplyPayload.send(player, uuid, "飞控核心当前未加载");
            return null;
        }
        if (!core.terminalAllowed(NavTerminalData.peekUuid(stack))) {
            if (report) TerminalReplyPayload.send(player, uuid, "该飞控核心已绑定其他终端，或已解绑");
            return null;
        }
        return core;
    }
}
