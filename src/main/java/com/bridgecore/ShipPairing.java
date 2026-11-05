package com.bridgecore;

import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Shared pilot-channel pairing metric (gamepad + keyboard typewriter), extracted
 * from the 2026-09-09 gamepad fix so both channels run ONE logic: on a
 * physicalized structure, pair when the input source is inside the structure's
 * real bounding box plus {@link #PAIR_MARGIN} (a pose-origin point radius fails
 * on big hulls — the modpack ship linked at dist=50.1 > 32); a bench core with
 * no structure falls back to a {@link #PAIR_RANGE} point radius around the core.
 *
 * Two coordinate frames exist and must not be mixed: players live in world
 * space (pair against {@link #worldBounds}), block entities live at shipyard
 * coordinates (pair against {@link #shipyardBounds} — Sable's plot bounds are
 * the ship-local frame the pose maps to world, which IS the blocks' coordinate
 * space).
 */
final class ShipPairing {
    /** Bench-core fallback: max core<->source distance, blocks. */
    static final double PAIR_RANGE = 32.0;
    /** Structured ship: pair inside the structure's bounding box plus this
     *  margin (deck/bridge devices sit ON the hull). */
    static final double PAIR_MARGIN = 8.0;

    /** Distance from a point to an AABB (0 when inside). */
    static double distanceTo(AABB box, Vec3 p) {
        double dx = Math.max(box.minX - p.x, Math.max(0.0, p.x - box.maxX));
        double dy = Math.max(box.minY - p.y, Math.max(0.0, p.y - box.maxY));
        double dz = Math.max(box.minZ - p.z, Math.max(0.0, p.z - box.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** World-space structure bounds (gamepad: the player walks in world space). */
    static AABB worldBounds(ServerSubLevel sub) {
        BoundingBox3dc bb = sub.boundingBox();
        return new AABB(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());
    }

    /** Shipyard-space structure bounds (keyboard: the typewriter BE sits at
     *  shipyard coordinates). Max corner +1 to cover the full block volume,
     *  same convention as Sable's own global-bounds derivation. Null when the
     *  plot bounds are momentarily unavailable — callers fall back to the
     *  point radius. */
    static @Nullable AABB shipyardBounds(ServerSubLevel sub) {
        if (sub.getPlot() == null || sub.getPlot().getBoundingBox() == null) {
            return null;
        }
        BoundingBox3ic bb = sub.getPlot().getBoundingBox();
        return new AABB(bb.minX(), bb.minY(), bb.minZ(),
                bb.maxX() + 1.0, bb.maxY() + 1.0, bb.maxZ() + 1.0);
    }

    private ShipPairing() {
    }
}
