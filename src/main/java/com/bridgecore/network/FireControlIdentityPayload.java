package com.bridgecore.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.firecontrol.SectorArc;
import com.bridgecore.menu.FireControlMenu;
import com.bridgecore.menu.FireControlMenuActions;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Read-only names/equipment for an open core menu. Numeric live state stays
 * in ContainerData. Counts and strings are bounded independently of packet size;
 * menu id, roster epoch and full UUID tie these labels to the displayed rows.
 * Axis guns (轴炮, macro-cannon cores) ride the same snapshot since 3b. */
public record FireControlIdentityPayload(int menuId, int epoch, List<String> channels, List<Turret> turrets,
                                         List<AxisGun> guns)
        implements CustomPacketPayload {
    public static final int TEXT_LIMIT = 128;
    public record Mount(int no, String weapon, String location, @Nullable BlockPos block) {
        public Mount(int no, String weapon, String location) { this(no, weapon, location, null); }
        public Mount { weapon = text(weapon); location = text(location); }
    }
    public record Turret(UUID id, String name, String weapon, String yaw, String pitch,
                         String coreOffset, List<Mount> mounts, SectorArc sectors,
                         @Nullable BlockPos yawBlock, int yawRightSign) {
        public Turret(UUID id, String name, String weapon, String yaw, String pitch,
                      String coreOffset, List<Mount> mounts, SectorArc sectors) {
            this(id, name, weapon, yaw, pitch, coreOffset, mounts, sectors, null, -1);
        }
        public Turret {
            name = text(name); weapon = text(weapon); yaw = text(yaw); pitch = text(pitch);
            coreOffset = text(coreOffset); mounts = List.copyOf(mounts);
            if (mounts.size() > 4) throw new IllegalArgumentException("Too many mounts");
        }

        Turret withSectors(SectorArc arc) {
            return new Turret(id, name, weapon, yaw, pitch, coreOffset, mounts, arc, yawBlock, yawRightSign);
        }

        Turret withMounts(List<Mount> locations) {
            return new Turret(id, name, weapon, yaw, pitch, coreOffset, locations, sectors, yawBlock, yawRightSign);
        }

        Turret withBearing(@Nullable BlockPos block, int sign) {
            return new Turret(id, name, weapon, yaw, pitch, coreOffset, mounts, sectors, block, sign);
        }
    }
    /** Axis-gun identity: name + macro-core world location. Live status,
     *  energy and heat are ContainerData ints, not text. */
    public record AxisGun(UUID id, String name, String location) {
        public AxisGun { name = text(name); location = text(location); }
    }
    public FireControlIdentityPayload {
        channels = channels.stream().map(FireControlIdentityPayload::text).toList();
        turrets = List.copyOf(turrets);
        guns = List.copyOf(guns);
        if (channels.isEmpty() || channels.size() > com.bridgecore.firecontrol.WeaponGroups.MAX || turrets.size() > FireControlMenu.MAX_TURRETS
                || guns.size() > FireControlMenuActions.FIXED_GUNS
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
        buf.writeVarInt(channels.size());
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
        buf.writeVarInt(guns.size());
        for (AxisGun gun : guns) {
            buf.writeUUID(gun.id());
            buf.writeUtf(gun.name(), TEXT_LIMIT); buf.writeUtf(gun.location(), TEXT_LIMIT);
        }
        boolean mountInfo = turrets.stream().flatMap(turret -> turret.mounts().stream())
                .anyMatch(mount -> mount.block() != null);
        boolean bearingInfo = mountInfo || turrets.stream()
                .anyMatch(turret -> turret.yawBlock() != null || turret.yawRightSign() > 0);
        boolean sectors = bearingInfo || turrets.stream().anyMatch(turret -> turret.sectors().enabled());
        buf.writeByte((sectors ? 1 : 0) | (bearingInfo ? 2 : 0) | (mountInfo ? 4 : 0));
        if (sectors) {
            for (var turret : turrets) FireControlSectorPayload.writeArc(buf, turret.sectors());
        }
        if (bearingInfo) {
            for (var turret : turrets) {
                buf.writeNullable(turret.yawBlock(), (out, pos) -> out.writeBlockPos(pos));
                buf.writeBoolean(turret.yawRightSign() > 0);
            }
        }
        if (mountInfo) {
            for (var turret : turrets) {
                for (var mount : turret.mounts()) buf.writeNullable(mount.block(), (out, pos) -> out.writeBlockPos(pos));
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
        int groups = count(buf, com.bridgecore.firecontrol.WeaponGroups.MAX);
        List<String> channels = new ArrayList<>(groups);
        for (int i = 0; i < groups; i++) channels.add(buf.readUtf(TEXT_LIMIT));
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
            turrets.add(new Turret(id, name, weapon, yaw, pitch, offset, mounts, SectorArc.UNIFORM));
        }
        List<AxisGun> guns = new ArrayList<>();
        int gunRows = count(buf, FireControlMenuActions.FIXED_GUNS);
        for (int i = 0; i < gunRows; i++) {
            guns.add(new AxisGun(buf.readUUID(), buf.readUtf(TEXT_LIMIT), buf.readUtf(TEXT_LIMIT)));
        }
        int sections = buf.readUnsignedByte();
        if (sections != 0 && sections != 1 && sections != 3 && sections != 7) {
            throw new DecoderException("Invalid fire-control identity sections");
        }
        if ((sections & 1) != 0) {
            for (int i = 0; i < rows; i++) turrets.set(i, turrets.get(i).withSectors(FireControlSectorPayload.readArc(buf)));
        }
        if ((sections & 2) != 0) {
            for (int i = 0; i < rows; i++) {
                var block = buf.readNullable(input -> input.readBlockPos());
                turrets.set(i, turrets.get(i).withBearing(block, buf.readBoolean() ? 1 : -1));
            }
        }
        if ((sections & 4) != 0) {
            for (int i = 0; i < rows; i++) {
                var turret = turrets.get(i);
                var mounts = new ArrayList<Mount>(turret.mounts().size());
                for (var mount : turret.mounts()) {
                    mounts.add(new Mount(mount.no(), mount.weapon(), mount.location(),
                            buf.readNullable(input -> input.readBlockPos())));
                }
                turrets.set(i, turret.withMounts(mounts));
            }
        }
        return new FireControlIdentityPayload(menuId, epoch, channels, turrets, guns);
    }
    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(FireControlIdentityPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().containerMenu instanceof FireControlMenu menu && menu.containerId == payload.menuId())
                menu.acceptIdentity(payload);
        });
    }
}
