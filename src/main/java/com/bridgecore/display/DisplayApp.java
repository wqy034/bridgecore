package com.bridgecore.display;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;

/**
 * A display application: draws one frame of content onto the server-side canvas.
 * The attitude indicator is the first app; fire-control readouts later are just
 * more apps (2026-09-01 user ruling: the screen is a general canvas, not a gauge).
 */
public interface DisplayApp {
    void render(Canvas canvas, DisplayContext ctx);

    /** Per-frame data-source hook, called by the host (tiled-screen GPU) before
     *  render() — apps whose data does not fit the shared DisplayContext resolve
     *  their source here (FireControlPanelApp looks up its core this way).
     *  Server thread. Default: no binding needed. */
    default void bind(Level level, @Nullable ServerSubLevel sub, BlockPos hostPos) {
    }

    /** Whole-screen click, routed by the host ONLY to the currently active app
     *  (2026-09-18 registry ruling). Server thread. Default: not interactive. */
    default void onClick(Player player, int x, int y, boolean sneak) {
    }

    /**
     * Everything an app may need for one frame. The attitude snapshot is read by the
     * screen block itself off the sub-level it rides on (zero-link design); {@code facing}
     * is the screen block's own facing = the direction the reading pilot looks = bow.
     * {@code gear} is the first core-private readout (M2): the thrust gear of the
     * structure's bridge core via CoreDirectory, -1 = no core on the structure.
     * {@code stickPitch}/{@code stickRoll} are the core's merged pilot stick demand
     * (-1..1, nose-down / starboard-down positive; 0 with no core or mode OFF) for
     * the legacy-style input line. {@code totalFe}/{@code totalFeCap} are the
     * structure-wide energy sum (distributor pools + extractor overflow buffers;
     * v0.13 removed the core-local buffer) and its capacity; {@code drawFePerTick}
     * is the core's low-passed consumption (tau ~0.5 s); {@code endSeconds} is
     * the endurance estimate from the measured stock decline (HudScreenBlockEntity's
     * 3 s sampling ring over totalFe + burnableFe; POSITIVE_INFINITY = not in
     * decline); {@code burnableFe} is the extractors' not-yet-realized FE estimate
     * (heat pools + fuel slots, taxed at current slider positions) — endurance
     * numerator material, NOT part of the stored total. All zero with no core /
     * no signal (endSeconds = POSITIVE_INFINITY).
     */
    record DisplayContext(boolean hasSignal, Quaterniond orientation,
                          double posX, double posY, double posZ,
                          double velX, double velY, double velZ,
                          double omegaX, double omegaY, double omegaZ,
                          Direction facing, int gear, double stickPitch, double stickRoll,
                          double totalFe, double totalFeCap, double drawFePerTick,
                          double endSeconds, double burnableFe) {
        public static DisplayContext noSignal(Direction facing) {
            return new DisplayContext(false, new Quaterniond(), 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    facing, -1, 0, 0, 0, 0, 0, Double.POSITIVE_INFINITY, 0);
        }
    }
}
