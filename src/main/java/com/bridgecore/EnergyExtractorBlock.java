package com.bridgecore;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Energy extractor block: burns fuel into FE for the structure's momentum
 * distributors. LIT mirrors the furnace convention (driven by the burn state).
 *
 * Fuel input: right-click with a burnable item inserts one unit into an empty
 * slot (consumed in survival, kept in creative — vanilla convention), or via
 * the item-handler capability (hopper etc.). Non-fuel items fall through
 * (PASS_TO_DEFAULT_BLOCK_INTERACTION) to useWithoutItem, which opens the GUI.
 */
public class EnergyExtractorBlock extends Block implements EntityBlock {
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public EnergyExtractorBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(LIT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EnergyExtractorBlockEntity(pos, state);
    }

    /** Right-click with fuel = insert one unit (empty slot only); burning starts
     *  on the next serverTick on its own. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (EnergyExtractorBlockEntity.isFuel(stack)) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof EnergyExtractorBlockEntity be) {
                String refusal = be.insertFuel(stack);
                if (refusal == null) {
                    if (!player.getAbilities().instabuild) {
                        stack.shrink(1);
                    }
                    player.sendSystemMessage(Component.literal(
                            "[bridgecore] fuel inserted: " + stack.getHoverName().getString()));
                } else {
                    player.sendSystemMessage(Component.literal(refusal));
                }
            }
            return ItemInteractionResult.SUCCESS;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    /** Empty-hand / non-fuel right-click opens the GUI. The menu binds the BE
     *  directly (position is only used to locate the BE client-side), so Sable
     *  shipyard coordinates need no special handling here. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof EnergyExtractorBlockEntity be) {
            player.openMenu(be, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != BCRegistry.ENERGY_EXTRACTOR_BE.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<EnergyExtractorBlockEntity>)
                EnergyExtractorBlockEntity::serverTick;
    }
}

