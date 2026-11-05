package com.bridgecore.firecontrol.railgun;

import org.jetbrains.annotations.Nullable;

import com.bridgecore.firecontrol.MountLink;
import com.bridgecore.firecontrol.ballistics.SolveChain;
import com.fe_railgun.api.FEGunApi;
import com.fe_railgun.api.FEMountHandle;
import com.fe_railgun.api.FireStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * fe_railgun 1.7.0-alpha linkage (P3) — the ONLY class in this mod allowed to
 * reference com.fe_railgun API types (soft dependency: compileOnly jar +
 * ModList guard, same pattern as the cc package's CCIntegration — except the
 * call sites here run per-tick inside the fire-control loop, so instead of
 * gating registration this class gates every method on {@link #available()}
 * and callers hold the mount handle as an opaque Object; when fe_railgun is
 * absent the whole chain short-circuits to null/safe defaults and the
 * fe_railgun classes are never resolved).
 *
 * <p>Contract: docs/firecontrol-api-handoff.md (fe_railgun side). Yard
 * coordinates on the main ServerLevel resolve directly (same resolution rule
 * as SwivelTurretActuator.bearingAt) — no sub-level transform is needed.
 *
 * <p>Handles are stateless light wrappers and safe to hold across ticks, but
 * we re-resolve via {@link #mountAt} instead of caching: a disassembled/broken
 * mount block must drop out immediately.
 */
public final class RailgunLink {

    private RailgunLink() {}

    /** Mount status codes broadcast to the GUI row (FireControlMenu +10).
     *  Aliases of the normalized MountLink codes (火力指挥层②, 2026-10-08):
     *  the semantics are shared across mount types; kept here as
     *  compile-time constants so existing switch labels stay valid. */
    public static final int STATUS_NO_MOUNT = MountLink.STATUS_NO_MOUNT;
    public static final int STATUS_GUN_NONE = MountLink.STATUS_GUN_NONE;
    public static final int STATUS_READY = MountLink.STATUS_READY;
    public static final int STATUS_NOT_ASSEMBLED = MountLink.STATUS_NOT_ASSEMBLED;
    public static final int STATUS_NO_AMMO = MountLink.STATUS_NO_AMMO;
    public static final int STATUS_INSUFFICIENT_ENERGY = MountLink.STATUS_INSUFFICIENT_ENERGY;
    public static final int STATUS_OVERHEATED = MountLink.STATUS_OVERHEATED;
    public static final int STATUS_BREECH_BUSY = MountLink.STATUS_BREECH_BUSY;

    /** Cached ModList verdict — the mod set is fixed after construction. */
    private static Boolean loaded;

    public static boolean available() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded("fe_railgun");
        }
        return loaded;
    }

    /** The fe gun mount at a (yard-frame) position, or null when fe_railgun is
     *  absent / the block is not one of its cannon mounts. */
    public static @Nullable FEMountHandle mountAt(Level level, BlockPos pos) {
        if (!available()) return null;
        return FEGunApi.mountAt(level, pos).orElse(null);
    }

    /** Defensive downcast for the opaque handles callers hold; null-safe and
     *  never resolves fe_railgun types while the mod is absent. */
    private static @Nullable FEMountHandle asHandle(@Nullable Object handle) {
        if (!available()) return null;
        return handle instanceof FEMountHandle h ? h : null;
    }

    /** True when the handle is valid AND an fe gun (RAILGUN/PARTICLE/COILGUN/
     *  LASER) is assembled on it — gunType() != NONE. */
    public static boolean hasGun(@Nullable Object handle) {
        FEMountHandle h = asHandle(handle);
        return h != null && h.gunType() != FEMountHandle.GunType.NONE;
    }

    /** True when the mounted gun has NO breech cycle (RAILGUN=粒子炮 pure-FE,
     *  LASER=激光机炮 4t breech cooldown that throttles itself) — only these
     *  get the cadence stepper; PARTICLE=磁轨炮/COILGUN=线圈炮 pace at their
     *  quickfiring breech and a user cadence below that is meaningless.
     *  (枚举→中文名映射 2026-10-06 按 zh_cn.json+contraption 源码核实:
     *  RAILGUN=粒子炮 railgun_barrel=粒子加速炮管, PARTICLE=磁轨炮
     *  railgun_cannon_barrel=磁轨加速炮管 — 对面交接文档的括号注是反的。) */
    public static boolean breechless(@Nullable Object handle) {
        FEMountHandle h = asHandle(handle);
        return h != null && (h.gunType() == FEMountHandle.GunType.RAILGUN
                || h.gunType() == FEMountHandle.GunType.LASER);
    }

    /** GUI row status code: STATUS_NO_MOUNT / STATUS_GUN_NONE / readiness(). */
    public static int mountStatusCode(@Nullable Object handle) {
        FEMountHandle h = asHandle(handle);
        if (h == null) return STATUS_NO_MOUNT;
        if (h.gunType() == FEMountHandle.GunType.NONE) return STATUS_GUN_NONE;
        return switch (h.readiness()) {
            case READY -> STATUS_READY;
            case NOT_ASSEMBLED -> STATUS_NOT_ASSEMBLED;
            case NO_AMMO -> STATUS_NO_AMMO;
            case INSUFFICIENT_ENERGY -> STATUS_INSUFFICIENT_ENERGY;
            case OVERHEATED -> STATUS_OVERHEATED;
            case BREECH_BUSY -> STATUS_BREECH_BUSY;
            default -> STATUS_NO_MOUNT; // readiness() never returns FIRED (contract §3)
        };
    }

    /** Atomic fire (the handle re-runs readiness internally — READY only fires,
     *  failures consume nothing). @return null on FIRED, otherwise the
     *  rejection's enum name (e.g. "OVERHEATED"). */
    public static @Nullable String fire(@Nullable Object handle) {
        FEMountHandle h = asHandle(handle);
        if (h == null) return "UNAVAILABLE";
        FireStatus r = h.fire();
        return r == FireStatus.FIRED ? null : r.name();
    }

    /** Cache-invalidation signature for the per-turret solver params: any
     *  field that feeds {@link #paramsFor}. "" for no usable mount. */
    public static String signature(@Nullable Object handle) {
        FEMountHandle h = asHandle(handle);
        if (h == null) return "";
        return h.gunType() + "|" + h.muzzleVelocity() + "|" + h.slugGravity() + "|" + h.slugDrag();
    }

    /** Build the solver params for this mount from the live gun readings;
     *  returns {@code base} unchanged when the mount is unusable (absent /
     *  NONE / no muzzle velocity). Field mapping (units per the handoff):
     *  <ul>
     *    <li>shellVelocity <- muzzleVelocity() (m/s; the solver folds /20
     *        internally, so it is passed through raw);</li>
     *    <li>gravPT <- slugGravity() (blocks/tick^2, positive down, as-is);</li>
     *    <li>dragPT: the solver's dragPT is a per-tick velocity RETENTION
     *        ratio (flightTime takes the drag-free fast path outside
     *        (0.001, 0.999)); fe_railgun's slugDrag is a constant decel in
     *        blocks/tick^2. Approximated by the first-tick relative loss
     *        r = 1 - a/v0, clamped into (0.001, 0.999). slugDrag <= 0 (the
     *        default config) maps to 1.0 = drag-free;</li>
     *    <li>barrelLength <- the pairing-time |muzzleTip - barrelBase| marker
     *        distance in blocks (0 = unmarked);</li>
     *    <li>every other field is copied from {@code base}.</li>
     *  </ul> */
    public static SolveChain.Params paramsFor(@Nullable Object handle, SolveChain.Params base,
                                              double barrelLen) {
        FEMountHandle h = asHandle(handle);
        if (h == null || h.gunType() == FEMountHandle.GunType.NONE) return base;
        double muzzle = h.muzzleVelocity();
        if (muzzle <= 0) return base;
        double dragPT = 1.0;
        double slugDrag = h.slugDrag();
        if (slugDrag > 0) {
            dragPT = Math.max(0.001, Math.min(0.999, 1.0 - slugDrag / (muzzle / 20.0)));
        }
        return new SolveChain.Params(
                dragPT, h.slugGravity(), muzzle, barrelLen,
                base.minPitchAngle(), base.maxPitchAngle(), base.lockYawRange(),
                base.lockZoneDir(), base.lockZoneRange(), base.forecastMov(),
                base.forecastRot(), base.tgVelScale(), base.invertYaw(),
                base.invertPitch(), base.yawTrim(), base.face());
    }

    // ---- status passthroughs ----

    public static String gunTypeName(@Nullable Object handle) {
        FEMountHandle h = asHandle(handle);
        return h == null ? "NONE" : h.gunType().name();
    }

    /** Live muzzle velocity, m/s (0 when absent/NONE). */
    public static double muzzleVelocity(@Nullable Object handle) {
        FEMountHandle h = asHandle(handle);
        return h == null ? 0 : h.muzzleVelocity();
    }

    public static double heat(@Nullable Object handle) {
        FEMountHandle h = asHandle(handle);
        return h == null ? 0 : h.heat();
    }

    public static double maxHeat(@Nullable Object handle) {
        FEMountHandle h = asHandle(handle);
        return h == null ? 0 : h.maxHeat();
    }

    public static boolean isOverheated(@Nullable Object handle) {
        FEMountHandle h = asHandle(handle);
        return h != null && h.isOverheated();
    }

    public static long energyAvailable(@Nullable Object handle) {
        FEMountHandle h = asHandle(handle);
        return h == null ? 0 : h.energyAvailable();
    }

    public static long energyCapacity(@Nullable Object handle) {
        FEMountHandle h = asHandle(handle);
        return h == null ? 0 : h.energyCapacity();
    }
}
