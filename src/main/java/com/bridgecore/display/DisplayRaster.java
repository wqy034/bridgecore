package com.bridgecore.display;

import java.util.ArrayDeque;
import java.util.Deque;

/** RGB drawing adapter over the existing sink and Canvas font. Server thread only. */
public final class DisplayRaster {
    private final DisplayContentSink sink;
    private final Canvas glyph = new Canvas(6, 9);
    private final Deque<int[]> clips = new ArrayDeque<>();
    private int[] clip;

    public DisplayRaster(DisplayContentSink sink) { this.sink = sink; resetClip(); }
    public void resetClip() { clips.clear(); clip = new int[]{0, 0, sink.width(), sink.height()}; }
    public void pushClip(int x, int y, int w, int h) {
        if (clips.size() == 4) throw new IllegalArgumentException("clip stack overflow (depth 4)");
        if (w < 0 || h < 0) throw new IllegalArgumentException("negative clip size");
        clips.push(clip);
        clip = new int[]{Math.max(clip[0], x), Math.max(clip[1], y),
                (int)Math.min(clip[2], (long)x + w), (int)Math.min(clip[3], (long)y + h)};
    }
    public void popClip() {
        if (clips.isEmpty()) throw new IllegalArgumentException("clip stack underflow");
        clip = clips.pop();
    }
    public void fill(int x, int y, int w, int h, int rgb) {
        if (w < 0 || h < 0) throw new IllegalArgumentException("negative rectangle size");
        int x0 = Math.max(clip[0], x), y0 = Math.max(clip[1], y);
        int x1 = (int)Math.min(clip[2], (long)x + w), y1 = (int)Math.min(clip[3], (long)y + h);
        if (x1 > x0 && y1 > y0) sink.fill(x0, y0, x1 - x0, y1 - y0, rgb);
    }
    public void rect(int x, int y, int w, int h, int rgb) {
        if (w < 0 || h < 0) throw new IllegalArgumentException("negative rectangle size");
        if (w == 0 || h == 0) return;
        // Long endpoints prevent wraparound from untrusted Lua coordinates.
        fill(x, y, w, 1, rgb); fill(x, y, 1, h, rgb);
        long bottom = (long)y + h - 1, right = (long)x + w - 1;
        if (bottom <= Integer.MAX_VALUE) fill(x, (int)bottom, w, 1, rgb);
        if (right <= Integer.MAX_VALUE) fill((int)right, y, 1, h, rgb);
    }
    public void text(int x, int y, String text, int rgb, int scale) {
        if (scale < 1 || scale > 10) throw new IllegalArgumentException("font scale must be 1..10");
        long advance = 6L * scale;
        int first = (int)Math.min(text.length(), Math.max(0L, ((long)clip[0] - x) / advance));
        for (int i = first; i < text.length(); i++) {
            long cx = (long)x + i * advance;
            if (cx >= clip[2]) break;
            if ((long)y + 7L * scale <= clip[1] || y >= clip[3]) break;
            glyph.clear();
            glyph.drawText(0, 0, String.valueOf(text.charAt(i)), 1);
            for (int gy = 0; gy < 7; gy++) for (int gx = 0; gx < 5; gx++) {
                if (glyph.getPixel(gx, gy) != 0) {
                    long px = cx + gx * scale, py = (long)y + gy * scale;
                    if (px >= Integer.MIN_VALUE && px <= Integer.MAX_VALUE && py <= Integer.MAX_VALUE)
                        fill((int)px, (int)py, scale, scale, rgb);
                }
            }
        }
    }
}
