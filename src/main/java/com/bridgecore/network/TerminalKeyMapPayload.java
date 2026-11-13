package com.bridgecore.network;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.TypewriterInput;
import com.bridgecore.control.NudgeDirection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The bound flight core's six translation keys, in nudge wire order. */
public record TerminalKeyMapPayload(UUID terminal, long request, List<Integer> keys) implements CustomPacketPayload {
    public static final Type<TerminalKeyMapPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "terminal_key_map"));
    public static final StreamCodec<FriendlyByteBuf, TerminalKeyMapPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalKeyMapPayload::write, TerminalKeyMapPayload::read);
    private static final int KEY_COUNT = NudgeDirection.values().length;

    public TerminalKeyMapPayload {
        keys = List.copyOf(keys);
        if (keys.size() != KEY_COUNT) throw new IllegalArgumentException("Invalid translation key count");
    }

    public static TerminalKeyMapPayload fromCore(UUID terminal, long request, int[] map) {
        return new TerminalKeyMapPayload(terminal, request, List.of(
                map[TypewriterInput.CH_TRANS_FORWARD], map[TypewriterInput.CH_TRANS_BACKWARD],
                map[TypewriterInput.CH_TRANS_LEFT], map[TypewriterInput.CH_TRANS_RIGHT],
                map[TypewriterInput.CH_TRANS_UP], map[TypewriterInput.CH_TRANS_DOWN]));
    }

    private static TerminalKeyMapPayload read(FriendlyByteBuf buf) {
        UUID terminal = buf.readUUID();
        long request = buf.readLong();
        var keys = new ArrayList<Integer>(KEY_COUNT);
        for (int i = 0; i < KEY_COUNT; i++) keys.add((int) buf.readShort());
        return new TerminalKeyMapPayload(terminal, request, keys);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(terminal);
        buf.writeLong(request);
        for (int key : keys) buf.writeShort(key);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(TerminalKeyMapPayload packet, IPayloadContext context) {
        com.bridgecore.client.NavTerminalScreen.acceptKeyMap(packet);
    }
}
