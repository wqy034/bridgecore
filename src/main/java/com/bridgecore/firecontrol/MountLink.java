package com.bridgecore.firecontrol;

import org.jetbrains.annotations.Nullable;

import com.bridgecore.firecontrol.ballistics.SolveChain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Gun-mount link abstraction (火力指挥层②, 2026-10-08): every weapon mount the
 * fire director can drive — fe_railgun today, vanilla CBC cannon mounts and
 * the macro-cannon peripheral later — hides behind this single interface.
 * The state machine and the gate chain talk ONLY to MountLink; there are no
 * scattered {@code if (isFeRailgun)} checks.
 *
 * <p>Opaque-handle style (same soft-dependency pattern as RailgunLink):
 * callers hold handles as {@code Object} so an implementation whose backing
 * mod is absent never gets its types loaded. Handles are re-resolved every
 * tick via {@link #mountAt} — a broken/disassembled mount drops out
 * immediately.
 *
 * <p>Capability degradation is the contract's core: an implementation that
 * cannot know a state (a vanilla CBC mount has no FE/heat concept) reports
 * the closest STATUS_* it can justify and never invents precision — the
 * director treats unknown-ready as ready-at-own-risk, matching that mount
 * type's semantics.
 */
public interface MountLink {

    /** Normalized mount status codes (shared semantics across mount types;
     *  broadcast to the GUI row via FireControlMenu +10). */
    int STATUS_NO_MOUNT = -1;            // no mount paired / block gone
    int STATUS_GUN_NONE = 0;             // mount present, no gun assembled
    int STATUS_READY = 1;
    int STATUS_NOT_ASSEMBLED = 2;
    int STATUS_NO_AMMO = 3;
    int STATUS_INSUFFICIENT_ENERGY = 4;
    int STATUS_OVERHEATED = 5;
    int STATUS_BREECH_BUSY = 6;

    /** True when the backing mod/integration is present in this instance. */
    boolean available();

    /** The mount at a (yard-frame) position, or null when this link does not
     *  recognize the block (or the backing mod is absent). */
    @Nullable Object mountAt(Level level, BlockPos pos);

    /** GUI row status code: STATUS_NO_MOUNT / STATUS_GUN_NONE / readiness. */
    int mountStatusCode(@Nullable Object handle);

    /** Atomic fire (the mount re-runs readiness internally — READY only
     *  fires, failures consume nothing).
     *  @return null on FIRED, otherwise a short rejection tag. */
    @Nullable String fire(@Nullable Object handle);

    /** True when the handle is valid AND a gun is assembled on it. */
    boolean hasGun(@Nullable Object handle);

    /** True when the mounted gun has NO breech cycle (pure-FE / self-paced
     *  types) — only these get the cadence stepper; breeched guns pace at
     *  their own breech and a user cadence below that is meaningless. */
    boolean breechless(@Nullable Object handle);

    /** Cache-invalidation signature for the per-turret solver params: any
     *  field that feeds {@link #paramsFor}. "" for no usable mount. */
    String signature(@Nullable Object handle);

    /** Build the solver params for this mount from the live gun readings;
     *  returns {@code base} unchanged when the mount is unusable. */
    SolveChain.Params paramsFor(@Nullable Object handle, SolveChain.Params base,
                                double barrelLen);

    /** Stable gun-type tag ("RAILGUN"/"PARTICLE"/…, "NONE" when unusable). */
    String gunTypeName(@Nullable Object handle);

    /** Live muzzle velocity, m/s (0 when absent/NONE). */
    double muzzleVelocity(@Nullable Object handle);

    /** Optional read-only FE telemetry. Unsupported weapons return null,
     * distinct from a supported empty store (available=0, capacity>0). */
    record EnergyReading(long available, long capacity) {}

    default @Nullable EnergyReading energyReading(@Nullable Object handle) {
        return null;
    }

    double heat(@Nullable Object handle);

    double maxHeat(@Nullable Object handle);
}
