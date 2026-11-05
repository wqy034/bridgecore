package com.bridgecore.network;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.GamepadInput;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * C2S gamepad input frame (M3a): the full-precision profile view from Create:
 * Tweaked Controllers — 6 axes (float, player deadzone/bounds already applied)
 * + 15 buttons as a bitmask. Sent by the client reader on change with a 5-tick
 * heartbeat; the server treats entries older than 30 ticks as released.
 * Raw values only — all semantics (which axis is pitch, which button is
 * auto-level) are resolved server-side in {@link GamepadInput}, so a future
 * remapping UI never touches the wire format.
 */
public record GamepadInputPayload(float[] axes, int buttons) implements CustomPacketPayload {

    public static final int AXIS_COUNT = 6;

    public static final Type<GamepadInputPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "gamepad_input"));

    public static final StreamCodec<FriendlyByteBuf, GamepadInputPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                for (int i = 0; i < AXIS_COUNT; i++) {
                    buf.writeFloat(p.axes[i]);
                }
                buf.writeInt(p.buttons);
            },
            buf -> {
                float[] axes = new float[AXIS_COUNT];
                for (int i = 0; i < AXIS_COUNT; i++) {
                    axes[i] = buf.readFloat();
                }
                return new GamepadInputPayload(axes, buf.readInt());
            });

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GamepadInputPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                GamepadInput.onInput(context.player().getUUID(), payload.axes(), payload.buttons()));
    }
}
