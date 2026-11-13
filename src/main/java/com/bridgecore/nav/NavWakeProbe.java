package com.bridgecore.nav;

import com.bridgecore.BCConfig;
import com.bridgecore.BridgeCoreBlockEntity;
import com.bridgecore.BridgeCoreMod;
import com.bridgecore.control.CruiseHeading;
import com.bridgecore.control.CruiseRouteWatch;
import com.bridgecore.control.NavigationController;
import com.bridgecore.network.TerminalLinkPayload;
import com.bridgecore.network.TerminalReplyPayload;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.SubLevelTicketsSavedData;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.tracking_points.SubLevelTrackingPointSavedData;
import dev.ryanhcode.sable.sublevel.tracking_points.TrackingPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-thread ship wake and short recall. One ship per server, with no scan while idle. */
public final class NavWakeProbe {
    private static final int POLL_TICKS = 5;
    private static final int TIMEOUT_TICKS = 200;
    private static final int RELEASE_CHECK_TICKS = 60;
    private static final int START_COOLDOWN_TICKS = 100;
    private static final int RECALL_MAX_RANGE = 256;
    private static final double RECALL_MAX_SPEED = 4.0;
    private static final int RECALL_FLIGHT_TICKS = 6 * 60 * 20;
    private static final int ROUTE_TICKET_RADIUS = 1;
    private static final double ROUTE_PREFETCH_SPEED = 7.0;
    private static final TicketType<UUID> ROUTE_TICKET = TicketType.create("bridgecore_recall_route", UUID::compareTo);
    private static final SubLevelLoadingTicketType<Unit> TICKET = SubLevelLoadingTicketType.create(
            ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "nav_wake_probe"), Unit.CODEC);
    private static final Map<MinecraftServer, State> STATES = new HashMap<>();

    private NavWakeProbe() {}

    /** The in-memory holding chunk can differ from the last saved pointer until autosave. */
    public interface HoldingLocation {
        @Nullable ChunkPos bridgecore$holdingChunk();
        void bridgecore$setHoldingChunk(ChunkPos pos);
    }

    private static final class State {
        int nextStart;
        @Nullable Probe active;
    }

    private record Probe(ServerLevel level, ServerSubLevelContainer container, ServerSubLevel ship, BlockPos core,
                         UUID terminal, UUID player, int startedTick, boolean refreshActor,
                         @Nullable Recall recall) {}

    private static final class Recall {
        final UUID id = UUID.randomUUID();
        final Vec3 destination;
        final double speed;
        final double turnRate;
        final Set<ChunkPos> columns = new HashSet<>();
        @Nullable BridgeCoreBlockEntity runningCore;
        @Nullable Vec3 target;
        int launchedTick;

        Recall(Vec3 destination, double speed, double turnRate) {
            this.destination = destination;
            this.speed = speed;
            this.turnRate = turnRate;
        }
    }

    public static void register() {
        SableEventPlatform.INSTANCE.onSubLevelContainerReady((level, container) -> {
            if (container instanceof ServerSubLevelContainer serverContainer) {
                serverContainer.addObserver(new SubLevelObserver() {
                    @Override
                    public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
                        if (reason == SubLevelRemovalReason.UNLOADED && subLevel instanceof ServerSubLevel ship) {
                            recordFirstHoldingLocation(serverContainer, ship);
                        }
                    }
                });
            }
        });
        NeoForge.EVENT_BUS.addListener(NavWakeProbe::tick);
        NeoForge.EVENT_BUS.addListener(NavWakeProbe::clearRecoveredTickets);
        NeoForge.EVENT_BUS.addListener(NavWakeProbe::stop);
    }

    private static UUID trackingKey(UUID terminal) {
        return UUID.nameUUIDFromBytes(("bridgecore:nav_wake:" + terminal).getBytes(StandardCharsets.UTF_8));
    }

    /** Bind a location in Sable's persistent point index without holding any chunks. */
    public static void trackBinding(ServerLevel level, BridgeCoreBlockEntity core, UUID terminal) {
        if (!(Sable.HELPER.getContaining(core) instanceof ServerSubLevel ship)) return;
        BlockPos pos = core.getBlockPos();
        var point = new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        var pointer = ship.getLastSerializationPointer();
        SubLevelTrackingPointSavedData.getOrLoad(level).setTrackingPoint(trackingKey(terminal),
                new TrackingPoint(true, ship.getUniqueId(), pointer, point, null));
    }

    public static void untrackBinding(ServerLevel level, BlockPos core, UUID terminal) {
        var points = SubLevelTrackingPointSavedData.getOrLoad(level);
        UUID key = trackingKey(terminal);
        if (matches(points.getTrackingPoint(key), core)) points.removeTrackingPoint(key);
    }

    private static boolean matches(@Nullable TrackingPoint point, BlockPos pos) {
        return point != null && point.inSubLevel() && point.subLevelID() != null
                && pos.equals(BlockPos.containing(point.point().x, point.point().y, point.point().z));
    }

    private static void recordFirstHoldingLocation(ServerSubLevelContainer container, ServerSubLevel ship) {
        if (ship.getLastSerializationPointer() != null) return;
        var holding = container.getHoldingChunkMap().getHoldingSubLevel(ship.getUniqueId());
        if (!((Object) holding instanceof HoldingLocation location) || location.bridgecore$holdingChunk() == null) return;
        ChunkPos chunk = location.bridgecore$holdingChunk();
        var points = SubLevelTrackingPointSavedData.getOrLoad(container.getLevel());
        for (var actor : ship.getPlot().getBlockEntityActors()) {
            if (!(actor instanceof BridgeCoreBlockEntity core) || core.boundTerminalUuid() == null) continue;
            UUID key = trackingKey(core.boundTerminalUuid());
            var point = points.getTrackingPoint(key);
            if (matches(point, core.getBlockPos()) && ship.getUniqueId().equals(point.subLevelID())
                    && point.lastSavedSubLevelPointer() == null) {
                // Sable cannot migrate a tracking pointer on the first save of an already dormant ship.
                var placeholder = new Vector3d(chunk.getMiddleBlockX(), 0, chunk.getMiddleBlockZ());
                points.setTrackingPoint(key, new TrackingPoint(true, point.subLevelID(), null, point.point(), placeholder));
            }
        }
    }

    public static String request(ServerPlayer player, ItemStack terminalStack, UUID terminal) {
        return request(player, terminalStack, terminal, null);
    }

    public static String recall(ServerPlayer player, ItemStack terminalStack, UUID terminal,
                                double speed, double turnRate) {
        if (!(speed >= NavigationController.MIN_TERMINAL_SPEED && speed <= NavigationController.MAX_SPEED)
                || !(turnRate >= CruiseHeading.MIN_RATE && turnRate <= CruiseHeading.MAX_RATE)) {
            return "请在巡航设置中填写有效的速度和转向率";
        }
        Vec3 destination = Sable.HELPER.projectOutOfSubLevel(player.serverLevel(), player.position());
        if (!player.serverLevel().getWorldBorder().isWithinBounds(destination.x, destination.z)) {
            return "召回目标超出本维度边界";
        }
        return request(player, terminalStack, terminal, new Recall(destination, Math.min(speed, RECALL_MAX_SPEED), turnRate));
    }

    private static String request(ServerPlayer player, ItemStack terminalStack, UUID terminal, @Nullable Recall recall) {
        if (!player.isAlive() || player.isSpectator()) return "当前无法请求唤醒";
        ServerLevel level = player.serverLevel();
        BlockPos pos = NavTerminalData.boundCorePos(terminalStack);
        var dimension = NavTerminalData.boundCoreDim(terminalStack);
        if (pos == null || dimension == null) return "请先绑定飞控核心";
        if (!level.dimension().location().equals(dimension)) return "唤醒请求须与飞船处于同一维度";

        var points = SubLevelTrackingPointSavedData.getOrLoad(level);
        var point = points.getTrackingPoint(trackingKey(terminal));
        if (!matches(point, pos)) {
            // Existing terminals can enroll as soon as their original core is reachable.
            var core = loadedCore(level, pos);
            if (core == null || !core.terminalAllowed(terminal)
                    || !(Sable.HELPER.getContaining(core) instanceof ServerSubLevel)) {
                return "暂无船体唤醒记录，请在已装配的船上重新绑定终端";
            }
            trackBinding(level, core, terminal);
            if (recall == null) return "飞船已加载，已登记唤醒位置";
            point = points.getTrackingPoint(trackingKey(terminal));
            if (!matches(point, pos)) return "无法登记这艘船的召回位置";
        }

        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return "当前维度的船体容器不可用";
        MinecraftServer server = level.getServer();
        State state = STATES.computeIfAbsent(server, ignored -> new State());
        if (state.active != null && state.active.ship.getUniqueId().equals(point.subLevelID())) {
            return state.active.recall == null ? "这艘船正在等待飞控采样" : "这艘船已有召回任务";
        }
        if (container.getSubLevel(point.subLevelID()) instanceof ServerSubLevel ship) {
            var core = authorizedCore(level, ship, pos, terminal);
            if (core == null) return "船上已找不到绑定核心，请重新绑定";
            if (recall == null) {
                return core.telemetrySnapshot() == null ? "船体已加载，飞控尚无有效采样" : "船体已加载，飞控遥测正常";
            }
            if (state.active != null) return "服务器正在处理另一艘船，请稍后重试";
            start(state, player, container, ship, core, terminal, recall);
            return "正在准备短程召回";
        }
        if (state.active != null) return "服务器正在处理另一艘船，请稍后重试";
        int tick = server.getTickCount();
        if (tick < state.nextStart) return "唤醒冷却中，请稍后重试";
        state.nextStart = tick + START_COOLDOWN_TICKS;

        var holdingMap = container.getHoldingChunkMap();
        var holding = holdingMap.getHoldingSubLevel(point.subLevelID());
        GlobalSavedSubLevelPointer pointer = point.lastSavedSubLevelPointer();
        if (holding != null && (Object) holding instanceof HoldingLocation location
                && location.bridgecore$holdingChunk() != null) {
            pointer = holdingChunkLocator(location.bridgecore$holdingChunk());
        } else if (pointer == null && point.globalPlaceholderPosition() != null) {
            var placeholder = point.globalPlaceholderPosition();
            pointer = holdingChunkLocator(new ChunkPos(BlockPos.containing(placeholder.x, placeholder.y, placeholder.z)));
        }
        if (pointer == null) return "船体还没有可用的存档位置，请在船旁加载后重试";

        try {
            holdingMap.snatchAndLoad(pointer, point.subLevelID());
        } catch (RuntimeException exception) {
            BridgeCoreMod.LOGGER.warn("[bridgecore] nav wake restore failed ship={} dimension={}",
                    point.subLevelID(), level.dimension().location(), exception);
            return "船体恢复失败，请查看服务器日志";
        }
        if (!(container.getSubLevel(point.subLevelID()) instanceof ServerSubLevel ship)) {
            return "Sable 未能从存储位置恢复飞船";
        }
        var core = authorizedCore(level, ship, pos, terminal);
        if (core == null) return "船体已恢复，但核心绑定已变更";

        start(state, player, container, ship, core, terminal, recall);
        return recall == null ? "船体已恢复，正在等待飞控采样" : "船体已恢复，正在准备短程召回";
    }

    private static void start(State state, ServerPlayer player, ServerSubLevelContainer container, ServerSubLevel ship,
                              BridgeCoreBlockEntity core, UUID terminal, @Nullable Recall recall) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = core.getBlockPos();
        boolean refreshActor = level.getBlockEntity(pos) != core;
        container.addForceLoadTicket(ship, TICKET, Unit.INSTANCE);
        state.active = new Probe(level, container, ship, pos, terminal, player.getUUID(),
                level.getServer().getTickCount(), refreshActor, recall);
    }

    private static @Nullable Probe pending(ServerPlayer player, UUID terminal) {
        State state = STATES.get(player.serverLevel().getServer());
        return state != null && state.active != null && state.active.terminal.equals(terminal)
                && state.active.player.equals(player.getUUID()) ? state.active : null;
    }

    public static @Nullable String status(ServerPlayer player, UUID terminal) {
        Probe probe = pending(player, terminal);
        if (probe == null) return null;
        if (probe.recall == null) return "正在等待唤醒后的飞控采样";
        return probe.recall.runningCore == null ? "正在准备短程召回" : "无人召回途中";
    }

    public static @Nullable String cancel(ServerPlayer player, UUID terminal) {
        Probe probe = pending(player, terminal);
        if (probe == null) return null;
        State state = STATES.get(player.serverLevel().getServer());
        finish(state, "cancelled");
        return probe.recall == null ? "唤醒已取消，测试票已释放" : "召回已取消，加载票已释放";
    }

    private static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        State state = STATES.get(server);
        if (state == null) return;
        int tick = server.getTickCount();
        Probe probe = state.active;
        if (probe == null) return;
        int elapsed = tick - probe.startedTick;
        int interval = probe.recall != null && probe.recall.runningCore != null
                ? CruiseRouteWatch.CHECK_INTERVAL : POLL_TICKS;
        if (elapsed <= 0 || elapsed % interval != 0) return;
        var player = server.getPlayerList().getPlayer(probe.player);
        if (player == null || player.serverLevel() != probe.level || !player.isAlive() || player.isSpectator()
                || (probe.recall == null && NavTerminalData.heldTerminal(player, probe.terminal) == null)) {
            finish(state, "requester unavailable");
            return;
        }
        var core = authorizedCore(probe.level, probe.ship, probe.core, probe.terminal);
        if (core == null) {
            finish(state, "binding or ship lost");
            TerminalReplyPayload.send(player, probe.terminal, "任务中止，飞控或绑定已失效");
            return;
        }
        if (probe.refreshActor && elapsed == POLL_TICKS) {
            // Same-tick restoration may have indexed the previous instance through the chunk cache.
            // Refresh the core once the normal world ticks have cleared that cache.
            probe.ship.getPlot().onBlockChange(probe.core, core.getBlockState());
        }
        var sample = core.telemetrySnapshot();
        if (probe.recall != null) {
            tickRecall(state, probe, player, core, sample == null ? null : sample.position(), tick, elapsed);
            return;
        }
        if (sample != null) {
            finish(state, "sample ready");
            state.nextStart = Math.max(state.nextStart, tick + RELEASE_CHECK_TICKS);
            TerminalReplyPayload.send(player, probe.terminal, "唤醒成功，飞控已取得有效采样，测试票已释放");
        } else if (elapsed >= TIMEOUT_TICKS) {
            finish(state, "sample timeout");
            TerminalReplyPayload.send(player, probe.terminal, "等待飞控采样超时，测试票已释放");
        }
    }

    private static void tickRecall(State state, Probe probe, ServerPlayer player, BridgeCoreBlockEntity core,
                                   @Nullable Vec3 position, int tick, int elapsed) {
        Recall recall = probe.recall;
        if (recall.runningCore != null) {
            var status = core.navigationStatus();
            if (status == null || !recall.id.equals(status.id()) || recall.runningCore != core) {
                completeRecall(state, probe, player, "control lost", "召回控制已交还");
            } else if (status.phase() != NavigationController.Phase.ACTIVE) {
                completeRecall(state, probe, player, status.phase().name(), TerminalLinkPayload.describe(status));
            } else if (position == null || tick - recall.launchedTick >= RECALL_FLIGHT_TICKS) {
                completeRecall(state, probe, player, "sample lost or timeout", "召回采样中断或超时");
            } else {
                updateRecallRoute(probe.level, recall, position);
            }
            return;
        }
        if (position == null) {
            if (elapsed >= TIMEOUT_TICKS) {
                completeRecall(state, probe, player, "preflight timeout", "等待飞控采样超时");
            }
            return;
        }
        if (!core.energyStatus().hasEnergy()) {
            completeRecall(state, probe, player, "power unavailable", "飞船供能未就绪");
            return;
        }
        if (recall.target == null) {
            int limit = Math.min(RECALL_MAX_RANGE, BCConfig.UNSUPERVISED_CRUISE_RANGE.get());
            if (Math.hypot(recall.destination.x - position.x, recall.destination.z - position.z) > limit) {
                completeRecall(state, probe, player, "out of range", "短程召回距离上限为 " + limit + " 格");
                return;
            }
            recall.target = new Vec3(recall.destination.x, position.y, recall.destination.z);
        }
        if (recall.columns.isEmpty() || elapsed % CruiseRouteWatch.CHECK_INTERVAL == 0) {
            updateRecallRoute(probe.level, recall, position);
        }
        var admission = core.requestCruise(recall.id, probe.level.dimension(), recall.target, recall.speed, recall.turnRate);
        if (admission == NavigationController.Admission.ACCEPTED) {
            recall.runningCore = core;
            recall.launchedTick = tick;
            TerminalReplyPayload.send(player, probe.terminal, "无人召回已起航");
        } else if (admission != NavigationController.Admission.ROUTE_UNLOADED || elapsed >= TIMEOUT_TICKS) {
            completeRecall(state, probe, player, admission.name(), TerminalLinkPayload.cruiseReply(admission, ""));
        }
    }

    private static void updateRecallRoute(ServerLevel level, Recall recall, Vec3 position) {
        // Prefetch farther than the 4 m/s cruise guard with the same seven-column bound.
        Set<ChunkPos> next = new HashSet<>();
        CruiseRouteWatch.loadedAhead(position.x, position.z, recall.destination.x, recall.destination.z,
                ROUTE_PREFETCH_SPEED, (x, z) -> {
                    next.add(new ChunkPos(x, z));
                    return true;
                });
        var chunks = level.getChunkSource();
        for (ChunkPos column : next) {
            if (recall.columns.add(column)) chunks.addRegionTicket(ROUTE_TICKET, column, ROUTE_TICKET_RADIUS, recall.id);
        }
        recall.columns.removeIf(column -> {
            if (next.contains(column)) return false;
            chunks.removeRegionTicket(ROUTE_TICKET, column, ROUTE_TICKET_RADIUS, recall.id);
            return true;
        });
    }

    private static void completeRecall(State state, Probe probe, ServerPlayer player, String reason, String message) {
        finish(state, reason);
        TerminalReplyPayload.send(player, probe.terminal, message + "，加载票已释放");
    }

    private static @Nullable BridgeCoreBlockEntity loadedCore(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof BridgeCoreBlockEntity core
                && !core.isRemoved() ? core : null;
    }

    private static @Nullable BridgeCoreBlockEntity authorizedCore(ServerLevel level, ServerSubLevel ship, BlockPos pos,
                                                                  UUID terminal) {
        if (ship.isRemoved() || Sable.HELPER.getContaining(level, pos) != ship) return null;
        var plot = ship.getPlot();
        var chunk = plot.getChunk(plot.toLocal(new ChunkPos(pos)));
        return chunk != null && chunk.getBlockEntity(pos) instanceof BridgeCoreBlockEntity core
                && !core.isRemoved() && core.terminalAllowed(terminal) ? core : null;
    }

    private static GlobalSavedSubLevelPointer holdingChunkLocator(ChunkPos chunk) {
        // Sable 2.0.3's snatchAndLoad looks up the holding chunk and then the UUID.
        return new GlobalSavedSubLevelPointer(chunk, (short) 0, (short) 0);
    }

    private static void finish(State state, String reason) {
        Probe probe = state.active;
        if (probe == null) return;
        state.active = null;
        if (probe.recall != null) {
            Recall recall = probe.recall;
            if (recall.runningCore != null) recall.runningCore.cancelNavigation(recall.id);
            for (ChunkPos column : recall.columns) {
                probe.level.getChunkSource().removeRegionTicket(ROUTE_TICKET, column, ROUTE_TICKET_RADIUS, recall.id);
            }
        }
        probe.container.removeForceLoadTicket(probe.ship, TICKET, Unit.INSTANCE);
    }

    private static void stop(ServerStoppingEvent event) {
        State state = STATES.remove(event.getServer());
        if (state != null) finish(state, "server stopping");
    }

    private static void clearRecoveredTickets(ServerStartedEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            var container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            // Sable restores persistent tickets during level initialization, before this event.
            for (var entry : List.copyOf(container.getAllTickets().entrySet())) {
                if (entry.getValue().tickets().stream().noneMatch(ticket -> ticket.getType() == TICKET)) continue;
                if (container.getSubLevel(entry.getKey()) instanceof ServerSubLevel ship) {
                    container.removeForceLoadTicket(ship, TICKET, Unit.INSTANCE);
                } else {
                    entry.getValue().tickets().removeIf(ticket -> ticket.getType() == TICKET);
                    SubLevelTicketsSavedData.getOrLoad(level).setDirty();
                }
            }
        }
    }
}
