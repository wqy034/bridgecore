package com.bridgecore.display;

/**
 * HUD-specific 256-entry palette (2026-10-29 outline-font vertical slice).
 * Entries 0..15 retain the semantics of {@link Canvas#PALETTE}, with HUD-only tints;
 * entries 16..143 are eight
 * 16-level ALPHA LADDERS (same RGB, alpha = source alpha x coverage) that
 * {@code OutlineTextRenderer} quantizes glyph antialiasing coverage into —
 * that is what makes smooth TrueType edges expressible on an indexed-color
 * canvas. Entries 144..159 hold the dark halo; 160..255 hold six text
 * coverage ramps composited over that halo (no lost antialiasing coverage).
 *
 * Ladder bases are the drawing API's color parameter: an outline-text call
 * takes a ladder base and picks base+level per pixel (level 0 = fully
 * transparent, level 15 = source color at full alpha). Ladder entry 0 of
 * every ladder is fully transparent by construction (coverage 0).
 *
 * Installed once per HUD screen via {@code Canvas.setPalette} — see
 * HudScreenBlockEntity. With a 256-entry palette the sync layer can no
 * longer nibble-pack and ships RLE instead (CanvasSyncPayload).
 */
public final class HudPalette {
    /** Ladder step count (coverage 0..255 quantized to 16 levels, i*17). */
    public static final int LEVELS = 16;

    public static final int LADDER_WHITE = 16;
    public static final int LADDER_DIM_BLUE = 32;
    public static final int LADDER_CYAN = 48;
    public static final int LADDER_YELLOW = 64;
    public static final int LADDER_GREEN = 80;
    public static final int LADDER_RED = 96;

    /** Readable secondary text, separate from faint instrument strokes. */
    public static final int LADDER_LABEL = 112;
    public static final int LADDER_ORANGE = 128;
    public static final int LADDER_HALO = 144;
    private static final int INK_WHITE = 160, INK_CYAN = 176, INK_YELLOW = 192;
    private static final int INK_GREEN = 208, INK_RED = 224, INK_ORANGE = 240;

    /** Text coverage already composited over the local dark backing. */
    static int contrastInk(int index) {
        int base = index & ~15;
        int ink = switch (base) {
            case LADDER_WHITE -> INK_WHITE;
            case LADDER_CYAN, LADDER_LABEL -> INK_CYAN;
            case LADDER_YELLOW -> INK_YELLOW;
            case LADDER_GREEN -> INK_GREEN;
            case LADDER_RED -> INK_RED;
            case LADDER_ORANGE -> INK_ORANGE;
            default -> -1;
        };
        return ink < 0 ? index : ink + (index & 15);
    }

    /** Halo source coverage: dim idle text and radar triangles deliberately
     * retain low salience. Palette entry zero remains transparent. */
    static int contrastCoverage(int index) {
        if (index < 16) return switch (index) {
            case 1, 4, 5, 6, 7, 8, 11, 14, 15 -> 15;
            default -> 0;
        };
        return index < LADDER_HALO && (index & ~15) != LADDER_DIM_BLUE
                ? index & 15 : 0;
    }

    public static int textLadder(int color) {
        return switch (color) {
            case 1 -> LADDER_WHITE;
            case 4 -> LADDER_YELLOW;
            case 5 -> LADDER_RED;
            case 6, 14 -> LADDER_GREEN;
            case 7 -> LADDER_CYAN;
            case 11 -> LADDER_ORANGE;
            default -> LADDER_LABEL;
        };
    }

    private HudPalette() {
    }

    /** A fresh 256-entry table (each screen installs its own copy). */
    public static int[] create() {
        int[] p = new int[256];
        System.arraycopy(Canvas.PALETTE, 0, p, 0, Canvas.PALETTE.length);
        // HUD instance only: shared Canvas.PALETTE and console colors are unchanged.
        p[1] = 0xFFF0FFFF;
        p[4] = 0xFFFFFF32;
        p[5] = 0xFFFF3636;
        p[6] = 0xFF36FF7C;
        p[7] = 0xFF42FFFF;
        p[8] = 0xE033E6FF;
        p[9] = 0x90618D99;
        p[11] = 0xFFFF961F;
        ladder(p, LADDER_WHITE, p[1]);
        ladder(p, LADDER_DIM_BLUE, p[9]);
        ladder(p, LADDER_CYAN, p[7]);
        ladder(p, LADDER_YELLOW, p[4]);
        ladder(p, LADDER_GREEN, p[6]);
        ladder(p, LADDER_RED, p[5]);
        ladder(p, LADDER_LABEL, p[7]);
        ladder(p, LADDER_ORANGE, p[11]);
        int halo = 0xED02080D;
        ladder(p, LADDER_HALO, halo);
        inkLadder(p, INK_WHITE, p[1], halo);
        inkLadder(p, INK_CYAN, p[7], halo);
        inkLadder(p, INK_YELLOW, p[4], halo);
        inkLadder(p, INK_GREEN, p[6], halo);
        inkLadder(p, INK_RED, p[5], halo);
        inkLadder(p, INK_ORANGE, p[11], halo);
        return p;
    }

    /** Source-over a coverage-scaled foreground onto a dark local backing.
     * Indexed pixels replace one another, so this palette composition keeps
     * text edges smooth without overwriting their backing with transparency. */
    private static void inkLadder(int[] p, int base, int fg, int bg) {
        for (int i = 0; i < LEVELS; i++) {
            double a = (fg >>> 24) / 255.0 * i / (LEVELS - 1);
            double b = (bg >>> 24) / 255.0 * (1 - a);
            double out = a + b;
            int value = (int) Math.round(out * 255) << 24;
            for (int shift : new int[]{16, 8, 0}) {
                int c = (int) Math.round((((fg >>> shift) & 255) * a
                        + ((bg >>> shift) & 255) * b) / out);
                value |= c << shift;
            }
            p[base + i] = value;
        }
    }

    /** base..base+15 = the source color with alpha scaled by coverage i*17/255. */
    private static void ladder(int[] p, int base, int argb) {
        int srcAlpha = argb >>> 24;
        for (int i = 0; i < LEVELS; i++) {
            int coverage = i * 17;
            int alpha = srcAlpha * coverage / 255;
            p[base + i] = (alpha << 24) | (argb & 0x00FFFFFF);
        }
    }
}
