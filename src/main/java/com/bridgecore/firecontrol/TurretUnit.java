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
    /** Weapon-channel membership (P3 武器频道): 0..CHANNEL_COUNT-1, -1 = 未编组
     *  (ungrouped turrets answer to no channel — neither volley nor auto-fire). */
    private int channel = -1;

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

    public int channel() {
        return channel;
    }

    /** Exclusive membership: entering a channel leaves the previous one. */
    public void setChannel(int ch) {
        channel = ch;
    }

    public SwivelTurretActuator actuator() {
        return actuator;
    }

    public void save(CompoundTag tag) {
        tag.putUUID("unitId", id);
        tag.putString("unitName", name);
        tag.putInt("unitChannel", channel);
        actuator.save(tag);
    }

    public static TurretUnit load(CompoundTag tag, Runnable markDirty, Supplier<BlockPos> corePos) {
        UUID id = tag.hasUUID("unitId") ? tag.getUUID("unitId") : UUID.randomUUID();
        String name = tag.contains("unitName") ? tag.getString("unitName") : "炮塔";
        TurretUnit u = new TurretUnit(id, name, markDirty, corePos);
        if (tag.contains("unitChannel")) u.channel = tag.getInt("unitChannel");
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
