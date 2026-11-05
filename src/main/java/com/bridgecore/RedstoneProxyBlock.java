package com.bridgecore;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Redstone proxy (2026-09-02, architecture §2 input layer phase 1; merged to a
 * single block 2026-09-18 per user ruling): the bridge core's body does not
 * take redstone in the product design — proxies do. ONE block with a blockstate
 * {@code mode} switching the semantics: ATTITUDE (six faces -> torque axes) or
 * TRANSLATION (six faces -> thrust).
 *
 * Interaction: right-click cycles the mode (actionbar feedback; the blockstate
 * also swaps the texture — attitude red / translation orange); SNEAK +
 * right-click cycles the structure core's thrust gear (the old translation
 * proxy's test hook, moved here).
 *
 * The block is deliberately dumb: it polls its own six faces and forwards the
 * raw strengths to every core on the same structure (CoreDirectory). All
 * semantics — bow-relative axis mapping, hover coupling, pricing — stay in the
 * core, which is why the proxy has no FACING of its own.
 */
public class RedstoneProxyBlock extends Block implements EntityBlock {
    /** Face semantics; also the blockstate property values (serialized lowercase). */
    public enum Kind implements StringRepresentable {
        ATTITUDE, TRANSLATION;

        @Override
        public @NotNull String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final EnumProperty<Kind> MODE = EnumProperty.create("mode", Kind.class);

    public RedstoneProxyBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(MODE, Kind.ATTITUDE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(MODE);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RedstoneProxyBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        // M2 test-grade gear shifting (was plain right-click on the translation
        // proxy; moved to sneak-click when the two proxies merged). Production:
        // typewriter key / UI.
        if (player.isShiftKeyDown()) {
            if (Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel sub) {
                var cores = CoreDirectory.coresOf(sub);
                if (!cores.isEmpty()) {
                    player.displayClientMessage(Component.literal(cores.get(0).cycleGear()), true);
                }
            }
            return InteractionResult.SUCCESS;
        }
        Kind next = state.getValue(MODE) == Kind.ATTITUDE ? Kind.TRANSLATION : Kind.ATTITUDE;
        level.setBlock(pos, state.setValue(MODE, next), Block.UPDATE_ALL);
        player.displayClientMessage(Component.translatable(
                "block.bridgecore.redstone_proxy.mode." + next.getSerializedName()), true);
        return InteractionResult.SUCCESS;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != BCRegistry.REDSTONE_PROXY_BE.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<RedstoneProxyBlockEntity>) RedstoneProxyBlockEntity::serverTick;
    }
}
