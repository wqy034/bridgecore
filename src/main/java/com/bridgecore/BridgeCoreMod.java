package com.bridgecore;

import com.bridgecore.cc.CCIntegration;
import com.bridgecore.firecontrol.SwivelBearingAxisLock;
import com.bridgecore.firecontrol.TurretPairing;
import com.bridgecore.network.BCNetworking;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

@Mod(BridgeCoreMod.MOD_ID)
public class BridgeCoreMod {
    public static final String MOD_ID = "bridgecore";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BridgeCoreMod(IEventBus modEventBus, ModContainer modContainer) {
        BCRegistry.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, BCConfig.SPEC);
        // IEnergyStorage so any tech mod's cables/generators can push FE into the core;
        // v0.13: received FE is poured straight into the structure's distributor pools
        // (no core-local buffer).
        modEventBus.addListener(RegisterCapabilitiesEvent.class,
                event -> event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK,
                        BCRegistry.BRIDGE_CORE_BE.get(), (be, side) -> be.energyAccount().externalView()));
        // Momentum distributor: charge-only FE pool (receive 1M FE/t, extract 0).
        modEventBus.addListener(RegisterCapabilitiesEvent.class,
                event -> event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK,
                        BCRegistry.MOMENTUM_DISTRIBUTOR_BE.get(), (be, side) -> be.energyStorage()));
        // Energy extractor: single fuel slot, fuel-only insertion (hoppers etc.).
        modEventBus.addListener(RegisterCapabilitiesEvent.class,
                event -> event.registerBlockEntity(Capabilities.ItemHandler.BLOCK,
                        BCRegistry.ENERGY_EXTRACTOR_BE.get(), (be, side) -> be.itemHandler()));
        modEventBus.addListener(RegisterPayloadHandlersEvent.class, BCNetworking::register);
        // CC:Tweaked channel-table peripherals — SOFT dependency: CCIntegration
        // references CC API types, so it must only load when CC is present (same
        // guard pattern as the gamepad client reader in BCClientSetup).
        if (ModList.get().isLoaded("computercraft")) {
            CCIntegration.register(modEventBus);
        }
        // Fire-control turret pairing clicks reference the aeronautics swivel
        // bearing class — only register when simulated is present (soft dep).
        if (ModList.get().isLoaded("simulated")) {
            NeoForge.EVENT_BUS.addListener(TurretPairing::onRightClickBlock);
            // AxisLock: kinematic takeover of claimed shipborne swivel bearings
            // (sable is simulated's hard dependency, so one gate covers both).
            SwivelBearingAxisLock.init();
            NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.event.server.ServerStoppingEvent e)
                            -> SwivelBearingAxisLock.onServerStopping());
        }
        com.bridgecore.display.HudTargetProviders.register(
                new com.bridgecore.firecontrol.FireControlHudTargetProvider());
    }
}
