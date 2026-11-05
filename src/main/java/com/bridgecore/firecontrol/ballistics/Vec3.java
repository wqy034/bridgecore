package com.bridgecore.firecontrol.ballistics;

/**
 * Minimal 3D vector for the pure-Java ballistics port of OnCannon.lua.
 * Deliberately free of any Minecraft/NeoForge types.
 */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public Vec3 scale(double s) {
        return new Vec3(x * s, y * s, z * s);
    }

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 sub(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o) {
        return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** Unit vector, or null when degenerate (< 1e-9) — mirrors the Lua vnorm. */
    public Vec3 normalized() {
        double l = length();
        if (l < 1e-9) return null;
        return new Vec3(x / l, y / l, z / l);
    }
}
