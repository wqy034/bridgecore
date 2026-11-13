package com.bridgecore.client;

import com.bridgecore.nav.NavTerminalData;
import com.bridgecore.nav.NavTerminalItem;
import com.bridgecore.network.TerminalLinkPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Terminal strike-marking scope (auto-navigation-research.md v2 §4):
 * sneak + hold right-click with the terminal = spyglass-style zoomed view;
 * left-click while scoped = raycast the crosshair point (≤256 m) and uplink a
 * STRIKE_MARK. No spyglass overlay texture — FOV zoom only.
 */
public final class NavTerminalScope {
    private static final float ZOOM = 0.15f; // ~6.7x

    private NavTerminalScope() {}

    /** 开镜状态（客户端 toggle，2026-11-08 改版）：潜行右键切换，不再依赖
     *  isUsingItem——按住右键的输入模型会吞掉开镜时的左键事件。 */
    private static boolean scoped;

    public static void toggle() {
        scoped = !scoped;
        Player p = Minecraft.getInstance().player;
        scopedSlot = scoped && p != null ? p.getInventory().selected : -1;
    }

    public static void close() {
        scoped = false;
    }

    /** Scope active = toggled on AND the terminal is in the main hand. */
    public static boolean active() {
        Player p = Minecraft.getInstance().player;
        return scoped && p != null
                && p.getMainHandItem().getItem() instanceof NavTerminalItem;
    }

    /** 2026-11-08 用户要求：切换手持物品后**解除**瞄准（不是暂时失效）。
     *  客户端每 tick 检查：开镜中主手槽位变动或主手不再是终端 → 关镜。 */
    private static int scopedSlot = -1;

    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!scoped) return;
        Player p = Minecraft.getInstance().player;
        if (p == null) { scoped = false; return; }
        if (p.getInventory().selected != scopedSlot
                || !(p.getMainHandItem().getItem() instanceof NavTerminalItem)) {
            scoped = false;
        }
    }

    public static void onFov(ComputeFovModifierEvent event) {
        if (active()) {
            event.setNewFovModifier(ZOOM);
        }
    }

    /** Left-click (air or block) while scoped = mark the crosshair point. */
    public static void onLeftClick(PlayerInteractEvent.LeftClickEmpty event) {
        if (active()) mark();
    }

    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (active()) {
            event.setCanceled(true); // 开镜标定不破坏方块
            mark();
        }
    }

    private static void mark() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getCameraEntity() == null) return;
        var camera = mc.getCameraEntity();
        Vec3 eye = camera.getEyePosition();
        Vec3 look = camera.getLookAngle();
        var hit = mc.level.clip(new ClipContext(eye, eye.add(look.scale(NavTerminalData.STRIKE_RANGE)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, camera));
        if (hit.getType() != HitResult.Type.BLOCK) return; // 没打到东西不标定
        ItemStack stack = mc.player.getMainHandItem();
        var uuid = NavTerminalData.peekUuid(stack);
        if (uuid == null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("导航终端初始化中，请再试一次"), true);
            return;
        }
        String name = "打击" + (NavTerminalData.marks(stack).size() + 1);
        PacketDistributor.sendToServer(new TerminalLinkPayload(
                TerminalLinkPayload.PROTOCOL_VERSION,
                TerminalLinkPayload.Cmd.STRIKE_MARK.ordinal(), uuid, name, "",
                net.minecraft.core.BlockPos.containing(hit.getLocation()).asLong(),
                mc.level.dimension().location().toString(), true));
    }
}
