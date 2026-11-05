package com.bridgecore.mixin;

import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Tweaked Controllers' lectern range check ship-aware. Upstream
 * {@code playerInRange} measures plain world distance to the lectern's raw
 * BlockPos (0.4x reach, feet position) — on a Sable ship the player separates
 * from it once the ship rotates, and TC force-drops the "using" state
 * mid-flight (observed 2026-09-03: control disconnects past ~30 deg of
 * pitch/roll, standing or seated). Mirrors aeronautics' own Linked Typewriter
 * check instead: {@code Sable.HELPER.distanceSquaredWithSubLevels} with eye
 * position and FULL block-interaction reach — battle-proven on the same
 * physics engine; TC's tighter parameters do not absorb the sublevel
 * transform's systematic offset under rotation.
 *
 * Target mod is an optional dependency: this mixin is gated by
 * {@link BridgeCoreMixinPlugin} on the target class actually being present.
 */
@Mixin(targets = "com.getitemfromblock.create_tweaked_controllers.block.TweakedLecternControllerBlockEntity")
public abstract class TweakedLecternRangeMixin {

    @Inject(method = "playerInRange", at = @At("HEAD"), cancellable = true)
    private static void bridgecore$shipAwareRange(Player player, Level world, BlockPos pos,
                                                  CallbackInfoReturnable<Boolean> cir) {
        double reach = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        cir.setReturnValue(Sable.HELPER.distanceSquaredWithSubLevels(world, player.getEyePosition(),
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < reach * reach);
    }
}
