package com.bridgecore.display;

import java.util.Arrays;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side indexed-color canvas: the screen block's universal drawing surface.
 * Display apps rasterize into this; the whole buffer ships to clients on change
 * (test-scale: 96x96 = 9 KB per update, RLE/dirty-rect compression is backlog).
 *
 * Architecture reference (per 2026-09-01 author ruling, borrowing with attribution):
 * retained server canvas + sync mirrors VoidPower's hologram (buffered HologramTE),
 * but this implementation is written from scratch. Layout of the first app follows
 * the legacy flight controller's hologram attitude object.
 *
 * Palette: the global default is 16 entries; index 0 is fully transparent
 * (hologram look). A per-instance custom palette (16 or 256 ARGB entries)
 * can be installed via {@link #setPalette} — the byte pixel buffer already
 * expresses 0..255, so 256-color support costs the sync protocol nothing.
 * Consumers use {@link #palette()} and fall back to index 0 (transparent)
 * for pixel indices beyond the table length.
 *
 * Size: variable since 2026-09-17 (tiled-screen apps render at the physical
 * array resolution, tiles × tileRes). The no-arg constructor keeps the legacy
 * HUD canvas — 1024×1024 since the 2026-10-28 fidelity pass (512² NEAREST
 * showed Bresenham staircases on the magnified panel); DEFAULT_WIDTH/HEIGHT
 * remain as the sync-packet cap.
 */
public final class Canvas {
    public static final int DEFAULT_WIDTH = 1024;
    public static final int DEFAULT_HEIGHT = 1024;

    private final int width;
    private final int height;

    /** ARGB palette, index 0 = transparent. */
    public static final int[] PALETTE = {
            0x00000000, // 0 transparent
            0xFFFFFFFF, // 1 white
            0xFF38B6FF, // 2 sky blue
            0xFFB5651D, // 3 ground brown
            0xFFFFFF00, // 4 yellow (fixed aircraft symbol)
            0xFFFF4444, // 5 red (warnings / no signal)
            0xFF44FF44, // 6 green
            0xFF44FFFF, // 7 cyan (primary readout/ladder color — white is unreadable
                        //    against sky; shipboard FC style, 2026-09-01 user ruling)
            0xAA33CCFF, // 8 dim blue (pitch ladder secondary; 0x66 washed out in daylight)
            0xFF888888, // 9 gray
            0xFF000000, // 10 black
            0xFFFF8800, // 11 orange
            0xFFFF00FF, // 12 magenta
            0xFF0000FF, // 13 blue
            0xFF00FF00, // 14 bright green
            0xFFFFFFFF, // 15 (spare, white)
    };

    private final byte[] pixels;
    private boolean dirty = true;
    /** Per-instance palette (16 or 256 ARGB entries); null = the global
     *  default PALETTE. Defensive copy held; see setPalette. */
    private int @Nullable [] customPalette;
    /** Monotonic counter, bumped by every setPalette call — the sync layer
     *  compares it against what it last shipped instead of diffing tables. */
    private int paletteVersion;
    /** Clip stack (intersect semantics), depth cap 4 — see pushClip. */
    private int clipX0, clipY0, clipX1, clipY1;
    private final int[] clipStack = new int[16]; // 4 levels x 4 ints
    private int clipDepth;

    public Canvas() {
        this(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    public Canvas(int w, int h) {
        width = w;
        height = h;
        pixels = new byte[w * h];
        clipX1 = w;
        clipY1 = h;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Install a per-instance palette: 16 or 256 ARGB entries (index 0 keeps
     *  the transparent-by-convention meaning; consumers map it to nothing on
     *  the holo and to black on opaque screens). null resets to the global
     *  default. Bumps {@link #paletteVersion()} and marks the canvas dirty so
     *  the sync layer reships. Other lengths are rejected. */
    public void setPalette(int @Nullable [] argb) {
        if (argb != null) {
            if (argb.length != 16 && argb.length != 256) {
                throw new IllegalArgumentException(
                        "palette must have 16 or 256 entries, got " + argb.length);
            }
            customPalette = argb.clone();
        } else {
            customPalette = null;
        }
        paletteVersion++;
        dirty = true;
    }

    /** The effective palette: the custom one when installed, else the global
     *  default. Do not mutate. Pixel indices beyond the table length fall
     *  back to index 0 (transparent) at the consumers. */
    public int[] palette() {
        return customPalette != null ? customPalette : PALETTE;
    }

    /** Palette change counter (0 until the first setPalette call). */
    public int paletteVersion() {
        return paletteVersion;
    }

    /** The custom palette only, or null when the default is in effect —
     *  the sync layer uses this to decide whether palette bytes ride the
     *  packet at all. */
    public int @Nullable [] customPalette() {
        return customPalette;
    }

    public void clear() {
        Arrays.fill(pixels, (byte) 0);
        dirty = true;
    }

    /** Push a clip rectangle; NESTED pushes INTERSECT with the current one
     *  (javadoc contract). All drawing (setPixel) is clipped to the top of
     *  the stack. Stack depth is capped at 4; excess pushes throw. Pair
     *  every push with {@link #popClip()}. */
    public void pushClip(int x, int y, int w, int h) {
        if (clipDepth >= 4) {
            throw new IllegalStateException("clip stack overflow (depth 4)");
        }
        clipStack[clipDepth * 4] = clipX0;
        clipStack[clipDepth * 4 + 1] = clipY0;
        clipStack[clipDepth * 4 + 2] = clipX1;
        clipStack[clipDepth * 4 + 3] = clipY1;
        clipDepth++;
        clipX0 = Math.max(clipX0, x);
        clipY0 = Math.max(clipY0, y);
        clipX1 = Math.min(clipX1, x + w);
        clipY1 = Math.min(clipY1, y + h);
    }

    /** Restore the clip rectangle saved by the matching pushClip. */
    public void popClip() {
        if (clipDepth <= 0) {
            throw new IllegalStateException("clip stack underflow");
        }
        clipDepth--;
        clipX0 = clipStack[clipDepth * 4];
        clipY0 = clipStack[clipDepth * 4 + 1];
        clipX1 = clipStack[clipDepth * 4 + 2];
        clipY1 = clipStack[clipDepth * 4 + 3];
    }

    public void setPixel(int x, int y, int color) {
        if (x < clipX0 || y < clipY0 || x >= clipX1 || y >= clipY1) return;
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        pixels[y * width + x] = (byte) color;
        dirty = true;
    }

    public int getPixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0;
        return pixels[y * width + x];
    }

    /** Bresenham. */
    public void drawLine(int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            setPixel(x0, y0, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    public void drawRect(int x, int y, int w, int h, int color) {
        drawLine(x, y, x + w - 1, y, color);
        drawLine(x, y + h - 1, x + w - 1, y + h - 1, color);
        drawLine(x, y, x, y + h - 1, color);
        drawLine(x + w - 1, y, x + w - 1, y + h - 1, color);
    }

    /** Bresenham with a square brush: stamps a width×width block at every line
     *  point. On the 1024² HUD canvas a 2–3 px stroke reads as a smooth line
     *  where a 1 px staircase shows on the magnified panel (2026-10-28
     *  fidelity pass). width <= 1 degenerates to {@link #drawLine}. */
    public void drawThickLine(int x0, int y0, int x1, int y1, int color, int width) {
        if (width <= 1) {
            drawLine(x0, y0, x1, y1, color);
            return;
        }
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int off = width / 2;
        while (true) {
            fillRect(x0 - off, y0 - off, width, width, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    /** Polyline variant of {@link #drawThickLine}. */
    public void drawPolyline(int[] xs, int[] ys, int color, int width) {
        if (width <= 1) {
            drawPolyline(xs, ys, color);
            return;
        }
        if (xs.length != ys.length) {
            throw new IllegalArgumentException("xs/ys length mismatch");
        }
        for (int i = 0; i + 1 < xs.length; i++) {
            drawThickLine(xs[i], ys[i], xs[i + 1], ys[i + 1], color, width);
        }
    }

    public void fillRect(int x, int y, int w, int h, int color) {
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
                setPixel(xx, yy, color);
            }
        }
    }

    /** Midpoint circle outline. */
    public void drawCircle(int cx, int cy, int r, int color) {
        int x = r, y = 0, err = 1 - r;
        while (x >= y) {
            setPixel(cx + x, cy + y, color); setPixel(cx + y, cy + x, color);
            setPixel(cx - y, cy + x, color); setPixel(cx - x, cy + y, color);
            setPixel(cx - x, cy - y, color); setPixel(cx - y, cy - x, color);
            setPixel(cx + y, cy - x, color); setPixel(cx + x, cy - y, color);
            y++;
            if (err < 0) { err += 2 * y + 1; }
            else { x--; err += 2 * (y - x) + 1; }
        }
    }

    /** Horizontal span fast path (scanline fills). Endpoint order free. */
    public void hLine(int x0, int x1, int y, int color) {
        if (x0 > x1) { int t = x0; x0 = x1; x1 = t; }
        for (int x = x0; x <= x1; x++) {
            setPixel(x, y, color);
        }
    }

    /** Vertical span fast path. Endpoint order free. */
    public void vLine(int x, int y0, int y1, int color) {
        if (y0 > y1) { int t = y0; y0 = y1; y1 = t; }
        for (int y = y0; y <= y1; y++) {
            setPixel(x, y, color);
        }
    }

    /** Filled disc (radar contact blips, gauge needles' pivot). */
    public void fillCircle(int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int hw = (int) Math.floor(Math.sqrt((double) r * r - (double) dy * dy));
            hLine(cx - hw, cx + hw, cy + dy, color);
        }
    }

    /** Circular arc in DEGREES (0 = +x, positive = clockwise in screen space,
     *  i.e. toward +y) — radar rings and sweep lines. Wraps (end < start
     *  sweeps through 360). */
    public void drawArc(int cx, int cy, int r, double startDeg, double endDeg, int color) {
        if (r < 0) return;
        double sweep = endDeg - startDeg;
        while (sweep < 0) sweep += 360.0;
        if (sweep > 360.0) sweep = 360.0;
        double steps = Math.max(8.0, sweep / 360.0 * 2.0 * Math.PI * r * 2.0);
        for (int i = 0; i <= steps; i++) {
            double a = Math.toRadians(startDeg + sweep * i / steps);
            setPixel(cx + (int) Math.round(Math.cos(a) * r),
                    cy + (int) Math.round(Math.sin(a) * r), color);
        }
    }

    /** Axis-aligned ellipse outline (gauge bezels). */
    public void drawEllipse(int cx, int cy, int rx, int ry, int color) {
        if (rx < 0 || ry < 0) return;
        double steps = Math.max(16.0, Math.PI * (rx + ry));
        for (int i = 0; i <= steps; i++) {
            double a = 2.0 * Math.PI * i / steps;
            setPixel(cx + (int) Math.round(Math.cos(a) * rx),
                    cy + (int) Math.round(Math.sin(a) * ry), color);
        }
    }

    /** Triangle outline (three Bresenham edges). */
    public void drawTriangle(int x0, int y0, int x1, int y1, int x2, int y2, int color) {
        drawLine(x0, y0, x1, y1, color);
        drawLine(x1, y1, x2, y2, color);
        drawLine(x2, y2, x0, y0, color);
    }

    /** Filled triangle (scanline via hLine; standard split at the middle
     *  vertex's y). */
    public void fillTriangle(int x0, int y0, int x1, int y1, int x2, int y2, int color) {
        // sort vertices by y
        if (y0 > y1) { int t = y0; y0 = y1; y1 = t; t = x0; x0 = x1; x1 = t; }
        if (y0 > y2) { int t = y0; y0 = y2; y2 = t; t = x0; x0 = x2; x2 = t; }
        if (y1 > y2) { int t = y1; y1 = y2; y2 = t; t = x1; x1 = x2; x2 = t; }
        if (y0 == y2) { // degenerate: single scanline band
            hLine(Math.min(x0, Math.min(x1, x2)), Math.max(x0, Math.max(x1, x2)), y0, color);
            return;
        }
        for (int y = y0; y <= y2; y++) {
            double t02 = (double) (y - y0) / (y2 - y0);
            int xa = x0 + (int) Math.round((x2 - x0) * t02);
            int xb;
            if (y <= y1) {
                xb = y1 == y0 ? x1 : x0 + (int) Math.round((x1 - x0) * (double) (y - y0) / (y1 - y0));
            } else {
                xb = y2 == y1 ? x1 : x1 + (int) Math.round((x2 - x1) * (double) (y - y1) / (y2 - y1));
            }
            hLine(xa, xb, y, color);
        }
    }

    /** Connected line strip (waveforms, trend graphs). Lengths must match;
     *  fewer than 2 points draws nothing. */
    public void drawPolyline(int[] xs, int[] ys, int color) {
        if (xs.length != ys.length) {
            throw new IllegalArgumentException("xs/ys length mismatch");
        }
        for (int i = 1; i < xs.length; i++) {
            drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], color);
        }
    }

    /** Closed polyline (last point connects back to the first). */
    public void drawPolygon(int[] xs, int[] ys, int color) {
        drawPolyline(xs, ys, color);
        if (xs.length >= 2) {
            drawLine(xs[xs.length - 1], ys[xs.length - 1], xs[0], ys[0], color);
        }
    }

    /** Width in px of a string as drawText would lay it out (glyph advance
     *  6·scale px per char). For right/center alignment math. */
    public int textWidth(String text, int scale) {
        return text.length() * 6 * scale;
    }

    /** Right-aligned text: x is the RIGHT edge of the last glyph. */
    public void drawTextRight(int x, int y, String text, int color, int scale) {
        drawText(x - textWidth(text, scale), y, text, color, scale);
    }

    /** 5x7 font, 1px spacing -> each glyph advances 6px. Unknown chars render as space. */
    public void drawText(int x, int y, String text, int color) {
        drawText(x, y, text, color, 1);
    }

    /** Scaled variant: each font pixel becomes an s×s block; glyph advances 6·s px. */
    public void drawText(int x, int y, String text, int color, int scale) {
        int cx = x;
        for (char c : text.toCharArray()) {
            drawGlyph(cx, y, Character.toUpperCase(c), color, scale);
            cx += 6 * scale;
        }
    }

    public void drawTextCentered(int x, int y, String text, int color) {
        drawText(x - text.length() * 6 / 2, y, text, color, 1);
    }

    public void drawTextCentered(int x, int y, String text, int color, int scale) {
        drawText(x - text.length() * 6 * scale / 2, y, text, color, scale);
    }

    /** Bilingual (CJK + ASCII) text for panel titles / body copy, using the
     *  packed Fusion Pixel font (see CjkFont). sizePx is the line height:
     *  12 = 12px font ×1, 16 = 8px font ×2 (kept for existing callers),
     *  24 = 12px font ×2, 36 = 12px font ×3 — pixel fonts stay crisp at
     *  integer factors. ASCII uses the font's own half-width glyphs; unknown
     *  code points render as space.
     *  Supplementary characters (>0xFFFF) are skipped without advancing. */
    public void drawCjkText(int x, int y, String text, int color, int sizePx) {
        CjkFont font = cjkFontFor(sizePx);
        int scale = cjkScaleFor(sizePx);
        int cx = x;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp > 0xFFFF) {
                continue;
            }
            font.drawGlyph(this, cx, y, cp, color, scale);
            cx += font.advance(cp) * scale;
        }
    }

    /** Width in px of a string as drawCjkText would lay it out. */
    public int cjkTextWidth(String text, int sizePx) {
        CjkFont font = cjkFontFor(sizePx);
        int scale = cjkScaleFor(sizePx);
        int w = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp > 0xFFFF) {
                continue;
            }
            w += font.advance(cp) * scale;
        }
        return w;
    }

    /** sizePx -> packed font: upstream Fusion Pixel ships monospaced builds at
     *  8/10/12px only, so 24 and 36 are the TRUE 12px font at integer scale
     *  (the 2026-10-28 HUD pass used 16 = 8px×2 for labels — visibly blurry;
     *  16 stays for existing callers like the fire-control panel). */
    private static CjkFont cjkFontFor(int sizePx) {
        return switch (sizePx) {
            case 12, 24, 36 -> CjkFont.of(12);
            case 16 -> CjkFont.of(8);
            default -> throw new IllegalArgumentException(
                    "CJK text size must be 12, 16, 24 or 36 px, got " + sizePx);
        };
    }

    /** sizePx -> integer render scale (see cjkFontFor). */
    private static int cjkScaleFor(int sizePx) {
        return switch (sizePx) {
            case 12 -> 1;
            case 16, 24 -> 2;
            case 36 -> 3;
            default -> throw new IllegalArgumentException(
                    "CJK text size must be 12, 16, 24 or 36 px, got " + sizePx);
        };
    }

    private void drawGlyph(int x, int y, char c, int color, int scale) {
        String[] glyph = Font5x7.get(c);
        if (glyph == null) return;
        for (int row = 0; row < 7; row++) {
            String bits = glyph[row];
            for (int col = 0; col < 5; col++) {
                if (bits.charAt(col) == '#') {
                    if (scale == 1) {
                        setPixel(x + col, y + row, color);
                    } else {
                        fillRect(x + col * scale, y + row * scale, scale, scale, color);
                    }
                }
            }
        }
    }

    /** Raw buffer for the sync packet. Do not mutate. */
    public byte[] raw() {
        return pixels;
    }

    public boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }
}
