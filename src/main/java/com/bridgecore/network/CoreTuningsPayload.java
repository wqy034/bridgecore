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
 * C2S config write (core GUI tabs 1+4): all eight control-law tunings, x100 as ints,
 * in the fixed Tunings field order (kd, kp, transKv, hoverKp, climbKv, climbRate,
 * attMaxAlpha, rotPriceMult). The server clamps each field per its range
 * (Tunings.fromArray is the single funnel, shared with NBT load) and checks the BE
 * type; the new values reach the client via the menu's ContainerData on the next
 * broadcast.
 */
public record CoreTuningsPayload(BlockPos pos, int kd, int kp, int transKv,
                                 int hoverKp, int climbKv, int climbRate,
                                 int attMaxAlpha, int rotPriceMult)
        implements CustomPacketPayload {

    public static final Type<CoreTuningsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "core_tunings"));

    public static final StreamCodec<FriendlyByteBuf, CoreTuningsPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBlockPos(p.pos);
                buf.writeInt(p.kd);
                buf.writeInt(p.kp);
                buf.writeInt(p.transKv);
                buf.writeInt(p.hoverKp);
                buf.writeInt(p.climbKv);
                buf.writeInt(p.climbRate);
                buf.writeInt(p.attMaxAlpha);
                buf.writeInt(p.rotPriceMult);
            },
            buf -> new CoreTuningsPayload(buf.readBlockPos(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readInt()));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CoreTuningsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().level().getBlockEntity(payload.pos())
                    instanceof BridgeCoreBlockEntity be) {
                // Per-field clamp (not reject): sliders quantize client-side, but a
                // hand-crafted packet just gets pulled into the legal range.
                be.setTunings(BridgeCoreBlockEntity.Tunings.fromArray(new double[]{
                        payload.kd() / 100.0, payload.kp() / 100.0, payload.transKv() / 100.0,
                        payload.hoverKp() / 100.0, payload.climbKv() / 100.0,
                        payload.climbRate() / 100.0, payload.attMaxAlpha() / 100.0,
                        payload.rotPriceMult() / 100.0}));
            }
        });
    }
}
