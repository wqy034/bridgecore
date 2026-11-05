package com.bridgecore.display;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registration point for {@link HudTargetProvider}s (2026-09-22, handbook
 * §2.27 mounting ruling: thin SPI pull, server-thread at HUD render time;
 * snapshot model evolved to §2.28 on 2026-09-23, v3 频道任务协议 2026-10-27).
 * First non-null snapshot wins; with no provider (or all abstaining) the HUD
 * draws exactly what it drew before lock boxes existed.
 * v3 (修订 I, 2026-10-28): the v2 write-back routing (requestLock /
 * requestUnlock / requestMultiLock) is deleted — uplink commands go from the
 * flight core to the fire-control core directly, never through this hub.
 *
 * CopyOnWriteArrayList: registration is rare (mod init), pulls are per-tick.
 */
public final class HudTargetProviders {
    /** Provider + registration-time reflection notes on which SPI methods it
     *  actually implements (the SPI defaults are no-ops we cannot observe). */
    private record Entry(HudTargetProvider provider,
                         boolean implementsSuppressHudTargeting,
                         boolean implementsChannelTasks,
                         boolean implementsActiveChannelOf) {
    }

    private static final List<Entry> PROVIDERS = new CopyOnWriteArrayList<>();

    private HudTargetProviders() {
    }

    public static void register(HudTargetProvider provider) {
        PROVIDERS.add(new Entry(provider,
                overrides(provider, "suppressHudTargeting"),
                overrides(provider, "channelTasks", Level.class, ServerSubLevel.class),
                overrides(provider, "activeChannelOf", Level.class, ServerSubLevel.class)));
    }

    /** Reflection note: does this provider's class declare the named SPI
     *  write-back itself (vs inheriting the interface default)? */
    private static boolean overrides(HudTargetProvider provider, String method,
                                     Class<?>... sig) {
        try {
            return provider.getClass().getMethod(method, sig).getDeclaringClass()
                    != HudTargetProvider.class;
        } catch (NoSuchMethodException e) {
            return false; // unreachable — the default exists
        }
    }

    /** Pull the current snapshot; null when nobody feeds this HUD. */
    public static @Nullable HudTargetProvider.HudTargetSnapshot pull(Level level,
            @Nullable ServerSubLevel sub, BlockPos hudPos, Direction facing) {
        for (Entry e : PROVIDERS) {
            HudTargetProvider.HudTargetSnapshot snap =
                    e.provider().snapshot(level, sub, hudPos, facing);
            if (snap != null) return snap;
        }
        return null;
    }

    /** Read-only weapon view; first provider with a view for this HUD wins. */
    public static @Nullable HudTargetProvider.WeaponStatusView pullWeaponStatus(
            Level level, @Nullable ServerSubLevel sub, BlockPos hudPos, Direction facing) {
        for (Entry e : PROVIDERS) {
            var view = e.provider().weaponStatus(level, sub, hudPos, facing);
            if (view != null) return view;
        }
        return null;
    }

    /** v3 (§2.29 §4): every channel's authoritative task view from the FIRST
     *  provider that implements channelTasks; empty when nobody does (no
     *  fire-control core — HUD draws no task line and no red boxes). */
    public static List<HudTargetProvider.ChannelTaskView> pullChannelTasks(
            Level level, @Nullable ServerSubLevel sub) {
        for (Entry e : PROVIDERS) {
            if (e.implementsChannelTasks()) {
                return e.provider().channelTasks(level, sub);
            }
        }
        return List.of();
    }

    /** v3: the command channel (X 循环) from the FIRST provider that implements
     *  activeChannelOf; 0 when nobody does. */
    public static int pullActiveChannel(Level level, @Nullable ServerSubLevel sub) {
        for (Entry e : PROVIDERS) {
            if (e.implementsActiveChannelOf()) {
                return e.provider().activeChannelOf(level, sub);
            }
        }
        return 0;
    }

    /** v3.1 (2026-10-01): poll the FIRST provider that overrides
     *  suppressHudTargeting — same single-recipient routing as the
     *  write-backs. Default false when nobody overrides it. Server thread. */
    public static boolean suppressHudTargeting() {
        for (Entry e : PROVIDERS) {
            if (e.implementsSuppressHudTargeting()) {
                return e.provider().suppressHudTargeting();
            }
        }
        return false;
    }

    public static boolean isEmpty() {
        return PROVIDERS.isEmpty();
    }
}
