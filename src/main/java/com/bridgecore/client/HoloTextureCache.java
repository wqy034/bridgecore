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
import net.minecraft.world.level.ChunkPos;
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
 */
public final class HoloTextureCache {
    private static final Map<BlockPos, DynamicTexture> TEXTURES = new ConcurrentHashMap<>();
    private static final Map<BlockPos, ResourceLocation> IDS = new ConcurrentHashMap<>();
    /** Partial multi-band frames in flight, keyed like TEXTURES. */
    private static final Map<BlockPos, byte[][]> BANDS = new ConcurrentHashMap<>();
    /** R8-2：半帧时间戳——缺段超过 5s 的缓冲帧直接丢弃，不永久占位。 */
    private static final Map<BlockPos, Long> BAND_TIMES = new ConcurrentHashMap<>();

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
        // R8-2：bandCount 上限 16（对端可伪造 VarInt 全域，防 OOM）。
        if (bandCount < 1 || bandCount > 16 || bandIndex < 0 || bandIndex >= bandCount) {
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
        // R8-3：同一 pos 的 canvas 尺寸变化时释放旧纹理重建（此前永不重建，
        // 错误尺寸先到会把正确帧永久挡在外面）。
        DynamicTexture tex = TEXTURES.get(pos);
        if (tex != null) {
            NativeImage old = tex.getPixels();
            if (old != null && (old.getWidth() != w || old.getHeight() != h)) {
                remove(pos);
                tex = null;
            }
        }
        if (tex == null) tex = TEXTURES.computeIfAbsent(pos, p -> create(p, w, h));
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
        long now = System.currentTimeMillis();
        Long stamp = BAND_TIMES.get(pos);
        if (stamp != null && now - stamp > 5000L) {
            BANDS.remove(pos);
        }
        BAND_TIMES.put(pos, now);
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
        BAND_TIMES.remove(pos);
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

    static void expireBands() {
        long now = System.currentTimeMillis();
        BAND_TIMES.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= 5000L) return false;
            BANDS.remove(entry.getKey());
            return true;
        });
    }

    static void unloadChunk(ChunkPos chunk) {
        IDS.keySet().forEach(pos -> { if (inChunk(pos, chunk)) remove(pos); });
        BAND_TIMES.keySet().forEach(pos -> {
            if (inChunk(pos, chunk)) { BANDS.remove(pos); BAND_TIMES.remove(pos); }
        });
    }

    static void clear() {
        IDS.keySet().forEach(HoloTextureCache::remove);
        BANDS.clear();
        BAND_TIMES.clear();
    }

    static boolean inChunk(BlockPos pos, ChunkPos chunk) {
        return pos.getX() >> 4 == chunk.x && pos.getZ() >> 4 == chunk.z;
    }

    private static void remove(BlockPos pos) {
        TEXTURES.remove(pos);
        ResourceLocation id = IDS.remove(pos);
        if (id != null) Minecraft.getInstance().getTextureManager().release(id);
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
