package me.hurtful.lootFilter;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class LootFilter extends JavaPlugin implements Listener, TabCompleter {

    private boolean pluginEnabled = true;
    private Map<UUID, Set<Material>> playerPickupPreferences = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        // Register the tab completer
        getCommand("lootfilter").setTabCompleter(this);

        getLogger().info("LootFilter plugin has been enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("LootFilter plugin has been disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;
        UUID playerId = player.getUniqueId();

        if (command.getName().equalsIgnoreCase("lootfilter")) {
            if (args.length == 0) {
                sendHelpMessage(player);
                return true;
            }

            String subCommand = args[0].toLowerCase();

            switch (subCommand) {
                case "toggle":
                    pluginEnabled = !pluginEnabled;
                    player.sendMessage("§aLoot filtering is now " + (pluginEnabled ? "enabled" : "disabled") + ".");
                    return true;

                case "add":
                    if (args.length < 2) {
                        player.sendMessage("§cUsage: /lootfilter add <material>");
                        return true;
                    }

                    try {
                        Material material = Material.valueOf(args[1].toUpperCase());

                        // Initialize the set if it doesn't exist
                        playerPickupPreferences.computeIfAbsent(playerId, k -> new HashSet<>());

                        // Add the material to the player's preferences
                        playerPickupPreferences.get(playerId).add(material);
                        player.sendMessage("§aAdded " + material.toString() + " to your pickup list.");
                    } catch (IllegalArgumentException e) {
                        player.sendMessage("§cInvalid material name: " + args[1]);
                    }
                    return true;

                case "remove":
                    if (args.length < 2) {
                        player.sendMessage("§cUsage: /lootfilter remove <material>");
                        return true;
                    }

                    try {
                        Material material = Material.valueOf(args[1].toUpperCase());

                        // Check if the player has any preferences
                        if (playerPickupPreferences.containsKey(playerId)) {
                            playerPickupPreferences.get(playerId).remove(material);
                            player.sendMessage("§aRemoved " + material.toString() + " from your pickup list.");
                        } else {
                            player.sendMessage("§cYou don't have any materials in your pickup list.");
                        }
                    } catch (IllegalArgumentException e) {
                        player.sendMessage("§cInvalid material name: " + args[1]);
                    }
                    return true;

                case "clear":
                    playerPickupPreferences.remove(playerId);
                    player.sendMessage("§aCleared your pickup list.");
                    return true;

                case "list":
                    Set<Material> preferences = playerPickupPreferences.getOrDefault(playerId, new HashSet<>());

                    if (preferences.isEmpty()) {
                        player.sendMessage("§cYou don't have any materials in your pickup list.");
                    } else {
                        player.sendMessage("§aYour pickup list:");
                        for (Material material : preferences) {
                            player.sendMessage("§7- §f" + material.toString());
                        }
                    }
                    return true;

                default:
                    sendHelpMessage(player);
                    return true;
            }
        }

        return false;
    }

    @EventHandler
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        // If the plugin is disabled, let vanilla mechanics handle it
        if (!pluginEnabled) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Item item = event.getItem();
        Material material = item.getItemStack().getType();

        // If the player has preferences and the item is not in their list, cancel the pickup
        if (playerPickupPreferences.containsKey(playerId) && !playerPickupPreferences.get(playerId).contains(material)) {
            event.setCancelled(true);
        }
    }

    private void sendHelpMessage(Player player) {
        player.sendMessage("§6LootFilter Commands:");
        player.sendMessage("§7/lootfilter toggle §f- Enable/disable the plugin");
        player.sendMessage("§7/lootfilter add <material> §f- Add a material to your pickup list");
        player.sendMessage("§7/lootfilter remove <material> §f- Remove a material from your pickup list");
        player.sendMessage("§7/lootfilter clear §f- Clear your pickup list");
        player.sendMessage("§7/lootfilter list §f- Show your pickup list");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // First argument - provide subcommands
            String[] subCommands = {"toggle", "add", "remove", "clear", "list"};
            for (String subCommand : subCommands) {
                if (subCommand.startsWith(args[0].toLowerCase())) {
                    completions.add(subCommand);
                }
            }
        } else if (args.length == 2) {
            // Second argument - provide material names for add/remove commands
            if (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove")) {
                String partialMaterial = args[1].toUpperCase();
                for (Material material : Material.values()) {
                    if (material.name().startsWith(partialMaterial)) {
                        // Only suggest blocks/items that make sense to pick up
                        if (material.isBlock() || material.isItem()) {
                            completions.add(material.name());
                        }
                    }
                }
            }
        }

        return completions;
    }
}