package com.bridgecore.firecontrol;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * One hull-fixed gun (轴炮) registered directly under a fire-control core —
 * docs/axis-gun-design.md: an fe_railgun MACRO cannon core (宏炮核心, its own
 * core block — NOT a CBC-style cannon mount) sitting on the SAME main
 * structure as the fire-control core, no bearings; the ship is the carriage
 * and aiming is the flight core's pointing service. Integration goes through
 * RailgunLink.macroAt (FEGunApi/FEMacroHandle), never MountLinks.
 *
 * <p>Deliberately NOT a TurretUnit: that class hard-binds
 * SwivelTurretActuator, and axis guns have no joints to drive.
 * Membership uses the same group mask as turrets.
 */
public final class FixedGun {

    private final UUID id;
    private final String name;
    private final BlockPos gunPos;
    private int groups;

    public FixedGun(UUID id, String name, BlockPos gunPos) {
        this.id = id;
        this.name = name;
        this.gunPos = gunPos.immutable();
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** Macro-core position in the clicked level's (yard) frame — same
     *  convention as SwivelTurretActuator.Mount positions. */
    public BlockPos gunPos() {
        return gunPos;
    }

    public int groups() { return groups; }

    public boolean belongsTo(int group) { return (groups & WeaponGroups.bit(group)) != 0; }

    public void setGroups(int groups) { this.groups = groups; }


    public void save(CompoundTag tag) {
        tag.putUUID("gunId", id);
        tag.putString("gunName", name);
        tag.putLong("gunPos", gunPos.asLong());
        tag.putInt("gunGroups", groups);
    }

    public static FixedGun load(CompoundTag tag) {
        UUID id = FireControlCoreBlockEntity.hasUuid4(tag, "gunId") ? tag.getUUID("gunId") : UUID.randomUUID();
        String name = tag.contains("gunName") ? tag.getString("gunName") : "轴炮";
        FixedGun gun = new FixedGun(id, name, BlockPos.of(tag.getLong("gunPos")));
        gun.groups = tag.contains("gunGroups") ? tag.getInt("gunGroups")
                : WeaponGroups.bit(tag.contains("gunChannel") ? tag.getInt("gunChannel") : -1);
        return gun;
    }

    /** "轴炮 N" numbering mirrors nextTurretName(): lowest free number,
     *  deletions never renumber the survivors. */
    public static String nextName(Iterable<FixedGun> existing) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (FixedGun g : existing) names.add(g.name());
        for (int i = 1;; i++) {
            String n = "轴炮 " + i;
            if (!names.contains(n)) return n;
        }
    }
}
