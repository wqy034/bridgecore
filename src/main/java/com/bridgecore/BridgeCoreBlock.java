package com.bridgecore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * The bridge core is a helm: it has a FRONT. FACING = the direction the placing player
 * was looking = the ship's bow. All attitude input semantics are facing-relative
 * (bow/stern faces pitch, port/starboard yaw, up/down roll); thrust stays symmetric
 * (push toward the powered face) and needs no facing.
 */
public class BridgeCoreBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public BridgeCoreBlock(Properties properties) {
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
        // The placer's look direction is the bow.
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BridgeCoreBlockEntity(pos, state);
    }

    /**
     * Interaction semantics (handoff §8 unified):
     *   right-click                    = open the config GUI (gears / key mapping / HUD calib)
     *   sneak + right-click            = cycle control mode OFF -> STARSHIP -> DAMPING
     *   stick right-click              = toggle gravity compensation
     *   lever right-click              = switch redstone face meaning THRUST <-> TORQUE
     *   slime ball right-click         = starship coupled/decoupled toggle
     *   feather right-click            = altitude-follow toggle (pitch converts to climb/dive)
     *   blaze rod right-click          = keyboard pilot channel (Linked Typewriter direct read)
     *   (any other item right-click falls through to useWithoutItem -> opens the GUI)
     *
     * NOTE: sneak + item-in-hand skips the block interaction entirely (same
     * mechanism as sneak-placing against a chest) and the click is swallowed by
     * items with no use behavior of their own.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof BridgeCoreBlockEntity be) {
            if (player.isShiftKeyDown()) {
                player.sendSystemMessage(Component.literal(be.cycleMode()));
            } else {
                player.openMenu(be, pos);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Item-in-hand controls (kept off the empty-hand path). */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (stack.is(Items.STICK) || stack.is(Items.SLIME_BALL) || stack.is(Items.FEATHER)
                || stack.is(Items.BLAZE_ROD)) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof BridgeCoreBlockEntity be) {
                if (stack.is(Items.STICK)) {
                    player.sendSystemMessage(Component.literal(be.toggleGravityComp()));
                } else if (stack.is(Items.SLIME_BALL)) {
                    player.sendSystemMessage(Component.literal(be.toggleCoupled()));
                } else if (stack.is(Items.FEATHER)) {
                    player.sendSystemMessage(Component.literal(be.toggleAltitudeFollow()));
                } else {
                    player.sendSystemMessage(Component.literal(be.toggleKeyboardChannel()));
                }
            }
            return ItemInteractionResult.SUCCESS;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    /** World-side ticker: hosts the physics-frame watchdog (runs even when the
     *  structure is de-physicalized, unlike the Sable callbacks). */
    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != BCRegistry.BRIDGE_CORE_BE.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<BridgeCoreBlockEntity>) BridgeCoreBlockEntity::serverTick;
    }
}
