package com.bridgecore;

/**
 * Pointing mode request types (interface v1 §2.1, frozen 2026-09-10): fire
 * control asks flight control to aim a body axis (default: bow +X) at a target.
 * Flight control executes inside its own attitude control law — fire control
 * never writes torque/thrust directly.
 */
public enum PointingMode {
    /** Release pointing; fall back to player control. */
    OFF,
    /** Aim at a world position (payload: Vec3 world coordinates). */
    POINT_AT_POS,
    /** Track an entity (payload: entity UUID). */
    TRACK_ENTITY,
    /** Hold a pure direction (payload: Vec3 unit vector, world frame). */
    POINT_AT_DIR
}
