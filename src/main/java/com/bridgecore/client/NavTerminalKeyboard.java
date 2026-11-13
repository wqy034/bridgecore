package com.bridgecore.client;

import com.bridgecore.control.RemoteKeyTiming;
import com.bridgecore.control.RemoteManeuver;
import com.bridgecore.nav.NavTerminalData;
import com.bridgecore.nav.NavTerminalItem;
import com.bridgecore.network.TerminalNudgePayload;
import com.bridgecore.network.TerminalManeuverPayload;
import com.bridgecore.network.TerminalReplyPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.UUID;

/** Client input capture. Authority and the held-input expiry stay on the server. */
public final class NavTerminalKeyboard {
    private static final RemoteKeyTiming timing = new RemoteKeyTiming();
    private static UUID terminal;
    private static UUID session;
    private static InteractionHand hand;
    private static List<Integer> translationKeys = List.of();
    private static int sentMask;
    private static int heartbeat;
    private static int speedTenths = 20;
    private static int hintTicks;
    private static int replyTicks;

    private NavTerminalKeyboard() {}

    /** 键盘捕获模式是否激活（2026-11-10：终端模型亮屏谓词用）。 */
    public static boolean active() {
        return terminal != null;
    }

    public static void enter(InteractionHand selectedHand, UUID selectedTerminal, List<Integer> keys, int speed) {
        Minecraft mc = Minecraft.getInstance();
        hintTicks = replyTicks = 0;
        NavTerminalScope.close();
        mc.setScreen(null);
        translationKeys = keys;
        speedTenths = speed;
        timing.reset(pressedKeys(mc));
        sentMask = heartbeat = 0;
        hand = selectedHand;
        terminal = selectedTerminal;
        session = UUID.randomUUID();
    }

    public static void onTick(ClientTickEvent.Pre event) {
        if (terminal == null) return;
        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || mc.screen != null || !mc.isWindowActive() || !player.isAlive()
                || player.isSpectator()) {
            exit(mc);
            return;
        }
        var held = player.getItemInHand(hand);
        if (!(held.getItem() instanceof NavTerminalItem) || !terminal.equals(NavTerminalData.peekUuid(held))) {
            exit(mc);
            return;
        }
        if (InputConstants.isKeyDown(mc.getWindow().getWindow(), GLFW.GLFW_KEY_ESCAPE)) {
            exit(mc);
            return;
        }

        suppressVanillaClicks(mc);
        var update = timing.sample(pressedKeys(mc), System.nanoTime());
        boolean changed = update.held() != sentMask;
        if (changed || (update.held() != 0 && --heartbeat <= 0)) {
            sentMask = update.held();
            sendHeld(sentMask, changed);
            heartbeat = 2;
        }
        if (update.step() != null) {
            PacketDistributor.sendToServer(new TerminalNudgePayload(terminal, update.step().wireId()));
        }

        if (replyTicks > 0) replyTicks--;
        if (hintTicks > 0) hintTicks--;
        if (replyTicks == 0 && hintTicks == 0) {
            hintTicks = 20;
            player.displayClientMessage(Component.literal("短按半格 · 长按移动 · Q/E 转向 · Esc 退出"), true);
        }
    }

    public static void onMovement(MovementInputUpdateEvent event) {
        if (terminal == null || event.getEntity() != Minecraft.getInstance().player) return;
        var input = event.getInput();
        input.leftImpulse = input.forwardImpulse = 0.0f;
        input.up = input.down = input.left = input.right = input.jumping = input.shiftKeyDown = false;
    }

    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (terminal == null || event.getNewScreen() == null) return;
        Minecraft mc = Minecraft.getInstance();
        exit(mc);
        if (event.getNewScreen() instanceof PauseScreen
                && InputConstants.isKeyDown(mc.getWindow().getWindow(), GLFW.GLFW_KEY_ESCAPE)) {
            event.setCanceled(true);
        }
    }

    public static boolean acceptReply(TerminalReplyPayload packet) {
        if (!packet.terminal().equals(terminal) || Minecraft.getInstance().player == null) return false;
        replyTicks = 45;
        Minecraft.getInstance().player.displayClientMessage(
                Component.literal(packet.message() + " · Esc 退出"), true);
        return true;
    }

    private static void exit(Minecraft mc) {
        suppressVanillaClicks(mc);
        if (sentMask != 0 && mc.getConnection() != null && mc.player != null) sendHeld(0, true);
        sentMask = 0;
        terminal = null;
        session = null;
        hand = null;
        translationKeys = List.of();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("已退出六向微调键盘模式"), true);
    }

    private static void sendHeld(int mask, boolean changed) {
        PacketDistributor.sendToServer(new TerminalManeuverPayload(terminal, session, mask, speedTenths, changed));
    }

    private static void suppressVanillaClicks(Minecraft mc) {
        suppressBinding(mc.options.keyDrop);
        suppressBinding(mc.options.keyInventory);
        suppressBinding(mc.options.keySwapOffhand);
    }

    private static void suppressBinding(KeyMapping mapping) {
        var key = mapping.getKey();
        if (key.getType() == InputConstants.Type.KEYSYM
                && (key.getValue() == GLFW.GLFW_KEY_Q || key.getValue() == GLFW.GLFW_KEY_E
                || translationKeys.contains(key.getValue()))) {
            while (mapping.consumeClick()) { /* This key belongs to the terminal while capturing input. */ }
        }
    }

    private static int pressedKeys(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        int mask = 0;
        for (int i = 0; i < translationKeys.size(); i++) {
            int key = translationKeys.get(i);
            if (key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST
                    && key != GLFW.GLFW_KEY_Q && key != GLFW.GLFW_KEY_E && key != GLFW.GLFW_KEY_ESCAPE
                    && InputConstants.isKeyDown(window, key)) {
                mask |= 1 << i;
            }
        }
        if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_Q)) mask |= RemoteManeuver.TURN_LEFT;
        if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_E)) mask |= RemoteManeuver.TURN_RIGHT;
        return mask;
    }
}
