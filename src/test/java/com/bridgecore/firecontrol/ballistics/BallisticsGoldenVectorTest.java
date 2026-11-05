package com.bridgecore.firecontrol.ballistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Golden-vector diff test: replays every recorded vector through the Java port
 * and compares against the Lua-recorded outputs. The vector format is documented
 * in resources/firecontrol/ballistics_vectors.txt. Tolerance 1e-9 covers the
 * ~1 ulp libm gap between lupa's C math and
 * Java Math; NaN matches NaN; nil means "no command issued".
 */
class BallisticsGoldenVectorTest {

    private static final double TOL = 1e-9;

    private record Line(String layer, Map<String, String> in, Map<String, String> out) {}

    private static List<Line> load() throws Exception {
        InputStream is = BallisticsGoldenVectorTest.class
                .getResourceAsStream("/firecontrol/ballistics_vectors.txt");
        if (is == null) fail("ballistics_vectors.txt not on test classpath");
        List<Line> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            for (String ln = r.readLine(); ln != null; ln = r.readLine()) {
                if (ln.isEmpty() || ln.startsWith("#")) continue;
                int arrow = ln.indexOf("=>");
                String layer = ln.substring(0, ln.indexOf('|'));
                Map<String, String> in = parse(ln.substring(ln.indexOf('|') + 1, arrow));
                Map<String, String> out = parse(ln.substring(arrow + 3)); // skip "=>|"
                lines.add(new Line(layer, in, out));
            }
        }
        return lines;
    }

    private static Map<String, String> parse(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String tok : s.split("\\|")) {
            int eq = tok.indexOf('=');
            m.put(tok.substring(0, eq), tok.substring(eq + 1));
        }
        return m;
    }

    /** nil -> null; NaN/Infinity literals; plain doubles otherwise. */
    private static Double num(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null) throw new IllegalArgumentException("missing key " + key);
        return switch (v) {
            case "nil" -> null;
            case "NaN" -> Double.NaN;
            case "Infinity" -> Double.POSITIVE_INFINITY;
            case "-Infinity" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(v);
        };
    }

    private static double d(Map<String, String> m, String key) {
        Double v = num(m, key);
        if (v == null) throw new IllegalArgumentException(key + " is nil");
        return v;
    }

    private static boolean bool(Map<String, String> m, String key) {
        return "1".equals(m.get(key));
    }

    private static void assertClose(String what, double expected, double actual) {
        if (Double.isNaN(expected)) {
            assertTrue(Double.isNaN(actual), what + ": expected NaN, got " + actual);
        } else {
            assertEquals(expected, actual, TOL,
                    what + ": diff=" + Math.abs(expected - actual));
        }
    }

    private static void assertCmd(String what, Double expected, Double actual) {
        if (expected == null) {
            assertTrue(actual == null, what + ": expected nil, got " + actual);
        } else {
            assertTrue(actual != null, what + ": expected " + expected + ", got nil");
            assertClose(what, expected, actual);
        }
    }

    @Test
    void kernelVectors() throws Exception {
        int n = 0;
        double maxDiff = 0;
        for (Line l : load()) {
            if (!l.layer().equals("A")) continue;
            n++;
            String id = l.in().get("id");
            try {
                double sv = d(l.in(), "sv");
                double drag = d(l.in(), "drag");
                Double gy = num(l.in(), "gy");
                double bl = d(l.in(), "bl");
                double dragPT = BallisticsSolver.initDragPT(drag);
                double gravPT = BallisticsSolver.initGravPT(gy);
                assertEquals(d(l.in(), "dragPT"), dragPT, 0, id + " dragPT");
                assertEquals(d(l.in(), "gravPT"), gravPT, 0, id + " gravPT");
                BallisticsSolver s = new BallisticsSolver(dragPT, gravPT, sv, bl);
                double v0pt = sv / 20;
                double a1 = d(l.in(), "a1"), a2 = d(l.in(), "a2");
                Double o1 = num(l.out(), "o1"), o2 = num(l.out(), "o2");
                switch (l.in().get("func")) {
                    case "flightTime" -> check(id, "o1", o1, s.flightTime(a1, a2, v0pt));
                    case "dropAt" -> check(id, "o1", o1, s.dropAt(a1, a2, v0pt));
                    case "solvePitch" -> {
                        double[] r = s.solvePitch(a1, a2);
                        check(id, "o1", o1, r[0]);
                        check(id, "o2", o2, r[1]);
                    }
                    default -> fail(id + ": unknown func " + l.in().get("func"));
                }
            } catch (AssertionError | RuntimeException e) {
                throw new AssertionError(id + " failed: " + e.getMessage(), e);
            }
        }
        assertEquals(31, n, "kernel vector count");
    }

    private static void check(String id, String field, Double expected, double actual) {
        if (expected == null) fail(id + " " + field + ": expected nil output");
        if (Double.isNaN(expected)) {
            assertTrue(Double.isNaN(actual), id + " " + field + ": expected NaN, got " + actual);
        } else {
            assertEquals(expected, actual, TOL,
                    id + " " + field + ": diff=" + Math.abs(expected - actual));
        }
    }

    @Test
    void chainVectors() throws Exception {
        int n = 0;
        for (Line l : load()) {
            if (!l.layer().equals("B")) continue;
            n++;
            String id = l.in().get("id");
            try {
                SolveChain.Params p = new SolveChain.Params(
                        d(l.in(), "dragPT"), d(l.in(), "gravPT"),
                        d(l.in(), "sv"), d(l.in(), "bl"),
                        d(l.in(), "minP"), d(l.in(), "maxP"),
                        d(l.in(), "lockYaw"), l.in().get("lzDir"), d(l.in(), "lzRange"),
                        d(l.in(), "fcMov"), d(l.in(), "fcRot"), d(l.in(), "tvs"),
                        bool(l.in(), "invY"), bool(l.in(), "invP"),
                        d(l.in(), "yawTrim"), l.in().get("face"));
                SolveChain.ChainInput in = new SolveChain.ChainInput(
                        new Vec3(d(l.in(), "tgx"), d(l.in(), "tgy"), d(l.in(), "tgz")),
                        new Vec3(d(l.in(), "tvx"), d(l.in(), "tvy"), d(l.in(), "tvz")),
                        (int) d(l.in(), "mode"), bool(l.in(), "ccFire"),
                        bool(l.in(), "ground"),
                        new Quat(d(l.in(), "qw"), d(l.in(), "qx"), d(l.in(), "qy"), d(l.in(), "qz")),
                        new Vec3(d(l.in(), "omx"), d(l.in(), "omy"), d(l.in(), "omz")),
                        new Vec3(d(l.in(), "vx"), d(l.in(), "vy"), d(l.in(), "vz")),
                        new Vec3(d(l.in(), "spx"), d(l.in(), "spy"), d(l.in(), "spz")),
                        new Vec3(d(l.in(), "cx"), d(l.in(), "cy"), d(l.in(), "cz")),
                        bool(l.in(), "hasMount"), bool(l.in(), "asm"));
                SolveChain.SolveOutcome o = SolveChain.solveCore(p, in);
                assertClose(id + " yaw", d(l.out(), "yaw"), o.yawT());
                assertClose(id + " pitch", d(l.out(), "pitch"), o.pitchT());
                assertEquals(bool(l.out(), "locked"), o.locked(), id + " locked");
                assertEquals(bool(l.out(), "fire"), o.fire(), id + " fire");
                assertEquals(l.out().get("src"), o.src(), id + " src");
                assertCmd(id + " yawCmd", num(l.out(), "yawCmd"), o.yawCmd());
                assertCmd(id + " pitchCmd", num(l.out(), "pitchCmd"), o.pitchCmd());
                assertEquals((int) d(l.out(), "fired"), o.fired(), id + " fired");
            } catch (AssertionError | RuntimeException e) {
                throw new AssertionError(id + " failed: " + e.getMessage(), e);
            }
        }
        assertEquals(36, n, "chain vector count");
    }
}
