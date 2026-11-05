package com.bridgecore.display;
import java.util.List;
/** Explicit V cycling; an absent old anchor must not hide a sole replacement. */
public final class HudTargetCycle {
    private HudTargetCycle() {}
    public static String next(List<String> candidates, String anchor) {
        if (candidates.isEmpty()) return null;
        int at = candidates.indexOf(anchor);
        if (at < 0) return candidates.get(0);
        return candidates.size() == 1 ? null : candidates.get((at + 1) % candidates.size());
    }
}
