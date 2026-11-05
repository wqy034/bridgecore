package com.bridgecore.display;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Packed pixel-font reader for bilingual panel text (fire-control console
 * titles / body text, 2026-09-27 request). Glyphs are converted offline from
 * the Fusion Pixel Font monospaced BDF builds (TakWolf/fusion-pixel-font,
 * release 2026.09.25, OFL-1.1 — see assets/bridgecore/font/ofl.txt and
 * tools/font/) into the compact BCJF binary by tools/font/bdf2bin.py.
 *
 * Binary layout (little-endian): 20-byte header (magic "BCJF", version,
 * cellHeight, ascent, halfAdvance, fullAdvance, glyphCount), then a
 * glyphCount × 16-byte index (codepoint, advance, bmpW, bmpH, xoff, yoff,
 * reserved, bitmapOffset), then 1bpp MSB-left row-packed bitmaps.
 *
 * Loading: plain classloader resource reads — the bins live inside our own
 * jar, so no Minecraft resource manager is involved and both dists are safe.
 * The whole file is held in memory (few hundred KB after subsetting); glyphs
 * decode on demand. Unknown code points render as space — never an error.
 */
final class CjkFont {
    private static final int MAGIC = 0x42434A46; // "BCJF"
    private static final int HEADER = 20;
    private static final int ENTRY = 16;

    private static final Map<Integer, CjkFont> CACHE = new ConcurrentHashMap<>();

    /** The font for a cell height (8 or 12). Unknown heights are rejected. */
    static CjkFont of(int cellHeight) {
        if (cellHeight != 8 && cellHeight != 12) {
            throw new IllegalArgumentException("no packed font for cell height " + cellHeight);
        }
        return CACHE.computeIfAbsent(cellHeight,
                h -> load("assets/bridgecore/font/cjk" + h + ".bin"));
    }

    private final byte[] data;
    private final int cellHeight;
    private final int ascent;
    private final int count;

    private CjkFont(byte[] data) {
        this.data = data;
        if (s32(0) != MAGIC) {
            throw new IllegalStateException("not a BCJF font blob");
        }
        cellHeight = u16(6);
        ascent = u16(8);
        count = s32(16);
    }

    private static CjkFont load(String path) {
        byte[] bytes;
        try (InputStream in = CjkFont.class.getResourceAsStream("/" + path)) {
            if (in == null) {
                throw new IllegalStateException("packed font missing from jar: " + path);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read packed font " + path, e);
        }
        return new CjkFont(bytes);
    }

    int cellHeight() {
        return cellHeight;
    }

    /** Index-slot offset of a code point's entry, or -1 (unknown = space). */
    private int lookup(int cp) {
        int lo = 0, hi = count - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int e = HEADER + mid * ENTRY;
            int ecp = s32(e);
            if (ecp == cp) {
                return e;
            }
            if (Integer.compareUnsigned(ecp, cp) < 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return -1;
    }

    /** Pen advance in design pixels: the glyph's own advance when known,
     *  else a space-width guess (half/full by code point range). */
    int advance(int cp) {
        int e = lookup(cp);
        if (e >= 0) {
            return u8(e + 4);
        }
        return cp < 0x2E80 ? u16(10) : u16(12); // header half/full advance
    }

    /** Draw one glyph, y = TOP of the text line, scale = integer upscale
     *  (pixel fonts stay crisp at integer factors). All writes go through
     *  the canvas so dirty tracking and the clip stack apply. Unknown code
     *  points draw nothing (the caller still advances a space). */
    void drawGlyph(Canvas canvas, int x, int y, int cp, int color, int scale) {
        int e = lookup(cp);
        if (e < 0) {
            return;
        }
        int w = u8(e + 5);
        int h = u8(e + 6);
        int xoff = (byte) u8(e + 7);
        int yoff = (byte) u8(e + 8);
        long off = u32(e + 12);
        int rowBytes = (w + 7) >> 3;
        int top = ascent - yoff - h; // glyph-top row relative to the line top
        for (int row = 0; row < h; row++) {
            int ry = y + (top + row) * scale;
            for (int col = 0; col < w; col++) {
                int b = data[(int) (off + (long) row * rowBytes + (col >> 3))] & 0xFF;
                if ((b & (0x80 >> (col & 7))) != 0) {
                    int rx = x + (xoff + col) * scale;
                    if (scale == 1) {
                        canvas.setPixel(rx, ry, color);
                    } else {
                        canvas.fillRect(rx, ry, scale, scale, color);
                    }
                }
            }
        }
    }

    private int u8(int off) {
        return data[off] & 0xFF;
    }

    private int u16(int off) {
        return (data[off] & 0xFF) | (data[off + 1] & 0xFF) << 8;
    }

    private int s32(int off) {
        return (data[off] & 0xFF) | (data[off + 1] & 0xFF) << 8
                | (data[off + 2] & 0xFF) << 16 | (data[off + 3] & 0xFF) << 24;
    }

    private long u32(int off) {
        return s32(off) & 0xFFFFFFFFL;
    }
}
