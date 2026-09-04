package dev.antiboatfly;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Boat;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.BoundingBox;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class BoatFlyListener implements Listener {
    private final AntiBoatFlyPlugin plugin;
    private final PacketVehicleListener packetListener;
    // Vehicle events can execute concurrently in different Folia regions.
    private final Map<UUID, FlightState> states = new ConcurrentHashMap<>();
    private final Map<UUID, RiderState> riderStates = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAlerts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPunishments = new ConcurrentHashMap<>();

    private volatile int graceTicks;
    private volatile boolean immediateBoatDetection;
    private volatile double minimumFallSpeed;
    private volatile double maximumUpwardAcceleration;
    private volatile double minimumHorizontalMotion;
    private volatile double motionTolerance;
    private volatile int animalJumpGraceTicks;
    private volatile double violationThreshold;
    private volatile double violationDecay;
    private volatile long alertCooldown;
    private volatile String action;

    BoatFlyListener(AntiBoatFlyPlugin plugin, PacketVehicleListener packetListener) {
        this.plugin = plugin;
        this.packetListener = packetListener;
        reloadSettings();
    }

    void reloadSettings() {
        FileConfiguration config = plugin.getConfig();
        graceTicks = Math.max(0, config.getInt("airborne-grace-ticks", 5));
        immediateBoatDetection = config.getBoolean("immediate-boat-detection", false);
        minimumFallSpeed = Math.max(0.0, config.getDouble("minimum-fall-speed", 0.025));
        maximumUpwardAcceleration = Math.max(0.0, config.getDouble("maximum-upward-acceleration", 0.035));
        minimumHorizontalMotion = Math.max(0.0, config.getDouble("minimum-horizontal-motion", 0.055));
        motionTolerance = Math.max(0.05, config.getDouble("player-vehicle-motion-tolerance", 0.30));
        animalJumpGraceTicks = Math.max(0, config.getInt("animal-jump-grace-ticks", 20));
        violationThreshold = Math.max(1.0, config.getDouble("violation-threshold", 6.0));
        violationDecay = Math.max(0.0, config.getDouble("violation-decay", 1.25));
        alertCooldown = Math.max(0L, config.getLong("alert-cooldown-ms", 1500L));
        action = config.getString("action", "dismount").toLowerCase();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        Vehicle vehicle = event.getVehicle();
        Player driver = getController(vehicle);
        if (driver == null || !plugin.shouldCheckPlayer(driver)) {
            states.remove(vehicle.getUniqueId());
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != to.getWorld()) {
            states.remove(vehicle.getUniqueId());
            return;
        }

        FlightState state = states.computeIfAbsent(vehicle.getUniqueId(), ignored -> new FlightState(from.clone()));
        double deltaY = to.getY() - from.getY();
        double deltaX = to.getX() - from.getX();
        double deltaZ = to.getZ() - from.getZ();
        double horizontal = Math.hypot(deltaX, deltaZ);

        boolean supported = hasPhysicalSupport(vehicle);
        boolean collisionSurfaceNearby = supported || hasCollisionSurfaceNearby(vehicle);
        if (supported) {
            state.airborneTicks = 0;
            state.jumpGraceTicks = 0;
            state.violation = Math.max(0.0, state.violation - violationDecay);
            state.lastSafeLocation = to.clone();
            state.lastDeltaY = deltaY;
            state.wasSupported = true;
            packetListener.updateServerState(driver, vehicle, to, true, true, 0);
            return;
        }

        state.airborneTicks++;
        packetListener.updateServerState(driver, vehicle, to, false,
                collisionSurfaceNearby, state.airborneTicks);
        // Packet simulation is the single source of truth for boats. Running the
        // legacy position heuristic as well would double-count legitimate movement.
        if (vehicle instanceof Boat && packetListener.isBoatSimulationEnabled()) {
            state.lastDeltaY = deltaY;
            state.wasSupported = false;
            return;
        }
        if (state.wasSupported && vehicle instanceof LivingEntity && deltaY > 0.0) {
            state.jumpGraceTicks = animalJumpGraceTicks;
        }
        state.wasSupported = false;
        if (state.jumpGraceTicks > 0) {
            state.jumpGraceTicks--;
            state.lastDeltaY = deltaY;
            return;
        }
        if (!(immediateBoatDetection && vehicle instanceof Boat) && state.airborneTicks <= graceTicks) {
            state.lastDeltaY = deltaY;
            return;
        }

        boolean rising = deltaY > 0.01;
        boolean hoveringWhileMoving = deltaY > -minimumFallSpeed && horizontal >= minimumHorizontalMotion;
        boolean acceleratingUpward = deltaY - state.lastDeltaY > maximumUpwardAcceleration;

        // Legitimate airborne boats continuously lose height. Only non-freefall motion adds VL.
        if (rising || hoveringWhileMoving || acceleratingUpward) {
            state.violation += rising ? 1.5 : 1.0;
            if (immediateBoatDetection && vehicle instanceof Boat) {
                punish(driver, vehicle, state.lastSafeLocation,
                        Math.max(violationThreshold, state.violation), "immediate-boat-motion");
                state.violation = 0.0;
                state.airborneTicks = 0;
                return;
            }
        } else {
            state.violation = Math.max(0.0, state.violation - violationDecay);
        }
        state.lastDeltaY = deltaY;

        if (state.violation >= violationThreshold) {
            punish(driver, vehicle, state.lastSafeLocation, state.violation, "vehicle-motion");
            state.violation = 0.0;
            state.airborneTicks = 0;
        }
    }

    private Player getController(Vehicle vehicle) {
        if (vehicle.getPassengers().isEmpty()) {
            return null;
        }
        Entity controller = vehicle.getPassengers().getFirst();
        return controller instanceof Player player && player.getVehicle() == vehicle ? player : null;
    }

    private boolean hasPhysicalSupport(Entity entity) {
        return hasSupportWithin(entity, 1, 0.0);
    }

    private boolean hasCollisionSurfaceNearby(Entity entity) {
        // The next packet can move the boat sideways before Bukkit reports the
        // landing. Include the reachable area so a one-block step-down landing
        // is recognised from the previous authoritative server position.
        return hasSupportWithin(entity, 2, 1.5);
    }

    private boolean hasSupportWithin(Entity entity, int blocksBelow, double horizontalMargin) {
        BoundingBox box = entity.getBoundingBox();
        int minX = floor(box.getMinX() + 0.05 - horizontalMargin);
        int maxX = floor(box.getMaxX() - 0.05 + horizontalMargin);
        int minZ = floor(box.getMinZ() + 0.05 - horizontalMargin);
        int maxZ = floor(box.getMaxZ() - 0.05 + horizontalMargin);
        int feetY = floor(box.getMinY());

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = feetY - blocksBelow; y <= feetY; y++) {
                    Block block = entity.getWorld().getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (block.isLiquid() || type == Material.BUBBLE_COLUMN || (!block.isPassable() && !type.isAir())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int floor(double value) {
        return (int) Math.floor(value);
    }

    /**
     * Mirrors the server-side vehicle check from the rider's movement stream. This catches
     * clients that try to desynchronise/spoof their own mounted motion from the real entity.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!plugin.shouldCheckPlayer(player)) {
            riderStates.remove(player.getUniqueId());
            return;
        }
        if (!(player.getVehicle() instanceof Vehicle vehicle) || !vehicle.getPassengers().contains(player)) {
            riderStates.remove(player.getUniqueId());
            return;
        }
        if (vehicle instanceof Boat && packetListener.isBoatSimulationEnabled()) {
            riderStates.remove(player.getUniqueId());
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        Location vehicleNow = vehicle.getLocation();
        if (from.getWorld() != to.getWorld() || to.getWorld() != vehicleNow.getWorld()) {
            riderStates.remove(player.getUniqueId());
            return;
        }

        RiderState state = riderStates.get(player.getUniqueId());
        if (state == null || !state.vehicleId.equals(vehicle.getUniqueId())) {
            riderStates.put(player.getUniqueId(), new RiderState(vehicle.getUniqueId(), vehicleNow.clone()));
            return;
        }

        double playerDx = to.getX() - from.getX();
        double playerDy = to.getY() - from.getY();
        double playerDz = to.getZ() - from.getZ();
        double vehicleDx = vehicleNow.getX() - state.lastVehicleLocation.getX();
        double vehicleDy = vehicleNow.getY() - state.lastVehicleLocation.getY();
        double vehicleDz = vehicleNow.getZ() - state.lastVehicleLocation.getZ();
        state.lastVehicleLocation = vehicleNow.clone();

        if (hasPhysicalSupport(vehicle)) {
            state.airborneTicks = 0;
            state.jumpGraceTicks = 0;
            state.violation = Math.max(0.0, state.violation - violationDecay);
            state.lastSafeLocation = vehicleNow.clone();
            state.lastPlayerDeltaY = playerDy;
            state.wasSupported = true;
            return;
        }

        state.airborneTicks++;
        if (state.wasSupported && vehicle instanceof LivingEntity && vehicleDy > 0.0) {
            state.jumpGraceTicks = animalJumpGraceTicks;
        }
        state.wasSupported = false;
        if (state.jumpGraceTicks > 0) {
            state.jumpGraceTicks--;
            state.lastPlayerDeltaY = playerDy;
            return;
        }
        if (!(immediateBoatDetection && vehicle instanceof Boat) && state.airborneTicks <= graceTicks) {
            state.lastPlayerDeltaY = playerDy;
            return;
        }

        double differenceX = playerDx - vehicleDx;
        double differenceY = playerDy - vehicleDy;
        double differenceZ = playerDz - vehicleDz;
        double motionDifference = Math.sqrt(differenceX * differenceX + differenceY * differenceY
                + differenceZ * differenceZ);
        double playerHorizontal = Math.hypot(playerDx, playerDz);
        boolean controlsVehicle = !vehicle.getPassengers().isEmpty()
                && vehicle.getPassengers().getFirst().equals(player);
        boolean motionSpoof = motionDifference > motionTolerance;
        boolean playerRising = controlsVehicle && playerDy > 0.01;
        boolean playerHovering = controlsVehicle && playerDy > -minimumFallSpeed
                && playerHorizontal >= minimumHorizontalMotion;
        boolean playerAcceleratingUp = controlsVehicle
                && playerDy - state.lastPlayerDeltaY > maximumUpwardAcceleration;

        if (motionSpoof || playerRising || playerHovering || playerAcceleratingUp) {
            state.violation += motionSpoof ? 2.0 : 1.0;
            if (immediateBoatDetection && vehicle instanceof Boat) {
                String reason = motionSpoof ? "immediate-boat-spoof" : "immediate-rider-motion";
                punish(player, vehicle, state.lastSafeLocation,
                        Math.max(violationThreshold, state.violation), reason);
                state.violation = 0.0;
                state.airborneTicks = 0;
                return;
            }
        } else {
            state.violation = Math.max(0.0, state.violation - violationDecay);
        }
        state.lastPlayerDeltaY = playerDy;

        if (state.violation >= violationThreshold) {
            String reason = motionSpoof ? "player-vehicle-spoof" : "player-motion";
            punish(player, vehicle, state.lastSafeLocation, state.violation, reason);
            state.violation = 0.0;
            state.airborneTicks = 0;
        }
    }

    private void punish(Player player, Vehicle vehicle, Location safeLocation, double violation, String reason) {
        long now = System.currentTimeMillis();
        if (now - lastPunishments.getOrDefault(player.getUniqueId(), 0L) < 1000L) {
            return;
        }
        lastPunishments.put(player.getUniqueId(), now);
        alertStaff(player, violation, reason);

        switch (action) {
            case "teleport" -> {
                Location setback = safeLocation.clone();
                vehicle.getScheduler().run(plugin, task -> {
                    if (vehicle.isValid()) {
                        vehicle.setVelocity(vehicle.getVelocity().zero());
                        vehicle.teleportAsync(setback);
                    }
                }, null);
            }
            case "dismount" -> player.getScheduler().run(plugin, task -> player.leaveVehicle(), null);
            case "none" -> { }
            default -> plugin.getLogger().warning("Unknown action in config.yml: " + action);
        }
    }

    void handlePacketViolation(Player player, String reason, double violation) {
        if (!plugin.shouldCheckPlayer(player)) {
            return;
        }
        if (player.getVehicle() instanceof Vehicle vehicle) {
            FlightState state = states.get(vehicle.getUniqueId());
            Location safe = state == null ? vehicle.getLocation() : state.lastSafeLocation;
            if (vehicle instanceof Boat
                    && (reason.startsWith("boat-simulation") || reason.startsWith("boat-hard-limit"))) {
                Location rollback = safe.clone();
                vehicle.getScheduler().run(plugin, task -> {
                    if (vehicle.isValid()) {
                        vehicle.setVelocity(new org.bukkit.util.Vector());
                        vehicle.teleportAsync(rollback);
                    }
                }, null);
            }
            punish(player, vehicle, safe, violation, reason);
            return;
        }
        alertStaff(player, violation, reason);
    }

    private void alertStaff(Player suspect, double violation, String reason) {
        long now = System.currentTimeMillis();
        long last = lastAlerts.getOrDefault(suspect.getUniqueId(), 0L);
        if (now - last < alertCooldown) {
            return;
        }
        lastAlerts.put(suspect.getUniqueId(), now);

        String message = plugin.getConfig().getString("messages.prefix", "&8[&cAntiBoatFly&8] ")
                + plugin.getConfig().getString("messages.alert", "&f%player% &7BoatFly &8(VL: &c%vl%&8)");
        message = plugin.color(message
                .replace("%player%", suspect.getName())
                .replace("%vl%", String.format("%.1f", violation))
                .replace("%reason%", reason));
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.hasPermission("antiboatfly.alerts")) {
                online.sendMessage(message);
            }
        }
        plugin.getLogger().warning(suspect.getName() + " failed mounted-fly: " + reason
                + " (VL " + String.format("%.1f", violation) + ")");
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player && event.getVehicle() instanceof Vehicle vehicle
                && plugin.shouldCheckPlayer(player)) {
            Location location = vehicle.getLocation();
            boolean supported = hasPhysicalSupport(vehicle);
            packetListener.updateServerState(player, vehicle, location, supported,
                    supported || hasCollisionSurfaceNearby(vehicle), 0);
        }
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        states.remove(event.getVehicle().getUniqueId());
        if (event.getExited() instanceof Player player) {
            riderStates.remove(player.getUniqueId());
            packetListener.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onEntityDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player) {
            riderStates.remove(player.getUniqueId());
            packetListener.remove(player.getUniqueId());
        }
        states.remove(event.getDismounted().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        riderStates.remove(playerId);
        lastAlerts.remove(playerId);
        lastPunishments.remove(playerId);
        packetListener.remove(playerId);
    }

    @EventHandler
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        states.remove(event.getVehicle().getUniqueId());
    }

    @EventHandler
    public void onEntityRemove(EntityRemoveEvent event) {
        UUID entityId = event.getEntity().getUniqueId();
        states.remove(entityId);
        riderStates.entrySet().removeIf(entry -> entry.getValue().vehicleId.equals(entityId));
    }

    int trackedVehicles() {
        return states.size();
    }

    void clear() {
        states.clear();
        riderStates.clear();
        lastAlerts.clear();
        lastPunishments.clear();
        packetListener.clear();
    }

    private static final class FlightState {
        private int airborneTicks;
        private double lastDeltaY;
        private double violation;
        private Location lastSafeLocation;
        private int jumpGraceTicks;
        private boolean wasSupported = true;

        private FlightState(Location initialLocation) {
            this.lastSafeLocation = initialLocation;
        }
    }

    private static final class RiderState {
        private final UUID vehicleId;
        private int airborneTicks;
        private int jumpGraceTicks;
        private double lastPlayerDeltaY;
        private double violation;
        private boolean wasSupported = true;
        private Location lastVehicleLocation;
        private Location lastSafeLocation;

        private RiderState(UUID vehicleId, Location initialVehicleLocation) {
            this.vehicleId = vehicleId;
            this.lastVehicleLocation = initialVehicleLocation;
            this.lastSafeLocation = initialVehicleLocation.clone();
        }
    }

}
