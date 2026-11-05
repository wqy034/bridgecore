package com.bridgecore.firecontrol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

/**
 * Target registry for the fire-control core (roadmap §2.1): scan bookkeeping
 * and whitelist — the pure SCAN FACTS layer. Port of the control_center.lua
 * semantics — server thread only, no MC types so it stays unit-testable.
 *
 * <p>频道任务协议 v1 (§2.29, 2026-10-27): the registry no longer holds any
 * engagement state — the old single {@code lockedId} engagement chain (and
 * cycleLock/lock/unlock) was retired; per-channel {@link ChannelTask}s are the
 * only authority on what is engaged. What remains here:
 * <ul>
 *   <li>velocity is derived from consecutive sightings, normalised by the real
 *       sighting interval (dt clamped to >= 1 tick) — Sable calls are slow and
 *       scan intervals drift; dividing by dt keeps the lead math honest
 *       (control_center.lua scanner:getPlayer comment).</li>
 *   <li>eviction is the Lua flag-sweep: a target missing from one scan pass is
 *       dropped immediately ("目标离开半径自动剔除").</li>
 *   <li>whitelist entries match by structure uuid / entity uuid / ship name
 *       (slug, legacy compat) — wlMatch(); toggling removes every matching
 *       entry, otherwise adds the stable uuid (whiteListToggle()). Whitelisted
 *       targets are scan facts only — the core's task layer evicts them from
 *       every channel task.</li>
 *   <li>registered cores are implicitly whitelisted (注册即白名单, roadmap §2.1):
 *       the scanner flags them; nothing is persisted for the implicit half.</li>
 * </ul>
 */
public final class TargetRegistry {

    /** Coarse target class (§2.28 HUD category filter): PLAYER / CREATURE
     *  (passive) / MONSTER (vanilla instanceof Monster ruling, tagged at scan
     *  time where the entity instance is at hand) / SHIP (physical structure). */
    public enum Category { PLAYER, CREATURE, MONSTER, SHIP }

    /** One scan observation. Velocities are NOT supplied — the registry derives
     *  them (see class doc). Coordinates are world blocks. health/maxHealth are
     *  nullable: living entities carry real values (HUD lock-box info zone,
     *  shared handbook §2.27), ships have no health concept -> null. species is
     *  the entity type registry path ("zombie") for the HUD species filter
     *  (§2.28); ships give their slug, or "ship" when unnamed. */
    public record Sighting(String id, String uuid, String slug, Category category, String label,
                           double x, double y, double z, boolean implicitWhitelist,
                           @Nullable Double health, @Nullable Double maxHealth, String species) {}

    /** Registry output row: a tracked target with derived velocity (blocks/tick).
     *  health/maxHealth nullable (null for SHIP). */
    public record Target(String id, Category category, String label,
                         double x, double y, double z,
                         double vx, double vy, double vz,
                         double dist, boolean whitelisted,
                         @Nullable Double health, @Nullable Double maxHealth, String species) {}

    private final Map<String, Sighting> prevSightings = new HashMap<>();
    private final Map<String, Long> prevTicks = new HashMap<>();
    private final List<Target> targets = new ArrayList<>();
    /** Persisted explicit whitelist (uuids; legacy slug entries may appear from old saves). */
    private final Set<String> whiteList = new LinkedHashSet<>();

    private static boolean wlMatch(Sighting v, String w) {
        return w.equals(v.id) || w.equals(v.uuid) || (v.slug != null && w.equals(v.slug));
    }

    public boolean isWhitelisted(Sighting v) {
        if (v.implicitWhitelist()) return true;
        for (String w : whiteList) {
            if (wlMatch(v, w)) return true;
        }
        return false;
    }

    /** Feed one scan pass. (ox,oy,oz) is the core's world position for distances. */
    public void updateScan(List<Sighting> sightings, long tick, double ox, double oy, double oz) {
        targets.clear();
        Set<String> seen = new java.util.HashSet<>();
        for (Sighting s : sightings) {
            seen.add(s.id());
            Long prevTick = prevTicks.get(s.id());
            Sighting prev = prevSightings.get(s.id());
            double vx = 0, vy = 0, vz = 0;
            if (prev != null && prevTick != null) {
                double dt = Math.max(tick - prevTick, 1); // ticks; clamp like the Lua version
                vx = (s.x() - prev.x()) / dt;
                vy = (s.y() - prev.y()) / dt;
                vz = (s.z() - prev.z()) / dt;
            }
            prevSightings.put(s.id(), s);
            prevTicks.put(s.id(), tick);
            double dx = s.x() - ox, dy = s.y() - oy, dz = s.z() - oz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            targets.add(new Target(s.id(), s.category(), s.label(), s.x(), s.y(), s.z(),
                    vx, vy, vz, dist, isWhitelisted(s), s.health(), s.maxHealth(), s.species()));
        }
        // Lua flag sweep: anything absent from this pass is gone (left radius / died / despawned)
        prevSightings.keySet().retainAll(seen);
        prevTicks.keySet().retainAll(seen);
        targets.sort(Comparator.comparingDouble(Target::dist));
    }

    /** Current tracked targets, nearest first (unmodifiable snapshot view of the last scan). */
    public List<Target> targets() {
        return List.copyOf(targets);
    }

    /** Lockable = tracked and not whitelisted. */
    public List<Target> lockCandidates() {
        List<Target> out = new ArrayList<>();
        for (Target t : targets()) {
            if (!t.whitelisted()) out.add(t);
        }
        return out;
    }

    /** Tracked target by id (task drive path), null when gone. */
    public @Nullable Target targetById(String id) {
        for (Target t : targets()) {
            if (t.id().equals(id)) return t;
        }
        return null;
    }

    /**
     * whiteListToggle(): if any entry matches, remove all matches (returns false);
     * otherwise add the stable id (returns true). Whitelisted means never engaged
     * — the caller (core) evicts the target from every channel task.
     */
    public boolean toggleWhitelist(Sighting s) {
        boolean removed = whiteList.removeIf(w -> wlMatch(s, w));
        if (removed) return false;
        whiteList.add(s.id());
        return true;
    }

    public List<String> whiteListEntries() {
        return List.copyOf(whiteList);
    }

    public void loadWhiteList(List<String> entries) {
        whiteList.clear();
        whiteList.addAll(entries);
    }
}
