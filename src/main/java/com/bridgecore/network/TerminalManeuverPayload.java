package com.bridgecore.network;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.control.RemoteManeuver;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Held-key stream. A zero mask releases the controls. */
public record TerminalManeuverPayload(UUID terminal, UUID session, int mask, int speedTenths, boolean changed)
        implements CustomPacketPayload {
    public static final Type<TerminalManeuverPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "terminal_maneuver"));
    public static final StreamCodec<FriendlyByteBuf, TerminalManeuverPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalManeuverPayload::write, TerminalManeuverPayload::new);

    private TerminalManeuverPayload(FriendlyByteBuf buf) {
        this(buf.readUUID(), buf.readUUID(), buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(terminal);
        buf.writeUUID(session);
        buf.writeByte(mask);
        buf.writeByte(speedTenths);
        buf.writeBoolean(changed);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(TerminalManeuverPayload packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !player.isAlive() || player.isSpectator()) return;
        var stack = TerminalLinkPayload.findHeldTerminal(player, packet.terminal);
        if (stack == null) return;
        boolean report = packet.changed && packet.mask != 0;
        var core = TerminalLinkPayload.boundCoreOf(player, stack, packet.terminal, report);
        if (core == null) return;
        var owner = new RemoteManeuver.Owner(player.getUUID(), packet.terminal, packet.session);
        boolean accepted = core.submitRemoteManeuver(owner, packet.mask, packet.speedTenths, packet.changed);
        if (report && !accepted) {
            TerminalReplyPayload.send(player, packet.terminal, "持续操纵未接受，请松开方向键并确认飞控可用");
        }
    }
}
