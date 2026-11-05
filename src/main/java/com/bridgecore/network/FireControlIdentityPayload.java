package com.bridgecore.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.menu.FireControlMenu;
import com.bridgecore.menu.FireControlMenuActions;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/** Read-only names/equipment for an open core menu. Numeric live state stays
 * in ContainerData. Counts and strings are bounded independently of packet size;
 * menu id, roster epoch and full UUID tie these labels to the displayed rows. */
public record FireControlIdentityPayload(int menuId, int epoch, List<String> channels, List<Turret> turrets)
        implements CustomPacketPayload {
    public static final int TEXT_LIMIT = 128;
    public record Mount(int no, String weapon, String location) {
        public Mount { weapon = text(weapon); location = text(location); }
    }
    public record Turret(UUID id, String name, String weapon, String yaw, String pitch,
                         String coreOffset, List<Mount> mounts) {
        public Turret {
            name = text(name); weapon = text(weapon); yaw = text(yaw); pitch = text(pitch);
            coreOffset = text(coreOffset); mounts = List.copyOf(mounts);
            if (mounts.size() > 4) throw new IllegalArgumentException("Too many mounts");
        }
    }
    public FireControlIdentityPayload {
        channels = channels.stream().map(FireControlIdentityPayload::text).toList();
        turrets = List.copyOf(turrets);
        if (channels.size() != 4 || turrets.size() > FireControlMenu.MAX_TURRETS
                || epoch < 0 || epoch > FireControlMenuActions.EPOCH_MASK)
            throw new IllegalArgumentException("Invalid identity snapshot");
    }
    private static String text(String value) {
        if (value == null) return "";
        // Avoid cutting a surrogate pair at the wire limit.
        int end = Math.min(value.length(), TEXT_LIMIT);
        if (end < value.length() && end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end).replace('\n', ' ').replace('\r', ' ');
    }
    public static final Type<FireControlIdentityPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "fire_control_identity"));
    public static final StreamCodec<FriendlyByteBuf, FireControlIdentityPayload> STREAM_CODEC =
            StreamCodec.of((buf, value) -> value.write(buf), FireControlIdentityPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(menuId); buf.writeVarInt(epoch);
        for (String channel : channels) buf.writeUtf(channel, TEXT_LIMIT);
        buf.writeVarInt(turrets.size());
        for (Turret turret : turrets) {
            buf.writeUUID(turret.id());
            buf.writeUtf(turret.name(), TEXT_LIMIT); buf.writeUtf(turret.weapon(), TEXT_LIMIT);
            buf.writeUtf(turret.yaw(), TEXT_LIMIT); buf.writeUtf(turret.pitch(), TEXT_LIMIT);
            buf.writeUtf(turret.coreOffset(), TEXT_LIMIT);
            buf.writeVarInt(turret.mounts().size());
            for (Mount mount : turret.mounts()) {
                buf.writeVarInt(mount.no()); buf.writeUtf(mount.weapon(), TEXT_LIMIT);
                buf.writeUtf(mount.location(), TEXT_LIMIT);
            }
        }
    }
    private static int count(FriendlyByteBuf buf, int max) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) throw new DecoderException("Invalid fire-control identity count");
        return count;
    }
    private static FireControlIdentityPayload read(FriendlyByteBuf buf) {
        int menuId = buf.readVarInt(), epoch = count(buf, FireControlMenuActions.EPOCH_MASK);
        List<String> channels = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) channels.add(buf.readUtf(TEXT_LIMIT));
        List<Turret> turrets = new ArrayList<>();
        int rows = count(buf, FireControlMenu.MAX_TURRETS);
        for (int i = 0; i < rows; i++) {
            UUID id = buf.readUUID();
            String name = buf.readUtf(TEXT_LIMIT), weapon = buf.readUtf(TEXT_LIMIT);
            String yaw = buf.readUtf(TEXT_LIMIT), pitch = buf.readUtf(TEXT_LIMIT), offset = buf.readUtf(TEXT_LIMIT);
            List<Mount> mounts = new ArrayList<>();
            int size = count(buf, 4);
            for (int m = 0; m < size; m++) mounts.add(new Mount(buf.readVarInt(),
                    buf.readUtf(TEXT_LIMIT), buf.readUtf(TEXT_LIMIT)));
            turrets.add(new Turret(id, name, weapon, yaw, pitch, offset, mounts));
        }
        return new FireControlIdentityPayload(menuId, epoch, channels, turrets);
    }
    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(FireControlIdentityPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().containerMenu instanceof FireControlMenu menu && menu.containerId == payload.menuId())
                menu.acceptIdentity(payload);
        });
    }
}
