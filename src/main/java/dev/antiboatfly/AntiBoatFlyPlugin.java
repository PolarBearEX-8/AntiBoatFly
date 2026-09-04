package dev.antiboatfly;

import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class AntiBoatFlyPlugin extends JavaPlugin implements TabExecutor {
    private BoatFlyListener listener;
    private PacketVehicleListener packetListener;
    private volatile BedrockPlayerDetector bedrockDetector;
    private volatile String targetMode;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getConfig().isSet("target-mode")) {
            getConfig().set("target-mode", "all");
            saveConfig();
        }
        reloadTargetMode();
        packetListener = new PacketVehicleListener(this);
        listener = new BoatFlyListener(this, packetListener);
        packetListener.setViolationHandler(listener);
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);
        getServer().getPluginManager().registerEvents(listener, this);
        if (getCommand("antiboatfly") != null) {
            getCommand("antiboatfly").setExecutor(this);
            getCommand("antiboatfly").setTabCompleter(this);
        }
        getLogger().info("AntiBoatFly enabled.");
    }

    @Override
    public void onDisable() {
        if (listener != null) {
            listener.clear();
        }
        if (packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            packetListener.clear();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("antiboatfly.admin")) {
            sender.sendMessage(color("&cYou do not have permission to use this command."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            reloadTargetMode();
            listener.reloadSettings();
            packetListener.reloadSettings();
            sender.sendMessage(color("&aAntiBoatFly configuration reloaded."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(color("&aAntiBoatFly is active &7| Mode: " + targetMode
                    + " &7| Tracked vehicles: " + listener.trackedVehicles()));
            return true;
        }
        sender.sendMessage(color("&eUsage: /" + label + " <reload|status>"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("reload", "status") : List.of();
    }

    String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    boolean shouldCheckPlayer(org.bukkit.entity.Player player) {
        if (player.hasPermission("antiboatfly.bypass")) {
            return false;
        }
        return !"geyser".equals(targetMode) || bedrockDetector.isBedrockPlayer(player);
    }

    private void reloadTargetMode() {
        String configured = getConfig().getString("target-mode", "all").toLowerCase(java.util.Locale.ROOT);
        if (!configured.equals("all") && !configured.equals("geyser")) {
            getLogger().warning("Unknown target-mode '" + configured + "'; using 'all'.");
            configured = "all";
        }
        targetMode = configured;
        bedrockDetector = new BedrockPlayerDetector(this);
    }
}
