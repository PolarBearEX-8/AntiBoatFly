package dev.antiboatfly;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

/** Resolves Bedrock players through optional Floodgate or Geyser APIs. */
final class BedrockPlayerDetector {
    private final Detector floodgate;
    private final Detector geyser;

    BedrockPlayerDetector(AntiBoatFlyPlugin plugin) {
        floodgate = createDetector(plugin, "floodgate",
                "org.geysermc.floodgate.api.FloodgateApi", "getInstance", "isFloodgatePlayer");
        geyser = createDetector(plugin, "Geyser-Spigot",
                "org.geysermc.geyser.api.GeyserApi", "api", "isBedrockPlayer");
    }

    boolean isBedrockPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        return matches(floodgate, playerId) || matches(geyser, playerId);
    }

    private boolean matches(Detector detector, UUID playerId) {
        if (detector == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(detector.checkMethod.invoke(detector.api, playerId));
        } catch (IllegalAccessException | InvocationTargetException ignored) {
            return false;
        }
    }

    private Detector createDetector(AntiBoatFlyPlugin owner, String pluginName,
                                    String apiClassName, String accessorName, String checkName) {
        Plugin dependency = owner.getServer().getPluginManager().getPlugin(pluginName);
        if (dependency == null || !dependency.isEnabled()) {
            return null;
        }
        try {
            Class<?> apiClass = Class.forName(apiClassName, true, dependency.getClass().getClassLoader());
            Method accessor = apiClass.getMethod(accessorName);
            Object api = accessor.invoke(null);
            Method check = apiClass.getMethod(checkName, UUID.class);
            return new Detector(api, check);
        } catch (ReflectiveOperationException | LinkageError exception) {
            owner.getLogger().warning("Could not connect to " + pluginName
                    + " for Geyser-only mode: " + exception.getClass().getSimpleName());
            return null;
        }
    }

    private record Detector(Object api, Method checkMethod) {
    }
}
