package com.bridgecore.control;

import com.bridgecore.BridgeCoreBlockEntity;
import com.bridgecore.CoreDirectory;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.joml.Vector3d;

import java.util.UUID;

/** Operator entry point for supervised N1 flight before terminal integration. */
public final class NavigationTestCommand {
    private static final double CORE_RANGE = 8.0;
    private static final double DEFAULT_SPEED = 4.0;

    private NavigationTestCommand() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(NavigationTestCommand::registerCommands);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        var offset = Commands.literal("offset").then(Commands.argument("dx", DoubleArgumentType.doubleArg())
                .then(Commands.argument("dy", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("dz", DoubleArgumentType.doubleArg())
                                .executes(ctx -> startOffset(ctx, DEFAULT_SPEED))
                                .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.1, NavigationController.MAX_SPEED))
                                        .executes(ctx -> startOffset(ctx, DoubleArgumentType.getDouble(ctx, "speed")))))));
        var nav = withActions(Commands.literal("nav"), false)
                .then(offset)
                .then(withActions(Commands.argument("core", BlockPosArgument.blockPos()), true));
        event.getDispatcher().register(Commands.literal("bridgecore").requires(source -> source.hasPermission(2))
                .then(nav));
    }

    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T withActions(T root, boolean explicit) {
        var target = Commands.argument("target", Vec3Argument.vec3(false))
                .executes(ctx -> start(ctx, explicit, DEFAULT_SPEED))
                .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.1, NavigationController.MAX_SPEED))
                        .executes(ctx -> start(ctx, explicit, DoubleArgumentType.getDouble(ctx, "speed"))));
        return root
                .then(Commands.literal("start").then(target))
                .then(Commands.literal("status").executes(ctx -> report(ctx, explicit, false)))
                .then(Commands.literal("cancel").executes(ctx -> report(ctx, explicit, true)));
    }

    private static int start(CommandContext<CommandSourceStack> ctx, boolean explicit, double speed)
            throws CommandSyntaxException {
        var core = coreOf(ctx, explicit);
        return core == null ? 0 : submit(ctx.getSource(), core, Vec3Argument.getVec3(ctx, "target"), speed);
    }

    private static int startOffset(CommandContext<CommandSourceStack> ctx, double speed) {
        var core = nearbyCore(ctx.getSource());
        if (core == null) return 0;
        var sample = core.telemetrySnapshot();
        if (sample == null) {
            ctx.getSource().sendFailure(Component.literal("The nearby flight core has no valid physics sample."));
            return 0;
        }
        Vec3 target = sample.position().add(DoubleArgumentType.getDouble(ctx, "dx"),
                DoubleArgumentType.getDouble(ctx, "dy"), DoubleArgumentType.getDouble(ctx, "dz"));
        return submit(ctx.getSource(), core, target, speed);
    }

    private static int submit(CommandSourceStack source, BridgeCoreBlockEntity core, Vec3 target, double speed) {
        UUID id = UUID.randomUUID();
        var admission = core.requestCruise(id, source.getLevel().dimension(), target, speed);
        if (admission != NavigationController.Admission.ACCEPTED) {
            source.sendFailure(Component.literal("Navigation rejected: " + admission));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Navigation " + id + " accepted -> " + target
                + " (core shipyard " + core.getBlockPos().toShortString() + ")"), false);
        return 1;
    }

    private static int report(CommandContext<CommandSourceStack> ctx, boolean explicit, boolean cancel)
            throws CommandSyntaxException {
        var core = coreOf(ctx, explicit);
        if (core == null) return 0;
        var status = core.navigationStatus();
        if (status == null || (cancel && !core.cancelNavigation(status.id()))) {
            ctx.getSource().sendFailure(Component.literal(cancel ? "No active navigation task." : "No navigation task."));
            return 0;
        }
        var result = core.navigationStatus();
        ctx.getSource().sendSuccess(() -> Component.literal("Navigation " + result.id() + ": " + result.phase()), false);
        return 1;
    }

    private static BridgeCoreBlockEntity coreOf(CommandContext<CommandSourceStack> ctx, boolean explicit)
            throws CommandSyntaxException {
        if (!explicit) return nearbyCore(ctx.getSource());
        var pos = BlockPosArgument.getLoadedBlockPos(ctx, "core");
        if (ctx.getSource().getLevel().getBlockEntity(pos) instanceof BridgeCoreBlockEntity core) return core;
        ctx.getSource().sendFailure(Component.literal("Select a loaded flight core by its shipyard coordinates."));
        return null;
    }

    private static BridgeCoreBlockEntity nearbyCore(CommandSourceStack source) {
        var position = source.getPosition();
        BridgeCoreBlockEntity match = null;
        var worldCenter = new Vector3d();
        if (SubLevelContainer.getContainer(source.getLevel()) instanceof ServerSubLevelContainer container) {
            for (var ship : container.getAllSubLevels()) {
                if (ship.isRemoved()) continue;
                for (var core : CoreDirectory.coresOf(ship)) {
                    if (core.isRemoved()) continue;
                    var pos = core.getBlockPos();
                    worldCenter.set(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    ship.logicalPose().transformPosition(worldCenter, worldCenter);
                    if (!worldCenter.isFinite()
                            || worldCenter.distanceSquared(position.x, position.y, position.z) > CORE_RANGE * CORE_RANGE) continue;
                    if (match != null) {
                        source.sendFailure(Component.literal(
                                "Multiple flight cores within 8 blocks. Move closer or select by shipyard coordinates: "
                                        + match.getBlockPos().toShortString() + " / " + pos.toShortString()));
                        return null;
                    }
                    match = core;
                }
            }
        }
        if (match == null) {
            source.sendFailure(Component.literal("Stand within 8 blocks of an assembled ship's flight core."));
        }
        return match;
    }
}
