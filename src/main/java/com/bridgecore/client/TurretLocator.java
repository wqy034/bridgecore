package com.bridgecore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import java.util.List;

/** A short-lived, client-only outline around the selected turret mounts. */
final class TurretLocator {
    private record Marker(ClientLevel level, List<BlockPos> positions, long expires) {}
    private static Marker marker;

    private TurretLocator() {}

    static void show(ClientLevel level, List<BlockPos> positions) {
        marker = new Marker(level, List.copyOf(positions), System.nanoTime() + 12_000_000_000L);
    }

    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || marker == null) return;
        var mc = Minecraft.getInstance();
        var current = marker;
        if (mc.level != current.level() || System.nanoTime() >= current.expires()) {
            marker = null;
            return;
        }
        var buffers = mc.renderBuffers().bufferSource();
        var lines = RenderType.lines();
        for (var pos : current.positions()) {
            if (current.level().hasChunkAt(pos)) draw(event, current.level(), pos, buffers.getBuffer(lines));
        }
        buffers.endBatch(lines);
    }

    private static void draw(RenderLevelStageEvent event, ClientLevel level, BlockPos pos, VertexConsumer lines) {
        var center = new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        var sub = Sable.HELPER.getContaining(level, pos);
        var pose = sub instanceof ClientSubLevel client ? client.renderPose() : null;
        if (pose != null) pose.transformPosition(center, center);
        var camera = event.getCamera().getPosition();
        PoseStack stack = event.getPoseStack();
        stack.pushPose();
        stack.translate(center.x - camera.x, center.y - camera.y, center.z - camera.z);
        if (pose != null) stack.mulPose(new Quaternionf(pose.orientation()));
        LevelRenderer.renderLineBox(stack, lines, new AABB(-0.57, -0.57, -0.57, 0.57, 0.57, 0.57),
                0.23f, 0.9f, 1.0f, 1.0f);
        stack.popPose();
    }
}
