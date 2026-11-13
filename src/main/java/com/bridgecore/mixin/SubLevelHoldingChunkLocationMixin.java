package com.bridgecore.mixin;

import com.bridgecore.nav.NavWakeProbe;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SubLevelHoldingChunk.class, remap = false)
public abstract class SubLevelHoldingChunkLocationMixin {
    @Shadow
    public abstract ChunkPos getChunkPos();

    @Inject(method = "acceptHoldingSubLevel", at = @At("TAIL"))
    private void bridgecore$recordHoldingChunk(HoldingSubLevel holding, CallbackInfo ci) {
        ((NavWakeProbe.HoldingLocation) (Object) holding).bridgecore$setHoldingChunk(getChunkPos());
    }
}
