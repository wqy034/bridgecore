package com.bridgecore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintHandle;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;

/**
 * Angle-direct-write access to the aeronautics swivel bearing (phase-3 fire
 * control). Same primitive as the cbc_ee simulated_swivel_bearing peripheral
 * (handoff: cbc-ee-swivel-bearing-handoff.md §1): with no kinetic input the
 * target-angle register is static, so writing it IS angle direct-write; the
 * aeronautics inertia-adaptive PD does the servo work. Callers must observe
 * the wake-up discipline (see SwivelTurretActuator.wakeAfterWrite).
 */
@Mixin(SwivelBearingBlockEntity.class)
public interface SwivelBearingAccessorMixin {

    @Accessor("targetAngleDegrees")
    double bridgecore$getTargetAngleDegrees();

    @Accessor("targetAngleDegrees")
    void bridgecore$setTargetAngleDegrees(double degrees);

    /** AxisLock (kinematic takeover): read the native constraint handle so
     *  suppression only fires (and only logs) when a handle actually exists. */
    @Accessor("handle")
    RotaryConstraintHandle bridgecore$getHandle();

    /** AxisLock (kinematic takeover): drop the native rotary constraint; the
     *  BE tolerates handle == null while assembled (validateConstraintHandle /
     *  updateServoCoefficients both early-out on null). Callers must wrap in
     *  SwivelBearingAxisLock's suppression guard so the removeHandle mixin
     *  hook does not mistake this for a native detach. */
    @Invoker("removeHandle")
    void bridgecore$invokeRemoveHandle();
}
