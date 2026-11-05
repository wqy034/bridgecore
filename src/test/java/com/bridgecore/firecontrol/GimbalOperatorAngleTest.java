package com.bridgecore.firecontrol;

import com.bridgecore.firecontrol.ballistics.Quat;
import com.bridgecore.firecontrol.ballistics.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GimbalOperatorAngleTest {
    private static Vec3 rotate(Vec3 axis, double degrees, Vec3 forward) {
        double half = Math.toRadians(degrees) / 2;
        return new Quat(Math.cos(half), axis.x() * Math.sin(half), axis.y() * Math.sin(half), axis.z() * Math.sin(half)).rotate(forward);
    }
    @Test void mirroredBearingsBothReportUpPositiveForEveryCardinalBarrel() {
        for (Vec3 forward : new Vec3[]{new Vec3(1,0,0), new Vec3(-1,0,0), new Vec3(0,0,1), new Vec3(0,0,-1)}) {
            Vec3 left = forward.cross(new Vec3(0,1,0));
            for (Vec3 axis : new Vec3[]{left, left.scale(-1)}) {
                int expected = axis.cross(forward).y() > 0 ? 1 : -1;
                for (double elevation : new double[]{-60,-10,0,30,80,130}) {
                    double raw = expected * elevation;
                    int sense = GimbalMath.pitchUpSense(axis, rotate(axis, raw, forward), raw);
                    assertEquals(expected, sense);
                    assertEquals(elevation, sense * raw, 1e-8);
                }
            }
        }
    }
    @Test void asymmetricElevationLimitsClampTheSamePhysicalSideOnBothMounts() {
        for (int sense : new int[]{-1,1}) {
            assertEquals(sense * 20, GimbalMath.clampJointDelta(sense * 60, sense * 40, -10, 80, sense), 1e-9);
            assertEquals(sense * -10, GimbalMath.clampJointDelta(0, sense * -30, -10, 80, sense), 1e-9);
            assertEquals(sense * 5, GimbalMath.clampJointDelta(sense * 20, sense * 5, -10, 80, sense), 1e-9);
        }
    }
    @Test void yawConventionIsUnchanged() {
        for (double relative : new double[]{-170,-20,0,25,160})
            for (double delta : new double[]{-90,-.1,0,.1,90})
                assertEquals(Math.max(-100, Math.min(120, relative + delta)) - relative,
                        GimbalMath.clampJointDelta(relative, delta, -100, 120, 1), 1e-9);
    }
    @Test void degenerateZeroHasNoInventedUpSign() {
        assertEquals(0, GimbalMath.pitchUpSense(new Vec3(0,1,0), new Vec3(1,0,0), 0));
        assertEquals(0, GimbalMath.pitchUpSense(Vec3.ZERO, new Vec3(1,0,0), 0));
        assertEquals(0, GimbalMath.pitchUpSense(new Vec3(0,0,1), new Vec3(1,0,0), Double.NaN));
    }
}
