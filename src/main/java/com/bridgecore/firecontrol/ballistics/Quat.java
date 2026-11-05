package com.bridgecore.firecontrol.ballistics;

/**
 * Quaternion, ported line-by-line from OnCannon.lua (quatMultiply /
 * RotateVectorByQuat / negaQ). Operation order matches the Lua source so
 * floating-point results reproduce the golden vectors.
 */
public record Quat(double w, double x, double y, double z) {

    public static final Quat IDENTITY = new Quat(1, 0, 0, 0);

    /** OnCannon.lua quatMultiply(q1, q2) with this = q1. */
    public Quat multiply(Quat q2) {
        return new Quat(
                w * q2.w - x * q2.x - y * q2.y - z * q2.z,
                w * q2.x + x * q2.w + y * q2.z - z * q2.y,
                w * q2.y + y * q2.w + z * q2.x - x * q2.z,
                w * q2.z + z * q2.w + x * q2.y - y * q2.x);
    }

    /** OnCannon.lua RotateVectorByQuat(quat, v). */
    public Vec3 rotate(Vec3 v) {
        double x2 = x * 2, y2 = y * 2, z2 = z * 2;
        double xx = x * x2, yy = y * y2, zz = z * z2;
        double xy = x * y2, xz = x * z2, yz = y * z2;
        double wx = w * x2, wy = w * y2, wz = w * z2;
        return new Vec3(
                (1 - (yy + zz)) * v.x() + (xy - wz) * v.y() + (xz + wy) * v.z(),
                (xy + wz) * v.x() + (1 - (xx + zz)) * v.y() + (yz - wx) * v.z(),
                (xz - wy) * v.x() + (yz + wx) * v.y() + (1 - (xx + yy)) * v.z());
    }

    /** OnCannon.lua negaQ: conjugate (w kept, xyz negated). */
    public Quat negate() {
        return new Quat(w, -x, -y, -z);
    }
}
