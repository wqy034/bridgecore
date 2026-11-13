package com.bridgecore.client;

import com.bridgecore.nav.NavTerminalData;
import com.bridgecore.nav.NavTerminalItem;
import com.bridgecore.network.TerminalStrikeRequest;
import com.bridgecore.network.TerminalStrikeSnapshot;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.Objects;
import java.util.UUID;

public final class NavTerminalQuickStrike {
    static final KeyMapping KEY = new KeyMapping("key.bridgecore.terminal_quick_strike", KeyConflictContext.IN_GAME,
            InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_5, "key.bridgecore.category.firecontrol");
    private enum Phase { PREVIEWING, READY, EXECUTING }

    private record HeldTerminal(UUID id, ClientLevel level, int slot, CustomData data, BlockPos core,
                                ResourceLocation dimension, NavTerminalData.QuickStrike preset) {}
    private record Request(UUID token, int channel, Phase phase, long expires) {}
    private record RosterLookup(UUID token, int scrollSteps, long expires) {}
    private static HeldTerminal held;
    private static Request request;
    private static RosterLookup lookup;
    private static TerminalStrikeSnapshot roster;
    private static int selectedChannel = -1;
    private static boolean selectionStale;
    private static long scrollUntil;

    private NavTerminalQuickStrike() {}

    static void tick(ClientTickEvent.Pre event) {
        boolean pressed = KEY.consumeClick();
        while (KEY.consumeClick()) { /* A burst of clicks in one tick counts as one press. */ }
        if (!pressed && held == null) return;
        var mc = Minecraft.getInstance();
        if (!checkHeld(mc)) {
            if (pressed && canUse(mc)) show("请先绑定飞控核心");
            return;
        }
        if (!canUse(mc)) {
            request = null;
            lookup = null;
            scrollUntil = 0;
            return;
        }
        long now = System.nanoTime();
        if (request != null && now >= request.expires()) {
            if (request.phase() != Phase.EXECUTING) show("快捷预瞄已过期");
            request = null;
        }
        if (lookup != null && now >= lookup.expires()) {
            lookup = null;
            show("读取频道超时，请再滚动一次");
        }
        if (!pressed || lookup != null) return;
        if (request != null) {
            if (request.phase() != Phase.READY) return;
            request = new Request(request.token(), request.channel(), Phase.EXECUTING, now + 5_000_000_000L);
            PacketDistributor.sendToServer(TerminalStrikeRequest.quick(held.id(), request.token(), TerminalStrikeRequest.Action.EXECUTE));
            show("正在提交快捷打击…");
            return;
        }
        var profile = held.preset();
        if (selectionStale) {
            show("频道已变化，请开镜滚轮重新选择");
            return;
        }
        if (selectedChannel < 0 && profile == null) {
            show("开镜后可滚轮选频道，也可在「打击 → 快捷设置」保存默认频道");
            return;
        }
        int channel = selectedChannel >= 0 ? selectedChannel : profile.channel();
        request = new Request(UUID.randomUUID(), channel, Phase.PREVIEWING, now + 5_000_000_000L);
        PacketDistributor.sendToServer(selectedChannel >= 0
                ? TerminalStrikeRequest.quickPreview(roster, request.token(), channel)
                : TerminalStrikeRequest.quick(held.id(), request.token(), TerminalStrikeRequest.Action.PREVIEW));
        show("正在预瞄 · " + (selectedChannel >= 0 ? channelLabel(roster, channel) : profile.name()));
    }

    static void scroll(InputEvent.MouseScrollingEvent event) {
        int step = -(int) Math.signum(event.getScrollDeltaY());
        long now = System.nanoTime();
        var mc = Minecraft.getInstance();
        if (step == 0 || !canUse(mc) || !checkHeld(mc)
                || !NavTerminalScope.active() && request == null && lookup == null && now >= scrollUntil) return;
        event.setCanceled(true);
        if (request != null && request.phase() == Phase.EXECUTING) return;
        scrollUntil = now + 3_000_000_000L;
        request = null;
        if (roster != null) {
            selectChannel(step);
        } else if (lookup != null) {
            lookup = new RosterLookup(lookup.token(), lookup.scrollSteps() + step, lookup.expires());
        } else {
            lookup = new RosterLookup(UUID.randomUUID(), step, now + 5_000_000_000L);
            PacketDistributor.sendToServer(new TerminalStrikeRequest(held.id(), lookup.token(), BlockPos.ZERO, 0, "", -1, 0));
            show("正在读取火控频道…");
        }
    }

    public static boolean accept(TerminalStrikeSnapshot packet) {
        if (held == null || !packet.terminal().equals(held.id())) return false;
        boolean lookupReply = lookup != null && packet.request().equals(lookup.token());
        var current = request;
        if (!lookupReply && (current == null || !packet.request().equals(current.token()))) return false;
        var mc = Minecraft.getInstance();
        if (!canUse(mc) || !checkHeld(mc)) return true;
        if (lookupReply) {
            if (lookup == null) return true;
            int steps = lookup.scrollSteps();
            lookup = null;
            if (packet.channels().isEmpty()) show(packet.message());
            else {
                roster = packet;
                selectChannel(steps);
            }
            return true;
        }
        if (request != current) return true;
        if (!packet.channels().isEmpty()) {
            if (selectedChannel >= 0 && (!roster.core().equals(packet.core()) || roster.revision() != packet.revision())) {
                selectedChannel = -1;
                selectionStale = true;
            }
            roster = packet;
        }
        if (current.phase() == Phase.PREVIEWING && packet.executableWeapons() > 0) {
            request = new Request(current.token(), current.channel(), Phase.READY,
                    System.nanoTime() + 9_000_000_000L);
            String mode = packet.message().contains(" · 偏航") ? "需转舰" : "预瞄就绪";
            String target = packet.message().split(" · ", 2)[0];
            show(channelLabel(packet, current.channel()) + " · " + mode + " · " + target + " · "
                    + packet.executableWeapons() + " 座 · 再按 "
                    + KEY.getTranslatedKeyMessage().getString() + " 确认");
        } else {
            request = null;
            if (current.phase() == Phase.PREVIEWING && !packet.rows().isEmpty()) {
                String reason = packet.rows().stream().filter(row -> !row.eligible()).map(row -> row.reason())
                        .findFirst().orElse("炮位无法共同瞄准");
                show(channelLabel(packet, current.channel()) + " · 预瞄未通过 · " + reason);
            } else show(packet.message());
        }
        return true;
    }

    private static void selectChannel(int steps) {
        var profile = held.preset();
        int base = selectedChannel;
        if (base < 0 && profile != null && profile.channel() >= 0 && profile.channel() < roster.channels().size()
                && roster.channels().get(profile.channel()).equals(profile.name())) base = profile.channel();
        if (base < 0) base = steps < 0 ? 0 : -1;
        selectedChannel = Math.floorMod(base + steps, roster.channels().size());
        selectionStale = false;
        show("频道 · " + channelLabel(roster, selectedChannel) + " · 按 "
                + KEY.getTranslatedKeyMessage().getString() + " 预瞄");
    }

    private static String channelLabel(TerminalStrikeSnapshot packet, int channel) {
        return packet.channels().get(channel) + " · " + (packet.axialChannel(channel) ? "轴炮" : "转塔炮击");
    }

    private static boolean canUse(Minecraft mc) {
        return holdingTerminal(mc) && mc.screen == null && mc.isWindowActive() && !NavTerminalKeyboard.active();
    }

    private static boolean holdingTerminal(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.player.isAlive() && !mc.player.isSpectator()
                && mc.player.getMainHandItem().getItem() instanceof NavTerminalItem;
    }

    private static boolean checkHeld(Minecraft mc) {
        var stack = holdingTerminal(mc) ? mc.player.getMainHandItem() : null;
        var data = stack == null ? null : stack.get(DataComponents.CUSTOM_DATA);
        var previous = held;
        if (data != null) {
            int slot = mc.player.getInventory().selected;
            boolean sameSlot = previous != null && previous.level() == mc.level && previous.slot() == slot;
            if (sameSlot && previous.data() == data) return true;
            var id = NavTerminalData.peekUuid(stack);
            var core = NavTerminalData.boundCorePos(stack);
            var dimension = NavTerminalData.boundCoreDim(stack);
            if (id != null && core != null && dimension != null) {
                var preset = NavTerminalData.quickStrike(stack);
                held = new HeldTerminal(id, mc.level, slot, data, core, dimension, preset);
                if (sameSlot && previous.id().equals(id) && previous.core().equals(core)
                        && previous.dimension().equals(dimension) && Objects.equals(previous.preset(), preset)) return true;
            } else held = null;
        } else held = null;
        request = null;
        lookup = null;
        roster = null;
        selectedChannel = -1;
        selectionStale = false;
        scrollUntil = 0;
        return held != null;
    }

    private static void show(String text) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal(text), true);
    }
}
