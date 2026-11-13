package com.bridgecore.network;

import com.bridgecore.menu.BridgeCoreMenu;
import com.bridgecore.menu.EnergyExtractorMenu;
import com.bridgecore.menu.MomentumDistributorMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/**
 * 2026-11-09 审查 P1-4：C2S 配置包的统一权限校验——**玩家当前打开的菜单
 * 必须是目标方块自己的菜单**（菜单类型 + 位置一致）。配置写只能在打开
 * 对应 GUI 时发生，堵住"任意玩家向任意坐标核心发包"的面。
 * 船载菜单无距离检查（shipyard vs logical 不同空间，见各菜单 javadoc），
 * 打开菜单的右键动作即授权。
 */
public final class PayloadGuards {
    private PayloadGuards() {}

    public static boolean menuOpen(Player player, Class<?> menuType, BlockPos pos) {
        var menu = player.containerMenu;
        if (!menuType.isInstance(menu)) return false;
        BlockPos open = switch (menu) {
            case BridgeCoreMenu m -> m.corePos();
            case EnergyExtractorMenu m -> m.extractorPos();
            case MomentumDistributorMenu m -> m.poolPos();
            default -> null;
        };
        return pos.equals(open);
    }
}
