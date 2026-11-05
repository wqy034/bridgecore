package com.bridgecore.network;

import com.bridgecore.BridgeCoreBlockEntity;
import com.bridgecore.BridgeCoreMod;
import com.bridgecore.GamepadInput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * C2S config write (core GUI tab 2, gamepad sub-section): rebind one gamepad
 * channel. kind 0 = axis channel (value1 = source id 0..8, value2 = invert 0/1);
 * kind 1 = button channel (value1 = button id 0..14, value2 ignored).
 * The server re-validates every field and the BE type; the new map reaches the
 * client via the menu's ContainerData on the next broadcast.
 */
public record CorePadBindPayload(BlockPos pos, int kind, int channel, int value1, int value2)
        implements CustomPacketPayload {

    public static final Type<CorePadBindPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "core_pad_bind"));

    public static final int KIND_AXIS = 0;
    public static final int KIND_BUTTON = 1;

    public static final StreamCodec<FriendlyByteBuf, CorePadBindPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBlockPos(p.pos);
                buf.writeInt(p.kind);
                buf.writeInt(p.channel);
                buf.writeInt(p.value1);
                buf.writeInt(p.value2);
            },
            buf -> new CorePadBindPayload(buf.readBlockPos(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt()));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CorePadBindPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            boolean valid = switch (payload.kind()) {
                case KIND_AXIS -> payload.channel() >= 0
                        && payload.channel() < GamepadInput.AXIS_CHANNEL_COUNT
                        && payload.value1() >= 0 && payload.value1() < GamepadInput.SRC_COUNT
                        && (payload.value2() == 0 || payload.value2() == 1);
                case KIND_BUTTON -> payload.channel() >= 0
                        && payload.channel() < GamepadInput.BTN_CHANNEL_COUNT
                        && payload.value1() >= 0 && payload.value1() <= GamepadInput.MAX_BUTTON_ID;
                default -> false;
            };
            if (!valid) {
                BridgeCoreMod.LOGGER.warn("[bridgecore] rejecting pad bind kind={} ch={} v={} from {}",
                        payload.kind(), payload.channel(), payload.value1(),
                        context.player().getName().getString());
                return;
            }
            if (context.player().level().getBlockEntity(payload.pos())
                    instanceof BridgeCoreBlockEntity be) {
                if (payload.kind() == KIND_AXIS) {
                    be.setPadAxisBinding(payload.channel(), payload.value1(), payload.value2());
                } else {
                    be.setPadButtonBinding(payload.channel(), payload.value1());
                }
            }
        });
    }
}
