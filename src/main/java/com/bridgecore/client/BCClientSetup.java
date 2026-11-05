package com.bridgecore.client;

import com.bridgecore.BCRegistry;
import com.bridgecore.BridgeCoreMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only entry: block entity renderer + menu screen registration. */
@Mod(value = BridgeCoreMod.MOD_ID, dist = Dist.CLIENT)
public class BCClientSetup {
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
        // M3a gamepad channel: SOFT dependency — the reader references the TC API
        // directly, so it must only load (and register) when the mod is present.
        if (ModList.get().isLoaded("create_tweaked_controllers")) {
            GamepadClientReader.register(NeoForge.EVENT_BUS);
        }
    }
}
