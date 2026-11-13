package com.bridgecore.control;

import com.bridgecore.BridgeCoreBlockEntity.ControlMode;
import com.bridgecore.CoreDirectory;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.content.blocks.docking_connector.DockingConnectorBlockEntity;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBlockEntity;
import dev.simulated_team.simulated.content.blocks.rope.strand.server.RopeAttachmentPoint;
import dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerRopeStrand;
import net.neoforged.fml.ModList;
import org.joml.Matrix3dc;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Physics-thread state for passive cargo attached to a coupled STARSHIP. */
public final class CargoFlight {
    private final DockedMass dockedMass = new DockedMass();
    private final TetherLoad tether = new TetherLoad();
    private final Vector3d groupVelocity = new Vector3d();
    private final Vector3d memberVelocity = new Vector3d();
    private final Vector3d memberCenter = new Vector3d();
    private final Vector3d worldCenter = new Vector3d();
    private final Quaterniond memberRotation = new Quaterniond();
    private final Vector3d submittedImpulse = new Vector3d();
    private Connections connections = Connections.EMPTY;
    private long connectionTick = Long.MIN_VALUE;
    private boolean docked;
    private double tetherMassRatio;
    private double previousControlMass;
    private double previousHeight;
    private double previousHeightStep;

    public void reset() {
        connections = Connections.EMPTY;
        connectionTick = Long.MIN_VALUE;
        docked = false;
        tetherMassRatio = 0.0;
        previousControlMass = 0.0;
        previousHeightStep = 0.0;
        tether.reset();
    }

    /** Returns true when the height and angular samplers need a new docked reference. */
    public boolean update(ServerSubLevel root, long tick, Pose3d pose, MassData hull, Vector3dc hullVelocity) {
        if (!ModList.get().isLoaded("simulated") || hull.getMass() <= 0.0) {
            boolean wasDocked = docked;
            reset();
            return wasDocked;
        }
        boolean changedDock = false;
        if (tick != connectionTick) {
            Connections next = Simulated.findConnections(root);
            changedDock = !connections.docks().equals(next.docks());
            if (changedDock || !connections.ropes().equals(next.ropes())
                    || !connections.tethers().equals(next.tethers())) tether.reset();
            connections = next;
            connectionTick = tick;
        }

        boolean wasDocked = docked;
        docked = updateDockedMass(root, pose, hull, hullVelocity);
        double tetherMass = 0.0;
        for (ServerSubLevel body : connections.tethers()) {
            MassData mass = body.isRemoved() ? null : body.getMassTracker();
            if (mass != null && Double.isFinite(mass.getMass()) && mass.getMass() > 0.0) tetherMass += mass.getMass();
        }
        double controlMass = docked ? dockedMass.mass() : hull.getMass();
        tetherMassRatio = Double.isFinite(tetherMass) ? tetherMass / controlMass : 0.0;
        submittedImpulse.zero();
        boolean referenceChanged = changedDock || wasDocked != docked;
        if (!hasTether() || referenceChanged || Math.abs(controlMass - previousControlMass) > 0.02 * controlMass) {
            tether.reset();
        }
        previousControlMass = controlMass;
        if (referenceChanged || (!docked && !hasTether())) previousHeightStep = 0.0;
        return referenceChanged;
    }

    private boolean updateDockedMass(ServerSubLevel root, Pose3d pose, MassData hull, Vector3dc hullVelocity) {
        if (connections.docks().isEmpty()) return false;
        dockedMass.reset(hull.getMass(), hull.getCenterOfMass(), hull.getInertiaTensor());
        groupVelocity.set(hullVelocity).mul(hull.getMass());
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.require(root.getLevel());
        for (ServerSubLevel member : connections.docks()) {
            MassData mass = member.isRemoved() ? null : member.getMassTracker();
            if (mass == null || !Double.isFinite(mass.getMass()) || mass.getMass() <= 0.0
                    || mass.getCenterOfMass() == null || !mass.getCenterOfMass().isFinite()
                    || mass.getInertiaTensor() == null || !mass.getInertiaTensor().isFinite()) continue;
            var handle = system.getPhysicsHandle(member);
            if (handle == null || !handle.isValid() || !handle.getLinearVelocity(memberVelocity).isFinite()) continue;
            Pose3d memberPose = member.logicalPose();
            memberPose.transformPosition(mass.getCenterOfMass(), memberCenter);
            pose.transformPositionInverse(memberCenter, memberCenter);
            memberRotation.set(pose.orientation()).conjugate().mul(memberPose.orientation());
            if (!memberCenter.isFinite() || !memberRotation.isFinite()) continue;
            dockedMass.add(mass.getMass(), memberCenter, mass.getInertiaTensor(), memberRotation);
            groupVelocity.fma(mass.getMass(), memberVelocity);
        }
        if (!(dockedMass.mass() > hull.getMass()) || !Double.isFinite(dockedMass.mass())
                || !dockedMass.center().isFinite() || !dockedMass.inertia().isFinite()) return false;
        groupVelocity.div(dockedMass.mass());
        pose.transformPosition(dockedMass.center(), worldCenter);
        return groupVelocity.isFinite() && worldCenter.isFinite();
    }

    public boolean hasDocked() { return docked; }
    public boolean hasTether() { return tetherMassRatio > 0.0; }
    public double mass() { return dockedMass.mass(); }
    public Vector3dc center() { return dockedMass.center(); }
    public Matrix3dc inertia() { return dockedMass.inertia(); }
    public Vector3dc velocity() { return groupVelocity; }
    public double height() { return worldCenter.y; }
    public Set<ServerSubLevel> dockedBodies() { return connections.docks(); }
    public Set<ServerSubLevel> tetheredBodies() { return connections.tethers(); }
    public double tautness() { return connections.tautness(); }
    public double tetherMassRatio() { return tetherMassRatio; }
    public double horizontalRatio() { return tether.horizontalRatio(); }
    public double verticalSupport() { return tether.support(); }

    public double sampleVerticalSpeed(double height, double rawVelocity, double timeStep) {
        // Near rest the rope solver can report a downward velocity even while
        // the pose stays still. Use displacement to confirm a settled capture.
        double speed = previousHeightStep > 0.0 ? (height - previousHeight) / previousHeightStep : rawVelocity;
        previousHeight = height;
        previousHeightStep = timeStep;
        return Math.abs(speed - rawVelocity) <= 0.5 ? speed : rawVelocity;
    }

    public void observe(Vector3dc velocity, double gravity, double maxThrust, boolean canLearnSupport) {
        tether.observe(velocity, gravity, maxThrust, tautness(), tetherMassRatio, canLearnSupport);
    }

    public double verticalResponse(double gravity) {
        double observedRatio = Math.abs(gravity) > 1.0e-6 ? verticalSupport() / Math.abs(gravity) : 0.0;
        return 1.0 / (1.0 + Math.max(observedRatio, tetherMassRatio * tautness()));
    }

    public void slewHorizontalForce(Vector3d force, double hullMass, double timeStep) {
        tether.slewHorizontalForce(force, hullMass, tautness(), timeStep);
    }

    public void recordHoverImpulse(double impulseY) {
        submittedImpulse.y = impulseY;
    }

    public void recordVelocityImpulse(Vector3dc worldForce, double scaleTime) {
        submittedImpulse.fma(scaleTime, worldForce);
    }

    public void finish(Vector3dc velocity, double controlMass, double timeStep) {
        tether.record(velocity, submittedImpulse, controlMass, timeStep);
    }

    private record Connections(Set<ServerSubLevel> docks, Set<ServerSubLevel> tethers, Set<UUID> ropes,
                               double tautness) {
        static final Connections EMPTY = new Connections(Set.of(), Set.of(), Set.of(), 0.0);
    }

    /** Keep optional Simulated classes behind the mod-loaded check. */
    private static final class Simulated {
        static Connections findConnections(ServerSubLevel root) {
            var container = SubLevelContainer.getContainer(root.getLevel());
            var pending = new ArrayDeque<ServerSubLevel>();
            var visited = new HashSet<UUID>();
            var docks = new HashSet<ServerSubLevel>();
            var strands = new HashMap<UUID, ServerRopeStrand>();
            visited.add(root.getUniqueId());
            pending.add(root);
            while (!pending.isEmpty()) {
                ServerSubLevel member = pending.removeFirst();
                for (var actor : member.getPlot().getBlockEntityActors()) {
                    if (actor instanceof DockingConnectorBlockEntity dock && dock.isLocked()) {
                        var other = dock.getOtherConnector();
                        if (other == null || !other.isLocked() || other.getOtherConnector() != dock) continue;
                        UUID id = dock.otherConnectorSubLevelId;
                        // A fixed link to the level or another piloted craft is not passive cargo.
                        if (id == null) return Connections.EMPTY;
                        if (container.getSubLevel(id) instanceof ServerSubLevel body && !body.isRemoved()
                                && visited.add(id)) {
                            if (CoreDirectory.coresOf(body).stream().anyMatch(core -> core.mode() != ControlMode.OFF)) {
                                return Connections.EMPTY;
                            }
                            docks.add(body);
                            pending.add(body);
                        }
                    }
                    if (actor instanceof RopeStrandHolderBlockEntity holder) {
                        var behavior = holder.getBehavior();
                        if (behavior != null) {
                            addStrand(strands, behavior.getOwnedStrand());
                            addStrand(strands, behavior.getAttachedStrand());
                        }
                    }
                }
            }

            var tethers = new HashSet<ServerSubLevel>();
            var ropes = new HashSet<UUID>();
            double tautness = 0.0;
            for (ServerRopeStrand strand : strands.values()) {
                var start = strand.getAttachment(RopeAttachmentPoint.START);
                var end = strand.getAttachment(RopeAttachmentPoint.END);
                if (start == null || end == null) continue;
                boolean startHere = visited.contains(start.subLevelID());
                boolean endHere = visited.contains(end.subLevelID());
                if (startHere == endHere) continue;
                UUID payload = (startHere ? end : start).subLevelID();
                if (payload == null || !(container.getSubLevel(payload) instanceof ServerSubLevel body)
                        || body.isRemoved()) continue;
                tethers.add(body);
                ropes.add(strand.getUUID());
                var points = strand.getPoints();
                if (points.size() < 2) continue;
                double rest = (points.size() - 2) * ServerRopeStrand.SEGMENT_LENGTH + strand.getExtension();
                double chord = points.getFirst().distance(points.getLast());
                if (rest > 0.0 && Double.isFinite(rest) && Double.isFinite(chord)) {
                    // Sag is an indication for thrust slew; vertical support comes from the solver.
                    tautness = Math.max(tautness, Math.clamp((chord / rest - 0.85) / 0.12, 0.0, 1.0));
                }
            }
            return new Connections(Set.copyOf(docks), Set.copyOf(tethers), Set.copyOf(ropes), tautness);
        }

        private static void addStrand(Map<UUID, ServerRopeStrand> strands, ServerRopeStrand strand) {
            if (strand != null && strand.isActive()) strands.put(strand.getUUID(), strand);
        }
    }
}
