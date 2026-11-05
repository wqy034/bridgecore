package com.bridgecore.display;

/** Per-controller density. Zero is automatic; old 64/128 configs must not
 * keep the standard 3x2 fire-control wall below its usable resolution. */
public final class DisplayResolution {
    public static final int DEFAULT = 256, MAX = 512;
    private DisplayResolution() {}
    public static boolean validChoice(int value) {
        return value == 0 || value == 128 || value == 256 || value == 512;
    }
    public static int effective(int choice, int configured) {
        if (!validChoice(choice)) choice = 0;
        return choice == 0 ? Math.max(DEFAULT, Math.min(MAX, configured)) : choice;
    }
    public static boolean fits(int columns, int rows, int resolution, long byteLimit) {
        return columns >= 0 && rows >= 0 && columns <= 64 && rows <= 64
                && resolution > 0 && resolution <= MAX
                && (long) columns * rows * resolution * resolution * Integer.BYTES <= byteLimit;
    }
}
