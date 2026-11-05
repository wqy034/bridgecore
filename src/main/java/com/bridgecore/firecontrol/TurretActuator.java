package com.bridgecore.firecontrol;

/**
 * Execution-backend abstraction (roadmap §3): the fire-control core does not
 * bind itself to one actuator kind. Backends by maturity: swivel-bearing
 * direct-write (phase 3), cbc_ee cannon mount (later), servo motors (frozen,
 * no Java hook will ever be written).
 *
 * <p>Phase-3 ruling (user, 2026-09-12): aim-only — the fire path stays
 * unimplemented until the cbc_ee single-block mount lands; {@link #fire} is
 * part of the contract so later phases don't reshape the interface.
 */
public interface TurretActuator {

    /** Paired, calibrated, both joints assembled and locked — ready to aim. */
    boolean isReady();

    /** Current angle between the actual and desired muzzle directions,
     *  degrees (the fire gate's input). NaN when not aiming. */
    double getAimingErrorDeg();

    /** One-line diagnostic for the chat status channel. */
    String statusLine();

    /** Fire path — NOT wired in phase 3 (aim-only ruling); returns false. */
    default boolean fire() {
        return false;
    }
}
