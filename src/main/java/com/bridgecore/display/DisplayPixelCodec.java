package com.bridgecore.display;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Opaque ABGR tiles on the wire: RGB24 literal blocks interleaved with runs.
 * A literal holds <=128 pixels, so worst-case 512² noise stays below 800 KiB;
 * text/panel backgrounds compress into short runs. No compression library or
 * unbounded inflate operation; decoded pixel count is known before allocation. */
public final class DisplayPixelCodec {
    public static final int MAX_PIXELS = DisplayResolution.MAX * DisplayResolution.MAX;
    public static final int MAX_BYTES = MAX_PIXELS * 3 + ((MAX_PIXELS + 127) / 128) * 2;
    private DisplayPixelCodec() {}

    public static byte[] encode(int[] pixels) {
        if (pixels.length > MAX_PIXELS) throw new IllegalArgumentException("Oversized display tile");
        for (int color : pixels) if ((color >>> 24) != 255)
            throw new IllegalArgumentException("Display tiles must be opaque ABGR");
        var out = new ByteArrayOutputStream(Math.min(pixels.length * 3, 8192));
        int i = 0;
        while (i < pixels.length) {
            int run = run(pixels, i);
            if (run >= 3) {
                token(out, (run << 1) | 1);
                color(out, pixels[i]);
                i += run;
            } else {
                int start = i++;
                while (i < pixels.length && i - start < 128
                        && !(i + 2 < pixels.length && pixels[i] == pixels[i + 1] && pixels[i] == pixels[i + 2])) i++;
                token(out, (i - start) << 1);
                for (int p = start; p < i; p++) color(out, pixels[p]);
            }
        }
        return out.toByteArray();
    }
    private static int run(int[] pixels, int start) {
        int end = start + 1;
        while (end < pixels.length && pixels[end] == pixels[start]) end++;
        return end - start;
    }
    private static void token(ByteArrayOutputStream out, int value) {
        while ((value & ~127) != 0) { out.write((value & 127) | 128); value >>>= 7; }
        out.write(value);
    }
    private static void color(ByteArrayOutputStream out, int value) {
        out.write(value >>> 16); out.write(value >>> 8); out.write(value);
    }
    public static int[] decode(byte[] data, int expectedPixels) {
        if (expectedPixels < 0 || expectedPixels > MAX_PIXELS || data.length > MAX_BYTES)
            throw new IllegalArgumentException("Oversized display data");
        int[] pixels = new int[expectedPixels];
        int offset = 0, written = 0;
        while (written < expectedPixels) {
            int token = 0, shift = 0, next;
            do {
                if (offset >= data.length || shift > 14) throw new IllegalArgumentException("Invalid display token");
                next = data[offset++] & 255;
                token |= (next & 127) << shift;
                shift += 7;
            } while ((next & 128) != 0);
            int count = token >>> 1;
            boolean run = (token & 1) != 0;
            if (count == 0 || count > expectedPixels - written
                    || data.length - offset < (run ? 3 : count * 3))
                throw new IllegalArgumentException("Invalid display block length");
            if (run) {
                int color = rgb(data, offset); offset += 3;
                Arrays.fill(pixels, written, written + count, color);
            } else {
                for (int i = 0; i < count; i++, offset += 3) pixels[written + i] = rgb(data, offset);
            }
            written += count;
        }
        if (offset != data.length) throw new IllegalArgumentException("Trailing display data");
        return pixels;
    }
    private static int rgb(byte[] data, int offset) {
        return 0xFF000000 | ((data[offset] & 255) << 16) | ((data[offset + 1] & 255) << 8) | (data[offset + 2] & 255);
    }
}
