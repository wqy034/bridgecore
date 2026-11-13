package com.bridgecore.network;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.firecontrol.WeaponGroups;
import com.bridgecore.menu.FireControlMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Rename a stable group index in the currently open fire-control menu. */
public record FireControlGroupNamePayload(int menuId, int group, String name) implements CustomPacketPayload {
    public static final Type<FireControlGroupNamePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "fire_control_group_name"));
    public static final StreamCodec<FriendlyByteBuf, FireControlGroupNamePayload> STREAM_CODEC =
            StreamCodec.of((buf, value) -> {
                buf.writeVarInt(value.menuId());
                buf.writeVarInt(value.group());
                buf.writeUtf(value.name(), WeaponGroups.NAME_LIMIT);
            }, buf -> new FireControlGroupNamePayload(buf.readVarInt(), buf.readVarInt(),
                    buf.readUtf(WeaponGroups.NAME_LIMIT)));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(FireControlGroupNamePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().containerMenu instanceof FireControlMenu menu && menu.containerId == payload.menuId())
                menu.renameGroup(context.player(), payload.group(), payload.name());
        });
    }
}
