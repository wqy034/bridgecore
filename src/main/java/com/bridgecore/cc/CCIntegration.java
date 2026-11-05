package com.bridgecore.cc;

import com.bridgecore.BCRegistry;
import com.bridgecore.BridgeCoreBlockEntity;
import com.bridgecore.MomentumDistributorBlockEntity;
import com.bridgecore.display.DisplayGpuBlockEntity;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * CC:Tweaked integration entry point: channel tables and whole-screen displays.
 * Registered from BridgeCoreMod ONLY when ModList says "computercraft" is loaded:
 * this class (and the whole cc package's CC-facing half) references CC API types,
 * so it must never class-load with CC absent (soft dependency).
 *
 * Registration is the NeoForge-era mechanism: one BlockCapability
 * (PeripheralCapability.get(), verified against the 1.120.0 jar) registered per
 * BE type — the legacy IPeripheralProvider / PeripheralLookup paths are gone in
 * this API generation.
 */
public final class CCIntegration {

    /** One peripheral instance per BE — CC's attach/detach diffing keys on
     *  peripheral identity, so a fresh instance per lookup would flap. Weak keys:
     *  dead BEs drop out on GC. */
    private static final Map<BlockEntity, GenericChannelPeripheral> PERIPHERALS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CCIntegration() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(RegisterCapabilitiesEvent.class, event -> {
            // The controller is the sole cable/adjacency endpoint. Tiles are deliberately NOT peripherals.
            event.registerBlockEntity(PeripheralCapability.get(), BCRegistry.DISPLAY_GPU_BE.get(),
                    (be, side) -> forDisplay(be));
            event.registerBlockEntity(PeripheralCapability.get(), BCRegistry.BRIDGE_CORE_BE.get(),
                    (be, side) -> forCore(be));
            event.registerBlockEntity(PeripheralCapability.get(), BCRegistry.MOMENTUM_DISTRIBUTOR_BE.get(),
                    (be, side) -> forDistributor(be));
        });
    }

    private static CCDisplayPeripheral forDisplay(DisplayGpuBlockEntity be) {
        var level = be.getLevel();
        if (level == null || level.isClientSide || be.isRemoved()) return null;
        if (be.externalSession() instanceof CCDisplayPeripheral peripheral) return peripheral;
        be.prepareExternalDisplay();
        var server = level.getServer();
        var peripheral = new CCDisplayPeripheral(be,
                () -> !be.isRemoved() && be.getLevel() == level && level.hasChunkAt(be.getBlockPos())
                        && level.getBlockEntity(be.getBlockPos()) == be,
                be::externalContentSelected, server::execute, be::resumeInternalDisplay);
        be.setExternalSession(peripheral);
        return peripheral;
    }

    private static GenericChannelPeripheral forCore(BridgeCoreBlockEntity be) {
        return PERIPHERALS.computeIfAbsent(be,
                k -> new GenericChannelPeripheral("bridgecore_core", BridgeCoreChannels.coreChannels(be)));
    }

    private static GenericChannelPeripheral forDistributor(MomentumDistributorBlockEntity be) {
        return PERIPHERALS.computeIfAbsent(be,
                k -> new GenericChannelPeripheral("bridgecore_distributor",
                        BridgeCoreChannels.distributorChannels(be)));
    }
}
