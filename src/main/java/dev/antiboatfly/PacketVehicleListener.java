package dev.antiboatfly;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientVehicleMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerVehicleMove;
import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Validates the client VEHICLE_MOVE stream before Bukkit applies it. */
final class PacketVehicleListener extends PacketListenerAbstract {
    private final AntiBoatFlyPlugin plugin;
    private final Map<UUID, PacketState> states = new ConcurrentHashMap<>();
    private volatile BoatFlyListener violationHandler;
    private volatile boolean enabled;
    private volatile double maximumServerDistanceSquared;
    private volatile double maximumPacketDisplacementSquared;
    private volatile double violationThreshold;
    private volatile double violationDecay;
    private volatile int airborneGraceTicks;
    private volatile double minimumFallSpeed;
    private volatile double minimumHorizontalMotion;
    private volatile boolean immediateBoatDetection;
    private volatile boolean simulationEnabled;
    private volatile boolean cancelInvalidSimulation;
    private volatile double maximumBoatUpwardMovement;
    private volatile double maximumBoatHorizontalMovement;
    private volatile double maximumBoatDownwardMovement;
    private volatile BoatSimulation boatSimulation;

    PacketVehicleListener(AntiBoatFlyPlugin plugin) {
        super(PacketListenerPriority.HIGHEST);
        this.plugin = plugin;
        reloadSettings();
    }

    void setViolationHandler(BoatFlyListener violationHandler) {
        this.violationHandler = violationHandler;
    }

    void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("packet-check.enabled", true);
        double serverDistance = Math.max(0.5,
                plugin.getConfig().getDouble("packet-check.maximum-server-distance", 3.0));
        double packetDistance = Math.max(0.5,
                plugin.getConfig().getDouble("packet-check.maximum-packet-displacement", 4.0));
        maximumServerDistanceSquared = serverDistance * serverDistance;
        maximumPacketDisplacementSquared = packetDistance * packetDistance;
        violationThreshold = Math.max(1.0,
                plugin.getConfig().getDouble("packet-check.violation-threshold", 6.0));
        violationDecay = Math.max(0.0, plugin.getConfig().getDouble("packet-check.violation-decay", 0.75));
        airborneGraceTicks = Math.max(0, plugin.getConfig().getInt("airborne-grace-ticks", 5));
        minimumFallSpeed = Math.max(0.0, plugin.getConfig().getDouble("minimum-fall-speed", 0.025));
        minimumHorizontalMotion = Math.max(0.0,
                plugin.getConfig().getDouble("minimum-horizontal-motion", 0.055));
        immediateBoatDetection = plugin.getConfig().getBoolean(
                "packet-check.cancel-immediately-for-boats", false);
        simulationEnabled = plugin.getConfig().getBoolean("boat-simulation.enabled", true);
        cancelInvalidSimulation = plugin.getConfig().getBoolean(
                "boat-simulation.cancel-invalid-packets", true);
        maximumBoatUpwardMovement = Math.max(0.01,
                plugin.getConfig().getDouble("boat-simulation.maximum-packet-upward-movement", 0.12));
        maximumBoatHorizontalMovement = Math.max(0.05,
                plugin.getConfig().getDouble("boat-simulation.maximum-packet-horizontal-movement", 1.25));
        maximumBoatDownwardMovement = Math.max(0.05,
                plugin.getConfig().getDouble("boat-simulation.maximum-packet-downward-movement", 4.0));
        boatSimulation = new BoatSimulation(
                Math.max(0.0, plugin.getConfig().getDouble("boat-simulation.gravity-per-tick", 0.04)),
                Math.max(0.0, Math.min(1.0,
                        plugin.getConfig().getDouble("boat-simulation.horizontal-drag", 0.90))),
                Math.max(0.10,
                        plugin.getConfig().getDouble("boat-simulation.vertical-offset-tolerance", 0.10)),
                Math.max(0.05,
                        plugin.getConfig().getDouble("boat-simulation.horizontal-offset-tolerance", 0.35))
        );
    }

    void updateServerState(Player player, Vehicle vehicle, Location location, boolean supported,
                           boolean collisionSurfaceNearby, int airborneTicks) {
        if (!plugin.shouldCheckPlayer(player)) {
            states.remove(player.getUniqueId());
            return;
        }
        PacketState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new PacketState());
        UUID previousVehicleId = state.vehicleId;
        Vector velocity = vehicle.getVelocity();
        state.mounted = true;
        state.vehicleId = vehicle.getUniqueId();
        state.boat = vehicle instanceof Boat;
        state.serverX = location.getX();
        state.serverY = location.getY();
        state.serverZ = location.getZ();
        state.serverYaw = location.getYaw();
        state.serverPitch = location.getPitch();
        state.serverVelocityX = velocity.getX();
        state.serverVelocityY = velocity.getY();
        state.serverVelocityZ = velocity.getZ();
        state.supported = supported;
        state.collisionSurfaceNearby = collisionSurfaceNearby;
        state.airborneTicks = airborneTicks;
        state.lastServerUpdateNanos = System.nanoTime();
        if (!state.simulationInitialized || supported || !vehicle.getUniqueId().equals(previousVehicleId)) {
            synchronized (state) {
                state.simulationX = location.getX();
                state.simulationY = location.getY();
                state.simulationZ = location.getZ();
                state.simulationVelocityX = velocity.getX();
                state.simulationVelocityY = velocity.getY();
                state.simulationVelocityZ = velocity.getZ();
                state.simulationNanos = state.lastServerUpdateNanos;
                state.simulationInitialized = true;
            }
        }
    }

    void remove(UUID playerId) {
        states.remove(playerId);
    }

    boolean isBoatSimulationEnabled() {
        return simulationEnabled;
    }

    void clear() {
        states.clear();
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        if (!plugin.shouldCheckPlayer(player)) {
            states.remove(player.getUniqueId());
            return;
        }

        UUID playerId = player.getUniqueId();
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            PacketState mountedState = states.get(playerId);
            if (mountedState != null && mountedState.mounted
                    && System.nanoTime() - mountedState.lastServerUpdateNanos <= 2_000_000_000L) {
                // Geyser may translate Bedrock movement differently. Boat position is
                // validated exclusively through VEHICLE_MOVE and the boat simulation.
                if (mountedState.boat) {
                    return;
                }
                mountedState.violation += 2.0;
                if ((immediateBoatDetection && mountedState.boat)
                        || mountedState.violation >= violationThreshold) {
                    event.setCancelled(true);
                    double finalViolation = Math.max(violationThreshold, mountedState.violation);
                    mountedState.violation = 0.0;
                    dispatchViolation(player, "player-position-while-mounted", finalViolation);
                }
            }
            return;
        }
        if (event.getPacketType() != PacketType.Play.Client.VEHICLE_MOVE) {
            return;
        }

        PacketState state = states.get(playerId);
        // Geyser and normal network ordering can leave VEHICLE_MOVE packets in
        // flight after a dismount. Without a recent authoritative server mount
        // these packets cannot control an entity, so they are safely ignored.
        if (state == null || !state.mounted
                || System.nanoTime() - state.lastServerUpdateNanos > 2_000_000_000L) {
            return;
        }
        WrapperPlayClientVehicleMove wrapper = new WrapperPlayClientVehicleMove(event);
        Vector3d position = wrapper.getPosition();
        double x = position.getX();
        double y = position.getY();
        double z = position.getZ();

        String reason = null;
        double addedViolation = 0.0;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            reason = "invalid-vehicle-position";
            addedViolation = violationThreshold;
        } else {
            if (simulationEnabled && state.boat) {
                synchronized (state) {
                    if (!state.simulationInitialized) {
                        state.simulationX = state.serverX;
                        state.simulationY = state.serverY;
                        state.simulationZ = state.serverZ;
                        state.simulationVelocityX = state.serverVelocityX;
                        state.simulationVelocityY = state.serverVelocityY;
                        state.simulationVelocityZ = state.serverVelocityZ;
                        state.simulationNanos = System.nanoTime();
                        state.simulationInitialized = true;
                    }

                    long packetNanos = System.nanoTime();
                    int elapsedTicks = elapsedPacketTicks(state.simulationNanos, packetNanos);
                    double packetDeltaX = x - state.simulationX;
                    double packetDeltaY = y - state.simulationY;
                    double packetDeltaZ = z - state.simulationZ;
                    double packetHorizontal = Math.hypot(packetDeltaX, packetDeltaZ);
                    double allowedUpward = maximumBoatUpwardMovement * elapsedTicks;
                    double allowedDownward = maximumBoatDownwardMovement * elapsedTicks;
                    double allowedHorizontal = maximumBoatHorizontalMovement * elapsedTicks;
                    // Water buoyancy and surface collisions can legitimately launch a
                    // supported boat upward. Apply the strict upward cap only after the
                    // server has confirmed that the boat is actually airborne.
                    boolean upwardExceeded = !state.supported && !state.collisionSurfaceNearby
                            && packetDeltaY > allowedUpward;
                    boolean downwardExceeded = -packetDeltaY > allowedDownward;
                    boolean horizontalExceeded = packetHorizontal > allowedHorizontal;
                    if (upwardExceeded || downwardExceeded || horizontalExceeded) {
                        String hardLimitReason = upwardExceeded
                                ? "boat-hard-limit-upward"
                                : (downwardExceeded
                                ? "boat-hard-limit-downward"
                                : "boat-hard-limit-horizontal");
                        cancelAndCorrect(event, player, state, hardLimitReason);
                        return;
                    }

                    if (!state.supported && state.airborneTicks > 0) {
                        BoatSimulation.Result simulation = boatSimulation.validate(
                                state.simulationX, state.simulationY, state.simulationZ,
                                state.simulationVelocityX, state.simulationVelocityY,
                                state.simulationVelocityZ, x, y, z, elapsedTicks
                        );
                        boolean legitimateCollisionBounce = !simulation.valid()
                                && state.collisionSurfaceNearby
                                && simulation.reason().startsWith("boat-simulation-vertical");
                        if (!simulation.valid() && !legitimateCollisionBounce) {
                            if (cancelInvalidSimulation) {
                                cancelAndCorrect(event, player, state, simulation.reason());
                            } else {
                                state.violation = 0.0;
                                dispatchViolation(player, simulation.reason(), violationThreshold);
                            }
                            return;
                        }

                        double observedVelocityY = (y - state.simulationY) / elapsedTicks;
                        if (legitimateCollisionBounce) {
                            // The packet can reach us before Bukkit reports the actual
                            // collision. Rebase only when the region thread previously
                            // confirmed a surface directly below the boat.
                            state.simulationVelocityX = packetDeltaX / elapsedTicks;
                            state.simulationVelocityY = observedVelocityY;
                            state.simulationVelocityZ = packetDeltaZ / elapsedTicks;
                        } else {
                            state.simulationVelocityX = simulation.predictedVelocityX();
                            // A faster downward movement is safe and becomes the new baseline.
                            state.simulationVelocityY = Math.min(
                                    observedVelocityY, simulation.predictedVelocityY());
                            state.simulationVelocityZ = simulation.predictedVelocityZ();
                        }
                    } else {
                        // Learn the translated Bedrock/Java vehicle motion while grounded
                        // and during the short water-to-air transition.
                        state.simulationVelocityX = (x - state.simulationX) / elapsedTicks;
                        state.simulationVelocityY = (y - state.simulationY) / elapsedTicks;
                        state.simulationVelocityZ = (z - state.simulationZ) / elapsedTicks;
                    }

                    state.simulationX = x;
                    state.simulationY = y;
                    state.simulationZ = z;
                    state.simulationNanos = packetNanos;
                }
            }

            // Boat packets already passed the hard limits and physics simulation.
            // Applying the generic server-distance accumulator as well causes
            // false positives when Geyser batches several movement ticks.
            if (!(simulationEnabled && state.boat)) {
                double serverDistanceSquared = distanceSquared(
                        x, y, z, state.serverX, state.serverY, state.serverZ);
                if (serverDistanceSquared > maximumServerDistanceSquared) {
                    reason = "vehicle-position-spoof";
                    addedViolation = 2.0;
                }
            }

            if (state.hasPacketPosition && !(simulationEnabled && state.boat)) {
                double dx = x - state.lastPacketX;
                double dy = y - state.lastPacketY;
                double dz = z - state.lastPacketZ;
                double horizontal = Math.hypot(dx, dz);
                if (dx * dx + dy * dy + dz * dz > maximumPacketDisplacementSquared) {
                    reason = "vehicle-packet-speed";
                    addedViolation = Math.max(addedViolation, 2.0);
                }
                if (!(simulationEnabled && state.boat) && !state.supported
                        && ((immediateBoatDetection && state.boat)
                        || state.airborneTicks > airborneGraceTicks)) {
                    if (wrapper.isOnGround()) {
                        reason = "vehicle-ground-spoof";
                        addedViolation = Math.max(addedViolation, 1.5);
                    }
                    if (dy > 0.01 || (dy > -minimumFallSpeed && horizontal >= minimumHorizontalMotion)) {
                        reason = "vehicle-packet-non-freefall";
                        addedViolation = Math.max(addedViolation, 1.0);
                    }
                }
            }
        }

        if (addedViolation > 0.0) {
            state.violation += addedViolation;
        } else {
            state.violation = Math.max(0.0, state.violation - violationDecay);
        }
        if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
            state.lastPacketX = x;
            state.lastPacketY = y;
            state.lastPacketZ = z;
            state.hasPacketPosition = true;
        }

        if (addedViolation > 0.0 && immediateBoatDetection && state.boat) {
            event.setCancelled(true);
            double finalViolation = Math.max(violationThreshold, state.violation);
            String finalReason = reason == null ? "immediate-boat-packet" : reason;
            state.violation = 0.0;
            dispatchViolation(player, finalReason, finalViolation);
            return;
        }

        if (state.violation >= violationThreshold) {
            event.setCancelled(true);
            double finalViolation = state.violation;
            String finalReason = reason == null ? "vehicle-packet-spoof" : reason;
            state.violation = 0.0;
            dispatchViolation(player, finalReason, finalViolation);
        }
    }

    private void dispatchViolation(Player player, String reason, double violation) {
        BoatFlyListener handler = violationHandler;
        if (handler != null) {
            player.getScheduler().run(plugin,
                    task -> handler.handlePacketViolation(player, reason, violation), null);
        }
    }

    private void cancelAndCorrect(PacketReceiveEvent event, Player player, PacketState state, String reason) {
        event.setCancelled(true);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                new WrapperPlayServerVehicleMove(
                        new Vector3d(state.simulationX, state.simulationY, state.simulationZ),
                        state.serverYaw, state.serverPitch));
        state.violation = 0.0;
        dispatchViolation(player, reason, violationThreshold);
    }

    private int elapsedPacketTicks(long previousNanos, long currentNanos) {
        if (previousNanos <= 0L || currentNanos <= previousNanos) {
            return 1;
        }
        long elapsed = currentNanos - previousNanos;
        long roundedTicks = (elapsed + 25_000_000L) / 50_000_000L;
        return (int) Math.max(1L, Math.min(10L, roundedTicks));
    }

    private double distanceSquared(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    private static final class PacketState {
        private volatile boolean mounted;
        private volatile UUID vehicleId;
        private volatile boolean boat;
        private volatile double serverX;
        private volatile double serverY;
        private volatile double serverZ;
        private volatile float serverYaw;
        private volatile float serverPitch;
        private volatile double serverVelocityX;
        private volatile double serverVelocityY;
        private volatile double serverVelocityZ;
        private volatile boolean supported;
        private volatile boolean collisionSurfaceNearby;
        private volatile int airborneTicks;
        private volatile long lastServerUpdateNanos;
        private boolean hasPacketPosition;
        private double lastPacketX;
        private double lastPacketY;
        private double lastPacketZ;
        private double violation;
        private boolean simulationInitialized;
        private double simulationX;
        private double simulationY;
        private double simulationZ;
        private double simulationVelocityX;
        private double simulationVelocityY;
        private double simulationVelocityZ;
        private long simulationNanos;
    }
}
