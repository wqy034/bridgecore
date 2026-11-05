package com.bridgecore.network;

import com.bridgecore.BridgeCoreMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/** Open/acknowledge the display editor. Success always comes from the server. */
public record DisplayGpuOpenPayload(BlockPos pos, String appId, int resolutionChoice,
        int effectiveResolution, int automaticResolution, int columns, int rows, int revision, int feedback) implements CustomPacketPayload {
    public static final Type<DisplayGpuOpenPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "display_gpu_open"));
    public static final StreamCodec<FriendlyByteBuf, DisplayGpuOpenPayload> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeBlockPos(value.pos()); buf.writeUtf(value.appId(), 32);
                buf.writeVarInt(value.resolutionChoice()); buf.writeVarInt(value.effectiveResolution()); buf.writeVarInt(value.automaticResolution());
                buf.writeVarInt(value.columns()); buf.writeVarInt(value.rows());
                buf.writeVarInt(value.revision()); buf.writeVarInt(value.feedback());
            }, buf -> new DisplayGpuOpenPayload(buf.readBlockPos(), buf.readUtf(32), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(DisplayGpuOpenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist.isClient()) com.bridgecore.client.DisplayGpuSelectScreen.open(payload);
        });
    }
}
