package com.mdvcraft.mdvmounts.command;

import com.mdvcraft.mdvmounts.MDVMountsPlugin;
import com.mdvcraft.mdvmounts.mount.MountManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import java.util.List;

public final class MDVMountsCommand implements CommandExecutor, TabCompleter {
    private final MDVMountsPlugin plugin;
    private final MountManager mountManager;

    public MDVMountsCommand(MDVMountsPlugin plugin, MountManager mountManager) {
        this.plugin = plugin;
        this.mountManager = mountManager;
    }

    @Override
    public boolean onCommand(CommandSender sender,
                             Command command,
                             String label,
                             String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "/mdvmounts reload" + ChatColor.GRAY + " - recarga config + storage");
            sender.sendMessage(ChatColor.YELLOW + "/mdvmounts status" + ChatColor.GRAY + " - sesiones activas");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadPluginConfig();
            String raw = plugin.getConfig().getString("messages.reloaded", "&aMDVMounts recargado.");
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', raw == null ? "&aMDVMounts recargado." : raw));
            return true;
        }

        if (args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(ChatColor.GOLD + "MDVMounts" + ChatColor.GRAY + " - monturas activas: "
                    + ChatColor.WHITE + mountManager.activeCount());
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender,
                                                 Command command,
                                                 String alias,
                                                 String[] args) {
        if (args.length == 1) {
            return List.of("reload", "status").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
