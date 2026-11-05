package com.bridgecore.firecontrol;

import java.util.*;

/** Channel-level multi-lock: maximize reachable distinct contacts first,
 * then allow remaining turrets to converge. Precise-lock bypasses this. */
public final class ChannelTargetAllocator {
    private ChannelTargetAllocator() {}
    public record Turret(String id, String currentId, List<TargetAssigner.Candidate> candidates) {}
    public static Map<String, String> assign(List<Turret> turrets) {
        Map<String, List<TargetAssigner.Candidate>> choices = new LinkedHashMap<>();
        for (Turret turret : turrets) {
            var candidates = new ArrayList<TargetAssigner.Candidate>();
            for (var c : turret.candidates()) if (c.inArc() && Double.isFinite(c.dist()) && c.dist() >= 0) candidates.add(c);
            candidates.sort(Comparator.comparingDouble(TargetAssigner.Candidate::dist).thenComparing(TargetAssigner.Candidate::id));
            String preferred = TargetAssigner.select(turret.currentId(), candidates);
            candidates.sort(Comparator.comparingInt(c -> c.id().equals(preferred) ? 0 : 1));
            choices.put(turret.id(), candidates);
        }
        List<String> order = new ArrayList<>(choices.keySet());
        order.sort(Comparator.comparingInt((String id) -> choices.get(id).size()).thenComparing(id -> id));
        Map<String, String> owners = new HashMap<>(), result = new LinkedHashMap<>();
        // Seed stable, still-preferred assignments before finding extra coverage.
        for (Turret turret : turrets) {
            var candidates = choices.get(turret.id());
            if (candidates.stream().anyMatch(c -> c.id().equals(turret.currentId()))
                    && !owners.containsKey(turret.currentId())) {
                owners.put(turret.currentId(), turret.id()); result.put(turret.id(), turret.currentId());
            }
        }
        // A materially closer unowned contact can replace a stable one without
        // sacrificing coverage. Do not swap two valid owners every heartbeat.
        for (String id : order) {
            String current = result.get(id);
            var candidates = choices.get(id);
            if (current == null || candidates.isEmpty()) continue;
            String preferred = candidates.getFirst().id();
            if (!preferred.equals(current) && !owners.containsKey(preferred)) {
                owners.remove(current); owners.put(preferred, id); result.put(id, preferred);
            }
        }
        for (String id : order) if (!result.containsKey(id)) augment(id, choices, owners, result, new HashSet<>());
        for (String id : order) if (!result.containsKey(id) && !choices.get(id).isEmpty()) result.put(id, choices.get(id).getFirst().id());
        return result;
    }
    private static boolean augment(String id, Map<String, List<TargetAssigner.Candidate>> choices,
            Map<String, String> owners, Map<String, String> result, Set<String> visited) {
        for (var candidate : choices.get(id)) {
            String target = candidate.id();
            if (!visited.add(target)) continue;
            String owner = owners.get(target);
            if (owner == null || augment(owner, choices, owners, result, visited)) {
                owners.put(target, id); result.put(id, target); return true;
            }
        }
        return false;
    }
}
