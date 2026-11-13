package com.bridgecore.network;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.client.FireControlScreen;
import com.bridgecore.firecontrol.StrikePreview;
import com.bridgecore.menu.FireControlMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public record FireControlStrikeResult(int menuId, @Nullable UUID request, String message, List<StrikePreview.Row> rows,
                                      boolean executable) implements CustomPacketPayload {
    public static final Type<FireControlStrikeResult> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "fire_control_strike_result"));
    public static final StreamCodec<FriendlyByteBuf, FireControlStrikeResult> STREAM_CODEC =
            CustomPacketPayload.codec(FireControlStrikeResult::write, FireControlStrikeResult::read);

    private static FireControlStrikeResult read(FriendlyByteBuf buf) {
        return new FireControlStrikeResult(buf.readVarInt(), buf.readNullable(input -> input.readUUID()),
                buf.readUtf(96), TerminalStrikeSnapshot.readRows(buf), buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(menuId);
        buf.writeNullable(request, (output, value) -> output.writeUUID(value));
        buf.writeUtf(message, 96);
        TerminalStrikeSnapshot.writeRows(buf, rows);
        buf.writeBoolean(executable);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(FireControlStrikeResult packet, IPayloadContext context) {
        if (!FireControlScreen.acceptStrikeResult(packet) && packet.request == null) {
            context.player().displayClientMessage(Component.literal(packet.message), false);
        }
    }

    public static void finished(ServerPlayer player, FireControlMenu menu, String message) {
        if (player.containerMenu == menu) {
            PacketDistributor.sendToPlayer(player, new FireControlStrikeResult(menu.containerId, null, message, List.of(), false));
        } else {
            player.displayClientMessage(Component.literal(message), false);
        }
    }
}
