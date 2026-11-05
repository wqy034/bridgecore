package com.bridgecore.firecontrol.ballistics;

/**
 * Pure-function ballistic kernel, ported line-by-line from OnCannon.lua
 * (ballisticsInit / flightTime / dropAt / pitchList / solvePitch).
 *
 * <p>Semantic notes honoured here:
 * <ul>
 *   <li>Lua {@code %} is floor-mod; Java {@code %} is truncated-mod. All Lua
 *       modulo sites go through {@link #luaMod} which reproduces Lua's
 *       fmod-then-sign-adjust rule bit-exactly ({@code r = m % n; if (r > 0
 *       ? n < 0 : r < 0) r += n}).</li>
 *   <li>NaN flows through as in Lua 5.5: comparisons are false,
 *       {@code math.floor(NaN)} is NaN, fmod(NaN, n) is NaN.</li>
 *   <li>pitchList is built by repeated += 0.375 accumulation (like the Lua
 *       numeric for-loop), not by multiplying an index.</li>
 *   <li>dropAt(Infinity, ...) is an infinite loop in the Lua original (a real
 *       upstream defect at max-range shots). We do NOT replicate the hang:
 *       the loop is capped at {@link #DROP_AT_LOOP_GUARD} iterations and the
 *       partial result is returned. No golden vector exercises this path.</li>
 * </ul>
 */
public final class BallisticsSolver {

    /** Hard iteration cap for dropAt; the Lua original hangs on t=Infinity. */
    private static final long DROP_AT_LOOP_GUARD = 100_000_000L;

    private final double dragPT;      // per-tick velocity retention
    private final double gravPT;      // blocks/tick^2, positive downwards
    private final double shellVelocity; // m/s
    private final double barrelLength;  // blocks

    private static final double[] PITCH_LIST = buildPitchList();

    public BallisticsSolver(double dragPT, double gravPT, double shellVelocity, double barrelLength) {
        this.dragPT = dragPT;
        this.gravPT = gravPT;
        this.shellVelocity = shellVelocity;
        this.barrelLength = barrelLength;
    }

    /** OnCannon.lua ballisticsInit dragPT: clamp <=0 or >=1 to 0.99. */
    public static double initDragPT(double shellDrag) {
        double dragPT = shellDrag;
        if (dragPT <= 0 || dragPT >= 1) dragPT = 0.99;
        return dragPT;
    }

    /**
     * OnCannon.lua ballisticsInit gravPT: |gravityY|/400 when the gravity
     * readback exists and is non-zero, else the 0.05 fallback.
     *
     * @param gravityY y component of ac.getGravity() in m/s^2, or null when
     *                 the API/value is absent
     */
    public static double initGravPT(Double gravityY) {
        if (gravityY != null && gravityY != 0) {
            return Math.abs(gravityY) / 400;
        }
        return 0.05;
    }

    public double dragPT() {
        return dragPT;
    }

    public double gravPT() {
        return gravPT;
    }

    /** Lua floor-mod semantics (lvm.h luaV_modf). */
    public static double luaMod(double m, double n) {
        double r = m % n; // Java % == C fmod == Lua's starting point
        if (r > 0 ? n < 0 : r < 0) {
            r += n;
        }
        return r;
    }

    /** OnCannon.lua wrap180. */
    public static double wrap180(double a) {
        return luaMod(a + 180, 360) - 180;
    }

    /** OnCannon.lua flightTime(dis, pitch, v0pt). */
    public double flightTime(double dis, double pitch, double v0pt) {
        double cosP = Math.abs(Math.cos(pitch));
        dis = dis - barrelLength * cosP;
        if (dragPT < 0.001 || dragPT > 0.999) {
            return dis / (cosP * v0pt);
        }
        return Math.abs(Math.log(1 - dis / (100 * cosP * v0pt)) / Math.log(dragPT));
    }

    /** OnCannon.lua dropAt(t, pitch, v0pt) — Y displacement t ticks after muzzle, up positive. */
    public double dropAt(double t, double pitch, double v0pt) {
        double sinP = Math.sin(pitch);
        double y = barrelLength * sinP;
        double vy = v0pt * sinP;
        long i = 1;
        double last = 0;
        double floorT = Math.floor(t); // NaN -> NaN, like Lua 5.5 math.floor(NaN)
        while (i < t + 1) {
            if (i > DROP_AT_LOOP_GUARD) {
                // Upstream defect: t=Infinity loops forever in Lua. We stop instead.
                break;
            }
            y = y + vy;
            vy = dragPT * vy - gravPT;
            if ((double) i == floorT) {
                last = y;
            }
            i = i + 1;
        }
        if (t < 1) {
            return y * t;
        }
        double f = luaMod(t, floorT);
        return last + (y - last) * f;
    }

    /**
     * OnCannon.lua solvePitch(xDis, yDis): binary search over pitchList,
     * tolerance +/-0.018 on height error; on exhaustion returns the last
     * probed pitch/time.
     *
     * @return {pitch (rad), time (ticks)}
     */
    public double[] solvePitch(double xDis, double yDis) {
        double v0pt = shellVelocity / 20;
        int low = 1, high = PITCH_LIST.length; // Lua 1-based indices, kept verbatim
        double pitch = 0, time = 0;
        while (low <= high) {
            int mid = (low + high) / 2; // == math.floor((low+high)/2) for positives
            pitch = PITCH_LIST[mid - 1];
            time = flightTime(xDis, pitch, v0pt);
            double err = yDis - dropAt(time, pitch, v0pt);
            if (err >= -0.018 && err <= 0.018) {
                break;
            } else if (err > 0) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return new double[]{pitch, time};
    }

    /** OnCannon.lua: for i=-90,90,0.375 -> math.rad(i); 481 entries. */
    private static double[] buildPitchList() {
        double[] list = new double[481];
        int k = 0;
        for (double i = -90; i <= 90; i += 0.375) {
            list[k++] = Math.toRadians(i);
        }
        assert k == list.length;
        return list;
    }
}
