package com.bridgecore.network;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.control.CruiseHeading;
import com.bridgecore.control.NavigationController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** One cruise request with an explicit target variant and per-task limits. */
public record TerminalCruisePayload(UUID terminal, @Nullable String mark, @Nullable Vec3 position,
                                    double speed, double turnRate) implements CustomPacketPayload {
    public TerminalCruisePayload {
        if ((mark == null) == (position == null)) throw new IllegalArgumentException("Expected one cruise target");
    }

    public static final Type<TerminalCruisePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "terminal_cruise"));
    public static final StreamCodec<FriendlyByteBuf, TerminalCruisePayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalCruisePayload::write, TerminalCruisePayload::read);

    private static TerminalCruisePayload read(FriendlyByteBuf buf) {
        UUID terminal = buf.readUUID();
        double speed = buf.readDouble();
        double turnRate = buf.readDouble();
        return buf.readBoolean()
                ? new TerminalCruisePayload(terminal, buf.readUtf(24), null, speed, turnRate)
                : new TerminalCruisePayload(terminal, null, new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                        speed, turnRate);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(terminal);
        buf.writeDouble(speed);
        buf.writeDouble(turnRate);
        buf.writeBoolean(mark != null);
        if (mark != null) {
            buf.writeUtf(mark, 24);
        } else {
            buf.writeDouble(position.x);
            buf.writeDouble(position.y);
            buf.writeDouble(position.z);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(TerminalCruisePayload packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !player.isAlive() || player.isSpectator()) return;
        var stack = TerminalLinkPayload.findHeldTerminal(player, packet.terminal);
        if (stack == null) return;
        var core = TerminalLinkPayload.boundCoreOf(player, stack, packet.terminal);
        if (core == null) return;
        if (!(packet.speed >= NavigationController.MIN_TERMINAL_SPEED && packet.speed <= NavigationController.MAX_SPEED)
                || !(packet.turnRate >= CruiseHeading.MIN_RATE && packet.turnRate <= CruiseHeading.MAX_RATE)) {
            TerminalReplyPayload.send(player, packet.terminal, "巡航速度或转向率超出允许范围");
            return;
        }
        String reply;
        if (packet.mark != null) {
            reply = TerminalLinkPayload.dispatch(player, stack, core, packet.mark, packet.speed, packet.turnRate);
        } else {
            var admission = core.requestCruise(UUID.randomUUID(), player.serverLevel().dimension(),
                    packet.position, packet.speed, packet.turnRate);
            reply = TerminalLinkPayload.cruiseReply(admission, "已接受坐标导航");
        }
        TerminalReplyPayload.send(player, packet.terminal, reply);
    }
}
