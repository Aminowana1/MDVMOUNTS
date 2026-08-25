package com.mdvcraft.mdvmounts;

import com.mdvcraft.mdvmounts.command.MDVMountsCommand;
import com.mdvcraft.mdvmounts.listener.MountListener;
import com.mdvcraft.mdvmounts.mount.MountManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MDVMountsPlugin extends JavaPlugin {
    private MountManager mountManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        mountManager = new MountManager(this);
        getServer().getPluginManager().registerEvents(new MountListener(this, mountManager), this);

        PluginCommand command = getCommand("mdvmounts");
        if (command != null) {
            MDVMountsCommand executor = new MDVMountsCommand(this, mountManager);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        mountManager.start();
        getLogger().info("MDVMounts 1.0.0 habilitado. Control por tags y velocidad nativa de la entidad.");
    }

    @Override
    public void onDisable() {
        if (mountManager != null) {
            mountManager.shutdown();
        }
    }

    public void reloadPluginConfig() {
        reloadConfig();
        mountManager.reloadSettings();
    }
}
