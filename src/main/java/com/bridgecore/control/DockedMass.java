package com.bridgecore.control;

import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Accumulates mass properties in the pilot body's local frame. */
final class DockedMass {
    private final Vector3d center = new Vector3d();
    private final Matrix3d inertia = new Matrix3d();
    private final Vector3d offset = new Vector3d();
    private final Matrix3d rotation = new Matrix3d();
    private final Matrix3d rotated = new Matrix3d();
    private double mass;

    void reset(double mass, Vector3dc center, Matrix3dc inertia) {
        this.mass = mass;
        this.center.set(center);
        this.inertia.set(inertia);
    }

    void add(double memberMass, Vector3dc memberCenter, Matrix3dc memberInertia, Quaterniondc memberRotation) {
        rotation.set(memberRotation);
        rotated.set(rotation).mul(memberInertia).mul(rotation.transpose());
        inertia.add(rotated);

        double total = mass + memberMass;
        double reducedMass = mass * (memberMass / total);
        offset.set(memberCenter).sub(center);
        double x = offset.x, y = offset.y, z = offset.z;
        double xy = -reducedMass * x * y;
        double xz = -reducedMass * x * z;
        double yz = -reducedMass * y * z;
        inertia.m00(inertia.m00() + reducedMass * (y * y + z * z));
        inertia.m11(inertia.m11() + reducedMass * (x * x + z * z));
        inertia.m22(inertia.m22() + reducedMass * (x * x + y * y));
        inertia.m01(inertia.m01() + xy).m10(inertia.m10() + xy);
        inertia.m02(inertia.m02() + xz).m20(inertia.m20() + xz);
        inertia.m12(inertia.m12() + yz).m21(inertia.m21() + yz);
        center.fma(memberMass / total, offset);
        mass = total;
    }

    double mass() { return mass; }
    Vector3dc center() { return center; }
    Matrix3dc inertia() { return inertia; }
}
