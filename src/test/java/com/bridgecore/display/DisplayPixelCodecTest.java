package com.bridgecore.display;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DisplayPixelCodecTest {
    @Test void panelBackgroundCompressesAndKeepsAbgrChannelOrder() {
        int[] pixels = new int[512 * 512];
        Arrays.fill(pixels, 0xFFA1B2C3);
        byte[] packed = DisplayPixelCodec.encode(pixels);
        assertTrue(packed.length < 16);
        assertArrayEquals(pixels, DisplayPixelCodec.decode(packed, pixels.length));
    }
    @Test void fullNoiseTileRemainsBelowClientPayloadLimit() {
        int[] pixels = new Random(19).ints(512 * 512).map(color -> color | 0xFF000000).toArray();
        byte[] packed = DisplayPixelCodec.encode(pixels);
        assertTrue(packed.length <= DisplayPixelCodec.MAX_BYTES);
        assertTrue(packed.length + 128 < 1024 * 1024);
        assertArrayEquals(pixels, DisplayPixelCodec.decode(packed, pixels.length));
    }
    @Test void mixedRunsAndLiteralBoundaryRoundTrip() {
        int[] pixels = new Random(27).ints(301 * 301).map(color -> color | 0xFF000000).toArray();
        for (int i = 0; i < pixels.length - 200; i += 271) Arrays.fill(pixels, i, i + 200, 0xFF010203);
        assertArrayEquals(pixels, DisplayPixelCodec.decode(DisplayPixelCodec.encode(pixels), pixels.length));
        assertArrayEquals(new int[0], DisplayPixelCodec.decode(DisplayPixelCodec.encode(new int[0]), 0));
    }
    @Test void malformedStreamsCannotOverrunOrSilentlyTruncate() {
        assertThrows(IllegalArgumentException.class, () -> DisplayPixelCodec.decode(new byte[]{0}, 1));
        assertThrows(IllegalArgumentException.class, () -> DisplayPixelCodec.decode(new byte[]{5, 1, 2, 3}, 1));
        assertThrows(IllegalArgumentException.class, () -> DisplayPixelCodec.decode(new byte[]{2, 1, 2}, 1));
        assertThrows(IllegalArgumentException.class, () -> DisplayPixelCodec.decode(new byte[]{3, 1, 2, 3, 0}, 1));
        assertThrows(IllegalArgumentException.class, () -> DisplayPixelCodec.decode(new byte[]{(byte)128,(byte)128,(byte)128,0}, 1));
        assertThrows(IllegalArgumentException.class, () -> DisplayPixelCodec.decode(new byte[0], Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> DisplayPixelCodec.decode(new byte[]{1}, 0));
    }
    @Test void unsupportedTransparencyCannotBeSilentlyLost() {
        assertThrows(IllegalArgumentException.class, () -> DisplayPixelCodec.encode(new int[]{0x00112233}));
        assertThrows(IllegalArgumentException.class, () -> DisplayPixelCodec.encode(new int[512 * 512 + 1]));
    }
}
