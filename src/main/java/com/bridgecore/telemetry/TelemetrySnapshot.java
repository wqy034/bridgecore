package com.bridgecore.telemetry;

import com.bridgecore.BridgeCoreBlockEntity;
import com.bridgecore.PointingLinkState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3dc;
import org.joml.Quaterniondc;

/**
 * Immutable telemetry snapshot of one bridge core's structure (interface v1
 * §1.1 field table + the frozen 2026-09-10 additions: structureId,
 * pointingSettled, pointingLink). Produced on the PHYSICS frame (40 Hz),
 * published via a volatile reference on the block entity; the fire-control
 * module (same mod, same jar) polls
 * {@code BridgeCoreBlockEntity.telemetrySnapshot()} from the server thread at
 * 20 Hz. Never mutate the joml views you get from here — the record hands out
 * read-only interfaces over per-snapshot copies.
 *
 * Frames/units (verified against the Sable 2.0.3 sources, 2026-09-11):
 * <ul>
 *   <li>position: world coordinates of the structure's center of mass —
 *       Sable's assembly path sets {@code logicalPose().position()} to the
 *       mass tracker's CoM (SubLevelAssemblyHelper), so the pose origin IS the
 *       CoM (interface §1.1 "质心世界坐标").</li>
 *   <li>velocity / acceleration: world frame, m/s and m/s². acceleration is a
 *       per-physics-frame numerical derivative of velocity, EMA-smoothed
 *       (tau ≈ 0.1 s) and magnitude-clamped at the exit (a garbage guard for
 *       physics spikes, not a signal shaper).</li>
 *   <li>orientation: body frame -> world frame quaternion.</li>
 *   <li>angularVelocity: BODY frame, rad/s (converted from the physics handle's
 *       world-frame reading via the orientation inverse — interface §1.1).</li>
 *   <li>mass kg. centerOfMass: MassTracker's own output frame — shipyard
 *       block-grid coordinates of the structure (structure-local, NOT
 *       CoM-relative). inertiaTensor: body frame about the CoM, kg·m²
 *       (MassTracker builds r = blockCenter - CoM, unrotated).</li>
 *   <li>energy*: structure distributor pool aggregate (same figures the HUD
 *       energy line shows); consumptionRate in FE/t (smoothed actual draw).</li>
 *   <li>gear: 1-based, same numbering as the GUI (G1..G5).</li>
 *   <li>structureId: {@code "<dimension>:<plotX>:<plotY>"} where plotX/plotY
 *       are the ServerSubLevel constructor's global plot coordinates, exposed
 *       publicly as {@code sub.getPlot().plotPos.x / .z} (Sable names the
 *       second axis "plotY" but stores it as the ChunkPos z). Stable for the
 *       structure's lifetime; a split-off structure allocates a new plot hence
 *       a new id. (Sable also has SubLevel.getUniqueId(); the plot-based id was
 *       the scheme frozen in the 2026-09-11 reply letter.)</li>
 *   <li>pointingSettled: always false and pointingLink driven by the request
 *       state machine until pointing execution lands (see PointingLinkState).</li>
 * </ul>
 */
public record TelemetrySnapshot(
        Vec3 position,
        Vec3 velocity,
        Vec3 acceleration,
        Quaterniondc orientation,
        Vec3 angularVelocity,
        double mass,
        Vec3 centerOfMass,
        Matrix3dc inertiaTensor,
        long energyStored,
        long energyCapacity,
        double consumptionRate,
        BridgeCoreBlockEntity.ControlMode flightMode,
        int gear,
        String structureId,
        boolean pointingSettled,
        PointingLinkState pointingLink) {
}
