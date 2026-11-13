package com.bridgecore.network;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.firecontrol.SectorArc;
import com.bridgecore.menu.FireControlMenu;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.UUID;

public record FireControlSectorPayload(int menuId, UUID turret, SectorArc expected, SectorArc replacement)
        implements CustomPacketPayload {
    public static final Type<FireControlSectorPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "fire_control_sectors"));
    public static final StreamCodec<FriendlyByteBuf, FireControlSectorPayload> STREAM_CODEC =
            CustomPacketPayload.codec(FireControlSectorPayload::write, FireControlSectorPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(menuId);
        buf.writeUUID(turret);
        writeArc(buf, expected);
        writeArc(buf, replacement);
    }

    private static FireControlSectorPayload read(FriendlyByteBuf buf) {
        return new FireControlSectorPayload(buf.readVarInt(), buf.readUUID(), readArc(buf), readArc(buf));
    }

    // A disabled endpoint uses a disjoint range in the signed yaw field.
    private static final int DISABLED_YAW_OFFSET = 512;

    static void writeArc(FriendlyByteBuf buf, SectorArc arc) {
        buf.writeByte(arc.endpoints().size());
        for (var point : arc.endpoints()) {
            buf.writeShort(point.enabled() ? point.yaw() : point.yaw() + DISABLED_YAW_OFFSET);
            buf.writeByte(point.lower());
            buf.writeByte(point.upper());
        }
    }

    static SectorArc readArc(FriendlyByteBuf buf) {
        int count = buf.readUnsignedByte();
        if (count == 0) return SectorArc.UNIFORM;
        if (count == 1 || count > SectorArc.MAX_ENDPOINTS) throw new DecoderException("Invalid sector count");
        try {
            var points = new ArrayList<SectorArc.Endpoint>(count);
            for (int i = 0; i < count; i++) {
                int yaw = buf.readShort();
                boolean enabled = yaw < DISABLED_YAW_OFFSET - 180;
                points.add(new SectorArc.Endpoint(enabled ? yaw : yaw - DISABLED_YAW_OFFSET,
                        buf.readByte(), buf.readByte(), enabled));
            }
            return new SectorArc(points);
        } catch (IllegalArgumentException invalid) {
            throw new DecoderException("Invalid sector arc", invalid);
        }
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(FireControlSectorPayload packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.containerMenu instanceof FireControlMenu menu && menu.containerId == packet.menuId()) {
                menu.replaceSectorArc(player, packet.turret(), packet.expected(), packet.replacement());
            }
        });
    }
}
