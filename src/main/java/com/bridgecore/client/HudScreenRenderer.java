package com.bridgecore.client;

import com.bridgecore.HudScreenBlock;
import com.bridgecore.HudScreenBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * Renders the floating projection panel above the screen block: a translucent quad
 * textured with the synced canvas. On a ship the Sable render dispatcher hands us a
 * PoseStack already in sub-level local space, so the panel rides the ship for free.
 *
 * Panel geometry (2026-10-26 v2, user-approved): center pinned to the FIXED
 * anchor height HudLockBoxLayer.PANEL_ANCHOR_Y (3.85 above the block base =
 * block top 1.0 + 1.6 clearance + reference half 1.25) plus the pilot-relative
 * offsets (calibX/calibY, applied after the yaw rotation). Size scales AROUND
 * THAT CENTER: half = 1.25 x screenScale (right-click notch) x calibScale —
 * 2.5 blocks wide at scale 1. The old model coupled size into position
 * (anchorHalf = max(half, 1.25)) and scaled the panel by calibEyeX/2 — the
 * legacy Lua (startup.lua:2119) never did that; calibEyeX/calibEyeY are
 * cockpit-geometry compensation for the PROJECTION EYE POINT only (see
 * HudLockBoxLayer.panelGeometry) and have no business here. Facing the pilot
 * (opposite of FACING = bow). Single-sided translucent quad — from behind it
 * reads mirrored, like any real see-through display.
 */
public class HudScreenRenderer implements BlockEntityRenderer<HudScreenBlockEntity> {

    public HudScreenRenderer(BlockEntityRendererProvider.Context context) {
    }

    /**
     * Renders the floating projection panel above the screen block: a translucent quad
     * textured with the synced canvas. On a ship the Sable render dispatcher hands us a
     * PoseStack already in sub-level local space, so the panel rides the ship for free.
     *
     * Panel geometry: center at the fixed anchor height (see the class javadoc),
     * size scaling around that center — scaling NEVER moves the panel. Keep in
     * sync with HudLockBoxLayer's panelGeometry (same formula, same constant).
     * Single-sided: entityTranslucent does NOT cull (2026-09-01 test: two coplanar quads
     * both blended through and the mirrored back face garbled the text), so we emit one
     * quad; from behind the panel reads mirrored, like any real see-through display.
     */
    @Override
    public void render(HudScreenBlockEntity be, float partialTicks, PoseStack stack,
                       MultiBufferSource buffers, int light, int overlay) {
        ResourceLocation tex = HoloTextureCache.get(be.getBlockPos());
        if (tex == null) return;

        // Core-pushed calibration (config GUI tab 3): calibScale stacks with the
        // block's own screenScale; the offsets are applied AFTER the yaw rotation so
        // they read pilot-relative (X = right, Y = up from the viewer's seat).
        float half = 1.25f * be.getScreenScale() * (float) be.getCalibScale();
        stack.pushPose();
        stack.translate(0.5, com.bridgecore.display.HudLockBoxLayer.PANEL_ANCHOR_Y, 0.5);
        Direction facing = be.getBlockState().getValue(HudScreenBlock.FACING);
        stack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot() + 180.0f));
        stack.translate(be.getCalibX(), be.getCalibY(), 0.0);

        VertexConsumer buf = buffers.getBuffer(RenderType.entityTranslucent(tex));
        Matrix4f mat = stack.last().pose();
        int fullBright = LightTexture.FULL_BRIGHT;
        int noOverlay = OverlayTexture.NO_OVERLAY;

        // (x right, y up in panel space; texture v=0 is the image top)
        buf.addVertex(mat, -half, half, 0f).setColor(1f, 1f, 1f, 1f).setUv(0f, 0f)
                .setOverlay(noOverlay).setLight(fullBright).setNormal(0, 0, 1);
        buf.addVertex(mat, -half, -half, 0f).setColor(1f, 1f, 1f, 1f).setUv(0f, 1f)
                .setOverlay(noOverlay).setLight(fullBright).setNormal(0, 0, 1);
        buf.addVertex(mat, half, -half, 0f).setColor(1f, 1f, 1f, 1f).setUv(1f, 1f)
                .setOverlay(noOverlay).setLight(fullBright).setNormal(0, 0, 1);
        buf.addVertex(mat, half, half, 0f).setColor(1f, 1f, 1f, 1f).setUv(1f, 0f)
                .setOverlay(noOverlay).setLight(fullBright).setNormal(0, 0, 1);

        stack.popPose();
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
