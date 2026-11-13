package com.bridgecore.network;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.firecontrol.WeaponGroups;
import com.bridgecore.menu.FireControlMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record FireControlTurretNamePayload(int menuId, UUID turret, String name) implements CustomPacketPayload {
    public static final Type<FireControlTurretNamePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "fire_control_turret_name"));
    public static final StreamCodec<FriendlyByteBuf, FireControlTurretNamePayload> STREAM_CODEC =
            StreamCodec.of((buf, value) -> {
                buf.writeVarInt(value.menuId());
                buf.writeUUID(value.turret());
                buf.writeUtf(value.name(), WeaponGroups.NAME_LIMIT);
            }, buf -> new FireControlTurretNamePayload(buf.readVarInt(), buf.readUUID(),
                    buf.readUtf(WeaponGroups.NAME_LIMIT)));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(FireControlTurretNamePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.containerMenu instanceof FireControlMenu menu && menu.containerId == payload.menuId()) {
                menu.renameTurret(player, payload.turret(), payload.name());
            }
        });
    }
}
