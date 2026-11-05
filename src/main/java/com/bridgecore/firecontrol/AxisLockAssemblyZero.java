package com.bridgecore.firecontrol;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import com.bridgecore.BridgeCoreMod;
import com.bridgecore.firecontrol.ballistics.Quat;
import com.bridgecore.firecontrol.ballistics.Vec3;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Assembly-zero store (AxisLock 第三批 step1, handoff §2.31): the trusted
 * mechanical datum for the immediate canonical-baseline rebuild (step2).
 *
 * <p>What is stored and why: at {@code assemble()} TAIL the head has just been
 * cut in place — plate mated to the bearing face — so the as-built relative
 * pose {@code qRelAssembly = conj(parentOri)·childOri} is mechanically legal
 * by construction. Its SWING component is therefore the one swing reference
 * we can trust; the twist at assembly is stored alongside for diagnostics.
 * Step2 will rebuild claim-time baselines as
 * {@code axisAngle(axis, liveTwist) ∘ swing(qRelAssembly)}: the swing comes
 * from this trusted datum, the twist from the live pose — a sagged or
 * mid-slew pose at claim time can no longer be blessed into the baseline
 * (the 歪头/翻转 failure class), and no stillness window is needed on the
 * normal path.
 *
 * <p>Persistence (GPT 收紧③, verified NeoForge 21.1.242): the bearing BE's
 * own {@code getPersistentData()} — saved with the block entity, no
 * serialization mixin required (upstream create_fire_control uses the same
 * mechanism for its CreateFireControlAssemblyZero). The in-memory cache is
 * only a warm layer; NBT is authoritative across reloads.
 *
 * <p>Producers (用户裁决 — a legacy save must NEVER have its current pose
 * silently promoted to a trusted zero on ordinary load): a successful
 * assemble, or the explicit establish flow (step3). Invalidation:
 * {@code disassemble()} HEAD purges; any consumer-side check failure
 * (childId / bearing facing / plate facing mismatch, non-finite values)
 * purges instead of reusing.
 *
 * <p>Ground bearings are skipped (parent is not a ServerSubLevel) — AxisLock
 * never claims them, their native servo already works.
 *
 * <p>Server thread only (assemble/disassemble run on it).
 */
public final class AxisLockAssemblyZero {

    /** NBT compound key inside the bearing BE's persistent data. */
    private static final String NBT_KEY = "bridgecore:axislock_zero";

    private record Key(ResourceKey<Level> dim, BlockPos pos) {}

    /** The trusted datum: as-built relative pose + assembly twist + identity. */
    public record Zero(UUID childId, Quaterniond qRelAssembly, double twistAssemblyDeg,
                       Direction bearingFacing, Direction plateFacing) {}

    private static final Map<Key, Zero> CACHE = new ConcurrentHashMap<>();

    private AxisLockAssemblyZero() {}

    // ---------------- capture (mixin: assemble TAIL) ----------------

    public static void onAssemble(SwivelBearingBlockEntity bearing) {
        try {
            if (!(bearing.getLevel() instanceof ServerLevel level)) return;
            // assembly-success gate: TAIL fires even when assemble() bailed
            if (!bearing.isAssembled() || bearing.getSubLevelID() == null) return;
            capture(bearing, level, "assemble");
        } catch (Throwable t) {
            BridgeCoreMod.LOGGER.warn("[axislock] assembly-zero capture failed, bearing unaffected", t);
        }
    }

    /** Explicit establish flow (step3, 用户裁决的第二个合法来源): capture the
     *  zero at a USER-CONFIRMED rest pose — the recalibration return-to-rest
     *  gate. Fills the gap for legacy saves; an existing zero (assembly or
     *  previously established) is NEVER overwritten. */
    public static void establishIfAbsent(SwivelBearingBlockEntity bearing, ServerLevel level) {
        try {
            if (bearing.getPersistentData().contains(NBT_KEY)) return;
            if (CACHE.containsKey(new Key(level.dimension(), bearing.getBlockPos()))) return;
            if (!bearing.isAssembled() || bearing.getSubLevelID() == null) return;
            capture(bearing, level, "establish-at-rest");
        } catch (Throwable t) {
            BridgeCoreMod.LOGGER.warn("[axislock] assembly-zero establish failed", t);
        }
    }

    private static void capture(SwivelBearingBlockEntity bearing, ServerLevel level, String source) {
        if (!(Sable.HELPER.getContaining(bearing) instanceof ServerSubLevel parent)) return; // ground
        ServerSubLevel child = SubLevelContainer.getContainer(level) instanceof ServerSubLevelContainer sc
                && sc.getSubLevel(bearing.getSubLevelID()) instanceof ServerSubLevel s ? s : null;
        if (child == null) return;
        BlockPos platePos = bearing.getPlatePos();
        if (platePos == null) return;
        Direction bearingFacing = bearing.getBlockState().getValue(BlockStateProperties.FACING);
        Direction plateFacing = level.getBlockState(platePos).getValue(BlockStateProperties.FACING);
        Quaterniond qRel = new Quaterniond(parent.logicalPose().orientation()).conjugate()
                .mul(child.logicalPose().orientation());
        double twist = twistDegOf(qRel, bearingFacing);
        // 宁缺毋滥 (GPT 收紧②): a capture that is not provably sound is
        // refused — a missing zero falls back to the gated path, a bad
        // zero poisons every future claim
        if (!qRel.isFinite() || !Double.isFinite(twist)) {
            BridgeCoreMod.LOGGER.warn("[axislock] assembly-zero capture refused on bearing@{}: non-finite pose/twist",
                    bearing.getBlockPos().toShortString());
            return;
        }
        Zero z = new Zero(bearing.getSubLevelID(), qRel, twist, bearingFacing, plateFacing);
        Key key = new Key(level.dimension(), bearing.getBlockPos().immutable());
        CACHE.put(key, z);
        writeNbt(bearing, z);
        bearing.setChanged();
    }

    // ---------------- invalidation (mixin: disassemble HEAD) ----------------

    public static void onDisassemble(SwivelBearingBlockEntity bearing) {
        try {
            if (!(bearing.getLevel() instanceof ServerLevel level)) return;
            Key key = new Key(level.dimension(), bearing.getBlockPos());
            CACHE.remove(key);
            if (bearing.getPersistentData().contains(NBT_KEY)) {
                bearing.getPersistentData().remove(NBT_KEY);
                bearing.setChanged();
            }
        } catch (Throwable t) {
            BridgeCoreMod.LOGGER.warn("[axislock] assembly-zero forget failed", t);
        }
    }

    // ---------------- validated read (step2 consumer) ----------------

    /**
     * The trusted zero for this bearing, or null when absent/invalid. Lazy
     * NBT restore into the cache; every check failure purges (never silently
     * reused across a child-identity or facing change).
     */
    public static @Nullable Zero get(ServerLevel level, BlockPos pos, SwivelBearingBlockEntity bearing) {
        Key key = new Key(level.dimension(), pos);
        Zero z = CACHE.get(key);
        if (z == null) {
            z = readNbt(bearing);
            if (z == null) return null;
            CACHE.put(key, z);
        }
        if (!z.childId().equals(bearing.getSubLevelID())) {
            purge(key, bearing);
            return null;
        }
        Direction facing = bearing.getBlockState().getValue(BlockStateProperties.FACING);
        if (z.bearingFacing() != facing) {
            purge(key, bearing);
            return null;
        }
        BlockPos platePos = bearing.getPlatePos();
        if (platePos != null
                && level.getBlockState(platePos).getValue(BlockStateProperties.FACING) != z.plateFacing()) {
            purge(key, bearing);
            return null;
        }
        return z;
    }

    private static void purge(Key key, SwivelBearingBlockEntity bearing) {
        CACHE.remove(key);
        bearing.getPersistentData().remove(NBT_KEY);
        bearing.setChanged();
    }

    /** Server stopping: drop the warm cache (NBT persists, it is authoritative). */
    public static void onServerStopping() {
        CACHE.clear();
    }

    // ---------------- NBT ----------------

    private static void writeNbt(SwivelBearingBlockEntity bearing, Zero z) {
        CompoundTag t = new CompoundTag();
        t.putDouble("qw", z.qRelAssembly().w);
        t.putDouble("qx", z.qRelAssembly().x);
        t.putDouble("qy", z.qRelAssembly().y);
        t.putDouble("qz", z.qRelAssembly().z);
        t.putDouble("twist", z.twistAssemblyDeg());
        t.putLong("childMost", z.childId().getMostSignificantBits());
        t.putLong("childLeast", z.childId().getLeastSignificantBits());
        t.putInt("bearingFacing", z.bearingFacing().ordinal());
        t.putInt("plateFacing", z.plateFacing().ordinal());
        bearing.getPersistentData().put(NBT_KEY, t);
    }

    private static @Nullable Zero readNbt(SwivelBearingBlockEntity bearing) {
        CompoundTag data = bearing.getPersistentData();
        if (!data.contains(NBT_KEY)) return null;
        CompoundTag t = data.getCompound(NBT_KEY);
        Quaterniond q = new Quaterniond(t.getDouble("qx"), t.getDouble("qy"),
                t.getDouble("qz"), t.getDouble("qw"));
        double twist = t.getDouble("twist");
        if (!q.isFinite() || !Double.isFinite(twist)) return null;
        int bf = t.getInt("bearingFacing"), pf = t.getInt("plateFacing");
        if (bf < 0 || bf >= 6 || pf < 0 || pf >= 6) return null;
        return new Zero(new UUID(t.getLong("childMost"), t.getLong("childLeast")), q, twist,
                Direction.values()[bf], Direction.values()[pf]);
    }

    /** Twist of the relative orientation about the hinge axis (bearing FACING),
     *  same convention as {@link GimbalMath#twistDeg}. */
    private static double twistDegOf(Quaterniond qRel, Direction bearingFacing) {
        return GimbalMath.twistDeg(
                new Quat(qRel.w, qRel.x, qRel.y, qRel.z),
                new Vec3(bearingFacing.getStepX(), bearingFacing.getStepY(), bearingFacing.getStepZ()));
    }

    /** Swing component of the as-built relative pose (twist removed about the
     *  hinge axis): the trusted swing reference for step2's canonical rebuild. */
    public static Quaterniond swingOf(Zero z, Vector3d axisLocalParent) {
        Quaterniond untwist = new Quaterniond().rotationAxis(
                Math.toRadians(-z.twistAssemblyDeg()), axisLocalParent.x, axisLocalParent.y, axisLocalParent.z);
        return untwist.mul(z.qRelAssembly());
    }
}
