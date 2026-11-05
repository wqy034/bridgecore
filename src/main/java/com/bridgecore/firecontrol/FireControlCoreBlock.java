package com.bridgecore.firecontrol;

import org.jetbrains.annotations.Nullable;

import com.bridgecore.BCRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Fire-control core block (roadmap §4): independent block, 20 Hz game-tick slow
 * loop, works standalone on shore batteries (no flight core required).
 *
 * <p>Chat-only interaction (monitor/display system comes later):
 * right-click prints the radar list; sneak+right-click cycles the lock through
 * candidates (wrap to unlock); stick+right-click toggles the whitelist on the
 * locked target; compass+right-click runs the turret-pairing flow (tap the
 * core again before recording a bearing to cancel). Turret management
 * (unpair) lives on the display panel's [TURRETS] page — sneak+compass can
 * NOT be used for it: sneaking skips block interaction in the vanilla
 * pipeline, so that combo never reaches this block.
 */
public class FireControlCoreBlock extends Block implements EntityBlock {

    public FireControlCoreBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FireControlCoreBlockEntity(pos, state);
    }

    /** Stick = whitelist toggle on the locked target; anything else falls
     *  through to useWithoutItem. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (stack.is(Items.STICK)) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof FireControlCoreBlockEntity be) {
                be.toggleWhitelistOfLocked(player);
            }
            return ItemInteractionResult.SUCCESS;
        }
        if (TurretPairing.isPairingTool(stack)) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof FireControlCoreBlockEntity be
                    && player instanceof ServerPlayer sp) {
                if (!TurretPairing.tryComplete(sp, be)) {
                    TurretPairing.begin(sp, pos);
                }
            }
            return ItemInteractionResult.SUCCESS;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof FireControlCoreBlockEntity be) {
            if (player.isShiftKeyDown()) {
                be.cycleLock(player);
            } else {
                player.openMenu(be, pos);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != BCRegistry.FIRE_CONTROL_CORE_BE.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<FireControlCoreBlockEntity>)
                FireControlCoreBlockEntity::serverTick;
    }
}
