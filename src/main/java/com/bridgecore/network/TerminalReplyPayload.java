package com.bridgecore.network;

import com.bridgecore.BridgeCoreMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public record TerminalReplyPayload(UUID terminal, String message, boolean status) implements CustomPacketPayload {
    public static final Type<TerminalReplyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "terminal_reply"));
    public static final StreamCodec<FriendlyByteBuf, TerminalReplyPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalReplyPayload::write, TerminalReplyPayload::new);

    public TerminalReplyPayload(UUID terminal, String message) { this(terminal, message, false); }

    private TerminalReplyPayload(FriendlyByteBuf buf) {
        this(buf.readUUID(), buf.readUtf(256), buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(terminal);
        buf.writeUtf(message, 256);
        buf.writeBoolean(status);
    }

    public static void send(ServerPlayer player, UUID terminal, String message) {
        PacketDistributor.sendToPlayer(player, new TerminalReplyPayload(terminal, message));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(TerminalReplyPayload reply, IPayloadContext context) {
        if (reply.status) {
            com.bridgecore.client.NavTerminalScreen.acceptReply(reply);
            return;
        }
        if (!com.bridgecore.client.NavTerminalScreen.acceptReply(reply)
                && !com.bridgecore.client.NavTerminalKeyboard.acceptReply(reply)) {
            context.player().displayClientMessage(net.minecraft.network.chat.Component.literal(reply.message), false);
        }
    }
}
