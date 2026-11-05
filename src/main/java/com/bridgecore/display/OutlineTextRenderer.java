package com.bridgecore.display;

import com.bridgecore.BridgeCoreMod;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Antialiased TrueType outline text on the indexed canvas (2026-10-29 HUD
 * outline-font vertical slice). Rasterizes glyph OUTLINES with AWT
 * (Graphics2D antialiasing — explicitly no LCD subpixel hints), reads the
 * per-pixel coverage back from a grayscale image and quantizes it into one
 * of the 16-level alpha ladders of {@link HudPalette}
 * ({@code pixel = ladderBase + coverage*15/255}; coverage 0 = skipped).
 * That is what lets a 256-color indexed canvas show smooth font edges.
 *
 * Fonts ship in the jar (assets/bridgecore/fonts, OFL — the license texts
 * sit next to the TTFs): Noto Sans SC Medium (CJK labels, instancer-pinned
 * from the variable build at wght=500), Barlow SemiCondensed Medium and
 * Rajdhani Medium (big numeric readouts — the pick between the two is the
 * BCConfig hud.digitFont A/B switch). Loaded lazily on first use; if loading fails the
 * draw falls back to the packed pixel font ({@link Canvas#drawCjkText},
 * size snapped to a supported grade) so the HUD never goes blank. AWT font
 * rasterization is headless-safe — the integrated server shares the JVM
 * with the client, and nothing here touches a display/Toolkit. First use
 * rasterizes a probe string ("速度 012") and logs the result.
 *
 * yTop convention: {@code y} parameters are the LINE TOP, matching the
 * canvas's dot-matrix calls (layout constants subtract the font height
 * from blueprint baselines); internally {@code baseline = yTop + ascent}.
 * Server-render thread only — the glyph cache is not synchronized.
 */
public final class OutlineTextRenderer {
    /** Noto Sans SC Medium — CJK labels. */
    public static final int FONT_NOTO = 0;
    /** Barlow SemiCondensed Medium — numeric readouts. */
    public static final int FONT_BARLOW = 1;
    /** Rajdhani Medium — numeric readouts (2026-10-29 A/B alternative,
     *  selected via BCConfig hud.digitFont). */
    public static final int FONT_RAJDHANI = 2;

    private static final String[] FONT_RESOURCES = {
            "/assets/bridgecore/fonts/NotoSansSC-Medium.ttf",
            "/assets/bridgecore/fonts/BarlowSemiCondensed-Medium.ttf",
            "/assets/bridgecore/fonts/Rajdhani-Medium.ttf",
    };

    /** Antialiased, non-fractional metrics: advances are rounded to whole
     *  canvas pixels anyway, and integer metrics keep draw and measure
     *  ({@link #outlineTextWidth}) on the same numbers. */
    private static final FontRenderContext FRC = new FontRenderContext(null, true, false);

    private static final Font[] BASE = new Font[FONT_RESOURCES.length];
    private static boolean loadAttempted;
    private static boolean loadFailed;

    /** deriveFont cache, key = fontId * 65536 + sizePx. */
    private static final Map<Integer, Font> DERIVED = new LinkedHashMap<>();
    private static final Map<Integer, Integer> READOUT_EM = new LinkedHashMap<>();
    /** Ascent per derived font, same key as DERIVED. */
    private static final Map<Integer, Integer> ASCENTS = new LinkedHashMap<>();
    /** Glyph coverage cache, key = fontId << 40 | sizePx << 20 | codepoint.
     *  Bounded LRU — the HUD rotates through a small digit/label alphabet,
     *  so a few hundred entries hold a working set easily. */
    private static final int GLYPH_CACHE_MAX = 512;
    private static final Map<Long, Glyph> GLYPHS =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Glyph> eldest) {
                    return size() > GLYPH_CACHE_MAX;
                }
            };

    /** One rasterized glyph: coverage map + placement relative to the
     *  BASELINE (x0 = left bearing, y0 = top, usually negative). */
    private record Glyph(int advance, int x0, int y0, int w, int h, byte[] coverage) {
    }

    private OutlineTextRenderer() {
    }

    /** True once both fonts loaded successfully (checked on first use). */
    public static boolean available() {
        ensureLoaded();
        return !loadFailed;
    }

    /** Draw {@code text} with the line top at (x, yTop); coverage quantizes
     *  into {@code ladderBase}..ladderBase+15 (see HudPalette). Code points
     *  the font cannot display render as a full-alpha hollow tofu box.
     *  Supplementary code points (>0xFFFF) are skipped without advancing
     *  (parity with drawCjkText). Font-load failure falls back to the
     *  packed pixel font. */
    public static void drawOutlineText(Canvas canvas, int x, int yTop, String text,
                                       int sizePx, int fontId, int ladderBase) {
        if (!available()) {
            canvas.drawCjkText(x, yTop, text, ladderBase + HudPalette.LEVELS - 1,
                    fallbackSize(sizePx));
            return;
        }
        Font font = derived(fontId, sizePx);
        int baseline = yTop + ascent(fontId, sizePx);
        int penX = x;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp > 0xFFFF) {
                continue;
            }
            if (!font.canDisplay(cp)) {
                int adv = Math.max(sizePx / 2, 1);
                canvas.drawRect(penX, yTop, adv, sizePx,
                        ladderBase + HudPalette.LEVELS - 1);
                penX += adv;
                continue;
            }
            Glyph g = glyph(font, fontId, sizePx, cp);
            for (int py = 0; py < g.h(); py++) {
                for (int px = 0; px < g.w(); px++) {
                    int coverage = g.coverage()[py * g.w() + px] & 0xFF;
                    if (coverage == 0) {
                        continue;
                    }
                    canvas.setPixel(penX + g.x0() + px, baseline + g.y0() + py,
                            ladderBase + coverage * (HudPalette.LEVELS - 1) / 255);
                }
            }
            penX += g.advance();
        }
    }

    /** Pixel width of {@code text} as {@link #drawOutlineText} would lay it
     *  out (sum of glyph advances). */
    public static int outlineTextWidth(int fontId, int sizePx, String text) {
        if (!available()) {
            return fallbackWidth(fontId, sizePx, text);
        }
        Font font = derived(fontId, sizePx);
        int w = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp > 0xFFFF) {
                continue;
            }
            if (!font.canDisplay(cp)) {
                w += Math.max(sizePx / 2, 1);
                continue;
            }
            w += glyph(font, fontId, sizePx, cp).advance();
        }
        return w;
    }

    /** Draw a numeric readout by VISIBLE cap height, centered by ink bounds.
     *  A 42px em is not a 42px digit. Use the font's own digit outline to
     *  choose em size; fit long values to the pod, then center the actual ink. */
    public static void drawReadout(Canvas canvas, int cx, int cy, String text,
                                   int inkHeight, int maxWidth, int fontId, int ladderBase) {
        int size = inkHeight;
        if (available()) {
            size = READOUT_EM.computeIfAbsent(fontId * 65536 + inkHeight, key -> {
                Rectangle2D caps = BASE[fontId].deriveFont(100.0f)
                        .createGlyphVector(FRC, "0123456789").getVisualBounds();
                return caps.getHeight() > 0
                        ? (int) Math.round(inkHeight * 100.0 / caps.getHeight()) : inkHeight;
            });
        }
        size = Math.max(18, Math.min(size, 96));
        while (size > 18 && outlineTextWidth(fontId, size, text) > maxWidth) size--;
        int width = outlineTextWidth(fontId, size, text);
        int top = cy - size / 2;
        if (available()) {
            Font font = derived(fontId, size);
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (int cp : text.codePoints().toArray()) {
                if (cp > 0xFFFF || !font.canDisplay(cp)) continue;
                Glyph g = glyph(font, fontId, size, cp);
                if (g.h() > 0) {
                    minY = Math.min(minY, g.y0());
                    maxY = Math.max(maxY, g.y0() + g.h());
                }
            }
            if (minY != Integer.MAX_VALUE) {
                int baseline = cy - Math.floorDiv(minY + maxY, 2);
                top = baseline - ascent(fontId, size);
            }
        }
        drawOutlineText(canvas, cx - width / 2, top, text, size, fontId, ladderBase);
    }

    private static void ensureLoaded() {
        if (loadAttempted) {
            return;
        }
        loadAttempted = true;
        for (int id = 0; id < FONT_RESOURCES.length; id++) {
            try (InputStream in = OutlineTextRenderer.class
                    .getResourceAsStream(FONT_RESOURCES[id])) {
                if (in == null) {
                    throw new IllegalStateException("missing " + FONT_RESOURCES[id]);
                }
                BASE[id] = Font.createFont(Font.TRUETYPE_FONT, in);
            } catch (Exception e) {
                loadFailed = true;
                BridgeCoreMod.LOGGER.warn("[bridgecore] outline font {} failed to load "
                        + "({}) — HUD outline text falls back to the pixel font",
                        FONT_RESOURCES[id], e.toString());
                return;
            }
        }
        // Headless smoke test (first use): rasterize a CJK + a digit glyph
        // and log the coverage so a broken AWT pipeline is visible in the
        // log instead of silently rendering nothing.
        Font probe = derived(FONT_NOTO, 24);
        Glyph cjk = glyph(probe, FONT_NOTO, 24, '速');
        Glyph digit = glyph(derived(FONT_BARLOW, 42), FONT_BARLOW, 42, '0');
        int nonzero = 0;
        for (byte b : cjk.coverage()) {
            nonzero += b != 0 ? 1 : 0;
        }
        for (byte b : digit.coverage()) {
            nonzero += b != 0 ? 1 : 0;
        }
        if (nonzero == 0) {
            loadFailed = true;
            BridgeCoreMod.LOGGER.warn("[bridgecore] outline probe rendered empty — "
                    + "falling back to the pixel font");
        }
    }

    private static Font derived(int fontId, int sizePx) {
        return DERIVED.computeIfAbsent(fontId * 65536 + sizePx,
                k -> BASE[fontId].deriveFont((float) sizePx));
    }

    private static int ascent(int fontId, int sizePx) {
        return ASCENTS.computeIfAbsent(fontId * 65536 + sizePx, k -> {
            LineMetrics lm = derived(fontId, sizePx).getLineMetrics("Mg", FRC);
            return (int) Math.ceil(lm.getAscent());
        });
    }

    /** Rasterize one code point (cached). */
    private static Glyph glyph(Font font, int fontId, int sizePx, int cp) {
        long key = ((long) fontId << 40) | ((long) sizePx << 20) | cp;
        Glyph cached = GLYPHS.get(key);
        if (cached != null) {
            return cached;
        }
        GlyphVector gv = font.createGlyphVector(FRC, new String(Character.toChars(cp)));
        int advance = Math.max(1, (int) Math.round(gv.getGlyphMetrics(0).getAdvanceX()));
        java.awt.Shape outline = gv.getGlyphOutline(0);
        Rectangle2D b = outline.getBounds2D();
        if (b.isEmpty()) {
            Glyph space = new Glyph(advance, 0, 0, 0, 0, new byte[0]);
            GLYPHS.put(key, space);
            return space;
        }
        int x0 = (int) Math.floor(b.getX());
        int y0 = (int) Math.floor(b.getY());
        int w = (int) Math.ceil(b.getMaxX()) - x0;
        int h = (int) Math.ceil(b.getMaxY()) - y0;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.translate(-x0, -y0);
            g2.fill(outline);
        } finally {
            g2.dispose();
        }
        byte[] coverage = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        Glyph glyph = new Glyph(advance, x0, y0, w, h, coverage);
        GLYPHS.put(key, glyph);
        return glyph;
    }

    /** Snap an arbitrary outline size to a pixel-font grade for the
     *  load-failure fallback (drawCjkText supports 12/16/24/36 only). */
    private static int fallbackSize(int sizePx) {
        if (sizePx <= 14) return 12;
        if (sizePx <= 20) return 16;
        if (sizePx <= 30) return 24;
        return 36;
    }

    private static int fallbackWidth(int fontId, int sizePx, String text) {
        // mirror Canvas.cjkTextWidth's grade mapping without a scratch canvas
        int size = fallbackSize(sizePx);
        CjkFont font = size == 16 ? CjkFont.of(8) : CjkFont.of(12);
        int scale = size == 12 ? 1 : size == 36 ? 3 : 2;
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
}
