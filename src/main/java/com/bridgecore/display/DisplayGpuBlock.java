package com.bridgecore.display;

import com.bridgecore.BCRegistry;
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
 * Display controller block: the brain of a tiled screen. Place it directly
 * against the tile array (any horizontal side of a corner tile). It has no
 * facing of its own — the array's facing defines the screen.
 *
 * The server ticker exists solely to consume rescan requests raised by
 * neighbor changes / tile removal (one rescan per tick at most, however many
 * blocks changed).
 *
 * Right-click: opens the app-selection screen (2026-09-18, user-ruled GUI over
 * the hardcoded content cycle) — the server sends the open packet carrying the
 * current app id; the client posts its pick back for a validated setApp.
 */
public class DisplayGpuBlock extends Block implements EntityBlock {

    public DisplayGpuBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DisplayGpuBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer sp
                && level.getBlockEntity(pos) instanceof DisplayGpuBlockEntity gpu) {
            gpu.openSettings(sp);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        if (!level.isClientSide && neighborBlock instanceof DisplayTileBlock
                && level.getBlockEntity(pos) instanceof DisplayGpuBlockEntity gpu) {
            gpu.markMonitorsDirty();
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof DisplayGpuBlockEntity gpu) {
            gpu.disconnectAll();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != BCRegistry.DISPLAY_GPU_BE.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<DisplayGpuBlockEntity>)
                DisplayGpuBlockEntity::serverTick;
    }
}
