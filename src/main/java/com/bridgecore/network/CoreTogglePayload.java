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
 * C2S config write (core GUI tab 0): toggle one of the core's switches. The
 * handler calls the SAME toggle methods the item interactions use — no parallel
 * logic. Switch ids (validated server-side):
 *   0 = cycle control mode   1 = gravity compensation   3 = coupled/decoupled
 *   4 = self-balance         5 = altitude follow        6 = keyboard channel
 *   7 = HUD dark outline (tab 3)
 * (id 2 was the core-body redstone face meaning — retired 2026-09-18 when the
 * core stopped taking redstone; the numeric gap stays so older clients click
 * harmlessly into the default branch.)
 */
public record CoreTogglePayload(BlockPos pos, int switchId) implements CustomPacketPayload {

    public static final Type<CoreTogglePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "core_toggle"));

    public static final int SWITCH_MODE = 0;
    public static final int SWITCH_GRAVITY_COMP = 1;
    public static final int SWITCH_COUPLED = 3;
    public static final int SWITCH_SELF_BALANCE = 4;
    public static final int SWITCH_ALTITUDE_FOLLOW = 5;
    public static final int SWITCH_KEYBOARD = 6;
    public static final int SWITCH_HUD_OUTLINE = 7;
    private static final int SWITCH_COUNT = 8;

    public static final StreamCodec<FriendlyByteBuf, CoreTogglePayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBlockPos(p.pos);
                buf.writeInt(p.switchId);
            },
            buf -> new CoreTogglePayload(buf.readBlockPos(), buf.readInt()));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CoreTogglePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.switchId() < 0 || payload.switchId() >= SWITCH_COUNT) {
                BridgeCoreMod.LOGGER.warn("[bridgecore] rejecting unknown toggle {} from {}",
                        payload.switchId(), context.player().getName().getString());
                return;
            }
            if (!(context.player().level().getBlockEntity(payload.pos())
                    instanceof BridgeCoreBlockEntity be)) {
                return;
            }
            switch (payload.switchId()) {
                case SWITCH_MODE -> be.cycleMode();
                case SWITCH_GRAVITY_COMP -> be.toggleGravityComp();
                case SWITCH_COUPLED -> be.toggleCoupled();
                case SWITCH_SELF_BALANCE -> be.toggleSelfBalance();
                case SWITCH_ALTITUDE_FOLLOW -> be.toggleAltitudeFollow();
                case SWITCH_KEYBOARD -> be.toggleKeyboardChannel();
                case SWITCH_HUD_OUTLINE -> be.toggleHudOutline();
                default -> {
                }
            }
            be.setChanged(); // the toggle methods predate the GUI and never marked dirty
        });
    }
}
