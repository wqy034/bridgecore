package com.bridgecore.mixin;

import com.bridgecore.firecontrol.AxisLockAssemblyZero;
import com.bridgecore.firecontrol.SwivelBearingAxisLock;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintHandle;

/**
 * AxisLock lifecycle hooks on the aeronautics swivel bearing (fire-control
 * kinematic takeover — mechanism lives in {@link SwivelBearingAxisLock}).
 *
 * <p>register: attachConstraints RETURN, handle non-null (attach succeeded —
 * the method early-returns with handle still null on platePos null / non-link
 * block / same sublevel). Also re-suppresses immediately when the bearing is
 * claimed: the native checkPersistence re-attaches the constraint whenever
 * handle == null, so the RETURN hook kills it in the same call stack (zero
 * physics substeps with a live constraint).
 *
 * <p>unregister: removeHandle HEAD (native detach — our own suppression is
 * flag-guarded inside SwivelBearingAxisLock) and remove HEAD (BE broken /
 * unloaded). validateConstraintHandle nulling the handle directly is covered
 * lazily by the enforce loop's stale-entry checks.
 *
 * <p>suppress sweep: updateServoCoefficients TAIL (native servo refresh path).
 *
 * <p>All hooks are try/catch wrapped at the AxisLock entry points: this
 * experiment must never break the native attach/disassemble flow.
 */
@Mixin(SwivelBearingBlockEntity.class)
public abstract class SwivelBearingAxisLockMixin {

    @Shadow
    private RotaryConstraintHandle handle;

    @Inject(method = "attachConstraints", at = @At("RETURN"))
    private void bridgecore$axisLockRegister(ServerSubLevel childSub, Vector3d attachPos, CallbackInfo ci) {
        if (handle == null || childSub == null) return;
        SwivelBearingAxisLock.onAttach((SwivelBearingBlockEntity) (Object) this, childSub, attachPos);
    }

    @Inject(method = "removeHandle", at = @At("HEAD"))
    private void bridgecore$axisLockOnRemoveHandle(CallbackInfo ci) {
        SwivelBearingAxisLock.onNativeRemoveHandle((SwivelBearingBlockEntity) (Object) this);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void bridgecore$axisLockOnRemove(CallbackInfo ci) {
        SwivelBearingAxisLock.onBearingRemoved((SwivelBearingBlockEntity) (Object) this);
    }

    @Inject(method = "updateServoCoefficients", at = @At("TAIL"))
    private void bridgecore$axisLockServoSweep(CallbackInfo ci) {
        SwivelBearingAxisLock.onServoCoefficients((SwivelBearingBlockEntity) (Object) this);
    }

    /** 第三批 step1 (§2.31): assembly-zero capture — TAIL is gated on
     *  isAssembled() && getSubLevelID() != null inside, so a bailed assemble
     *  writes nothing. */
    @Inject(method = "assemble", at = @At("TAIL"))
    private void bridgecore$assemblyZeroCapture(CallbackInfo ci) {
        AxisLockAssemblyZero.onAssemble((SwivelBearingBlockEntity) (Object) this);
    }

    /** 第三批 step1 (§2.31): disassemble = full invalidation point. */
    @Inject(method = "disassemble", at = @At("HEAD"))
    private void bridgecore$assemblyZeroForget(CallbackInfo ci) {
        AxisLockAssemblyZero.onDisassemble((SwivelBearingBlockEntity) (Object) this);
    }
}
