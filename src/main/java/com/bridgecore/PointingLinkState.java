package com.bridgecore;

/**
 * Pointing link state (interface v1 §2.3, frozen 2026-09-10): visible in
 * telemetry (TelemetrySnapshot.pointingLink) and both sides' GUIs.
 *
 * Until pointing EXECUTION lands (scheduled before fire-control phase 4), the
 * link never reaches SETTLED — a live request reads REQUESTING and
 * pointingSettled stays false.
 */
public enum PointingLinkState {
    /** No requester linked. */
    DISCONNECTED,
    /** A requester is linked but holds no active pointing request (it asked
     *  for OFF and has not disconnected). */
    CONNECTED,
    /** Active pointing request accepted, execution working toward the target. */
    REQUESTING,
    /** Pointing settled on target (body axis within the completion threshold).
     *  Unreachable until execution lands. */
    SETTLED
}
