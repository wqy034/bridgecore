package com.bridgecore;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-structure component directory (interface v1 §3): how components living on
 * the same Sable structure find each other — bridge cores, momentum
 * distributors, energy extractors, holo displays, and (by bucket-name
 * convention) the fire-control module's cores. Weak keys keep dead structures
 * from leaking. Test-grade: no heartbeats, no uuids.
 *
 * Generic buckets (fire-control request #1, 2026-09-11): any component registers
 * under a bucket NAME; the four built-in buckets are just named instances of the
 * same mechanism and the typed methods below are thin wrappers with unchanged
 * signatures. The fire-control module (same mod, same jar — corrected
 * 2026-09-11, no longer a separate mod) registers with its agreed bucket name
 * {@link #BUCKET_FIRE_CONTROL}.
 *
 * Threads (clarified 2026-09-11 — the old "register/deregister on the physics
 * thread" note described the call sites, not a constraint): EVERY method is
 * safe on BOTH the server thread and the physics thread. All map access is
 * synchronized, lists are CopyOnWrite, queries return immutable snapshots.
 * Block entities register from onLoad/server ticker (server thread) or the
 * physics callback (physics thread); both are fine.
 */
public final class CoreDirectory {
    /** Built-in bucket: bridge cores. */
    public static final String BUCKET_CORE = "bridgeCore";
    /** Built-in bucket: momentum distributors (the structure's FE pools). */
    public static final String BUCKET_DISTRIBUTOR = "momentumDistributor";
    /** Built-in bucket: energy extractors. */
    public static final String BUCKET_EXTRACTOR = "energyExtractor";
    /** Built-in bucket: holo displays. */
    public static final String BUCKET_HUD = "holoDisplay";
    /** Agreed bucket name for the fire-control module's cores (interface v1 §3;
     *  frozen 2026-09-11). Elements are the fire-control module's own block
     *  entities — CoreDirectory never casts them. */
    public static final String BUCKET_FIRE_CONTROL = "fireControlCore";

    /** bucket name -> (structure -> components). Per-structure maps are
     *  synchronized WeakHashMaps; every access below locks the inner map. */
    private static final Map<String, Map<ServerSubLevel, CopyOnWriteArrayList<Object>>> BUCKETS =
            new ConcurrentHashMap<>();

    private CoreDirectory() {
    }

    // ---- generic buckets (server thread AND physics thread, all three) ----

    /** Register a component under a bucket. Null sub = no structure = no-op
     *  (bench blocks simply never enter the directory). */
    public static void register(String bucket, ServerSubLevel sub, Object component) {
        if (sub == null || component == null) {
            return;
        }
        Map<ServerSubLevel, CopyOnWriteArrayList<Object>> map = bucket(bucket);
        synchronized (map) {
            map.computeIfAbsent(sub, k -> new CopyOnWriteArrayList<>()).addIfAbsent(component);
        }
    }

    /** Deregister; null sub is a no-op (matches the registration path). */
    public static void deregister(String bucket, ServerSubLevel sub, Object component) {
        if (sub == null || component == null) {
            return;
        }
        Map<ServerSubLevel, CopyOnWriteArrayList<Object>> map = bucket(bucket);
        synchronized (map) {
            CopyOnWriteArrayList<Object> list = map.get(sub);
            if (list != null) {
                list.remove(component);
                if (list.isEmpty()) {
                    map.remove(sub);
                }
            }
        }
    }

    /** @return immutable snapshot of the bucket's components on this structure
     *  (never null, possibly empty; null sub -> empty). */
    public static List<Object> componentsOf(String bucket, ServerSubLevel sub) {
        if (sub == null) {
            return List.of();
        }
        Map<ServerSubLevel, CopyOnWriteArrayList<Object>> map = bucket(bucket);
        synchronized (map) {
            CopyOnWriteArrayList<Object> list = map.get(sub);
            return list == null ? List.of() : List.copyOf(list);
        }
    }

    // ---- built-in typed wrappers (signatures unchanged since M2; delegates only) ----

    public static void register(ServerSubLevel sub, BridgeCoreBlockEntity core) {
        register(BUCKET_CORE, sub, (Object) core);
    }

    public static void deregister(ServerSubLevel sub, BridgeCoreBlockEntity core) {
        deregister(BUCKET_CORE, sub, (Object) core);
    }

    /** @return snapshot of the cores on this structure (never null, possibly empty) */
    public static List<BridgeCoreBlockEntity> coresOf(ServerSubLevel sub) {
        return typedOf(BUCKET_CORE, sub);
    }

    public static void registerPool(ServerSubLevel sub, MomentumDistributorBlockEntity pool) {
        register(BUCKET_DISTRIBUTOR, sub, (Object) pool);
    }

    public static void deregisterPool(ServerSubLevel sub, MomentumDistributorBlockEntity pool) {
        deregister(BUCKET_DISTRIBUTOR, sub, (Object) pool);
    }

    /** @return snapshot of the momentum distributors on this structure (never null, possibly empty) */
    public static List<MomentumDistributorBlockEntity> powerPoolsOf(ServerSubLevel sub) {
        return typedOf(BUCKET_DISTRIBUTOR, sub);
    }

    public static void registerExtractor(ServerSubLevel sub, EnergyExtractorBlockEntity extractor) {
        register(BUCKET_EXTRACTOR, sub, (Object) extractor);
    }

    public static void deregisterExtractor(ServerSubLevel sub, EnergyExtractorBlockEntity extractor) {
        deregister(BUCKET_EXTRACTOR, sub, (Object) extractor);
    }

    /** @return snapshot of the energy extractors on this structure (never null, possibly empty) */
    public static List<EnergyExtractorBlockEntity> extractorsOf(ServerSubLevel sub) {
        return typedOf(BUCKET_EXTRACTOR, sub);
    }

    public static void registerHudScreen(ServerSubLevel sub, HudScreenBlockEntity screen) {
        register(BUCKET_HUD, sub, (Object) screen);
    }

    public static void deregisterHudScreen(ServerSubLevel sub, HudScreenBlockEntity screen) {
        deregister(BUCKET_HUD, sub, (Object) screen);
    }

    /** @return snapshot of the holo displays on this structure (never null, possibly empty) */
    public static List<HudScreenBlockEntity> hudScreensOf(ServerSubLevel sub) {
        return typedOf(BUCKET_HUD, sub);
    }

    private static Map<ServerSubLevel, CopyOnWriteArrayList<Object>> bucket(String name) {
        return BUCKETS.computeIfAbsent(name, k -> Collections.synchronizedMap(new WeakHashMap<>()));
    }

    /** The typed wrappers own what goes into their bucket, so the cast is safe. */
    @SuppressWarnings("unchecked")
    private static <T> List<T> typedOf(String bucket, ServerSubLevel sub) {
        return (List<T>) (List<?>) componentsOf(bucket, sub);
    }
}
