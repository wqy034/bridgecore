package com.bridgecore.network;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.display.DisplayApps;
import com.bridgecore.display.DisplayGpuBlockEntity;
import com.bridgecore.display.DisplayResolution;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/** Bounded choices only; recent block interaction and revision checked by GPU. */
public record DisplayGpuSelectPayload(BlockPos pos, String appId, int resolutionChoice, int revision)
        implements CustomPacketPayload {
    public static final Type<DisplayGpuSelectPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "display_gpu_select"));
    public static final StreamCodec<FriendlyByteBuf, DisplayGpuSelectPayload> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeBlockPos(value.pos()); buf.writeUtf(value.appId(), 32);
                buf.writeVarInt(value.resolutionChoice()); buf.writeVarInt(value.revision());
            }, buf -> new DisplayGpuSelectPayload(buf.readBlockPos(), buf.readUtf(32), buf.readVarInt(), buf.readVarInt()));
    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(DisplayGpuSelectPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!DisplayApps.isValid(payload.appId()) || !DisplayResolution.validChoice(payload.resolutionChoice())) return;
            if (context.player() instanceof ServerPlayer player && player.level().hasChunkAt(payload.pos())
                    && player.level().getBlockEntity(payload.pos()) instanceof DisplayGpuBlockEntity gpu)
                gpu.applySettings(player, payload.appId(), payload.resolutionChoice(), payload.revision());
        });
    }
}
