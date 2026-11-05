package com.bridgecore.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

/**
 * Shared GL tuning for the synced dynamic textures (HoloTextureCache,
 * DisplayTileTextureCache) — 2026-10-28 minification fix: at the default panel
 * size the 1024² HUD texture is MINIFIED on screen, and NEAREST minification
 * randomly drops pixel rows/columns, which read as "broken" 1 px lines. Goal:
 * mag = NEAREST (pixel-crisp when zoomed), min = LINEAR_MIPMAP_LINEAR
 * (minified thin lines fade instead of breaking).
 *
 * NeoForge 1.21.1 facts (verified against the merged sources jar,
 * net.minecraft.client.renderer.texture.*):
 *  - DynamicTexture(w,h,...) -> TextureUtil.prepareImage allocates ONLY mip
 *    level 0 and sets TEXTURE_MAX_LEVEL=0 — MAX_LEVEL must be raised once at
 *    creation or the sampler never looks past level 0.
 *  - DynamicTexture.upload() -> NativeImage._upload calls
 *    setFilter(false,false) on EVERY upload (MIN/MAG back to NEAREST/NEAREST)
 *    and rewrites level 0 via glTexSubImage2D — so the filter params must be
 *    re-asserted and the mip chain regenerated after every upload (the chain
 *    storage itself survives; its content goes stale).
 *  - AbstractTexture.setFilter(blur,mipmap) cannot express mag=NEAREST +
 *    min=LINEAR_MIPMAP_LINEAR (blur drives both) — hence direct GL calls.
 *
 * Threading: all entry points require the render thread. Both caches are
 * reached from payload handlers via IPayloadContext.enqueueWork (client main
 * = render thread) — the same discipline the pre-existing upload() calls
 * already relied on.
 */
final class TextureFiltering {
    private TextureFiltering() {
    }

    /** One-time, right after construction: raise MAX_LEVEL to the full chain
     *  (prepareImage set it to 0) and set mag=NEAREST /
     *  min=LINEAR_MIPMAP_LINEAR. */
    static void enableMipmapChain(DynamicTexture tex, int width, int height) {
        RenderSystem.assertOnRenderThreadOrInit();
        tex.bind();
        int maxLevel = 31 - Integer.numberOfLeadingZeros(Math.max(width, height));
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, maxLevel);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
    }

    /** Every frame after DynamicTexture.upload(): re-assert the filters
     *  (upload resets them to NEAREST/NEAREST) and rebuild the mip chain
     *  (1024² -> ~0.3 M texel reads on the GPU, negligible at 10 Hz). */
    static void afterUpload(DynamicTexture tex) {
        RenderSystem.assertOnRenderThreadOrInit();
        tex.bind();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
    }
}
