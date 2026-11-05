package com.bridgecore.firecontrol;

import java.util.function.BooleanSupplier;

/**
 * Fire director (火力指挥层, 2026-10-08 architecture ruling): the ONE ordered
 * gate chain every fire entry funnels through — H-key volley, GUI volley
 * button and the per-tick auto-fire all evaluate the SAME gates in the SAME
 * order; they differ only in the mode flag and which turrets are offered.
 *
 * <p>Gate order (first failure wins, cheapest first, the LOF raycast last —
 * the caller supplies it lazily so turrets still swinging never pay for it):
 * <ol>
 *   <li>NO_TARGET — no firing solution this tick (auto: des==null; volley:
 *       aim error NaN, the actuator has never seen a target);</li>
 *   <li>NO_CHANNEL — auto only: ungrouped turret (channel &lt; 0);</li>
 *   <li>AUTO_OFF — auto only: the turret's OWN channel auto-fire switch
 *       (auto-fire looks at the turret's own channel, NOT the active one —
 *       the four channels can each fire independently);</li>
 *   <li>NOT_READY — any mount not READY (多联装: one unready mount silences
 *       the whole turret); detail names the first bad mount;</li>
 *   <li>AIMING — aim error &ge; {@link #AIM_GATE_DEG};</li>
 *   <li>OUT_OF_ARC — a fire-arc clamp is pinning a joint;</li>
 *   <li>CADENCE — auto only: the turret's own fire interval has not elapsed
 *       (volley is the operator's explicit trigger and ignores pacing);</li>
 *   <li>BLOCKED — line of fire crosses the own hull or a sibling turret.
 *       Direction differs by mode: volley casts along the barrel's ACTUAL
 *       direction (the AIMING gate already guarantees cur≈des), auto along
 *       the solved direction.</li>
 * </ol>
 *
 * <p>Pure Java, no MC types: the caller (FireControlCoreBlockEntity) resolves
 * handles, raycasts and Chinese strings; this class owns only the DECISION,
 * so the chain stays unit-testable and its result drivable straight into GUI
 * / HUD status narration (每塔当前卡在哪个闸门).
 */
public final class FireDirector {

    private FireDirector() {}

    /** Settled-aim gate, degrees. Was a literal 1.5 duplicated at the volley
     *  and auto-fire call sites; unified here (2026-10-08). */
    public static final double AIM_GATE_DEG = 1.5;

    public enum Mode { VOLLEY, AUTO }

    public enum Gate {
        FIRE,
        NO_TARGET,
        NO_CHANNEL,
        AUTO_OFF,
        NOT_READY,
        AIMING,
        OUT_OF_ARC,
        CADENCE,
        BLOCKED
    }

    /**
     * @param hasTarget      a firing solution exists this tick
     * @param grouped        auto only: the turret belongs to a channel
     *                       (volley pre-filters by the active channel and
     *                       passes true)
     * @param autoFireOn     auto only: the turret's own channel auto-fire
     *                       switch is on (volley passes true)
     * @param allReady       every mount READY (a turret with zero mounts is
     *                       never offered by the callers)
     * @param notReadyDetail names the first bad mount for the GUI/chat echo
     * @param cadenceReady   auto only: the turret's fire interval elapsed
     *                       (volley passes true)
     */
    public record Input(Mode mode, boolean hasTarget, boolean grouped,
                        boolean autoFireOn, boolean allReady, String notReadyDetail,
                        double aimErrorDeg, boolean inArc, boolean cadenceReady) {}

    public record GateResult(Gate gate, String detail) {
        public boolean fires() {
            return gate == Gate.FIRE;
        }
    }

    /**
     * Evaluate the chain. {@code lofBlocked} is queried ONLY when every
     * earlier gate passed (the raycast walks the own hull's blocks — by far
     * the most expensive gate).
     */
    public static GateResult evaluate(Input in, BooleanSupplier lofBlocked) {
        if (!in.hasTarget()) return new GateResult(Gate.NO_TARGET, "无目标");
        if (in.mode() == Mode.AUTO) {
            // 2026-10-27 拆分(GPT 复审 I): 未编组与自动开火关是两个态,
            // 合并会让面板把"自动关"误报成"未编组"
            if (!in.grouped()) return new GateResult(Gate.NO_CHANNEL, "未编组");
            if (!in.autoFireOn()) return new GateResult(Gate.AUTO_OFF, "频道自动开火关");
        }
        if (!in.allReady()) return new GateResult(Gate.NOT_READY, in.notReadyDetail());
        if (in.aimErrorDeg() >= AIM_GATE_DEG) {
            return new GateResult(Gate.AIMING, "未瞄准");
        }
        if (!in.inArc()) return new GateResult(Gate.OUT_OF_ARC, "射界外");
        if (in.mode() == Mode.AUTO && !in.cadenceReady()) {
            return new GateResult(Gate.CADENCE, "等待装填节奏");
        }
        if (lofBlocked.getAsBoolean()) return new GateResult(Gate.BLOCKED, "火线遮挡");
        return new GateResult(Gate.FIRE, "");
    }
}
