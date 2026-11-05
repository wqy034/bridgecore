package com.bridgecore.firecontrol;

import org.jetbrains.annotations.Nullable;

import com.bridgecore.firecontrol.ballistics.SolveChain;
import com.bridgecore.firecontrol.railgun.RailgunLink;
import com.bridgecore.firecontrol.railgun.RailgunMountLink;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Mount-link registry (火力指挥层②, 2026-10-08): hands the fire-control core
 * the active {@link MountLink} implementation. Today the only real
 * implementation is fe_railgun's ({@link RailgunMountLink}); when the mod is
 * absent a no-op link answers with "no mount" semantics so the rest of the
 * state machine never special-cases absence.
 *
 * <p>Extension point for vanilla CBC / macro-cannon support: implement
 * MountLink, then widen {@link #active()} into a composite that tries each
 * link's {@code mountAt} in order (first recognizer wins). The core, the
 * gate chain and the pairing listener already speak only the interface.
 */
public final class MountLinks {

    private MountLinks() {}

    private static MountLink active;

    /** The active mount link (cached — the mod set is fixed after
     *  construction, same verdict RailgunLink.available() caches). */
    public static MountLink active() {
        if (active == null) {
            active = RailgunLink.available() ? RailgunMountLink.INSTANCE : Noop.INSTANCE;
        }
        return active;
    }

    /** Absent-mod stand-in: every mount reads as gone, fire always rejects,
     *  ballistics fall back to the caller's base params. */
    private static final class Noop implements MountLink {
        private static final Noop INSTANCE = new Noop();

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public @Nullable Object mountAt(Level level, BlockPos pos) {
            return null;
        }

        @Override
        public int mountStatusCode(@Nullable Object handle) {
            return STATUS_NO_MOUNT;
        }

        @Override
        public @Nullable String fire(@Nullable Object handle) {
            return "UNAVAILABLE";
        }

        @Override
        public boolean hasGun(@Nullable Object handle) {
            return false;
        }

        @Override
        public boolean breechless(@Nullable Object handle) {
            return false;
        }

        @Override
        public String signature(@Nullable Object handle) {
            return "";
        }

        @Override
        public SolveChain.Params paramsFor(@Nullable Object handle, SolveChain.Params base,
                                           double barrelLen) {
            return base;
        }

        @Override
        public String gunTypeName(@Nullable Object handle) {
            return "NONE";
        }

        @Override
        public double muzzleVelocity(@Nullable Object handle) {
            return 0;
        }

        @Override
        public double heat(@Nullable Object handle) {
            return 0;
        }

        @Override
        public double maxHeat(@Nullable Object handle) {
            return 0;
        }
    }
}
