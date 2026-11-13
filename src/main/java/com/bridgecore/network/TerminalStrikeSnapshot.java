package com.bridgecore.network;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.client.NavTerminalScreen;
import com.bridgecore.firecontrol.StrikePreview;
import com.bridgecore.firecontrol.WeaponGroups;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One directed response carrying the fire-control roster and an optional preview. */
public record TerminalStrikeSnapshot(UUID terminal, UUID request, BlockPos core, int revision,
                                     List<String> channels, List<StrikePreview.Row> rows, String message,
                                     int executableWeapons, int axialChannels)
        implements CustomPacketPayload {
    private static final int TEXT_LIMIT = 96;
    public static final Type<TerminalStrikeSnapshot> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "terminal_strike_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, TerminalStrikeSnapshot> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalStrikeSnapshot::write, TerminalStrikeSnapshot::read);

    public TerminalStrikeSnapshot(UUID terminal, UUID request, BlockPos core, int revision,
                                  List<String> channels, List<StrikePreview.Row> rows, String message) {
        this(terminal, request, core, revision, channels, rows, message, 0, 0);
    }

    public boolean axialChannel(int channel) {
        return (axialChannels & WeaponGroups.bit(channel)) != 0;
    }

    private static TerminalStrikeSnapshot read(FriendlyByteBuf buf) {
        UUID terminal = buf.readUUID();
        UUID request = buf.readUUID();
        BlockPos core = buf.readBlockPos();
        int revision = buf.readInt();
        int count = readCount(buf, WeaponGroups.MAX);
        List<String> channels = new ArrayList<>(count);
        for (int i = 0; i < count; i++) channels.add(buf.readUtf(TEXT_LIMIT));
        List<StrikePreview.Row> rows = readRows(buf);
        String message = buf.readUtf(TEXT_LIMIT);
        int executable = readCount(buf, StrikePreview.MAX_ROWS);
        int axialChannels = buf.readInt() & WeaponGroups.validMask(count);
        return new TerminalStrikeSnapshot(terminal, request, core, revision, channels, rows, message, executable, axialChannels);
    }

    private static int readCount(FriendlyByteBuf buf, int max) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) throw new DecoderException("Invalid strike snapshot count");
        return count;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(terminal);
        buf.writeUUID(request);
        buf.writeBlockPos(core);
        buf.writeInt(revision);
        buf.writeVarInt(channels.size());
        for (String channel : channels) writeText(buf, channel);
        writeRows(buf, rows);
        writeText(buf, message);
        buf.writeVarInt(executableWeapons);
        buf.writeInt(axialChannels);
    }

    static List<StrikePreview.Row> readRows(FriendlyByteBuf buf) {
        int count = readCount(buf, StrikePreview.MAX_ROWS);
        List<StrikePreview.Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new StrikePreview.Row(buf.readUtf(TEXT_LIMIT), buf.readUtf(TEXT_LIMIT), buf.readUtf(TEXT_LIMIT),
                    buf.readBoolean()));
        }
        return rows;
    }

    static void writeRows(FriendlyByteBuf buf, List<StrikePreview.Row> rows) {
        buf.writeVarInt(rows.size());
        for (var row : rows) {
            writeText(buf, row.name());
            writeText(buf, row.weapon());
            writeText(buf, row.reason());
            buf.writeBoolean(row.eligible());
        }
    }

    private static void writeText(FriendlyByteBuf buf, String value) {
        int end = Math.min(value.length(), TEXT_LIMIT);
        if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        buf.writeUtf(value.substring(0, end).replace('\n', ' ').replace('\r', ' '), TEXT_LIMIT);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(TerminalStrikeSnapshot packet, IPayloadContext context) {
        if (!com.bridgecore.client.NavTerminalQuickStrike.accept(packet)) NavTerminalScreen.acceptStrikeSnapshot(packet);
    }
}
