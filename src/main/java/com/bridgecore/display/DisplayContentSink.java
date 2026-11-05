package com.bridgecore.display;

/**
 * Internal content interface for tiled screens: the drawing surface a display app
 * paints into. Implemented by {@code DisplayGpuBlockEntity}; its frame buffer +
 * sync pipeline is the only backend. All methods run on the server thread.
 *
 * Coordinates are 0-based whole-screen pixels, origin top-left. Colors are
 * 0xRRGGBB; alpha is forced opaque (the tiles render fullbright).
 */
public interface DisplayContentSink {
    /** Whole-screen width in pixels (tile count × per-tile resolution). */
    int width();

    int height();

    void setPixel(int x, int y, int rgb);

    /** Rectangle, clipped to the screen. */
    void fill(int x, int y, int w, int h, int rgb);

    /** 5x7 font (uppercase via the Font5x7 convention), 6 px advance per glyph. */
    void drawText(int x, int y, String text, int rgb);

    /** Opaque black. */
    void clear();

    /**
     * Push pending writes to clients. Multiple flushes within one tick merge into
     * a single sync pass; only tiles whose pixels changed send packets.
     */
    void flush();
}
