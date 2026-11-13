package com.bridgecore.firecontrol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Cyclic yaw sectors. Each endpoint owns the pitch limits up to the next endpoint. */
public record SectorArc(List<Endpoint> endpoints) {
    public static final int MAX_ENDPOINTS = 4;
    public static final SectorArc UNIFORM = new SectorArc(List.of());
    private static final double EPSILON = 1e-4;
    private static final double EDGE_RECOVERY_DEG = 0.5;
    private static final double EDGE_PADDING_DEG = 0.3;

    private static final Limits CLOSED = new Limits(1, 0);

    public record Endpoint(int yaw, int lower, int upper, boolean enabled) {
        public Endpoint(int yaw, int lower, int upper) {
            this(yaw, lower, upper, true);
        }

        public Endpoint {
            if (yaw < -180 || yaw >= 180 || lower < -90 || upper > 90 || lower > upper) {
                throw new IllegalArgumentException("Invalid sector endpoint");
            }
        }
    }

    public record Limits(double lower, double upper) {
        boolean empty() { return lower > upper; }

        boolean contains(double pitch) { return !empty() && pitch >= lower - EPSILON && pitch <= upper + EPSILON; }

        double clamp(double pitch) { return Math.max(lower, Math.min(upper, pitch)); }

        Limits intersect(Limits other) {
            return new Limits(Math.max(lower, other.lower), Math.min(upper, other.upper));
        }
    }

    record Step(double yawDelta, double pitch, boolean targetAllowed, boolean crossing) {}

    public SectorArc {
        if (endpoints.size() == 1 || endpoints.size() > MAX_ENDPOINTS) {
            throw new IllegalArgumentException("Invalid number of sector endpoints");
        }
        endpoints = endpoints.stream().sorted(Comparator.comparingInt(Endpoint::yaw)).toList();
        for (int i = 1; i < endpoints.size(); i++) {
            if (endpoints.get(i - 1).yaw() == endpoints.get(i).yaw()) {
                throw new IllegalArgumentException("Duplicate sector endpoint");
            }
        }
    }

    public static SectorArc start(int lower, int upper) {
        return new SectorArc(List.of(new Endpoint(-90, lower, upper), new Endpoint(90, lower, upper)));
    }

    public boolean enabled() { return !endpoints.isEmpty(); }

    public SectorArc replace(int index, Endpoint value) {
        var points = new ArrayList<>(endpoints);
        points.set(index, value);
        return new SectorArc(points);
    }

    /** Edit both ends of a sector while keeping the cyclic order and the next sector settings. */
    public SectorArc editSector(int index, Endpoint start, int endYaw) {
        int size = endpoints.size();
        int nextIndex = (index + 1) % size;
        var end = endpoints.get(nextIndex);
        if (start.yaw() == endYaw) throw new IllegalArgumentException("Coincident sector boundaries");
        if (size > 2) {
            int previous = endpoints.get(Math.floorMod(index - 1, size)).yaw();
            int after = endpoints.get((index + 2) % size).yaw();
            int span = Math.floorMod(after - previous, 360);
            int startOffset = Math.floorMod(start.yaw() - previous, 360);
            int endOffset = Math.floorMod(endYaw - previous, 360);
            if (startOffset == 0 || endOffset <= startOffset || (span != 0 && endOffset >= span)) {
                throw new IllegalArgumentException("Sector boundaries cross a neighbouring sector");
            }
        }
        var points = new ArrayList<>(endpoints);
        points.set(index, start);
        points.set(nextIndex, new Endpoint(endYaw, end.lower(), end.upper(), end.enabled()));
        return new SectorArc(points);
    }

    public boolean canSplit(int index) {
        return endpoints.size() < MAX_ENDPOINTS && span(index) > 1;
    }

    public SectorArc split(int index) {
        if (!canSplit(index)) return this;
        var points = new ArrayList<>(endpoints);
        var start = points.get(index);
        int mid = (int) GimbalMath.wrapDeg(start.yaw() + span(index) / 2);
        points.add(new Endpoint(mid, start.lower(), start.upper(), start.enabled()));
        return new SectorArc(points);
    }

    public SectorArc remove(int index) {
        if (endpoints.size() <= 2) return this;
        var points = new ArrayList<>(endpoints);
        points.remove(index);
        return new SectorArc(points);
    }

    private int span(int index) {
        return Math.floorMod(endpoints.get((index + 1) % endpoints.size()).yaw() - endpoints.get(index).yaw(), 360);
    }

    public Limits limitsAt(double yaw, Limits mechanical) {
        if (!enabled()) return mechanical;
        double angle = GimbalMath.wrapDeg(yaw);
        int previous = endpoints.size() - 1;
        for (int i = 0; i < endpoints.size(); i++) {
            int boundary = endpoints.get(i).yaw();
            if (Math.abs(GimbalMath.wrapDeg(angle - boundary)) <= EPSILON) {
                var before = limits((i + endpoints.size() - 1) % endpoints.size());
                var after = limits(i);
                var edge = before.empty() ? after : after.empty() ? before : before.intersect(after);
                return mechanical.intersect(edge);
            }
            if (boundary < angle) previous = i;
        }
        return mechanical.intersect(limits(previous));
    }

    private Limits limits(int index) {
        var endpoint = endpoints.get(index);
        return endpoint.enabled() ? new Limits(endpoint.lower(), endpoint.upper()) : CLOSED;
    }

    boolean contains(double yaw, double pitch, Limits yawLimits, Limits pitchLimits) {
        return yawLimits.contains(withinYawFrame(yaw, yawLimits)) && limitsAt(yaw, pitchLimits).contains(pitch);
    }

    double marginAt(double yaw, double pitch, Limits yawLimits, Limits pitchLimits) {
        double angle = withinYawFrame(yaw, yawLimits);
        var elevation = limitsAt(angle, pitchLimits);
        double margin = Math.min(pitch - elevation.lower(), elevation.upper() - pitch);
        if (!fullCircle(yawLimits)) margin = Math.min(margin, Math.min(angle - yawLimits.lower(), yawLimits.upper() - angle));
        for (int i = 0; i < endpoints.size(); i++) {
            var before = limits((i + endpoints.size() - 1) % endpoints.size());
            var after = limits(i);
            if (!before.contains(pitch) || !after.contains(pitch)) {
                margin = Math.min(margin, Math.abs(GimbalMath.wrapDeg(angle - endpoints.get(i).yaw())));
            }
        }
        return Math.max(0, margin);
    }

    List<Double> yawSeeds(Limits yawLimits) {
        var seeds = new ArrayList<Double>();
        if (!enabled()) {
            seeds.add(yawLimits.lower());
            seeds.add((yawLimits.lower() + yawLimits.upper()) / 2);
            seeds.add(yawLimits.upper());
        }
        for (int i = 0; i < endpoints.size(); i++) {
            if (!endpoints.get(i).enabled()) continue;
            double start = endpoints.get(i).yaw();
            int width = span(i);
            seeds.add(start);
            seeds.add(start + width / 2.0);
            seeds.add(start + width);
        }
        return seeds;
    }

    double restYaw(Limits pitchLimits) {
        if (!onBoundary(0) || limitsAt(0, pitchLimits).empty()) return 0;
        boolean leftOpen = !limitsAt(-EDGE_PADDING_DEG, pitchLimits).empty();
        boolean rightOpen = !limitsAt(EDGE_PADDING_DEG, pitchLimits).empty();
        if (leftOpen == rightOpen) return 0;
        return leftOpen ? -EDGE_PADDING_DEG : EDGE_PADDING_DEG;
    }

    Step step(double yaw, double pitch, double targetYaw, double targetPitch, Limits yawLimits, Limits pitchLimits) {
        double from = withinYawFrame(yaw, yawLimits);
        var current = limitsAt(from, pitchLimits);
        if (!current.empty()) return stepFromOpenSector(from, pitch, targetYaw, targetPitch, yawLimits, pitchLimits, current);

        // A joint can settle just outside a closed edge. Recover across that edge before aiming.
        double recoveryYaw = nearbyOpenYaw(from, yawLimits, pitchLimits);
        if (!Double.isFinite(recoveryYaw)) return new Step(0, pitchLimits.clamp(pitch), false, true);
        var recovery = limitsAt(recoveryYaw, pitchLimits);
        double safePitch = recovery.clamp(pitch);
        var remaining = stepFromOpenSector(recoveryYaw, safePitch, targetYaw, targetPitch, yawLimits, pitchLimits, recovery);
        return new Step(recovery.contains(pitch) ? recoveryYaw - from : 0, safePitch, remaining.targetAllowed(), true);
    }

    private double nearbyOpenYaw(double from, Limits yawLimits, Limits pitchLimits) {
        double nearest = Double.NaN;
        double distance = Double.POSITIVE_INFINITY;
        for (var endpoint : endpoints) {
            double edge = from + GimbalMath.wrapDeg(endpoint.yaw() - from);
            double edgeDistance = Math.abs(edge - from);
            if (edgeDistance <= EPSILON || edgeDistance > EDGE_RECOVERY_DEG || edgeDistance >= distance) continue;
            double candidate = edge + Math.copySign(EDGE_PADDING_DEG, edge - from);
            if ((fullCircle(yawLimits) || yawLimits.contains(candidate)) && !limitsAt(candidate, pitchLimits).empty()) {
                nearest = candidate;
                distance = edgeDistance;
            }
        }
        return nearest;
    }

    private Step stepFromOpenSector(double from, double pitch, double targetYaw, double targetPitch,
                                    Limits yawLimits, Limits pitchLimits, Limits current) {
        double desired = from + GimbalMath.wrapDeg(targetYaw - from);
        double to = fullCircle(yawLimits) ? desired : yawLimits.clamp(withinYawFrame(desired, yawLimits));
        boolean reachable = routeOpen(from, to, pitchLimits);
        if (!reachable && fullCircle(yawLimits) && Math.abs(to - from) > EPSILON) {
            double alternative = to - Math.copySign(360, to - from);
            if (routeOpen(from, alternative, pitchLimits)) {
                to = alternative;
                reachable = true;
            }
        }
        var goal = limitsAt(to, pitchLimits);
        boolean targetAllowed = reachable && Math.abs(GimbalMath.wrapDeg(targetYaw - to)) <= EPSILON
                && goal.contains(targetPitch);
        if (!reachable || !current.contains(pitch)) {
            return new Step(0, current.clamp(pitch), targetAllowed, true);
        }
        double boundary = firstBoundary(from, to);
        if (Math.abs(to - from) > EPSILON && onBoundary(from)) {
            double nextYaw = Double.isNaN(boundary) ? to : (from + boundary) / 2;
            return new Step(nextYaw - from, current.clamp(targetPitch), targetAllowed, true);
        }
        if (Double.isNaN(boundary)) return new Step(to - from, goal.clamp(targetPitch), targetAllowed, false);

        var passage = limitsAt(boundary, pitchLimits);
        double padding = Math.min(0.1, (passage.upper() - passage.lower()) / 2);
        double crossingPitch = new Limits(passage.lower() + padding, passage.upper() - padding).clamp(targetPitch);
        if (!passage.contains(pitch)) return new Step(0, crossingPitch, targetAllowed, true);
        double after = firstBoundary(boundary, to);
        double crossingYaw = Double.isNaN(after) ? to : (boundary + after) / 2;
        return new Step(crossingYaw - from, crossingPitch, targetAllowed, true);
    }

    private boolean onBoundary(double yaw) {
        for (var endpoint : endpoints) {
            if (Math.abs(GimbalMath.wrapDeg(yaw - endpoint.yaw())) <= EPSILON) return true;
        }
        return false;
    }

    private boolean routeOpen(double from, double to, Limits mechanical) {
        if (limitsAt(from, mechanical).empty() || limitsAt(to, mechanical).empty()) return false;
        double start = from;
        double boundary = firstBoundary(start, to);
        while (!Double.isNaN(boundary)) {
            if (limitsAt((start + boundary) / 2, mechanical).empty()
                    || limitsAt(boundary, mechanical).empty()) return false;
            start = boundary;
            boundary = firstBoundary(start, to);
        }
        return !limitsAt((start + to) / 2, mechanical).empty();
    }

    private double firstBoundary(double from, double to) {
        double length = Math.abs(to - from), closest = Double.NaN;
        double sign = Math.signum(to - from);
        for (var endpoint : endpoints) {
            double distance = GimbalMath.norm360(sign * (endpoint.yaw() - from));
            if (distance > EPSILON && distance <= length + EPSILON
                    && (Double.isNaN(closest) || distance < Math.abs(closest - from))) {
                closest = from + sign * distance;
            }
        }
        return closest;
    }

    private static boolean fullCircle(Limits limits) { return limits.upper() - limits.lower() >= 360; }

    private static double withinYawFrame(double yaw, Limits limits) {
        double angle = GimbalMath.wrapDeg(yaw);
        if (angle < limits.lower() && limits.contains(angle + 360)) return angle + 360;
        return angle;
    }
}
