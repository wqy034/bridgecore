package com.bridgecore.display;

import java.util.Arrays;

/** Final HUD-only contrast pass. Creates a two-canvas-pixel dark contour
 * around salient ink; no full-panel tint and no change to target semantics.
 * Original ink is preserved above the backing. Scratch storage is reused. */
public final class HudContrastPass {
    private byte[] halo = new byte[0];
    private final int[] coverage = new int[256];
    private final int[] ink = new int[256];

    public HudContrastPass() {
        for (int i = 0; i < 256; i++) {
            coverage[i] = HudPalette.contrastCoverage(i);
            ink[i] = HudPalette.contrastInk(i);
        }
    }

    /** Call once after all HUD layers, with normal clip restored. The HUD
     * owns a 256-color palette; console canvases never call this pass. */
    public void apply(Canvas canvas) {
        byte[] pixels = canvas.raw();
        if (halo.length != pixels.length) halo = new byte[pixels.length];
        else Arrays.fill(halo, (byte) 0);
        int w = canvas.width(), h = canvas.height();
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int level = coverage[pixels[row + x] & 255];
                if (level == 0) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -2; dx <= 2; dx++) {
                        int distance = dx * dx + dy * dy;
                        if (distance > 5) continue;
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        // Soft outermost ring; core remains a narrow dark
                        // separator instead of a blurred glow.
                        int alpha = distance >= 4 ? (level * 3 + 2) / 4 : level;
                        int at = yy * w + xx;
                        if ((halo[at] & 255) < alpha) halo[at] = (byte) alpha;
                    }
                }
            }
        }
        for (int i = 0; i < pixels.length; i++) {
            int original = pixels[i] & 255;
            if (original == 0) {
                int level = halo[i] & 255;
                if (level > 0) pixels[i] = (byte) (HudPalette.LADDER_HALO + level);
            } else {
                pixels[i] = (byte) ink[original];
            }
        }
    }
}
