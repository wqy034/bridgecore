package com.bridgecore.network;

import com.bridgecore.BridgeCoreBlockEntity;
import com.bridgecore.BridgeCoreMod;
import com.bridgecore.firecontrol.StrikePreview;
import com.bridgecore.nav.NavTerminalData;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Roster query, preview, or a one-time execution confirmed by the terminal. */
public record TerminalStrikeRequest(UUID terminal, UUID request, BlockPos core, int revision,
                                    String mark, int channel, int mode, Action action, boolean quick) implements CustomPacketPayload {
    public TerminalStrikeRequest(UUID terminal, UUID request, BlockPos core, int revision,
                                 String mark, int channel, int mode, Action action) {
        this(terminal, request, core, revision, mark, channel, mode, action, false);
    }

    public static TerminalStrikeRequest quick(UUID terminal, UUID request, Action action) {
        if (action == Action.CANCEL) throw new IllegalArgumentException("Quick request expects preview or execution");
        return new TerminalStrikeRequest(terminal, request, BlockPos.ZERO, 0, "", -1, 0, action, true);
    }

    public static TerminalStrikeRequest quickPreview(TerminalStrikeSnapshot roster, UUID request, int channel) {
        return new TerminalStrikeRequest(roster.terminal(), request, roster.core(), roster.revision(),
                "", channel, 0, Action.PREVIEW, true);
    }
    public enum Action { PREVIEW, EXECUTE, CANCEL }

    private static final Map<ServerPlayer, Integer> LAST_REQUEST = new WeakHashMap<>();
    private static final Map<ServerPlayer, Integer> LAST_ROSTER = new WeakHashMap<>();
    private static final Map<MinecraftServer, Integer> LAST_PREVIEW = new WeakHashMap<>();
    private static final Map<ServerPlayer, Confirmation> CONFIRMATIONS = new WeakHashMap<>();
    private static final int CONFIRMATION_TICKS = 600;
    private static final int QUICK_CONFIRMATION_TICKS = 200;

    private record Confirmation(UUID request, UUID terminal, BlockPos core, int revision,
                                NavTerminalData.Mark mark, int channel, StrikePreview.Mode requestedMode, StrikePreview.Mode mode, Set<UUID> weapons, int tick,
                                StrikePreview.PosePlan posePlan, boolean quick, @Nullable NavTerminalData.QuickStrike preset) {}

    public TerminalStrikeRequest(UUID terminal, UUID request, BlockPos core, int revision,
                                 String mark, int channel, int mode) {
        this(terminal, request, core, revision, mark, channel, mode, Action.PREVIEW);
    }
    public static final Type<TerminalStrikeRequest> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "terminal_strike_request"));
    public static final StreamCodec<FriendlyByteBuf, TerminalStrikeRequest> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalStrikeRequest::write, TerminalStrikeRequest::read);

    private static TerminalStrikeRequest read(FriendlyByteBuf buf) {
        var preview = new TerminalStrikeRequest(buf.readUUID(), buf.readUUID(), buf.readBlockPos(), buf.readInt(),
                buf.readUtf(24), buf.readByte(), buf.readByte());
        int code = buf.readUnsignedByte();
        Action action = switch (code) {
            case 0, 3 -> Action.PREVIEW;
            case 1, 4 -> Action.EXECUTE;
            case 2 -> Action.CANCEL;
            default -> throw new io.netty.handler.codec.DecoderException("Invalid terminal strike action");
        };
        return new TerminalStrikeRequest(preview.terminal, preview.request, preview.core, preview.revision,
                preview.mark, preview.channel, preview.mode, action, code >= 3);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(terminal);
        buf.writeUUID(request);
        buf.writeBlockPos(core);
        buf.writeInt(revision);
        buf.writeUtf(mark, 24);
        buf.writeByte(channel);
        buf.writeByte(mode);
        if (quick) buf.writeByte(action == Action.PREVIEW ? 3 : 4);
        else buf.writeByte(switch (action) {
            case PREVIEW -> 0;
            case EXECUTE -> 1;
            case CANCEL -> 2;
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(TerminalStrikeRequest packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !player.isAlive() || player.isSpectator()) return;
        var stack = TerminalLinkPayload.findHeldTerminal(player, packet.terminal);
        if (stack == null) return;
        var level = player.serverLevel();
        var server = level.getServer();
        int tick = server.getTickCount();
        boolean rosterOnly = !packet.quick && packet.action == Action.PREVIEW && packet.channel == -1;
        if (!allow(rosterOnly ? LAST_ROSTER : LAST_REQUEST, player, tick, packet.action == Action.PREVIEW ? 5 : 1)) {
            packet.error(player, "请稍后再试");
            return;
        }
        var flight = TerminalLinkPayload.boundCoreOf(player, stack, packet.terminal, false);
        if (flight == null || !(Sable.HELPER.getContaining(flight) instanceof ServerSubLevel ship) || ship.isRemoved()) {
            packet.error(player, "请绑定当前维度内已加载的船体飞控核心");
            return;
        }
        var roster = StrikePreview.roster(level, ship, flight.getBlockPos());
        if (roster == null) {
            packet.error(player, "本船没有已加载的火控核心");
            return;
        }
        if (packet.quick) {
            packet.handleQuick(player, stack, flight, ship, roster, tick);
            return;
        }
        if (packet.action == Action.CANCEL) {
            var confirmation = CONFIRMATIONS.get(player);
            if (confirmation != null && confirmation.terminal().equals(packet.terminal)) CONFIRMATIONS.remove(player);
            String result = roster.core().cancelMarkedStrike(player, packet.terminal);
            packet.reply(player, roster, List.of(), result == null ? "当前没有可取消的标定打击" : result);
            return;
        }
        if (rosterOnly) {
            packet.reply(player, roster, List.of(), "已读取火控频道");
            return;
        }
        if (packet.action == Action.PREVIEW) CONFIRMATIONS.remove(player);
        if (!roster.core().getBlockPos().equals(packet.core) || roster.revision() != packet.revision) {
            packet.reply(player, roster, List.of(), "火控配置已刷新，请重新预瞄");
            return;
        }
        var requestedMode = StrikePreview.Mode.fromWire(packet.mode);
        if (requestedMode == null || packet.channel < 0 || packet.channel >= roster.channels().size()) {
            packet.reply(player, roster, List.of(), "请选择有效的武器频道");
            return;
        }
        var mode = roster.channelMode(packet.channel, requestedMode == StrikePreview.Mode.TURRET_POSE);
        if ((requestedMode == StrikePreview.Mode.AXIS_BEAM) != (mode == StrikePreview.Mode.AXIS_BEAM)) {
            packet.reply(player, roster, List.of(), "频道类型已变化，请重新预瞄");
            return;
        }
        var marks = NavTerminalData.marks(stack).stream().filter(mark -> mark.name().equals(packet.mark)).limit(2).toList();
        if (marks.size() != 1 || !marks.getFirst().strike() || !marks.getFirst().dim().equals(level.dimension().location())
                || !level.isInWorldBounds(marks.getFirst().pos())) {
            packet.reply(player, roster, List.of(), "请选择本维度内名称唯一的打击点");
            return;
        }
        var mark = marks.getFirst();
        if (packet.action == Action.EXECUTE) {
            var confirmation = CONFIRMATIONS.get(player);
            if (confirmation == null || mode != confirmation.requestedMode
                    || !packet.request.equals(confirmation.request) || !packet.terminal.equals(confirmation.terminal)
                    || !packet.core.equals(confirmation.core) || packet.revision != confirmation.revision
                    || !mark.equals(confirmation.mark) || packet.channel != confirmation.channel
                    || confirmation.quick || tick - confirmation.tick >= CONFIRMATION_TICKS) {
                packet.reply(player, roster, List.of(), "确认已失效，请重新预瞄");
                return;
            }
            CONFIRMATIONS.remove(player);
            String failure = roster.core().startMarkedStrike(player, packet.terminal, flight, ship, mark,
                    roster, packet.channel, confirmation.mode, confirmation.weapons, confirmation.posePlan, false);
            packet.reply(player, roster, List.of(), failure == null ? confirmation.mode.queuedMessage() : failure);
            return;
        }
        packet.preview(player, ship, roster, mark, packet.channel, mode, tick, null);
    }

    private void handleQuick(ServerPlayer player, ItemStack stack,
                             BridgeCoreBlockEntity flight, ServerSubLevel ship,
                             StrikePreview.Roster roster, int tick) {
        var profile = NavTerminalData.quickStrike(stack);
        if (action == Action.EXECUTE) {
            var confirmation = CONFIRMATIONS.get(player);
            if (confirmation == null || !confirmation.quick || !Objects.equals(profile, confirmation.preset)
                    || !terminal.equals(confirmation.terminal)
                    || !request.equals(confirmation.request) || !roster.core().getBlockPos().equals(confirmation.core)
                    || roster.revision() != confirmation.revision
                    || !player.level().dimension().location().equals(confirmation.mark.dim())
                    || tick - confirmation.tick >= QUICK_CONFIRMATION_TICKS) {
                reply(player, roster, List.of(), "快捷预瞄已失效，请重新预瞄");
                return;
            }
            CONFIRMATIONS.remove(player);
            var failure = roster.core().startMarkedStrike(player, terminal, flight, ship, confirmation.mark, roster,
                    confirmation.channel, confirmation.mode, confirmation.weapons, confirmation.posePlan, true);
            reply(player, roster, List.of(), failure == null ? confirmation.mode.queuedMessage() : failure);
            return;
        }
        CONFIRMATIONS.remove(player);
        int selected = channel;
        if (selected == -1) {
            if (profile == null || profile.channel() < 0 || profile.channel() >= roster.channels().size()
                    || !roster.channels().get(profile.channel()).equals(profile.name())) {
                reply(player, roster, List.of(), "请滚轮选择频道，或在「打击 → 快捷设置」保存默认频道");
                return;
            }
            selected = profile.channel();
        } else if (selected < 0 || selected >= roster.channels().size()
                || !roster.core().getBlockPos().equals(core) || roster.revision() != revision) {
            reply(player, roster, List.of(), "火控频道已变化，请重新选频道");
            return;
        }
        var mode = roster.channelMode(selected, profile == null || profile.mode() != StrikePreview.Mode.TURRET.wireId);
        if (channel == -1 && (profile.mode() == StrikePreview.Mode.AXIS_BEAM.wireId) != (mode == StrikePreview.Mode.AXIS_BEAM)) {
            reply(player, roster, List.of(), "默认频道类型已变化，请重新保存或用滚轮选台");
            return;
        }
        if (!allowPreview(player.serverLevel().getServer(), tick)) {
            reply(player, roster, List.of(), "服务器正在处理另一次预瞄，请稍后再试");
            return;
        }
        var eye = player.getEyePosition();
        var end = eye.add(player.getLookAngle().scale(NavTerminalData.STRIKE_RANGE));
        var level = player.serverLevel();
        var ray = new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        BlockHitResult hit = BlockGetter.traverseBlocks(eye, end, ray, (context, pos) -> {
            if (!level.hasChunkAt(pos)) return BlockHitResult.miss(end, Direction.UP, pos);
            var state = level.getBlockState(pos);
            return level.clipWithInteractionOverride(eye, end, pos, context.getBlockShape(state, level, pos), state);
        }, context -> null);
        if (hit == null || hit.getType() != HitResult.Type.BLOCK || !player.level().isInWorldBounds(hit.getBlockPos())) {
            reply(player, roster, List.of(), "准星未指向 256 格内的方块");
            return;
        }
        var mark = new NavTerminalData.Mark("快捷目标 " + hit.getBlockPos().toShortString(), hit.getBlockPos(),
                player.level().dimension().location(), true);
        preview(player, ship, roster, mark, selected, mode, tick, profile);
    }

    private void preview(ServerPlayer player, ServerSubLevel ship, StrikePreview.Roster roster, NavTerminalData.Mark mark,
                         int channel, StrikePreview.Mode mode, int tick, @Nullable NavTerminalData.QuickStrike profile) {
        var level = player.serverLevel();
        if (!quick && !allowPreview(level.getServer(), tick)) {
            reply(player, roster, List.of(), "服务器正在处理另一次预瞄，请稍后再试");
            return;
        }
        StrikePreview.Mode requested = mode;
        StrikePreview.PosePlan posePlan = null;
        List<StrikePreview.Row> rows;
        Set<UUID> weapons;
        String summary;
        if (mode == StrikePreview.Mode.TURRET_POSE) {
            var direct = StrikePreview.assess(level, ship, roster, channel, StrikePreview.Mode.TURRET, mark.pos());
            if (!direct.executableWeapons().isEmpty()) {
                mode = StrikePreview.Mode.TURRET;
                rows = direct.rows();
                weapons = direct.executableWeapons();
                summary = "当前舰姿可达 · " + direct.summary(mode, roster.limited());
            } else {
                var pose = StrikePreview.previewPose(level, ship, roster, channel, mark.pos());
                posePlan = pose.plan();
                rows = pose.rows();
                weapons = posePlan.turrets();
                summary = pose.summary();
            }
        } else {
            var assessment = StrikePreview.assess(level, ship, roster, channel, mode, mark.pos());
            rows = assessment.rows();
            weapons = assessment.executableWeapons();
            summary = assessment.summary(mode, roster.limited());
        }
        if (!weapons.isEmpty()) {
            CONFIRMATIONS.put(player, new Confirmation(request, terminal, roster.core().getBlockPos(), roster.revision(),
                    mark, channel, requested, mode, Set.copyOf(weapons), tick, posePlan, quick, profile));
        }
        if (quick) summary = mark.pos().toShortString() + " · " + summary;
        reply(player, roster, rows, summary, weapons.size());
    }

    static boolean allowPreview(MinecraftServer server, int tick) {
        return allow(LAST_PREVIEW, server, tick, 5);
    }

    private static <T> boolean allow(Map<T, Integer> requests, T key, int tick, int interval) {
        Integer last = requests.get(key);
        if (last != null && tick - last < interval) return false;
        requests.put(key, tick);
        return true;
    }

    private void error(ServerPlayer player, String message) {
        PacketDistributor.sendToPlayer(player, new TerminalStrikeSnapshot(terminal, request, BlockPos.ZERO, 0,
                List.of(), List.of(), message));
    }

    private void reply(ServerPlayer player, StrikePreview.Roster roster, List<StrikePreview.Row> rows, String message) {
        reply(player, roster, rows, message, 0);
    }

    private void reply(ServerPlayer player, StrikePreview.Roster roster, List<StrikePreview.Row> rows,
                       String message, int executableWeapons) {
        PacketDistributor.sendToPlayer(player, new TerminalStrikeSnapshot(terminal, request, roster.core().getBlockPos(),
                roster.revision(), roster.channels(), rows, message, executableWeapons, roster.axialChannels()));
    }
}
