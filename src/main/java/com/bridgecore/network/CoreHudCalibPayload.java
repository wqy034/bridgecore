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
 * C2S config write (core GUI tab 3): HUD calibration, all numerics x100 as ints
 * (offsets in blocks, scale/att-size multipliers, projection-eye compensation in
 * blocks, pitch interval in degrees, att-border fraction, lock target search range,
 * multi-lock cone half-angle in degrees, lock-box count cap) plus the input-line
 * flag as 0/1. 2026-10-26 v2: same fields, orthogonalized semantics — x/y
 * position the panel, scale sizes it, eyeX/eyeY place the projection eye point.
 * The server re-validates ranges and the BE type; the BE pushes the values to all
 * holo displays on the structure, which re-sync to clients via their BE update tag.
 */
public record CoreHudCalibPayload(BlockPos pos, int x100, int y100, int scale100,
                                  int eyeX100, int eyeY100, int pitchStep100,
                                  int attSize100, int attBorder100, int inputLine,
                                  int lockRange100, int lockCone100, int lockMax100)
        implements CustomPacketPayload {

    public static final Type<CoreHudCalibPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "core_hud_calib"));

    public static final StreamCodec<FriendlyByteBuf, CoreHudCalibPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBlockPos(p.pos);
                buf.writeInt(p.x100);
                buf.writeInt(p.y100);
                buf.writeInt(p.scale100);
                buf.writeInt(p.eyeX100);
                buf.writeInt(p.eyeY100);
                buf.writeInt(p.pitchStep100);
                buf.writeInt(p.attSize100);
                buf.writeInt(p.attBorder100);
                buf.writeInt(p.inputLine);
                buf.writeInt(p.lockRange100);
                buf.writeInt(p.lockCone100);
                buf.writeInt(p.lockMax100);
            },
            buf -> new CoreHudCalibPayload(buf.readBlockPos(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt()));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static boolean out(int v, double min, double max) {
        return v < (int) Math.round(min * 100) || v > (int) Math.round(max * 100);
    }

    public static void handle(CoreHudCalibPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            double R = BridgeCoreBlockEntity.HUD_OFFSET_RANGE;
            if (out(payload.x100(), -R, R)
                    || out(payload.y100(), -BridgeCoreBlockEntity.HUD_OFFSET_Y_RANGE,
                            BridgeCoreBlockEntity.HUD_OFFSET_Y_RANGE)
                    || out(payload.scale100(), BridgeCoreBlockEntity.HUD_SCALE_MIN,
                            BridgeCoreBlockEntity.HUD_SCALE_MAX)
                    || out(payload.eyeX100(), BridgeCoreBlockEntity.HUD_EYE_X_MIN,
                            BridgeCoreBlockEntity.HUD_EYE_X_MAX)
                    || out(payload.eyeY100(), -BridgeCoreBlockEntity.HUD_EYE_Y_RANGE,
                            BridgeCoreBlockEntity.HUD_EYE_Y_RANGE)
                    || out(payload.pitchStep100(), BridgeCoreBlockEntity.HUD_PITCH_STEP_MIN,
                            BridgeCoreBlockEntity.HUD_PITCH_STEP_MAX)
                    || out(payload.attSize100(), BridgeCoreBlockEntity.HUD_ATT_SIZE_MIN,
                            BridgeCoreBlockEntity.HUD_ATT_SIZE_MAX)
                    || out(payload.attBorder100(), 0.0, BridgeCoreBlockEntity.HUD_ATT_BORDER_MAX)
                    || out(payload.lockRange100(), BridgeCoreBlockEntity.HUD_LOCK_RANGE_MIN,
                            BridgeCoreBlockEntity.HUD_LOCK_RANGE_MAX)
                    || out(payload.lockCone100(), BridgeCoreBlockEntity.HUD_LOCK_CONE_MIN,
                            BridgeCoreBlockEntity.HUD_LOCK_CONE_MAX)
                    || out(payload.lockMax100(), BridgeCoreBlockEntity.HUD_LOCK_MAX_COUNT_MIN,
                            BridgeCoreBlockEntity.HUD_LOCK_MAX_COUNT_MAX)
                    || (payload.inputLine() & ~1) != 0) {
                BridgeCoreMod.LOGGER.warn("[bridgecore] rejecting hud calib from {}",
                        context.player().getName().getString());
                return;
            }
            if (context.player().level().getBlockEntity(payload.pos())
                    instanceof BridgeCoreBlockEntity be) {
                be.setHudCalib(payload.x100() / 100.0, payload.y100() / 100.0,
                        payload.scale100() / 100.0, payload.eyeX100() / 100.0,
                        payload.eyeY100() / 100.0, payload.pitchStep100() / 100.0,
                        payload.attSize100() / 100.0, payload.attBorder100() / 100.0,
                        payload.inputLine() != 0,
                        Math.round(payload.lockRange100() / 100.0f),
                        Math.round(payload.lockCone100() / 100.0f),
                        Math.round(payload.lockMax100() / 100.0f));
            }
        });
    }
}
