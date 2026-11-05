package com.bridgecore.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Every mixin in this config targets an OPTIONAL dependency (today: Create
 * Tweaked Controllers; Create Simulated/aeronautics for the fire-control
 * swivel bearing). Skip application when the target mod is absent so the core
 * mod keeps loading without it — same ModList-guard ruling as the client
 * reader (BCClientSetup), just at mixin level where ModList may not be
 * populated yet, hence class-resource probing as fallback.
 */
public final class BridgeCoreMixinPlugin implements IMixinConfigPlugin {

    /** mixin simple name -> mod id that must be loaded for it to apply. */
    private static final java.util.Map<String, String> REQUIRED_MOD = java.util.Map.of(
            "TweakedLecternRangeMixin", "create_tweaked_controllers",
            "SwivelBearingAccessorMixin", "simulated",
            "SwivelBearingAxisLockMixin", "simulated");

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        String requiredMod = REQUIRED_MOD.get(simpleName);
        // ModList is populated before mixin configs are applied on NeoForge 1.21;
        // a class-resource probe at this phase is unreliable (mod jars may not be
        // visible to the plugin's classloader yet — observed 2026-09-03: probe
        // silently skipped the TC lectern mixin).
        try {
            var modList = net.neoforged.fml.ModList.get();
            if (modList != null) {
                return requiredMod == null || modList.isLoaded(requiredMod);
            }
        } catch (Throwable ignored) {
            // fall through to the probe
        }
        String resource = targetClassName.replace('.', '/') + ".class";
        return BridgeCoreMixinPlugin.class.getClassLoader().getResource(resource) != null;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
