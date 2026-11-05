package com.bridgecore.display;

import com.bridgecore.BridgeCoreBlockEntity;
import com.bridgecore.CoreDirectory;
import com.bridgecore.EnduranceEstimator;
import com.bridgecore.EnergyExtractorBlockEntity;
import com.bridgecore.MomentumDistributorBlockEntity;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.joml.Quaterniond;

/**
 * Shared zero-link DisplayContext builder (2026-09-17 extraction): the frame
 * inputs every display app reads — containing sub-level pose/velocity, core
 * readouts (gear/stick/draw), structure-wide energy sums and the endurance
 * estimate — assembled exactly the way HudScreenBlockEntity's ticker did it.
 * Both the HUD screen and the tiled-screen GPU (energy panel) call this so the
 * two surfaces can never drift apart on what the numbers mean.
 *
 * The caller resolves the containing sub-level itself (it usually needs it for
 * other duties — the HUD screen needs it for CoreDirectory membership, the GPU
 * for the packet origin) and passes it in; null = no ship = no signal, which
 * also resets the estimator so the ring re-arms on the next signal.
 *
 * Server thread only (EnduranceEstimator is not thread-safe).
 */
public final class DisplayContexts {

    private DisplayContexts() {
    }

    public static DisplayApp.DisplayContext build(Level level, ServerSubLevel sub,
                                                  Direction facing, EnduranceEstimator endurance) {
        if (sub == null) {
            endurance.reset(); // re-arm the ring warm-up on the next signal
            return DisplayApp.DisplayContext.noSignal(facing);
        }
        Pose3d pose = sub.logicalPose();
        int gear = -1;
        double stickPitch = 0.0, stickRoll = 0.0, drawFePerTick = 0.0;
        // Structure-wide energy: distributor pools + extractor overflow buffers
        // (CoreDirectory tracks both; v0.13 removed the core-local buffer).
        double totalFe = 0.0, totalFeCap = 0.0, burnableFe = 0.0;
        var cores = CoreDirectory.coresOf(sub); // M2: core-private readouts (gear first)
        if (!cores.isEmpty()) {
            BridgeCoreBlockEntity core = cores.get(0);
            gear = core.gear();
            stickPitch = core.stickPitch();
            stickRoll = core.stickRoll();
            drawFePerTick = core.drawFePerTick();
        }
        for (MomentumDistributorBlockEntity pool : CoreDirectory.powerPoolsOf(sub)) {
            totalFe += pool.energy();
            totalFeCap += MomentumDistributorBlockEntity.CAPACITY;
        }
        for (EnergyExtractorBlockEntity ex : CoreDirectory.extractorsOf(sub)) {
            totalFe += ex.buffer();
            totalFeCap += EnergyExtractorBlockEntity.BUFFER_CAPACITY;
            burnableFe += ex.potentialFe();
        }
        // END from the measured stock decline (see EnduranceEstimator for the
        // why); sampled at 1 Hz off the shared estimator.
        double endSeconds = endurance.update(totalFe + burnableFe, drawFePerTick,
                level.getGameTime());
        return new DisplayApp.DisplayContext(true, new Quaterniond(pose.orientation()),
                pose.position().x(), pose.position().y(), pose.position().z(),
                sub.latestLinearVelocity.x(), sub.latestLinearVelocity.y(), sub.latestLinearVelocity.z(),
                sub.latestAngularVelocity.x(), sub.latestAngularVelocity.y(), sub.latestAngularVelocity.z(),
                facing, gear, stickPitch, stickRoll, totalFe, totalFeCap, drawFePerTick,
                endSeconds, burnableFe);
    }
}
