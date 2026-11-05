package com.bridgecore.cc;

import com.bridgecore.BridgeCoreBlockEntity;
import com.bridgecore.MomentumDistributorBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Channel declarations for the CC channel-table peripherals (2026-09-09 ruling:
 * telemetry freely exposed, control never depends on CC polling; channels are
 * additive only — Lua side probes with list()).
 *
 * Pure Java (no CC imports) so this class-loads fine with CC absent; only
 * CCIntegration (loaded behind the ModList guard) touches CC API types.
 * Readers/writers run on the server thread.
 */
public final class BridgeCoreChannels {

    private BridgeCoreChannels() {
    }

    /** Bridge core: mode/motion/attitude/energy telemetry + the two command
     *  channels (setMode/setGear — they land in the SAME BE methods the
     *  right-click/GUI paths use, no parallel control logic). */
    public static List<ChannelDef> coreChannels(BridgeCoreBlockEntity be) {
        return List.of(
                ChannelDef.read("mode", () -> be.mode().name().toLowerCase(Locale.ROOT),
                        "control mode: off/starship/damping"),
                ChannelDef.read("mass", () -> {
                            ServerSubLevel sub = subOf(be);
                            return sub != null && sub.getMassTracker() != null
                                    ? sub.getMassTracker().getMass() : 0.0;
                        }, "structure mass, kg (0 with no structure)"),
                ChannelDef.read("velocity", () -> vec(be.lastVelocity()),
                        "logical velocity, m/s, {x,y,z}"),
                ChannelDef.read("angularVelocity", () -> {
                            ServerSubLevel sub = subOf(be);
                            return sub != null ? vec(sub.latestAngularVelocity) : zeroVec();
                        }, "angular velocity, rad/s, {x,y,z}"),
                ChannelDef.read("attitude", () -> {
                            Quaterniond q = be.lastOrientation();
                            if (q == null) {
                                return zeroVec();
                            }
                            Vector3d e = q.getEulerAnglesXYZ(new Vector3d());
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("pitch", Math.toDegrees(e.x));
                            m.put("yaw", Math.toDegrees(e.y));
                            m.put("roll", Math.toDegrees(e.z));
                            return m;
                        }, "attitude Euler angles, degrees, {pitch,yaw,roll}"),
                ChannelDef.read("gear", () -> be.gear() + 1, "thrust gear, 1-based"),
                ChannelDef.read("poolFe", () -> be.energyAccount().totalStored(),
                        "structure distributor pool total, FE"),
                ChannelDef.read("poolCapacityFe", () -> be.energyAccount().totalCapacity(),
                        "structure distributor pool capacity, FE"),
                ChannelDef.read("drawFePerTick", be::drawFePerTick,
                        "smoothed consumption, FE/t"),
                ChannelDef.read("enduranceSeconds", be::enduranceSeconds,
                        "END estimate, s (inf = stock not declining)"),
                ChannelDef.write("setMode", value -> {
                            if (!(value instanceof String s)) {
                                throw new IllegalArgumentException("setMode expects a string "
                                        + "(off/starship/damping)");
                            }
                            BridgeCoreBlockEntity.ControlMode target;
                            try {
                                target = BridgeCoreBlockEntity.ControlMode.valueOf(
                                        s.toUpperCase(Locale.ROOT));
                            } catch (IllegalArgumentException e) {
                                throw new IllegalArgumentException("unknown mode '" + s
                                        + "' (off/starship/damping)");
                            }
                            be.setMode(target);
                            return be.mode().name().toLowerCase(Locale.ROOT);
                        }, "set control mode: off/starship/damping"),
                ChannelDef.write("setGear", value -> {
                            if (!(value instanceof Number n)) {
                                throw new IllegalArgumentException("setGear expects a number "
                                        + "(1.." + BridgeCoreBlockEntity.GEAR_COUNT + ")");
                            }
                            int g = n.intValue();
                            if (g < 1 || g > BridgeCoreBlockEntity.GEAR_COUNT) {
                                throw new IllegalArgumentException("gear out of range: " + g
                                        + " (1.." + BridgeCoreBlockEntity.GEAR_COUNT + ")");
                            }
                            be.setGear(g - 1);
                            return (double) (be.gear() + 1);
                        }, "set thrust gear, 1-based"));
    }

    /** Momentum distributor: pool level telemetry. */
    public static List<ChannelDef> distributorChannels(MomentumDistributorBlockEntity be) {
        return List.of(
                ChannelDef.read("poolFe", be::energy, "stored energy, FE"),
                ChannelDef.read("capacityFe", () -> MomentumDistributorBlockEntity.CAPACITY,
                        "pool capacity, FE"));
    }

    private static ServerSubLevel subOf(BridgeCoreBlockEntity be) {
        return be.getLevel() != null
                && Sable.HELPER.getContaining(be.getLevel(), be.getBlockPos()) instanceof ServerSubLevel s
                ? s : null;
    }

    private static Map<String, Object> vec(org.joml.Vector3dc v) {
        if (v == null) {
            return zeroVec();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", v.x());
        m.put("y", v.y());
        m.put("z", v.z());
        return m;
    }

    private static Map<String, Object> zeroVec() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", 0.0);
        m.put("y", 0.0);
        m.put("z", 0.0);
        return m;
    }
}
