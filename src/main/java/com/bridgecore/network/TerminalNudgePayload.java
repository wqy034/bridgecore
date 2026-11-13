package com.bridgecore.network;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.control.NudgeDirection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** One finite translation step. Distance, frame and speed are resolved server-side. */
public record TerminalNudgePayload(UUID terminal, int directionId) implements CustomPacketPayload {
    public static final Type<TerminalNudgePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "terminal_nudge"));
    public static final StreamCodec<FriendlyByteBuf, TerminalNudgePayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalNudgePayload::write, TerminalNudgePayload::new);

    private TerminalNudgePayload(FriendlyByteBuf buf) { this(buf.readUUID(), buf.readUnsignedByte()); }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(terminal);
        buf.writeByte(directionId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(TerminalNudgePayload packet, IPayloadContext context) {
        NudgeDirection direction = NudgeDirection.fromWireId(packet.directionId);
        if (direction == null || !(context.player() instanceof ServerPlayer player)) return;
        var stack = TerminalLinkPayload.findHeldTerminal(player, packet.terminal);
        if (stack == null) return;
        var core = TerminalLinkPayload.boundCoreOf(player, stack, packet.terminal);
        if (core == null) return;
        String reply = switch (core.submitNudge(direction)) {
            case ACCEPTED -> "已接受半格微调，可查询状态或取消";
            case NO_SAMPLE -> "飞控尚无有效物理采样";
            case MOVING -> "请先减速，再做微调";
            case NO_HEADING -> "船头近乎竖直，水平微调需要明确的船头方向";
            case REJECTED -> "微调未接受。请确认耦合星舰模式、无在途任务且目标在边界内";
        };
        TerminalReplyPayload.send(player, packet.terminal, reply);
    }
}
