package com.bridgecore.client;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.display.DisplayResolution;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client side of the tile slice sync: one DynamicTexture per screen tile, keyed
 * by (plot-space) BlockPos — positions are unique per sub-level, so a bare pos
 * key cannot collide across ships (same argument as HoloTextureCache).
 *
 * Slices arrive as ABGR ints, which is exactly NativeImage's channel order, so
 * the upload is a straight per-pixel copy.
 *
 * Eviction follows server clears, tile removal and client chunk or level unload.
 */
public final class DisplayTileTextureCache {
    private static final Map<BlockPos, Entry> TEXTURES = new ConcurrentHashMap<>();

    private DisplayTileTextureCache() {
    }

    public static void update(BlockPos pos, int w, int[] pixels) {
        if (w <= 0 || w > DisplayResolution.MAX || pixels.length != w * w) {
            BridgeCoreMod.LOGGER.warn("Ignoring malformed display tile slice: {} px for width {}",
                    pixels.length, w);
            return;
        }
        Entry entry = TEXTURES.compute(pos, (p, old) -> old != null && old.width == w ? old : create(p, w, old));
        NativeImage img = entry.tex.getPixels();
        if (img == null || img.getWidth() != w) return;
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < w; x++) {
                img.setPixelRGBA(x, y, pixels[y * w + x]);
            }
        }
        entry.tex.upload();
        // 2026-10-31 (火控 agent): tiles stay at upload()'s own NEAREST/NEAREST
        // reset — the 76d1b16 trilinear-min chain (TextureFiltering) was tuned
        // for the 1024² HUD hologram (minified at panel size, thin lines broke
        // under NEAREST); on 128..512px tile textures it visibly blurs the pixel
        // fonts of the panel apps (火控显示屏糊屏事件). The HUD keeps its chain.
    }

    private static Entry create(BlockPos pos, int w, Entry old) {
        if (old != null) {
            Minecraft.getInstance().getTextureManager().release(old.id);
        }
        DynamicTexture tex = new DynamicTexture(w, w, false);
        // no TextureFiltering.enableMipmapChain here — tiles are NEAREST-only
        // (see update()); the mip chain exists solely for the HUD hologram.
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID,
                "display_tile/" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ());
        Minecraft.getInstance().getTextureManager().register(id, tex);
        return new Entry(tex, id, w);
    }

    /** Texture for the renderer, or null before the first sync arrives. */
    public static ResourceLocation get(BlockPos pos) {
        Entry entry = TEXTURES.get(pos);
        return entry == null ? null : entry.id;
    }

    /** Drop a tile's texture (server told us the tile left its array, or the
     *  client BE was removed). Without this a re-placed tile at the same spot
     *  would show the previous tenant's last frame until its first sync. */
    public static void remove(BlockPos pos) {
        Entry entry = TEXTURES.remove(pos);
        if (entry != null) {
            Minecraft.getInstance().getTextureManager().release(entry.id);
        }
    }

    static void unloadChunk(ChunkPos chunk) {
        TEXTURES.keySet().forEach(pos -> { if (HoloTextureCache.inChunk(pos, chunk)) remove(pos); });
    }

    static void clear() {
        TEXTURES.keySet().forEach(DisplayTileTextureCache::remove);
    }

    private record Entry(DynamicTexture tex, ResourceLocation id, int width) {
    }
}
