package com.bridgecore.cc;

import com.bridgecore.BCRegistry;
import com.bridgecore.BridgeCoreBlockEntity;
import com.bridgecore.MomentumDistributorBlockEntity;
import com.bridgecore.display.DisplayGpuBlockEntity;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

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

    // R4-6：check-then-act 加锁——并发能力查询不会各 new 一个外设互相覆盖。
    private static synchronized CCDisplayPeripheral forDisplay(DisplayGpuBlockEntity be) {
        var level = be.getLevel();
        if (level == null || level.isClientSide || be.isRemoved()) return null;
        if (be.externalSession() instanceof CCDisplayPeripheral peripheral) return peripheral;
        var server = level.getServer();
        var peripheral = new CCDisplayPeripheral(be,
                () -> {
                    if (be.isRemoved() || be.getLevel() != level || !level.hasChunkAt(be.getBlockPos())
                            || level.getBlockEntity(be.getBlockPos()) != be) return false;
                    be.prepareExternalDisplay();
                    return true;
                },
                be::externalContentSelected, server::execute, be::resumeInternalDisplay);
        be.setExternalSession(peripheral);
        return peripheral;
    }

    // R10-2（R4-6 回归）：通道侧同样加锁，get-or-create 不许两段无锁。
    private static synchronized GenericChannelPeripheral forCore(BridgeCoreBlockEntity be) {
        // R4-3：外设挂在 BE 字段上随 BE 一起死，不再用 WeakHashMap（旧 map 的
        // value 经 channels lambda 强引用 key（BE），弱键自锁永不回收）。
        if (be.ccChannelPeripheral() instanceof GenericChannelPeripheral p) return p;
        var level = be.getLevel();
        if (level == null || level.isClientSide || be.isRemoved()) return null;
        var p = new GenericChannelPeripheral("bridgecore_core", BridgeCoreChannels.coreChannels(be),
                () -> !be.isRemoved() && be.getLevel() == level && level.hasChunkAt(be.getBlockPos())
                        && level.getBlockEntity(be.getBlockPos()) == be);
        be.setCcChannelPeripheral(p);
        return p;
    }

    private static synchronized GenericChannelPeripheral forDistributor(MomentumDistributorBlockEntity be) {
        if (be.ccChannelPeripheral() instanceof GenericChannelPeripheral p) return p;
        var level = be.getLevel();
        if (level == null || level.isClientSide || be.isRemoved()) return null;
        var p = new GenericChannelPeripheral("bridgecore_distributor",
                BridgeCoreChannels.distributorChannels(be),
                () -> !be.isRemoved() && be.getLevel() == level && level.hasChunkAt(be.getBlockPos())
                        && level.getBlockEntity(be.getBlockPos()) == be);
        be.setCcChannelPeripheral(p);
        return p;
    }
}
