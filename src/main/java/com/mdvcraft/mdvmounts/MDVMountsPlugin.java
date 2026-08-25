package com.mdvcraft.mdvmounts;

import com.mdvcraft.mdvmounts.command.MDVMountsCommand;
import com.mdvcraft.mdvmounts.listener.InvokerStorageListener;
import com.mdvcraft.mdvmounts.listener.MountListener;
import com.mdvcraft.mdvmounts.mount.MountManager;
import com.mdvcraft.mdvmounts.storage.InvokerStorageManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MDVMountsPlugin extends JavaPlugin {
    private MountManager mountManager;
    private InvokerStorageManager storageManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        mountManager = new MountManager(this);
        storageManager = new InvokerStorageManager(this);

        getServer().getPluginManager().registerEvents(new InvokerStorageListener(storageManager), this);
        getServer().getPluginManager().registerEvents(new MountListener(this, mountManager, storageManager), this);

        PluginCommand command = getCommand("mdvmounts");
        if (command != null) {
            MDVMountsCommand executor = new MDVMountsCommand(this, mountManager);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        mountManager.start();
        getLogger().info("MDVMounts 1.1.0 habilitado. Movilidad estable + alforjas ligadas al invocador.");
    }

    @Override
    public void onDisable() {
        if (storageManager != null) {
            storageManager.shutdown();
        }
        if (mountManager != null) {
            mountManager.shutdown();
        }
    }

    public void reloadPluginConfig() {
        reloadConfig();
        mountManager.reloadSettings();
        storageManager.reloadSettings();
    }
}
