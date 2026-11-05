package com.bridgecore.display;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Screen tile block. FACING (full six directions, floor/ceiling screens included)
 * = the direction the display face points; placement faces the player.
 *
 * The tile is dumb: right-clicks are forwarded to the owning GPU, and structural
 * changes (this tile removed, or a tile/GPU appearing next to a connected tile)
 * poke the GPU to rescan its array — no manual refresh.
 */
public class DisplayTileBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public DisplayTileBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DisplayTileBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DisplayTileBlockEntity tile) {
            tile.onBlockActivated(hitResult.getDirection(),
                    hitResult.getLocation().x - pos.getX(),
                    hitResult.getLocation().y - pos.getY(),
                    hitResult.getLocation().z - pos.getZ(),
                    player.isSecondaryUseActive(), player);
        }
        return state.getValue(FACING) == hitResult.getDirection()
                ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /** A connected tile's neighborhood changed (tile/GPU added or state-changed
     *  next to it): have the owning GPU rescan. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DisplayTileBlockEntity tile
                && tile.gpuPos() != null) {
            notifyGpu(level, tile.gpuPos());
        }
    }

    /** Tile removed: read the GPU link before the BE goes away, then rescan and
     *  clear the clients' texture for this position (a re-placed tile must not
     *  inherit the previous tenant's last frame). */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        BlockPos gpu = null;
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof DisplayTileBlockEntity tile) {
            gpu = tile.gpuPos();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            DisplayGpuBlockEntity.broadcastClear(level, pos);
            if (gpu != null) {
                notifyGpu(level, gpu);
            }
        }
    }

    private static void notifyGpu(Level level, BlockPos gpuPos) {
        if (level.getBlockEntity(gpuPos) instanceof DisplayGpuBlockEntity gpu) {
            gpu.markMonitorsDirty();
        }
    }
}
