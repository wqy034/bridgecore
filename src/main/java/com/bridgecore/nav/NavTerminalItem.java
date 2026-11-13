package com.bridgecore.nav;

import com.bridgecore.BridgeCoreBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Navigation terminal (auto-navigation-research.md v2 §4). Right-click a flight
 * core = bind (single-permission set); right-click anywhere else = open the
 * terminal screen. The core accepts commands ONLY from its bound terminal.
 */
public class NavTerminalItem extends Item {
    public NavTerminalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (!(level.getBlockEntity(ctx.getClickedPos()) instanceof BridgeCoreBlockEntity core)) {
            return InteractionResult.PASS;
        }
        // 只有潜行右键 = 绑定；普通右键放行给核心自己的 GUI（2026-11-08 用户裁决）。
        // 解绑在核心 GUI 里（按钮），不走道具侧。
        if (ctx.getPlayer() == null || !ctx.getPlayer().isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        Player player = ctx.getPlayer();
        ItemStack stack = ctx.getItemInHand();
        UUIDBindResult result = bind(core, stack, player);
        return result == UUIDBindResult.FAIL ? InteractionResult.FAIL : InteractionResult.CONSUME;
    }

    private enum UUIDBindResult { OK, FAIL }

    private static UUIDBindResult bind(BridgeCoreBlockEntity core, ItemStack stack, Player player) {
        var uuid = NavTerminalData.terminalUuid(stack);
        var oldPos = NavTerminalData.boundCorePos(stack);
        var oldDim = NavTerminalData.boundCoreDim(stack);
        if (player.level() instanceof ServerLevel level && oldPos != null && oldDim != null
                && (!oldPos.equals(core.getBlockPos()) || !oldDim.equals(level.dimension().location()))) {
            var oldLevel = level.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, oldDim));
            if (oldLevel != null) NavWakeProbe.untrackBinding(oldLevel, oldPos, uuid);
        }
        var existing = core.boundTerminalUuid();
        if (existing != null && !existing.equals(uuid)) {
            // 换绑 = 覆盖（研究稿口径），提示旧绑定被替换。
            player.displayClientMessage(Component.literal("[导航终端] 核心原绑定终端已被替换")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        core.bindTerminal(uuid);
        NavTerminalData.bindCore(stack, core.getBlockPos(),
                player.level().dimension().location());
        player.displayClientMessage(Component.literal("[导航终端] 已绑定飞控核心 "
                        + core.getBlockPos().toShortString())
                .withStyle(ChatFormatting.GREEN), false);
        return UUIDBindResult.OK;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) NavTerminalData.terminalUuid(stack);
        if (player.isShiftKeyDown()) {
            // 潜行右键 = 切换望远镜式打击标定视角（toggle，2026-11-08 改版：
            // 按住右键的输入模型会吞开镜时的左键事件，故不再 startUsingItem）。
            if (level.isClientSide) {
                com.bridgecore.client.NavTerminalScope.toggle();
            }
            return InteractionResultHolder.consume(stack);
        }
        if (level.isClientSide) {
            if (com.bridgecore.client.NavTerminalScope.active()) {
                // 开镜中普通右键 = 关镜，不开 GUI。
                com.bridgecore.client.NavTerminalScope.close();
            } else {
                // Client-only screen; edits go through TerminalLinkPayload (T2).
                com.bridgecore.client.NavTerminalScreen.open(hand);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
