package com.bridgecore.firecontrol;

import java.util.UUID;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * One turret under a fire-control core (multi-turret architecture v1 —
 * docs/multi-turret-architecture-v1.md): identity + display name + the
 * actuator (pairing data / calibration / AxisLock heartbeat / per-tick drive).
 * The per-turret parameter table (ballistics, field-of-fire limits) lands in
 * P2; v1 shares the core's single locked target across all units.
 */
public final class TurretUnit {

    private final UUID id;
    private String name;
    private final SwivelTurretActuator actuator;
    /** One bit per group this turret belongs to. */
    private int groups;

    public TurretUnit(UUID id, String name, Runnable markDirty, Supplier<BlockPos> corePos) {
        this.id = id;
        this.name = name;
        this.actuator = new SwivelTurretActuator(markDirty, corePos);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void rename(String n) {
        name = n;
    }

    public int groups() { return groups; }

    public boolean belongsTo(int group) { return (groups & WeaponGroups.bit(group)) != 0; }

    public void setGroups(int groups) { this.groups = groups; }


    public SwivelTurretActuator actuator() {
        return actuator;
    }

    public void save(CompoundTag tag) {
        tag.putUUID("unitId", id);
        tag.putString("unitName", name);
        tag.putInt("unitGroups", groups);
        actuator.save(tag);
    }

    public static TurretUnit load(CompoundTag tag, Runnable markDirty, Supplier<BlockPos> corePos) {
        UUID id = FireControlCoreBlockEntity.hasUuid4(tag, "unitId") ? tag.getUUID("unitId") : UUID.randomUUID();
        String name = tag.contains("unitName") ? tag.getString("unitName") : "炮塔";
        TurretUnit u = new TurretUnit(id, name, markDirty, corePos);
        u.groups = tag.contains("unitGroups") ? tag.getInt("unitGroups")
                : WeaponGroups.bit(tag.contains("unitChannel") ? tag.getInt("unitChannel") : -1);
        u.actuator().load(tag);
        return u;
    }

    /** False while a fire-arc clamp pins a joint (target outside the arc) —
     *  the aggregate fire gate must read 禁射 (P2). */
    public boolean inArc() {
        return actuator.isInArc();
    }

    /** Aggregate display helpers used by the panel/chat status. */
    public boolean isReady() {
        return actuator.isReady();
    }

    public double aimErrorDeg() {
        return actuator.getAimingErrorDeg();
    }

    public @Nullable String statusLine() {
        return actuator.statusLine();
    }
}
