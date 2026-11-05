package com.bridgecore.client;

import com.bridgecore.display.DisplayTileBlock;
import com.bridgecore.display.DisplayTileBlockEntity;
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
 * Renders one screen tile: a single fullbright quad textured with the synced
 * pixel slice, hovering 0.001 above the display face (the offset avoids
 * z-fighting with the block's own face). Works for all six facings: the
 * PoseStack is rotated about the block center so the +Z quad lands on the
 * FACING side. On a ship the Sable render dispatcher hands us a PoseStack
 * already in sub-level local space, so the tile rides the ship for free.
 */
public class DisplayTileRenderer implements BlockEntityRenderer<DisplayTileBlockEntity> {

    public DisplayTileRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(DisplayTileBlockEntity be, float partialTicks, PoseStack stack,
                       MultiBufferSource buffers, int light, int overlay) {
        ResourceLocation tex = DisplayTileTextureCache.get(be.getBlockPos());
        if (tex == null) return;

        stack.pushPose();
        Direction facing = be.getBlockState().getValue(DisplayTileBlock.FACING);
        stack.translate(0.5, 0.5, 0.5);
        if (facing.getAxis() != Direction.Axis.Y) {
            stack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        } else {
            stack.mulPose(Axis.XP.rotationDegrees(-facing.getStepY() * 90));
        }
        stack.translate(-0.5, -0.5, -0.5);

        VertexConsumer buf = buffers.getBuffer(RenderType.entityTranslucent(tex));
        Matrix4f mat = stack.last().pose();
        PoseStack.Pose pose = stack.last();
        int fullBright = LightTexture.FULL_BRIGHT;
        int noOverlay = OverlayTexture.NO_OVERLAY;

        float z = 1.001F;
        buf.addVertex(mat, 1, 1, z).setColor(1F, 1F, 1F, 1F).setUv(1, 0).setOverlay(noOverlay)
                .setLight(fullBright).setNormal(pose, 0.0F, 0.0F, 1.0F);
        buf.addVertex(mat, 0, 1, z).setColor(1F, 1F, 1F, 1F).setUv(0, 0).setOverlay(noOverlay)
                .setLight(fullBright).setNormal(pose, 0.0F, 0.0F, 1.0F);
        buf.addVertex(mat, 0, 0, z).setColor(1F, 1F, 1F, 1F).setUv(0, 1).setOverlay(noOverlay)
                .setLight(fullBright).setNormal(pose, 0.0F, 0.0F, 1.0F);
        buf.addVertex(mat, 1, 0, z).setColor(1F, 1F, 1F, 1F).setUv(1, 1).setOverlay(noOverlay)
                .setLight(fullBright).setNormal(pose, 0.0F, 0.0F, 1.0F);
        stack.popPose();
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
