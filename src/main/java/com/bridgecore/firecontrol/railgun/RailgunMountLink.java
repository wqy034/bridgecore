package com.bridgecore.firecontrol.railgun;

import org.jetbrains.annotations.Nullable;

import com.bridgecore.firecontrol.MountLink;
import com.bridgecore.firecontrol.ballistics.SolveChain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * fe_railgun implementation of {@link MountLink} (火力指挥层②, 2026-10-08):
 * a stateless singleton delegating to {@link RailgunLink}'s guarded statics —
 * the opaque handles stay opaque, fe_railgun types are never referenced from
 * this class, so loading it while the mod is absent is as safe as calling
 * RailgunLink directly (every delegate short-circuits on available()).
 */
public final class RailgunMountLink implements MountLink {

    public static final RailgunMountLink INSTANCE = new RailgunMountLink();

    private RailgunMountLink() {}

    @Override
    public boolean available() {
        return RailgunLink.available();
    }

    @Override
    public @Nullable Object mountAt(Level level, BlockPos pos) {
        return RailgunLink.mountAt(level, pos);
    }

    @Override
    public int mountStatusCode(@Nullable Object handle) {
        return RailgunLink.mountStatusCode(handle);
    }

    @Override
    public @Nullable String fire(@Nullable Object handle) {
        return RailgunLink.fire(handle);
    }

    @Override
    public boolean hasGun(@Nullable Object handle) {
        return RailgunLink.hasGun(handle);
    }

    @Override
    public boolean breechless(@Nullable Object handle) {
        return RailgunLink.breechless(handle);
    }

    @Override
    public String signature(@Nullable Object handle) {
        return RailgunLink.signature(handle);
    }

    @Override
    public SolveChain.Params paramsFor(@Nullable Object handle, SolveChain.Params base,
                                       double barrelLen) {
        return RailgunLink.paramsFor(handle, base, barrelLen);
    }

    @Override
    public String gunTypeName(@Nullable Object handle) {
        return RailgunLink.gunTypeName(handle);
    }

    @Override
    public double muzzleVelocity(@Nullable Object handle) {
        return RailgunLink.muzzleVelocity(handle);
    }

    @Override
    public @Nullable EnergyReading energyReading(@Nullable Object handle) {
        if (handle == null || !available()) return null;
        long capacity = RailgunLink.energyCapacity(handle);
        return capacity > 0 ? new EnergyReading(RailgunLink.energyAvailable(handle), capacity) : null;
    }

    @Override
    public double heat(@Nullable Object handle) {
        return RailgunLink.heat(handle);
    }

    @Override
    public double maxHeat(@Nullable Object handle) {
        return RailgunLink.maxHeat(handle);
    }
}
