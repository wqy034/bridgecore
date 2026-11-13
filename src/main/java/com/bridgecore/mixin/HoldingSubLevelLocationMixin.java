package com.bridgecore.mixin;

import com.bridgecore.nav.NavWakeProbe;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = HoldingSubLevel.class, remap = false)
public abstract class HoldingSubLevelLocationMixin implements NavWakeProbe.HoldingLocation {
    @Unique
    private @Nullable ChunkPos bridgecore$holdingChunk;

    @Override
    public @Nullable ChunkPos bridgecore$holdingChunk() {
        return bridgecore$holdingChunk;
    }

    @Override
    public void bridgecore$setHoldingChunk(ChunkPos pos) {
        bridgecore$holdingChunk = pos;
    }
}
