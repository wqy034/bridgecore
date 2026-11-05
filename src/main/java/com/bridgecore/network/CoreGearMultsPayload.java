package com.bridgecore.network;

import com.bridgecore.BridgeCoreBlockEntity;
import com.bridgecore.BridgeCoreMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * C2S config write (core GUI tab 1): all four gear multipliers, x1000 as ints.
 * The server re-validates the range and the BE type; the new values reach the
 * client via the menu's ContainerData on the next broadcast.
 */
public record CoreGearMultsPayload(BlockPos pos, int m0, int m1, int m2, int m3)
        implements CustomPacketPayload {

    public static final Type<CoreGearMultsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "core_gear_mults"));

    public static final StreamCodec<FriendlyByteBuf, CoreGearMultsPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBlockPos(p.pos);
                buf.writeInt(p.m0);
                buf.writeInt(p.m1);
                buf.writeInt(p.m2);
                buf.writeInt(p.m3);
            },
            buf -> new CoreGearMultsPayload(buf.readBlockPos(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt()));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CoreGearMultsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            int min = (int) Math.round(BridgeCoreBlockEntity.GEAR_MULT_MIN * 1000);
            int max = (int) Math.round(BridgeCoreBlockEntity.GEAR_MULT_MAX * 1000);
            int[] raw = {payload.m0(), payload.m1(), payload.m2(), payload.m3()};
            for (int v : raw) {
                if (v < min || v > max) {
                    BridgeCoreMod.LOGGER.warn("[bridgecore] rejecting out-of-range gear mult {} from {}",
                            v / 1000.0, context.player().getName().getString());
                    return;
                }
            }
            if (context.player().level().getBlockEntity(payload.pos())
                    instanceof BridgeCoreBlockEntity be) {
                be.setGearMults(new double[]{raw[0] / 1000.0, raw[1] / 1000.0, raw[2] / 1000.0,
                        raw[3] / 1000.0});
            }
        });
    }
}
