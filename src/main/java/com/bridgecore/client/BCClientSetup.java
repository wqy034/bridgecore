package com.bridgecore.client;

import com.bridgecore.BCRegistry;
import com.bridgecore.BridgeCoreMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Client-only entry: block entity renderer + menu screen registration. */
@Mod(value = BridgeCoreMod.MOD_ID, dist = Dist.CLIENT)
public class BCClientSetup {
    private int displayCleanupTicks;

    public BCClientSetup(IEventBus modEventBus) {
        modEventBus.addListener(EntityRenderersEvent.RegisterRenderers.class, event -> {
            event.registerBlockEntityRenderer(BCRegistry.HUD_SCREEN_BE.get(),
                    HudScreenRenderer::new);
            event.registerBlockEntityRenderer(BCRegistry.DISPLAY_TILE_BE.get(),
                    DisplayTileRenderer::new);
        });
        modEventBus.addListener(RegisterMenuScreensEvent.class, event -> {
            event.register(BCRegistry.ENERGY_EXTRACTOR_MENU.get(), EnergyExtractorScreen::new);
            event.register(BCRegistry.MOMENTUM_DISTRIBUTOR_MENU.get(), MomentumDistributorScreen::new);
            event.register(BCRegistry.BRIDGE_CORE_MENU.get(), BridgeCoreScreen::new);
            event.register(BCRegistry.FIRE_CONTROL_MENU.get(), FireControlScreen::new);
        });
        // 2026-10-30 落地②: the client-side fire-volley keybind is gone — H is
        // typewriter channel 19 now, arbitrated server-side by the core.
        // 导航终端打击标定开镜（2026-11-08）：FOV 缩放 + 开镜左键射线标定。
        NeoForge.EVENT_BUS.addListener(NavTerminalScope::onFov);
        NeoForge.EVENT_BUS.addListener(NavTerminalScope::onLeftClick);
        NeoForge.EVENT_BUS.addListener(NavTerminalScope::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(NavTerminalScope::onClientTick); // 切手持即关镜
        // 终端亮灯：bridgecore:active 物品属性 = 键盘捕获模式激活。
        // 必须在 FMLClientSetupEvent 里注册——构造函数阶段物品还没注册，
        // 直接 NAV_TERMINAL.get() 会 unbound value NPE（2026-11-11 实机）。
        modEventBus.addListener(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent.class, event -> {
            net.minecraft.client.renderer.item.ItemProperties.register(BCRegistry.NAV_TERMINAL.get(),
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "active"),
                    (stack, level, entity, seed) -> NavTerminalKeyboard.active() ? 1.0f : 0.0f);
        });
        modEventBus.addListener(RegisterKeyMappingsEvent.class, event -> event.register(NavTerminalQuickStrike.KEY));
        NeoForge.EVENT_BUS.addListener(NavTerminalQuickStrike::tick);
        NeoForge.EVENT_BUS.addListener(NavTerminalQuickStrike::scroll);
        NeoForge.EVENT_BUS.addListener(TurretLocator::render);
        NeoForge.EVENT_BUS.addListener(this::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(this::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(this::expireDisplayFrames);
        NeoForge.EVENT_BUS.addListener(NavTerminalKeyboard::onTick);
        NeoForge.EVENT_BUS.addListener(NavTerminalKeyboard::onMovement);
        NeoForge.EVENT_BUS.addListener(NavTerminalKeyboard::onScreenOpening);
        // M3a gamepad channel: SOFT dependency — the reader references the TC API
        // directly, so it must only load (and register) when the mod is present.
        if (ModList.get().isLoaded("create_tweaked_controllers")) {
            GamepadClientReader.register(NeoForge.EVENT_BUS);
        }
    }

    private void onChunkUnload(ChunkEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;
        var pos = event.getChunk().getPos();
        HoloTextureCache.unloadChunk(pos);
        DisplayTileTextureCache.unloadChunk(pos);
    }

    private void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;
        HoloTextureCache.clear();
        DisplayTileTextureCache.clear();
    }

    private void expireDisplayFrames(ClientTickEvent.Post event) {
        if (++displayCleanupTicks % 20 == 0) HoloTextureCache.expireBands();
    }
}
