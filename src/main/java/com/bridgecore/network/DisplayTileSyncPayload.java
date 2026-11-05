package com.bridgecore.network;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.display.DisplayPixelCodec;
import com.bridgecore.display.DisplayResolution;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * S2C per-tile screen slice sync (ABGR, row-major, width×width). Sent only for
 * tiles whose pixels changed, broadcast to players near the logical center —
 * plot chunks are untracked, so BE update packets alone would never reach
 * ship-mounted screens (same constraint as CanvasSyncPayload).
 *
 * width == 0 is a clear: the client drops the cached texture for that position
 * (tile released from its array, or the block was broken and re-placed).
 */
public record DisplayTileSyncPayload(BlockPos pos, int width, int[] pixels)
        implements CustomPacketPayload {

    public DisplayTileSyncPayload {
        if (width < 0 || width > DisplayResolution.MAX || pixels.length != width * width)
            throw new IllegalArgumentException("Malformed display slice");
    }

    public static final Type<DisplayTileSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "display_tile_sync"));

    public static final StreamCodec<FriendlyByteBuf, DisplayTileSyncPayload> STREAM_CODEC =
            StreamCodec.of((buf, value) -> {
                buf.writeBlockPos(value.pos());
                buf.writeVarInt(value.width());
                buf.writeByteArray(DisplayPixelCodec.encode(value.pixels()));
            }, buf -> {
                BlockPos pos = buf.readBlockPos();
                int width = buf.readVarInt();
                if (width < 0 || width > DisplayResolution.MAX) throw new DecoderException("Invalid display width");
                byte[] packed = buf.readByteArray(width == 0 ? 0 : DisplayPixelCodec.MAX_BYTES);
                try { return new DisplayTileSyncPayload(pos, width, DisplayPixelCodec.decode(packed, width * width)); }
                catch (IllegalArgumentException e) { throw new DecoderException(e); }
            });

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static DisplayTileSyncPayload clearOf(BlockPos pos) {
        return new DisplayTileSyncPayload(pos, 0, new int[0]);
    }

    public static void handle(DisplayTileSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist.isClient()) {
                if (payload.width() <= 0) {
                    com.bridgecore.client.DisplayTileTextureCache.remove(payload.pos());
                } else {
                    com.bridgecore.client.DisplayTileTextureCache.update(payload.pos(),
                            payload.width(), payload.pixels());
                }
            }
        });
    }
}
