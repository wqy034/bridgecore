package com.bridgecore.firecontrol;

import com.bridgecore.BridgeCoreBlock;
import com.bridgecore.CoreDirectory;
import com.bridgecore.firecontrol.ballistics.SolveChain;
import com.bridgecore.firecontrol.ballistics.Quat;
import com.bridgecore.firecontrol.ballistics.StrikeTrajectory;
import com.bridgecore.firecontrol.ballistics.Vec3;
import com.bridgecore.firecontrol.railgun.RailgunLink;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** On-demand server-thread preview. No engagement, actuator, or world-ticket writes. */
public final class StrikePreview {
    private static final int MAX_TURRETS = 8;
    public static final int MAX_ROWS = MAX_TURRETS * SwivelTurretActuator.MAX_MOUNTS;
    private static final int BLOCK_BUDGET = 256;
    static final String AXIS_ALIGNING = "当前舰姿未对准";
    private static final double AXIS_AIM_COS = Math.cos(Math.toRadians(FireControlCoreBlockEntity.AXIS_GUN_FIRE_TOLERANCE_DEG));

    public enum Mode {
        TURRET(0), AXIS_BEAM(1), TURRET_POSE(2);

        public final int wireId;
        Mode(int wireId) { this.wireId = wireId; }
        public static @Nullable Mode fromWire(int id) {
            return switch (id) { case 0 -> TURRET; case 1 -> AXIS_BEAM; case 2 -> TURRET_POSE; default -> null; };
        }
        public String queuedMessage() {
            return switch (this) {
                case TURRET -> "标定打击已登记，等待炮位瞄准";
                case AXIS_BEAM -> "轴炮打击已登记，舰体开始自动对准";
                case TURRET_POSE -> "炮组打击已登记，舰体开始对准";
            };
        }
    }

    public record Row(String name, String weapon, String reason, boolean eligible) {}

    public record Roster(FireControlCoreBlockEntity core, List<String> channels, int revision,
                         List<TurretUnit> turrets, List<FixedGun> fixedGuns, boolean limited) {
        public int axialChannels() {
            return core.axialStrikeChannels();
        }

        public Mode channelMode(int channel, boolean allowTurn) {
            if ((axialChannels() & WeaponGroups.bit(channel)) != 0) return Mode.AXIS_BEAM;
            return allowTurn ? Mode.TURRET_POSE : Mode.TURRET;
        }
    }

    record TurretAim(Vec3 direction, List<Vec3> mountDirections) {
        boolean agreesWith(Vec3 actual) {
            return mountDirections.stream().allMatch(direction -> withinAim(direction, actual));
        }
    }

    record AxisAim(net.minecraft.world.phys.Vec3 bodyAxis, net.minecraft.world.phys.Vec3 direction) {}

    private record AxisBeam(FixedGun gun, Object handle, Vec3 origin, Vec3 direction, double range) {}

    private record AxisPose(AxisAim aim, Quaterniond turn) {}

    public record Assessment(List<Row> rows, Map<UUID, TurretAim> turrets, Map<UUID, Object> axisShots,
                             @Nullable AxisAim axisAim) {
        public Set<UUID> executableWeapons() { return axisShots.isEmpty() ? turrets.keySet() : axisShots.keySet(); }

        public String summary(Mode mode, boolean limited) {
            long eligible = rows.stream().filter(Row::eligible).count();
            String message = rows.isEmpty() ? "该频道无此类炮位" : "可参与 " + eligible + " / " + rows.size();
            if (mode == Mode.TURRET && !rows.isEmpty()) message += " · 可执行炮塔 " + turrets.size();
            if (mode == Mode.AXIS_BEAM && !axisShots.isEmpty()) message += " · 可执行轴炮 " + axisShots.size();
            if (limited) message += " · 仅检查前 8 座转塔";
            return message;
        }
    }

    enum SightCheck { ESTIMATED, ACTUAL, SKIP }

    private StrikePreview() {}

    public static @Nullable Roster roster(ServerLevel level, ServerSubLevel ship, BlockPos near) {
        FireControlCoreBlockEntity best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Object item : CoreDirectory.componentsOf(CoreDirectory.BUCKET_FIRE_CONTROL, ship)) {
            if (!(item instanceof FireControlCoreBlockEntity core) || core.isRemoved() || core.getLevel() != level) continue;
            double candidate = core.getBlockPos().distSqr(near);
            if (candidate < distance) {
                distance = candidate;
                best = core;
            }
        }
        return best == null ? null : roster(best);
    }

    public static Roster roster(FireControlCoreBlockEntity best) {
        List<String> channels = new ArrayList<>();
        for (int i = 0; i < best.channelCount(); i++) channels.add(best.channelName(i));
        List<TurretUnit> turrets = best.turrets().stream().limit(MAX_TURRETS).toList();
        List<FixedGun> fixedGuns = best.fixedGuns().stream().limit(FireControlCoreBlockEntity.MAX_FIXED_GUNS).toList();
        int revision = 31 * System.identityHashCode(best) + channels.hashCode();
        for (TurretUnit unit : turrets) {
            var a = unit.actuator();
            var mounts = a.mounts();
            revision = 31 * revision + Objects.hash(unit.id(), unit.name(), unit.groups(), mounts.size(),
                    mounts.subList(0, Math.min(mounts.size(), SwivelTurretActuator.MAX_MOUNTS)),
                    a.yawMinDeg(), a.yawMaxDeg(), a.pitchMinDeg(), a.pitchMaxDeg(), a.sectorArc());
        }
        for (FixedGun gun : fixedGuns) {
            revision = 31 * revision + Objects.hash(gun.id(), gun.name(), gun.groups(), gun.gunPos());
        }
        return new Roster(best, List.copyOf(channels), revision, turrets, fixedGuns, best.turrets().size() > MAX_TURRETS);
    }

    public static List<Row> evaluate(ServerLevel level, ServerSubLevel ship, Roster roster, int channel,
                                     Mode mode, BlockPos targetPos) {
        return assess(level, ship, roster, channel, mode, targetPos).rows();
    }

    public static Assessment assess(ServerLevel level, ServerSubLevel ship, Roster roster, int channel,
                                    Mode mode, BlockPos targetPos) {
        return assess(level, ship, roster, channel, mode, targetPos, null, SightCheck.ESTIMATED);
    }

    static Assessment assess(ServerLevel level, ServerSubLevel ship, Roster roster, int channel,
                             Mode mode, BlockPos targetPos, @Nullable Set<UUID> selection, SightCheck sightCheck) {
        Vec3 target = new Vec3(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
        var hulls = new ArrayList<ServerSubLevel>();
        hulls.add(ship);
        Map<TurretUnit, Set<UUID>> children = new HashMap<>();
        var container = SubLevelContainer.getContainer(level);
        Set<UUID> seen = new HashSet<>();
        seen.add(ship.getUniqueId());
        for (TurretUnit unit : roster.turrets()) {
            Set<UUID> own = unit.actuator().childSubLevelIds(level, true);
            children.put(unit, own);
            for (UUID id : own) {
                if (sightCheck != SightCheck.SKIP && container != null && seen.add(id)
                        && container.getSubLevel(id) instanceof ServerSubLevel child
                        && !child.isRemoved()) hulls.add(child);
            }
        }

        List<Row> rows = new ArrayList<>();
        Map<UUID, TurretAim> aims = new HashMap<>();
        if (mode == Mode.AXIS_BEAM) {
            return assessAxes(level, ship, roster, channel, target, selection, sightCheck, hulls);
        }
        MountLink link = MountLinks.active();
        for (TurretUnit unit : roster.turrets()) {
            if (!unit.belongsTo(channel) || (selection != null && !selection.contains(unit.id()))) continue;
            var turret = unit.actuator();
            if (turret.mounts().isEmpty()) {
                rows.add(new Row(unit.name(), "", "未连接炮座", false));
                continue;
            }
            var arc = turret.previewArc(level);
            var mounts = turret.mounts();
            List<Vec3> directions = new ArrayList<>();
            for (int i = 0; i < Math.min(mounts.size(), SwivelTurretActuator.MAX_MOUNTS); i++) {
                rows.add(turretMount(level, link, unit, mounts.get(i), arc, target, hulls, children.get(unit),
                        roster.limited(), sightCheck, directions));
            }
            if (!directions.isEmpty() && directions.size() == mounts.size()) {
                Vec3 common = directions.stream().reduce(Vec3.ZERO, Vec3::add).normalized();
                if (common != null && arc != null && arc.test(common)
                        && directions.stream().allMatch(direction -> withinAim(direction, common))) {
                    aims.put(unit.id(), new TurretAim(common, List.copyOf(directions)));
                }
            }
        }
        return new Assessment(List.copyOf(rows), Map.copyOf(aims), Map.of(), null);
    }

    public record PosePlan(UUID ship, Quat orientation, Set<UUID> turrets) {
        public PosePlan { turrets = Set.copyOf(turrets); }
    }

    public record PosePreview(List<Row> rows, String summary, PosePlan plan) {}

    private record PoseMount(String name, String weapon, @Nullable String unavailable, Vec3 root,
                             MountLink.PreviewLimits limits, @Nullable SolveChain.Params params, double barrel,
                             double estimatedDrop) {}

    private record PoseTurret(UUID id, SwivelTurretActuator.PoseGeometry geometry, List<PoseMount> mounts, Set<UUID> own) {}

    private record PoseOffset(int yaw, int pitch) {}

    private record PoseScore(PoseOffset offset, int turrets, int mounts, double margin, double turn, List<Row> rows, Set<UUID> eligible) {}

    private static final int POSE_YAW_STEP = 15;
    private static final int MAX_POSE_PITCH = 30;
    private static final int POSE_MARGIN_DEG = 5;
    private static final int MAX_POSE_SEEDS = 256;
    private static final int PRECISE_POSES = 5;
    private static final int POSE_BLOCK_BUDGET = 8_192;
    private static final java.util.Comparator<PoseScore> POSE_ORDER = java.util.Comparator
            .comparingInt(PoseScore::turrets).thenComparingInt(PoseScore::mounts)
            .thenComparingDouble(score -> Math.min(POSE_MARGIN_DEG, score.margin()))
            .thenComparingDouble(score -> -score.turn());

    /** Read-only; snapshots hardware once and limits expensive checks to the shortlist. */
    public static PosePreview previewPose(ServerLevel level, ServerSubLevel ship, Roster roster, int channel,
                                          BlockPos targetPos) {
        Vec3 target = new Vec3(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
        var hullOrientation = new Quaterniond(ship.logicalPose().orientation());
        var pivot = vector(new Vector3d(ship.logicalPose().position()));
        var hulls = new ArrayList<ServerSubLevel>();
        hulls.add(ship);
        Set<UUID> seen = new HashSet<>();
        seen.add(ship.getUniqueId());
        var container = SubLevelContainer.getContainer(level);
        var turrets = new ArrayList<PoseTurret>();
        var link = MountLinks.active();
        for (var unit : roster.turrets()) {
            var actuator = unit.actuator();
            var own = actuator.childSubLevelIds(level, true);
            for (UUID id : own) {
                if (container != null && seen.add(id) && container.getSubLevel(id) instanceof ServerSubLevel child
                        && !child.isRemoved()) hulls.add(child);
            }
            if (!unit.belongsTo(channel)) continue;
            var geometry = actuator.previewPoseGeometry(level, ship);
            var mounts = new ArrayList<PoseMount>();
            for (var mount : actuator.mounts()) mounts.add(readPoseMount(level, ship, unit, mount, geometry, own, link, target));
            if (mounts.isEmpty()) mounts.add(unavailableMount(unit.name(), "", "未连接炮座"));
            turrets.add(new PoseTurret(unit.id(), geometry, List.copyOf(mounts), own));
        }

        // Pitch about the hull's horizontal transverse axis. World yaw preserves the roll baseline.
        var bow = CoreDirectory.coresOf(ship).stream().filter(core -> !core.isRemoved()).findFirst()
                .map(core -> core.getBlockState().getValue(BridgeCoreBlock.FACING).getNormal());
        var local = bow.map(dir -> new Vector3d(dir.getX(), dir.getY(), dir.getZ())).orElseGet(() -> new Vector3d(0, 0, 1));
        var forward = hullOrientation.transform(local);
        var pitchAxis = new Vector3d(-forward.z, 0, forward.x);
        if (pitchAxis.lengthSquared() < 1e-8) pitchAxis.set(-1, 0, 0);
        pitchAxis.normalize();
        var zero = new PoseOffset(0, 0);
        int[] sightBudget = {POSE_BLOCK_BUDGET};
        var current = scorePose(zero, pitchAxis, pivot, target, turrets, hulls, roster.limited(), sightBudget);
        var seeds = new java.util.LinkedHashSet<PoseOffset>();
        for (int yaw = -180; yaw < 180; yaw += POSE_YAW_STEP) {
            for (int pitch = -MAX_POSE_PITCH; pitch <= MAX_POSE_PITCH; pitch += 10) seeds.add(new PoseOffset(yaw, pitch));
        }
        for (var turret : turrets) {
            var geometry = turret.geometry();
            if (geometry == null || seeds.size() >= MAX_POSE_SEEDS) continue;
            var mount = turret.mounts().stream().filter(item -> item.unavailable() == null).findFirst().orElse(null);
            if (mount == null) continue;
            var direction = target.sub(mount.root()).normalized();
            var angles = direction == null ? null : geometry.solve(direction);
            if (angles == null || Math.abs(geometry.yawAxis().y()) < 0.9) continue;
            for (double seed : geometry.sectors().yawSeeds(geometry.yawLimits())) {
                if (seeds.size() >= MAX_POSE_SEEDS) break;
                int yaw = (int) Math.round(geometry.yawAxis().y() * (angles.yaw() - seed));
                seeds.add(poseOffset(yaw, 0));
                var pitchLimits = geometry.sectors().limitsAt(seed, geometry.pitchLimits());
                if (!pitchLimits.empty() && seeds.size() < MAX_POSE_SEEDS) {
                    int pitch = (int) Math.round(angles.pitch() - (pitchLimits.upper() + pitchLimits.lower()) / 2);
                    seeds.add(poseOffset(yaw, pitch));
                }
            }
        }
        var ranked = new ArrayList<PoseScore>();
        for (var candidate : seeds) ranked.add(scorePose(candidate, pitchAxis, pivot, target, turrets, hulls, roster.limited(), null));
        ranked.sort(POSE_ORDER.reversed());
        var centers = List.copyOf(ranked.subList(0, Math.min(4, ranked.size())));
        for (var center : centers) {
            if (center.turrets() == 0) break;
            for (int yaw : new int[]{-5, 0, 5}) for (int pitch : new int[]{-4, 0, 4}) {
                var candidate = poseOffset(center.offset().yaw() + yaw, center.offset().pitch() + pitch);
                if (seeds.add(candidate)) ranked.add(scorePose(candidate, pitchAxis, pivot, target, turrets, hulls, roster.limited(), null));
            }
        }
        ranked.sort(POSE_ORDER.reversed());
        var best = current;
        int checked = 0;
        for (var candidate : ranked) {
            if (candidate.turrets() == 0 || checked >= PRECISE_POSES || sightBudget[0] <= 0) break;
            if (candidate.offset().equals(zero)) continue;
            checked++;
            var verified = scorePose(candidate.offset(), pitchAxis, pivot, target, turrets, hulls, roster.limited(), sightBudget);
            if (POSE_ORDER.compare(verified, best) > 0) best = verified;
        }
        String summary = turrets.isEmpty() ? "该频道无转塔" : String.format(java.util.Locale.ROOT,
                "炮塔 %d→%d · 炮座 %d→%d · 偏航%+d° 俯仰%+d°",
                current.turrets(), best.turrets(), current.mounts(), best.mounts(), best.offset().yaw(), best.offset().pitch());
        if (roster.limited()) summary += " · 名单受限";
        if (sightBudget[0] <= 0) summary += " · 遮挡预算已满";
        var orientation = poseTurn(best.offset(), pitchAxis).mul(hullOrientation).normalize();
        var plan = new PosePlan(ship.getUniqueId(), new Quat(orientation.w, orientation.x, orientation.y, orientation.z),
                best.eligible());
        return new PosePreview(best.rows(), summary, plan);
    }

    private static PoseOffset poseOffset(int yaw, int pitch) {
        return new PoseOffset((int) GimbalMath.wrapDeg(yaw), Math.clamp(pitch, -MAX_POSE_PITCH, MAX_POSE_PITCH));
    }

    private static PoseMount unavailableMount(String name, String weapon, String reason) {
        return new PoseMount(name, weapon, reason, null, null, null, 0, 0);
    }

    private static PoseMount readPoseMount(ServerLevel level, ServerSubLevel hull, TurretUnit unit,
                                           SwivelTurretActuator.Mount mount, @Nullable SwivelTurretActuator.PoseGeometry geometry,
                                           Set<UUID> own, MountLink link, Vec3 target) {
        String name = unit.name() + " #" + mount.no();
        if (!level.hasChunkAt(mount.pos()) || !(Sable.HELPER.getContaining(level, mount.pos()) instanceof ServerSubLevel part)) {
            return unavailableMount(name, "", "炮座未加载");
        }
        if (part != hull && !own.contains(part.getUniqueId())) return unavailableMount(name, "", "炮座已脱离本炮塔");
        Object handle = link.mountAt(level, mount.pos());
        String weapon = handle == null ? "" : FireControlUiText.weapon(link.gunTypeName(handle));
        int status = link.mountStatusCode(handle);
        if (status != MountLink.STATUS_READY) return unavailableMount(name, weapon, hardware(status));
        if (geometry == null) return unavailableMount(name, weapon, "转塔未校准或姿态不可用");
        var turret = unit.actuator();
        var base = turret.barrelBase();
        var rootPos = turret.mountCount() == 1 && base != null && Sable.HELPER.getContaining(level, base) == part
                ? base : mount.pos();
        var pos = part.logicalPose().transformPosition(net.minecraft.world.phys.Vec3.atCenterOf(rootPos));
        var root = new Vec3(pos.x, pos.y, pos.z);
        var limits = link.previewLimits(handle);
        double barrel = turret.barrelLengthBlocks();
        if (limits == null || !Double.isFinite(barrel) || barrel < 0) return unavailableMount(name, weapon, "弹道参数不可用");
        var params = limits.beamRange() > 0 ? null : link.paramsFor(handle, FireControlCoreBlockEntity.DEFAULT_PARAMS, barrel);
        if (params == null && limits.beamRange() <= 0) return unavailableMount(name, weapon, "弹道参数不可用");
        var delta = target.sub(root);
        var nominal = params == null ? null : StrikeTrajectory.direction(delta, params, limits.lifetimeTicks());
        double horizontal = nominal == null ? 0 : Math.hypot(nominal.x(), nominal.z());
        double drop = horizontal > 1e-6 ? nominal.y() * Math.hypot(delta.x(), delta.z()) / horizontal - delta.y() : 0;
        return new PoseMount(name, weapon, null, root, limits, params, barrel, drop);
    }

    private static Quaterniond poseTurn(PoseOffset offset, Vector3d pitchAxis) {
        return new Quaterniond().rotationY(Math.toRadians(offset.yaw()))
                .rotateAxis(Math.toRadians(offset.pitch()), pitchAxis.x, pitchAxis.y, pitchAxis.z);
    }

    private static PoseScore scorePose(PoseOffset offset, Vector3d pitchAxis, Vec3 pivot, Vec3 target,
                                        List<PoseTurret> turrets, List<ServerSubLevel> hulls, boolean limited, @Nullable int[] sightBudget) {
        var turn = poseTurn(offset, pitchAxis);
        var inverse = turn.conjugate(new Quaterniond());
        var rows = sightBudget == null ? null : new ArrayList<Row>();
        var eligible = rows == null ? null : new HashSet<UUID>();
        int turretCount = 0, mountCount = 0;
        double margin = Double.POSITIVE_INFINITY;
        for (var turret : turrets) {
            double usable = scoreTurret(turret, pivot, target, turn, inverse, hulls, limited, sightBudget, rows);
            if (usable < 0) continue;
            turretCount++;
            if (eligible != null) eligible.add(turret.id());
            mountCount += turret.mounts().size();
            margin = Math.min(margin, usable);
        }
        double turnAngle = 2 * Math.toDegrees(Math.acos(Math.clamp(Math.abs(turn.w), 0, 1)));
        return new PoseScore(offset, turretCount, mountCount, turretCount == 0 ? 0 : margin, turnAngle,
                rows == null ? List.of() : List.copyOf(rows), eligible == null ? Set.of() : Set.copyOf(eligible));
    }

    private static double scoreTurret(PoseTurret turret, Vec3 pivot, Vec3 target, Quaterniond turn, Quaterniond inverse,
                                      List<ServerSubLevel> hulls, boolean limited, @Nullable int[] sightBudget,
                                      @Nullable List<Row> rows) {
        boolean precise = sightBudget != null;
        var geometry = turret.geometry();
        var mounts = turret.mounts();
        var roots = new ArrayList<Vec3>(mounts.size());
        var directions = new ArrayList<Vec3>(mounts.size());
        for (var mount : mounts) {
            if (mount.unavailable() != null) {
                return failedTurret(mounts, rows, mount, mount.unavailable());
            }
            roots.add(mount.root());
            var direction = poseDirection(mount, rotatePoint(mount.root(), pivot, turn), target, inverse, precise);
            if (direction == null) return failedTurret(mounts, rows, mount, "估算射程外或无弹道解");
            directions.add(direction);
        }
        var common = commonDirection(directions);
        var angles = common == null ? null : geometry.solve(common);
        if (angles == null) return failedTurret(mounts, rows, null, "炮座方向不可用");
        if (precise) {
            // Update the firing roots in the two actual hinge frames, then resolve the ballistic directions.
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < mounts.size(); i++) {
                    var mount = mounts.get(i);
                    var root = geometry.rootAfterAim(mount.root(), angles);
                    roots.set(i, root);
                    var direction = poseDirection(mount, rotatePoint(root, pivot, turn), target, inverse, true);
                    if (direction == null) return failedTurret(mounts, rows, mount, "估算射程外或无弹道解");
                    directions.set(i, direction);
                }
                common = commonDirection(directions);
                angles = common == null ? null : geometry.solve(common);
                if (angles == null) return failedTurret(mounts, rows, null, "炮座方向不可用");
            }
        }
        double margin = geometry.margin(angles);
        if (margin < 0) return failedTurret(mounts, rows, null, "射界外或转动路径受限");
        var aimed = geometry.directionAfterAim(angles);
        for (int i = 0; i < directions.size(); i++) {
            var direction = directions.get(i);
            var mount = mounts.get(i);
            if (!withinAim(direction, aimed)) return failedTurret(mounts, rows, mount, "无法与同塔炮座共用方向");
            if (precise) {
                // Transform the candidate world ray back to the current hull frame before traversing loaded blocks.
                var root = roots.get(i);
                var origin = root.add(aimed.scale(mount.barrel() + 0.25));
                double distance = target.sub(rotatePoint(root, pivot, turn)).length() - mount.barrel() - 0.25;
                var sight = sightLine(mount.name(), mount.weapon(), origin, aimed, distance, hulls, turret.own(), limited, sightBudget);
                if (!sight.eligible()) return failedTurret(mounts, rows, mount, sight.reason());
            }
        }
        if (precise) for (var mount : mounts) rows.add(new Row(mount.name(), mount.weapon(), "可参与", true));
        return margin;
    }

    private static double failedTurret(List<PoseMount> mounts, @Nullable List<Row> rows, @Nullable PoseMount failed,
                                       String reason) {
        if (rows != null) for (var mount : mounts) {
            String message = mount.unavailable() != null ? mount.unavailable()
                    : failed == null || mount == failed ? reason : "同塔炮座受限";
            rows.add(new Row(mount.name(), mount.weapon(), message, false));
        }
        return -1;
    }

    private static @Nullable Vec3 commonDirection(List<Vec3> directions) {
        Vec3 sum = Vec3.ZERO;
        for (var direction : directions) sum = sum.add(direction);
        return sum.normalized();
    }

    private static @Nullable Vec3 poseDirection(PoseMount mount, Vec3 worldRoot, Vec3 target, Quaterniond inverse, boolean precise) {
        Vec3 delta = target.sub(worldRoot);
        double distance = delta.length() - mount.barrel();
        if (!(distance > 0.25)) return null;
        Vec3 worldDirection;
        if (mount.limits().beamRange() > 0) {
            worldDirection = distance <= mount.limits().beamRange() ? delta.normalized() : null;
        } else if (precise) {
            worldDirection = StrikeTrajectory.direction(delta, mount.params(), mount.limits().lifetimeTicks());
        } else {
            worldDirection = delta.add(new Vec3(0, mount.estimatedDrop(), 0)).normalized();
        }
        return worldDirection == null ? null : vector(inverse.transform(vector(worldDirection)));
    }

    private static Vec3 rotatePoint(Vec3 point, Vec3 pivot, Quaterniond turn) {
        return pivot.add(vector(turn.transform(vector(point.sub(pivot)))));
    }

    private static boolean withinAim(Vec3 desired, Vec3 actual) {
        return desired.dot(actual) > Math.cos(Math.toRadians(FireDirector.AIM_GATE_DEG));
    }

    private static Row turretMount(ServerLevel level, MountLink link, TurretUnit unit, SwivelTurretActuator.Mount mount,
                                    @Nullable Predicate<Vec3> arc, Vec3 target, List<ServerSubLevel> hulls,
                                    Set<UUID> own, boolean limited, SightCheck sightCheck, List<Vec3> directions) {
        String name = unit.name() + " #" + mount.no();
        if (!level.hasChunkAt(mount.pos()) || !(Sable.HELPER.getContaining(level, mount.pos()) instanceof ServerSubLevel mountShip)) {
            return new Row(name, "", "炮座未加载", false);
        }
        if (mountShip != hulls.getFirst() && !own.contains(mountShip.getUniqueId())) {
            return new Row(name, "", "炮座已脱离本炮塔", false);
        }
        Object handle = link.mountAt(level, mount.pos());
        String weapon = handle == null ? "" : FireControlUiText.weapon(link.gunTypeName(handle));
        int status = link.mountStatusCode(handle);
        if (status != MountLink.STATUS_READY) return new Row(name, weapon, hardware(status), false);
        if (arc == null) return new Row(name, weapon, "转塔未校准或姿态不可用", false);
        var turret = unit.actuator();
        BlockPos markedBase = turret.barrelBase();
        BlockPos root = turret.mountCount() == 1 && markedBase != null
                && Sable.HELPER.getContaining(level, markedBase) == mountShip ? markedBase : mount.pos();
        var position = mountShip.logicalPose().transformPosition(net.minecraft.world.phys.Vec3.atCenterOf(root));
        Vec3 origin = new Vec3(position.x, position.y, position.z);
        Vec3 displacement = target.sub(origin);
        double distance = displacement.length();
        double barrel = turret.barrelLengthBlocks();
        var limits = link.previewLimits(handle);
        if (limits == null || !Double.isFinite(distance) || !Double.isFinite(barrel) || barrel < 0) {
            return new Row(name, weapon, "弹道参数不可用", false);
        }
        if (distance <= barrel + 0.25) return new Row(name, weapon, "目标过近", false);
        Vec3 direction = limits.beamRange() > 0
                ? distance - barrel <= limits.beamRange() ? displacement.normalized() : null
                : StrikeTrajectory.direction(displacement,
                        link.paramsFor(handle, FireControlCoreBlockEntity.DEFAULT_PARAMS, barrel), limits.lifetimeTicks());
        if (direction == null) return new Row(name, weapon, "估算射程外或无弹道解", false);
        if (!arc.test(direction)) return new Row(name, weapon, "射界外", false);
        Vec3 ray = sightCheck == SightCheck.ACTUAL ? turret.currentDirWorld() : direction;
        if (ray == null || !Double.isFinite(ray.length())) return new Row(name, weapon, "炮口方向不可用", false);
        Row row = sightCheck == SightCheck.SKIP
                ? new Row(name, weapon, "可参与", true)
                : sightLine(name, weapon, origin.add(ray.scale(barrel + 0.25)), ray,
                        distance - barrel - 0.25, hulls, own, limited);
        if (row.eligible()) directions.add(direction);
        return row;
    }

    private static Assessment assessAxes(ServerLevel level, ServerSubLevel ship, Roster roster, int channel,
                                         Vec3 target, @Nullable Set<UUID> selection, SightCheck sight,
                                         List<ServerSubLevel> hulls) {
        Map<FixedGun, Row> rows = new LinkedHashMap<>();
        List<AxisBeam> beams = new ArrayList<>();
        for (FixedGun gun : roster.fixedGuns()) {
            if (!gun.belongsTo(channel) || (selection != null && !selection.contains(gun.id()))) continue;
            rows.put(gun, readAxisBeam(level, ship, gun, beams));
        }
        var position = ship.logicalPose().position();
        Vec3 pivot = new Vec3(position.x(), position.y(), position.z());
        AxisPose pose = null;
        if (sight != SightCheck.ACTUAL) {
            for (var beam : beams) {
                var candidate = axisPose(level, beam, target, pivot);
                if (candidate == null) continue;
                var row = axisRow(beam, target, pivot, candidate, sight, hulls, roster.limited());
                rows.put(beam.gun(), row);
                if (row.eligible()) {
                    pose = candidate;
                    break;
                }
            }
        }
        Map<UUID, Object> shots = new LinkedHashMap<>();
        for (var beam : beams) {
            var row = rows.get(beam.gun());
            if (row == null || pose != null) row = axisRow(beam, target, pivot, pose, sight, hulls, roster.limited());
            rows.put(beam.gun(), row);
            if (row.eligible()) shots.put(beam.gun().id(), beam.handle());
        }
        return new Assessment(List.copyOf(rows.values()), Map.of(), java.util.Collections.unmodifiableMap(shots),
                pose == null ? null : pose.aim());
    }

    private static @Nullable Row readAxisBeam(ServerLevel level, ServerSubLevel ship, FixedGun gun, List<AxisBeam> beams) {
        if (!level.hasChunkAt(gun.gunPos()) || Sable.HELPER.getContaining(level, gun.gunPos()) != ship) {
            return axisFailure(gun, "轴炮不在本船或未加载");
        }
        var handle = RailgunLink.macroAt(level, gun.gunPos());
        int status = RailgunLink.macroStatusCode(handle);
        if (status != MountLink.STATUS_READY) return axisFailure(gun, hardware(status));
        var beam = RailgunLink.macroBeamAxis(handle);
        if (beam == null || !Double.isFinite(beam[6]) || beam[6] <= 0) return axisFailure(gun, "射束参数不可用");
        Vec3 origin = new Vec3(beam[0], beam[1], beam[2]);
        Vec3 axis = new Vec3(beam[3], beam[4], beam[5]).normalized();
        if (axis == null || !Double.isFinite(axis.length()) || !Double.isFinite(origin.length())) {
            return axisFailure(gun, "射束参数不可用");
        }
        beams.add(new AxisBeam(gun, handle, origin, axis, beam[6]));
        return null;
    }

    private static @Nullable AxisPose axisPose(ServerLevel level, AxisBeam beam, Vec3 target, Vec3 pivot) {
        Vec3 offset = beam.origin().sub(pivot);
        Vec3 targetFromPivot = target.sub(pivot);
        double along = offset.dot(beam.direction());
        // Rotating the hull preserves the distance from its pivot to a point on the beam.
        double discriminant = targetFromPivot.dot(targetFromPivot) - offset.dot(offset) + along * along;
        if (!(discriminant > 0.0)) return null;
        double travel = Math.sqrt(discriminant) - along;
        if (!(travel > 0.25 && travel <= beam.range())) return null;
        Vec3 intercept = offset.add(beam.direction().scale(travel));
        var turn = new Quaterniond().rotationTo(vector(intercept).normalize(), vector(targetFromPivot).normalize());
        var worldAim = turn.transform(vector(beam.direction()));
        var facing = level.getBlockState(beam.gun().gunPos()).getValue(BlockStateProperties.HORIZONTAL_FACING);
        var bodyAxis = net.minecraft.world.phys.Vec3.atLowerCornerOf(facing.getNormal());
        return new AxisPose(new AxisAim(bodyAxis, new net.minecraft.world.phys.Vec3(worldAim.x, worldAim.y, worldAim.z)), turn);
    }

    private static Row axisRow(AxisBeam beam, Vec3 target, Vec3 pivot, @Nullable AxisPose pose, SightCheck sight,
                               List<ServerSubLevel> hulls, boolean limited) {
        if (sight != SightCheck.ACTUAL && pose == null) return axisFailure(beam.gun(), "无法在射程内对准");
        Vec3 origin = beam.origin(), direction = beam.direction();
        if (pose != null) {
            origin = pivot.add(vector(pose.turn().transform(vector(origin.sub(pivot)))));
            direction = vector(pose.turn().transform(vector(direction)));
        }
        Vec3 delta = target.sub(origin);
        double distance = delta.length();
        if (distance <= 0.25) return axisFailure(beam.gun(), "目标过近");
        if (distance > beam.range() + 1e-8) return axisFailure(beam.gun(), "超出射束射程");
        if (direction.dot(delta) / distance < AXIS_AIM_COS) {
            return axisFailure(beam.gun(), sight == SightCheck.ACTUAL ? AXIS_ALIGNING : "无法与参考轴炮共用舰姿");
        }
        if (sight == SightCheck.SKIP) return new Row(beam.gun().name(), "轴炮", "可参与", true);
        // The projected ray rotates with the gun. Trace it in the ship's current pose.
        Row clearance = sightLine(beam.gun().name(), "轴炮", beam.origin().add(beam.direction().scale(0.25)),
                beam.direction(), distance - 0.25, hulls, Set.of(), limited);
        return clearance.eligible() && sight == SightCheck.ESTIMATED
                ? new Row(beam.gun().name(), "轴炮", "可参与 · 舰体自动对准", true) : clearance;
    }

    private static Row axisFailure(FixedGun gun, String reason) { return new Row(gun.name(), "轴炮", reason, false); }

    private static Vector3d vector(Vec3 vec) { return new Vector3d(vec.x(), vec.y(), vec.z()); }

    private static Vec3 vector(Vector3d vec) { return new Vec3(vec.x, vec.y, vec.z); }

    private static String hardware(int status) {
        return status == MountLink.STATUS_NO_MOUNT ? "炮位不可用" : FireControlUiText.mount(status);
    }

    private enum Sight { CLEAR, BLOCKED, LIMITED }

    private static Row sightLine(String name, String weapon, Vec3 origin, Vec3 direction, double distance,
                                  List<ServerSubLevel> hulls, Set<UUID> own, boolean limited) {
        return sightLine(name, weapon, origin, direction, distance, hulls, own, limited, null);
    }

    private static Row sightLine(String name, String weapon, Vec3 origin, Vec3 direction, double distance,
                                  List<ServerSubLevel> hulls, Set<UUID> own, boolean limited, @Nullable int[] sightBudget) {
        int[] remaining = {BLOCK_BUDGET};
        for (ServerSubLevel hull : hulls) {
            if (own.contains(hull.getUniqueId())) continue;
            var bb = hull.boundingBox();
            var entry = FireControlCoreBlockEntity.rayAabb(origin, direction,
                    bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());
            if (entry == null || entry[0] > distance) continue;
            Vec3 from = origin.add(direction.scale(entry[0]));
            Vec3 to = origin.add(direction.scale(Math.min(entry[1], distance)));
            var pose = hull.logicalPose();
            var localFrom = pose.transformPositionInverse(new net.minecraft.world.phys.Vec3(from.x(), from.y(), from.z()));
            var localTo = pose.transformPositionInverse(new net.minecraft.world.phys.Vec3(to.x(), to.y(), to.z()));
            var plot = hull.getPlot();
            Sight sight = BlockGetter.traverseBlocks(localFrom, localTo, remaining, (budget, pos) -> {
                if (budget[0]-- <= 0 || (sightBudget != null && sightBudget[0] <= 0)) return Sight.LIMITED;
                if (sightBudget != null) sightBudget[0]--;
                var chunk = plot.getChunk(plot.toLocal(new ChunkPos(pos)));
                return chunk != null && !chunk.getBlockState(pos).isAir() ? Sight.BLOCKED : null;
            }, budget -> Sight.CLEAR);
            if (sight == Sight.BLOCKED) return new Row(name, weapon, "本船结构遮挡", false);
            if (sight == Sight.LIMITED) return new Row(name, weapon, "遮挡检查达到上限", false);
        }
        return limited ? new Row(name, weapon, "部分转塔未纳入遮挡检查", false)
                : new Row(name, weapon, "可参与", true);
    }
}
