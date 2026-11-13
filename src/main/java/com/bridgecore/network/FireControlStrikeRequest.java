package com.bridgecore.network;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.firecontrol.StrikePreview;
import com.bridgecore.menu.FireControlMenu;
import dev.ryanhcode.sable.Sable;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

public record FireControlStrikeRequest(int menuId, UUID request, Action action, BlockPos target, int channel,
                                       int mode) implements CustomPacketPayload {
    public enum Action { PREVIEW, EXECUTE, CANCEL }

    private static final Map<FireControlMenu, Session> SESSIONS = new WeakHashMap<>();
    private static final int CONFIRMATION_TICKS = 600;
    private static final class Session {
        int lastRequest = -1;
        Confirmation confirmation;
    }

    private record Confirmation(UUID request, BlockPos target, int channel, StrikePreview.Mode requestedMode, StrikePreview.Mode mode,
                                int revision, int tick, Set<UUID> weapons, StrikePreview.PosePlan posePlan) {}

    public static final Type<FireControlStrikeRequest> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "fire_control_strike_request"));
    public static final StreamCodec<FriendlyByteBuf, FireControlStrikeRequest> STREAM_CODEC =
            CustomPacketPayload.codec(FireControlStrikeRequest::write, FireControlStrikeRequest::read);

    private static FireControlStrikeRequest read(FriendlyByteBuf buf) {
        int menuId = buf.readVarInt();
        UUID request = buf.readUUID();
        Action action = switch (buf.readUnsignedByte()) {
            case 0 -> Action.PREVIEW;
            case 1 -> Action.EXECUTE;
            case 2 -> Action.CANCEL;
            default -> throw new DecoderException("Invalid fire control strike action");
        };
        return new FireControlStrikeRequest(menuId, request, action,
                new BlockPos(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()), buf.readByte(), buf.readByte());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(menuId);
        buf.writeUUID(request);
        buf.writeByte(switch (action) { case PREVIEW -> 0; case EXECUTE -> 1; case CANCEL -> 2; });
        buf.writeVarInt(target.getX());
        buf.writeVarInt(target.getY());
        buf.writeVarInt(target.getZ());
        buf.writeByte(channel);
        buf.writeByte(mode);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(FireControlStrikeRequest packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.containerMenu instanceof FireControlMenu menu) || menu.containerId != packet.menuId) return;
        var core = menu.strikeCore(player);
        if (core == null) return;
        var level = player.serverLevel();
        var server = level.getServer();
        int tick = server.getTickCount();
        var session = SESSIONS.computeIfAbsent(menu, ignored -> new Session());
        if (session.lastRequest == tick) {
            packet.reply(player, "请稍后再试");
            return;
        }
        session.lastRequest = tick;
        if (packet.action == Action.CANCEL) {
            session.confirmation = null;
            String result = core.cancelCoordinateStrike(player, menu);
            packet.reply(player, result == null ? "当前没有可取消的标定打击" : result);
            return;
        }
        if (packet.action == Action.PREVIEW) session.confirmation = null;
        var ship = core.registeredSubLevel();
        if (ship == null || ship.isRemoved() || Sable.HELPER.getContaining(core) != ship || core.getLevel() != level) {
            packet.reply(player, "请从已装配并加载的船体使用坐标打击");
            return;
        }
        var mode = StrikePreview.Mode.fromWire(packet.mode);
        if (mode == null || packet.channel < 0 || packet.channel >= core.channelCount()
                || !level.isInWorldBounds(packet.target)) {
            packet.reply(player, "请选择有效频道并输入本维度内的世界坐标");
            return;
        }
        if (packet.action == Action.EXECUTE) {
            var confirmation = session.confirmation;
            if (confirmation == null || mode != confirmation.requestedMode || !packet.request.equals(confirmation.request)
                    || !packet.target.equals(confirmation.target) || packet.channel != confirmation.channel
                    || tick - confirmation.tick >= CONFIRMATION_TICKS) {
                packet.reply(player, "确认已失效，请重新试算");
                return;
            }
            session.confirmation = null;
            var roster = StrikePreview.roster(core);
            if (roster.revision() != confirmation.revision) {
                packet.reply(player, "火控配置已刷新，请重新试算");
                return;
            }
            String failure = core.startCoordinateStrike(player, menu, ship, packet.target, roster,
                    packet.channel, confirmation.mode, confirmation.weapons, confirmation.posePlan);
            packet.reply(player, failure == null ? confirmation.mode.queuedMessage() : failure);
            return;
        }
        var roster = StrikePreview.roster(core);
        var channelMode = roster.channelMode(packet.channel, mode == StrikePreview.Mode.TURRET_POSE);
        if (mode != channelMode) {
            packet.reply(player, "频道类型已变化，请重新预瞄");
            return;
        }
        if (!TerminalStrikeRequest.allowPreview(server, tick)) {
            packet.reply(player, "服务器正在处理另一次试算，请稍后再试");
            return;
        }
        var directMode = mode == StrikePreview.Mode.TURRET_POSE ? StrikePreview.Mode.TURRET : mode;
        var assessment = StrikePreview.assess(level, ship, roster, packet.channel, directMode, packet.target);
        var directWeapons = assessment.executableWeapons();
        if (mode != StrikePreview.Mode.TURRET_POSE || !directWeapons.isEmpty()) {
            if (!directWeapons.isEmpty()) {
                session.confirmation = new Confirmation(packet.request, packet.target, packet.channel, mode, directMode,
                        roster.revision(), tick, Set.copyOf(directWeapons), null);
            }
            String summary = assessment.summary(directMode, roster.limited());
            if (mode == StrikePreview.Mode.TURRET_POSE) summary = "当前舰姿可达 · " + summary;
            PacketDistributor.sendToPlayer(player, new FireControlStrikeResult(packet.menuId, packet.request,
                    summary, assessment.rows(), !directWeapons.isEmpty()));
            return;
        }
        var pose = StrikePreview.previewPose(level, ship, roster, packet.channel, packet.target);
        var weapons = pose.plan().turrets();
        if (!weapons.isEmpty()) {
            session.confirmation = new Confirmation(packet.request, packet.target, packet.channel, mode, mode,
                    roster.revision(), tick, weapons, pose.plan());
        }
        PacketDistributor.sendToPlayer(player, new FireControlStrikeResult(packet.menuId, packet.request,
                pose.summary(), pose.rows(), !weapons.isEmpty()));
    }

    private void reply(ServerPlayer player, String message) {
        PacketDistributor.sendToPlayer(player, new FireControlStrikeResult(menuId, request, message, List.of(), false));
    }
}
