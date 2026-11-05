package com.bridgecore.firecontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.bridgecore.firecontrol.FireDirector.Gate;
import com.bridgecore.firecontrol.FireDirector.Input;
import com.bridgecore.firecontrol.FireDirector.Mode;

/** Gate-chain ordering contract for the fire director (2026-10-08): first
 *  failure wins, LOF is lazy, volley skips the channel/cadence gates. */
class FireDirectorTest {

    private static Input auto(boolean hasTarget, boolean grouped, boolean autoFireOn,
                              boolean allReady, double err, boolean inArc,
                              boolean cadenceReady) {
        return new Input(Mode.AUTO, hasTarget, grouped, autoFireOn, allReady, "炮座1过热",
                err, inArc, cadenceReady);
    }

    @Test
    void clearAutoFires() {
        var r = FireDirector.evaluate(auto(true, true, true, true, 0.5, true, true), () -> false);
        assertEquals(Gate.FIRE, r.gate());
        assertTrue(r.fires());
    }

    @Test
    void firstFailureWins() {
        // no target beats every later gate
        assertEquals(Gate.NO_TARGET,
                FireDirector.evaluate(auto(false, false, false, false, 99, false, false), () -> true).gate());
        // ungrouped beats the channel auto-fire switch
        assertEquals(Gate.NO_CHANNEL,
                FireDirector.evaluate(auto(true, false, false, false, 99, false, false), () -> true).gate());
        // channel auto-fire off beats readiness (2026-10-27 拆分: 与未编组是两个态)
        assertEquals(Gate.AUTO_OFF,
                FireDirector.evaluate(auto(true, true, false, false, 99, false, false), () -> true).gate());
        // readiness beats aiming
        assertEquals(Gate.NOT_READY,
                FireDirector.evaluate(auto(true, true, true, false, 99, false, false), () -> true).gate());
        // aiming beats arc
        assertEquals(Gate.AIMING,
                FireDirector.evaluate(auto(true, true, true, true, 99, false, false), () -> true).gate());
        // arc beats cadence
        assertEquals(Gate.OUT_OF_ARC,
                FireDirector.evaluate(auto(true, true, true, true, 0.5, false, false), () -> true).gate());
        // cadence beats LOF
        assertEquals(Gate.CADENCE,
                FireDirector.evaluate(auto(true, true, true, true, 0.5, true, false), () -> true).gate());
        // LOF last
        assertEquals(Gate.BLOCKED,
                FireDirector.evaluate(auto(true, true, true, true, 0.5, true, true), () -> true).gate());
    }

    @Test
    void aimGateThresholdIsExclusive() {
        // err == AIM_GATE_DEG must NOT fire (legacy check was err < 1.5)
        assertEquals(Gate.AIMING, FireDirector.evaluate(
                auto(true, true, true, true, FireDirector.AIM_GATE_DEG, true, true), () -> false).gate());
        assertTrue(FireDirector.evaluate(
                auto(true, true, true, true, FireDirector.AIM_GATE_DEG - 1e-9, true, true), () -> false).fires());
    }

    @Test
    void lofIsLazy() {
        AtomicBoolean called = new AtomicBoolean(false);
        // blocked at AIMING: the raycast must never run
        FireDirector.evaluate(auto(true, true, true, true, 99, true, true),
                () -> { called.set(true); return true; });
        assertFalse(called.get());
        // everything clear: the raycast decides
        assertTrue(FireDirector.evaluate(auto(true, true, true, true, 0.5, true, true),
                () -> { called.set(true); return false; }).fires());
        assertTrue(called.get());
    }

    @Test
    void volleySkipsChannelAndCadence() {
        Input v = new Input(Mode.VOLLEY, true, false, false, true, "", 0.5, true, false);
        assertTrue(FireDirector.evaluate(v, () -> false).fires());
        // but the shared gates still hold in volley mode
        Input v2 = new Input(Mode.VOLLEY, true, false, false, false, "炮座1过热", 0.5, true, false);
        assertEquals(Gate.NOT_READY, FireDirector.evaluate(v2, () -> false).gate());
    }

    @Test
    void detailCarriesThrough() {
        var r = FireDirector.evaluate(auto(true, true, true, false, 0.5, true, true), () -> false);
        assertEquals("炮座1过热", r.detail());
    }
}
