package com.bridgecore.client;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.display.Canvas;
import com.bridgecore.network.CanvasSyncPayload;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Client side of the canvas sync: one DynamicTexture per screen block, keyed by
 * (plot-space) BlockPos. Plot positions are unique per sub-level, so a bare
 * BlockPos key cannot collide across ships.
 *
 * Decoding (2026-10-29 rework, see CanvasSyncPayload): the packet carries an
 * explicit encoding byte — raw / nibble / RLE — and multi-band frames are
 * reassembled here per position (band 0 resets the buffer; decode happens
 * once all bands are in). Malformed packets (bad band indices, bad frame
 * size, RLE overruns) are dropped with a warn.
 *
 * Backlog: textures are never evicted (chunk-unload cleanup); test-scale leak only.
 */
public final class HoloTextureCache {
    private static final Map<BlockPos, DynamicTexture> TEXTURES = new ConcurrentHashMap<>();
    private static final Map<BlockPos, ResourceLocation> IDS = new ConcurrentHashMap<>();
    /** Partial multi-band frames in flight, keyed like TEXTURES. */
    private static final Map<BlockPos, byte[][]> BANDS = new ConcurrentHashMap<>();

    private HoloTextureCache() {
    }

    /** Consume one sync packet (or one band of a multi-packet frame).
     *  {@code palette} = the sender's custom palette (null = the canvas runs
     *  the global default). Indices beyond the palette length fall back to
     *  entry 0 (transparent), matching the server-side consumers. */
    public static void update(BlockPos pos, int w, int h, int encoding,
                              int bandIndex, int bandCount, byte[] data,
                              int @Nullable [] palette) {
        if (w <= 0 || h <= 0 || w > Canvas.DEFAULT_WIDTH || h > Canvas.DEFAULT_HEIGHT) {
            BridgeCoreMod.LOGGER.warn("[bridgecore] canvas sync at {} dropped: bad size {}x{}",
                    pos, w, h);
            return;
        }
        if (bandCount < 1 || bandIndex < 0 || bandIndex >= bandCount) {
            BridgeCoreMod.LOGGER.warn("[bridgecore] canvas sync at {} dropped: bad band {}/{}",
                    pos, bandIndex, bandCount);
            return;
        }
        byte[] encoded = data;
        if (bandCount > 1) {
            encoded = reassemble(pos, bandIndex, bandCount, data);
            if (encoded == null) {
                return; // frame incomplete (or the band was buffered) — wait for the rest
            }
        }
        byte[] frame = decode(pos, w, h, encoding, encoded);
        if (frame == null) {
            return; // malformed — already logged
        }
        int[] pal = palette != null ? palette : Canvas.PALETTE;
        DynamicTexture tex = TEXTURES.computeIfAbsent(pos, p -> create(p, w, h));
        NativeImage img = tex.getPixels();
        if (img == null || img.getWidth() != w || img.getHeight() != h) return;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = frame[y * w + x] & 0xFF;
                img.setPixelRGBA(x, y, toABGR(idx < pal.length ? pal[idx] : pal[0]));
            }
        }
        tex.upload();
        // Minification fix (2026-10-28): upload() resets the filters to
        // NEAREST/NEAREST — re-assert mag=NEAREST/min=trilinear and rebuild
        // the mip chain, see TextureFiltering.
        TextureFiltering.afterUpload(tex);
    }

    /** Buffer one band of a multi-packet frame; returns the concatenated
     *  encoded stream once every band has arrived, else null. Band 0 (or a
     *  bandCount change) resets the buffer so a re-sent frame cannot mix with
     *  a stale partial one. Packets to one player arrive in send order, but
     *  nothing here relies on that. */
    private static byte @Nullable [] reassemble(BlockPos pos, int bandIndex, int bandCount,
                                                byte[] data) {
        byte[][] slots = BANDS.compute(pos, (p, old) ->
                old == null || old.length != bandCount || bandIndex == 0
                        ? new byte[bandCount][] : old);
        slots[bandIndex] = data;
        int total = 0;
        for (byte[] slot : slots) {
            if (slot == null) {
                return null;
            }
            total += slot.length;
        }
        BANDS.remove(pos);
        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        for (byte[] slot : slots) {
            out.write(slot, 0, slot.length);
        }
        return out.toByteArray();
    }

    /** Decode one complete encoded frame to raw pixel indices (length w×h);
     *  null + warn on anything malformed. */
    private static byte @Nullable [] decode(BlockPos pos, int w, int h, int encoding,
                                            byte[] data) {
        int expected = w * h;
        switch (encoding) {
            case CanvasSyncPayload.ENCODING_RAW -> {
                if (data.length == expected) {
                    return data;
                }
            }
            case CanvasSyncPayload.ENCODING_NIBBLE -> {
                if ((expected & 1) == 0 && data.length == expected / 2) {
                    return CanvasSyncPayload.unpackNibbles(data, expected);
                }
            }
            case CanvasSyncPayload.ENCODING_RLE -> {
                byte[] frame = CanvasSyncPayload.unpackRle(data, expected);
                if (frame != null) {
                    return frame;
                }
            }
            default -> {
                // falls through to the warn below
            }
        }
        BridgeCoreMod.LOGGER.warn("[bridgecore] canvas sync at {} dropped: encoding={} "
                + "bytes={} expected={}", pos, encoding, data.length, expected);
        return null;
    }

    private static DynamicTexture create(BlockPos pos, int w, int h) {
        DynamicTexture tex = new DynamicTexture(w, h, false);
        TextureFiltering.enableMipmapChain(tex, w, h);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("bridgecore",
                "holo/" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ());
        Minecraft.getInstance().getTextureManager().register(id, tex);
        IDS.put(pos, id);
        return tex;
    }

    /** Texture for the renderer, or null before the first sync arrives. */
    public static ResourceLocation get(BlockPos pos) {
        return IDS.get(pos);
    }

    /** Canvas palette is ARGB; NativeImage wants ABGR. */
    private static int toABGR(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
