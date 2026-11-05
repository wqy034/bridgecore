package com.bridgecore.firecontrol;

import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * Distributed-lock target assignment (分布式锁定, 2026-10-08 火力指挥层④):
 * with no manual pin, every turret whose channel has 自动升格 ON picks its
 * OWN engagement target out of the registry candidates — user ruling: "处于
 * 其射界内,而且距离最短". Distances are per-turret (muzzle → target); the
 * arc reachability is computed by the actuator against the same twist-space
 * clamp math the aim loop uses.
 *
 * <p>Selection is sticky: the current assignment is kept while it stays
 * lockable+in-arc and no rival beats it by more than the hysteresis margin —
 * a 4 Hz re-pick with no memory flaps between equidistant targets and makes
 * every turret twitch between them.
 *
 * <p>Pure Java, no MC types (FireDirector pattern): the core resolves the
 * world geometry, this class owns only the DECISION, so it stays
 * unit-testable.
 */
public final class TargetAssigner {

    private TargetAssigner() {}

    /** Keep-current hysteresis: a rival must beat the current target's
     *  distance by more than 15% + 2 blocks to take the assignment. */
    private static final double KEEP_FACTOR = 1.15;
    private static final double KEEP_FLAT = 2.0;

    /** One assignable candidate as seen from the turret's muzzle. */
    public record Candidate(String id, double dist, boolean inArc) {}

    /**
     * Pick this turret's target for the coming scan window.
     *
     * @param currentId  the turret's assignment from the previous window
     *                   (null = unassigned)
     * @param candidates lockable candidates, any order
     * @return the assigned target id, or null = hold (no reachable target)
     */
    public static @Nullable String select(@Nullable String currentId, List<Candidate> candidates) {
        Candidate best = null;
        Candidate current = null;
        for (Candidate c : candidates) {
            if (!c.inArc()) continue;
            if (best == null || c.dist() < best.dist()) best = c;
            if (c.id().equals(currentId)) current = c;
        }
        if (current != null && best != null
                && current.dist() <= best.dist() * KEEP_FACTOR + KEEP_FLAT) {
            return current.id();
        }
        return best != null ? best.id() : null;
    }
}
