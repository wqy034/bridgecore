package com.bridgecore.display;

import com.bridgecore.BCConfig;
import com.bridgecore.BridgeCoreBlockEntity;
import com.bridgecore.CoreDirectory;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

/** HUD-only candidate construction. Uses the exact calibrated eye, panel
 * projection and live entity body center used by HudLockBoxLayer. Candidates
 * obey the visible panel, configured cone, explicit category/species and LOS.
 * Only the command HUD consumes V species edges; mirrors remain read-only.
 * Actual tasks/anchors are owned by fire control, not this roaming focus. */
public final class HudTrackFilter {
    /** LOS raycast revalidation interval, ticks (0.5 s at 20 Hz). */
    private static final int LOS_RECHECK_TICKS = 10;
    /** Cache bound: stale ids of long-gone tracks linger; clear wholesale. */
    private static final int LOS_CACHE_MAX = 512;

    private record Scored(HudTargetProvider.Track track, double offAxis) {
    }

    private record LosEntry(long gameTime, Vec3 eye, Vec3 target, boolean visible) {
    }

    /** @param selectedId the HUD-side primary selection (null = none, box not drawn)
     *  @param stateText ASCII filter-state readout for the HUD corner
     *  (e.g. "MONSTER/ZOMBIE", "OFF" in the v3.1 no-lock notch)
     *  @param lockedIds focus first, then all explicit-filter candidates;
     *  yellow until the core reports the id as an actual assignment.
     *  @param candidateIds the full cone+category+species survivor id list in
     *  off-axis order — the command HUD's PrelockUpdate source (§2.29 §3).
     *  @param targetsOff true in the no-lock Tab notch: the renderer draws NO
     *  roam layer (no aux boxes, no dim triangles) — only the state text and
     *  the core's own task/red-box layer survive. */
    public record Result(@Nullable String selectedId, String stateText,
                         List<String> lockedIds, List<String> candidateIds,
                         boolean targetsOff) {
    }

    private @Nullable String selectedId;
    private int lastCategory = Integer.MIN_VALUE;
    private @Nullable String lastSpecies;
    private int lastSpeciesSerial = -1;
    /** Last computed candidate id list (off-axis ascending); the prelock set. */
    private List<String> candidateIds = List.of();
    /** Explicit V can choose another visible contact outside the MULTI cone. */
    private List<String> cycleCandidateIds = List.of();
    private final java.util.Map<String, LosEntry> losCache = new java.util.HashMap<>();

    /** @param categoryNotch the Tab notch, owned by the structure's flight
     *  core since 2026-10-30 (修订 C: structure-level, mirrored to every
     *  screen): -1 = OFF (target layer fully off), 0..3 = Category ordinal. */
    public Result update(Level level, @Nullable ServerSubLevel sub, BlockPos hudPos,
                         Direction facing, HudTargetProvider.HudTargetSnapshot snap,
                         HudLockBoxLayer.Calib cal, org.joml.Quaterniondc shipOrientation,
                         int categoryNotch, boolean commandHud) {
        // Species/serial/range state lives on the structure's bridge core
        // (persisted there); the Tab category notch arrives as a parameter.
        int catOrdinal = categoryNotch;
        String species = null;
        int serial = 0;
        int coneDeg = BridgeCoreBlockEntity.HUD_LOCK_CONE_DEFAULT;
        int maxCount = BridgeCoreBlockEntity.HUD_LOCK_MAX_COUNT_DEFAULT;
        BridgeCoreBlockEntity core = null;
        if (sub != null) {
            var cores = CoreDirectory.coresOf(sub);
            if (!cores.isEmpty()) {
                core = cores.get(0);
                species = core.hudLockSpecies();
                serial = core.hudLockSpeciesSerial();
                coneDeg = core.hudLockConeDeg();
                maxCount = core.hudLockMaxCount();
            }
        }
        boolean offNotch = catOrdinal < 0;

        // ---- cone cull + off-axis score (geometry identical to what the scan
        //      side validated: origin/forward converted through logicalPose).
        //      OFF notch: the HUD does NOT scan — no cone, no LOS raycasts,
        //      no candidates. ----
        HudLockBoxLayer.PanelGeometry geo = HudLockBoxLayer.panelGeometry(
                sub, hudPos, facing, shipOrientation, cal);
        List<Scored> cone = new ArrayList<>();
        if (!offNotch && geo != null) {
            Vector3d eyeOffset = new Vector3d(geo.eye()).sub(geo.centerW());
            double eyeDepth = eyeOffset.dot(geo.normW());
            Vec3 losEye = new Vec3(geo.eye().x, geo.eye().y, geo.eye().z);
            for (HudTargetProvider.Track t : snap.tracks()) {
                // Same live body center and calibrated eye/basis as drawing.
                Vector3d p = HudLockBoxLayer.freshPos(level, t.id(), t.pos());
                Vector3d ray = new Vector3d(p).sub(geo.eye());
                var projected = HudProjectionMath.project(ray.dot(geo.rightW()), ray.dot(geo.upW()),
                        -ray.dot(geo.normW()), eyeOffset.dot(geo.rightW()),
                        eyeOffset.dot(geo.upW()), eyeDepth);
                if (projected == null || !projected.onPanel(geo.half())) continue;
                Vec3 target = new Vec3(p.x, p.y, p.z);
                if (t.category() != HudTargetProvider.Category.SHIP
                        && !losVisible(level, t, losEye, target, hudPos)) continue;
                cone.add(new Scored(t, projected.offAxis()));
            }
            cone.sort(Comparator.comparingDouble(Scored::offAxis)
                    .thenComparing(v -> v.track().id()));
        }

        // ---- category filter (Tab state; 2026-09-25 ruling: a filter change
        //      is explicit user intent and drops the selection) ----
        HudTargetProvider.Category cat = catOrdinal < 0
                ? null : HudTargetProvider.Category.values()[catOrdinal];
        if (catOrdinal != lastCategory) {
            lastCategory = catOrdinal;
            selectedId = null;
        }
        List<Scored> catFiltered = new ArrayList<>(cone.size());
        for (Scored s : cone) {
            if (cat == null || s.track().category() == cat) {
                catFiltered.add(s);
            }
        }

        // ---- species filter (V): advance within the species actually present
        //      in the cone+category set; null = all. The serial bump is the
        //      core's signal; the list only exists here, so the advance (and
        //      the write-back) happens filter-side. Inert in the OFF notch
        //      (serial still re-synced, so leaving OFF does not fire a stale
        //      edge). v3: V's pinned-mode target cycle is GONE — precise-task
        //      target switching rides the flight core's V dispatch to
        //      cyclePreciseTarget (2026-10-30 GPT-designed narrow entry). ----
        if (serial != lastSpeciesSerial) {
            boolean initialized = lastSpeciesSerial != -1;
            lastSpeciesSerial = serial;
            // Mirrors never advance shared state; initial/reconnect sync is not V.
            if (initialized && commandHud && !offNotch && core != null) {
                TreeSet<String> present = new TreeSet<>();
                for (Scored s : catFiltered) {
                    present.add(s.track().species());
                }
                List<String> notches = new ArrayList<>(present.size() + 1);
                notches.add(null); // "all species" notch first
                notches.addAll(present);
                int idx = notches.indexOf(species);
                species = notches.get((idx + 1 + notches.size()) % notches.size());
                core.setHudLockSpecies(species);
            }
        }
        if (!Objects.equals(species, lastSpecies)) {
            lastSpecies = species;
            selectedId = null; // species notch changed — drop the selection
        }
        List<Scored> filtered = new ArrayList<>(catFiltered.size());
        for (Scored s : catFiltered) {
            if (species == null || s.track().species().equals(species)) {
                filtered.add(s);
            }
        }
        // The prelock candidate set (§2.29 §3): full filtered survivors,
        // off-axis ascending. The command HUD pushes this (capped) as its
        // PrelockUpdate heartbeat; V's precise-target cycle walks it too.
        List<String> candidates = new ArrayList<>(filtered.size());
        for (Scored s : filtered) {
            candidates.add(s.track().id());
        }
        cycleCandidateIds = List.copyOf(candidates);
        // Species cycling sees visible contacts; engagement uses the configured
        // cone. Background contacts outside it still draw as triangles.
        double maxOffAxis = Math.tan(Math.toRadians(coneDeg));
        filtered.removeIf(v -> v.offAxis() > maxOffAxis);
        candidates.clear();
        for (Scored v : filtered) candidates.add(v.track().id());
        candidateIds = List.copyOf(candidates);

        // Roam focus is valid only in the current candidates. Anchor retention
        // belongs to the authoritative precise task, never a stale local id.
        Scored best = filtered.isEmpty() ? null : filtered.get(0);
        Scored current = null;
        for (Scored v : filtered) if (v.track().id().equals(selectedId)) current = v;
        if (best == null) selectedId = null;
        else if (current == null || best.offAxis() < current.offAxis() * 0.7)
            selectedId = best.track().id();
        String effective = selectedId;

        // ---- roaming selection = PURE DISPLAY, zero write-back (2026-09-30
        //      ruling, carried into v3): hysteresis switches, auto-reselects
        //      and Tab/V filter-change reselects NEVER reach the core as
        //      commands. The only uplink is the command HUD's PrelockUpdate
        //      heartbeat of the candidate set (§2.29 §3). ----

        String stateText = (cat == null ? "OFF" : cat.name()) + "/"
                + (species == null ? "ALL" : species.toUpperCase(Locale.ROOT));

        // Every explicit-filter survivor is eligible, not only the focus species.
        List<String> ids = new ArrayList<>(maxCount);
        if (effective != null) ids.add(effective);
        for (Scored v : filtered) {
            if (ids.size() >= maxCount) break;
            if (!v.track().id().equals(effective)) ids.add(v.track().id());
        }
        List<String> lockedIds = List.copyOf(ids);
        return new Result(effective, stateText, lockedIds, candidateIds, offNotch);
    }

    /** The crosshair-focus candidate id (G's focusId source; null = none). */
    public @Nullable String focusId() {
        return selectedId;
    }

    /** The last computed prelock candidate id list (off-axis ascending). */
    public List<String> candidateIds() {
        return candidateIds;
    }

    /** V precise-target cycle (2026-10-30): the next candidate after
     *  {@code anchorId} in the off-axis-sorted list, wrapping; null when
     *  there is nothing to switch to. A sole different candidate is valid;
     *  an anchor that fell out of the list restarts at the nearest. */
    public @Nullable String nextCandidateAfter(String anchorId) {
        return HudTargetCycle.next(cycleCandidateIds, anchorId);
    }

    /** Block raycast ray origin -> track position, cached per track id and
     *  revalidated every LOS_RECHECK_TICKS (a dug/built wall takes ≤0.5 s to
     *  matter — cheap enough for the candidate set, never per-tick). MISS =
     *  clear line of sight.
     *
     *  Origin = the pilot's eye in world space (v15: the lock is what the
     *  pilot can see; geometry shared with HudLockBoxLayer). Fallback when
     *  there is no ship (bench HUD, sub == null): start 0.75 m out of the HUD
     *  block center toward the target. Safety rule: a hit on the HUD block
     *  itself, or any hit within 1.5 m of the start, is NOT occlusion (the
     *  eye can sit inside glass/a slab; on a ship the hull lives in plot space
     *  so the logical-space ray cannot hit it anyway). */
    private boolean losVisible(Level level, HudTargetProvider.Track t,
                               @Nullable Vec3 eye, Vec3 target, BlockPos hudPos) {
        long now = level.getGameTime();
        Vec3 center = Vec3.atCenterOf(hudPos);
        Vec3 from = eye != null ? eye
                : center.add(target.subtract(center).normalize().scale(0.75));
        LosEntry e = losCache.get(t.id());
        if (e != null && now - e.gameTime() < LOS_RECHECK_TICKS
                && e.eye().distanceToSqr(from) < 0.0625
                && e.target().distanceToSqr(target) < 0.0625) return e.visible();
        // VISUAL（canOcclude 判定）而非 COLLIDER：玻璃/玻璃板等透明方块不挡视线，
        // 否则驾驶舱前挡玻璃会把 LOS 全灭。实体方块照常遮挡。
        var hit = level.clip(new ClipContext(from, target,
                ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        boolean visible = hit.getType() != HitResult.Type.BLOCK
                || hit.getBlockPos().equals(hudPos)
                || hit.getLocation().distanceTo(from) < 1.5;
        if (losCache.size() >= LOS_CACHE_MAX) {
            losCache.clear();
        }
        losCache.put(t.id(), new LosEntry(now, from, target, visible));
        return visible;
    }

    /** Forget everything (screen broken / feed lost). */
    public void reset() {
        selectedId = null;
        lastSpeciesSerial = -1; // re-sync the edge guard on the next update
        candidateIds = List.of();
        cycleCandidateIds = List.of();
        losCache.clear();
    }
}
