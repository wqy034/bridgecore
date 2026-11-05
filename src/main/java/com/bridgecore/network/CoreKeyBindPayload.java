package com.bridgecore.network;

import com.bridgecore.BridgeCoreBlockEntity;
import com.bridgecore.BridgeCoreMod;
import com.bridgecore.TypewriterInput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * C2S config write (core GUI tab 2): rebind one keyboard channel to a GLFW key
 * code (-1 = unbind). The server re-validates channel/keyCode and the BE type;
 * conflicts are NOT rejected (the GUI marks them, the player may have reasons).
 */
public record CoreKeyBindPayload(BlockPos pos, int channel, int keyCode) implements CustomPacketPayload {

    public static final Type<CoreKeyBindPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "core_key_bind"));

    public static final StreamCodec<FriendlyByteBuf, CoreKeyBindPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBlockPos(p.pos);
                buf.writeInt(p.channel);
                buf.writeInt(p.keyCode);
            },
            buf -> new CoreKeyBindPayload(buf.readBlockPos(), buf.readInt(), buf.readInt()));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CoreKeyBindPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // GLFW key codes top out in the low hundreds; 1024 is a generous sanity cap.
            if (payload.channel() < 0 || payload.channel() >= TypewriterInput.CHANNEL_COUNT
                    || payload.keyCode() < -1 || payload.keyCode() > 1024) {
                BridgeCoreMod.LOGGER.warn("[bridgecore] rejecting key bind ch={} key={} from {}",
                        payload.channel(), payload.keyCode(), context.player().getName().getString());
                return;
            }
            if (context.player().level().getBlockEntity(payload.pos())
                    instanceof BridgeCoreBlockEntity be) {
                be.setKeyBinding(payload.channel(), payload.keyCode());
            }
        });
    }
}
