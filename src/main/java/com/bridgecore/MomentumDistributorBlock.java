package com.bridgecore;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Momentum distributor block: the structure's FE pool. Right-click opens the GUI.
 */
public class MomentumDistributorBlock extends Block implements EntityBlock {
    public MomentumDistributorBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MomentumDistributorBlockEntity(pos, state);
    }

    /** The menu binds the BE
     *  directly, so Sable shipyard coordinates need no special handling here. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof MomentumDistributorBlockEntity be) {
            player.openMenu(be, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** World-side ticker: keeps the CoreDirectory membership current. */
    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != BCRegistry.MOMENTUM_DISTRIBUTOR_BE.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<MomentumDistributorBlockEntity>)
                MomentumDistributorBlockEntity::serverTick;
    }
}
