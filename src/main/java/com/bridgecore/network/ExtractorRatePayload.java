package com.bridgecore.network;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.EnergyExtractorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * C2S rate-slider update from the energy extractor GUI: target ratePercent
 * (0..100) for the extractor at pos. The server re-validates the range and the
 * BE type; the new value reaches the client via the menu's ContainerData on the
 * next broadcast. (Replaces the three-gear ExtractorGearPayload.)
 */
public record ExtractorRatePayload(BlockPos pos, int percent) implements CustomPacketPayload {

    public static final Type<ExtractorRatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "extractor_rate"));

    public static final StreamCodec<FriendlyByteBuf, ExtractorRatePayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBlockPos(p.pos);
                buf.writeInt(p.percent);
            },
            buf -> new ExtractorRatePayload(buf.readBlockPos(), buf.readInt()));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ExtractorRatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.percent() < 0 || payload.percent() > 100) {
                BridgeCoreMod.LOGGER.warn("[bridgecore] rejecting out-of-range extractor rate {} from {}",
                        payload.percent(), context.player().getName().getString());
                return;
            }
            if (context.player().level().getBlockEntity(payload.pos())
                    instanceof EnergyExtractorBlockEntity be) {
                be.setRatePercent(payload.percent());
            }
        });
    }
}
