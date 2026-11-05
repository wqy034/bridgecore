package com.bridgecore;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Proxy block entity: polls its six faces and pushes the raw strengths to every
 * bridge core on the same structure. Pushes every tick INCLUDING zeros — a feed
 * that only writes on signal would leave the core holding stale demand until
 * the TTL expired (0.3 s of phantom thrust on release).
 *
 * Server-side getSignal polling works identically in the overworld and the
 * sub-level, so no neighborChanged plumbing is needed. The mode (attitude vs
 * translation) lives in the blockstate since the 2026-09-18 single-block merge.
 */
public class RedstoneProxyBlockEntity extends BlockEntity {
    private final int[] facePower = new int[6];

    public RedstoneProxyBlockEntity(BlockPos pos, BlockState state) {
        super(BCRegistry.REDSTONE_PROXY_BE.get(), pos, state);
    }

    private RedstoneProxyBlock.Kind kind() {
        return getBlockState().getValue(RedstoneProxyBlock.MODE);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, RedstoneProxyBlockEntity be) {
        for (Direction d : Direction.values()) {
            int sig = level.getSignal(pos.relative(d), d);
            be.facePower[d.ordinal()] = sig;
        }

        var containing = Sable.HELPER.getContaining(level, pos);
        if (!(containing instanceof ServerSubLevel sub)) {
            return;
        }
        var cores = CoreDirectory.coresOf(sub);
        if (cores.isEmpty()) {
            return;
        }
        for (BridgeCoreBlockEntity core : cores) {
            core.submitProxyInput(be.kind(), pos, be.facePower);
        }
    }
}
