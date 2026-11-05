package com.bridgecore.display;

import net.minecraft.world.entity.player.Player;

/**
 * Internal click event sink for tiled screens; register on the GPU block entity.
 * Called on the server thread with 0-based whole-screen pixel coordinates.
 */
@FunctionalInterface
public interface DisplayClickListener {
    void onDisplayClick(Player player, int x, int y, boolean sneaking);
}
