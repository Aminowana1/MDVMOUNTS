package com.mdvcraft.mdvmounts;

import com.mdvcraft.mdvmounts.command.MDVMountsCommand;
import com.mdvcraft.mdvmounts.config.PluginConfigFiles;
import com.mdvcraft.mdvmounts.listener.InvokerStorageListener;
import com.mdvcraft.mdvmounts.listener.MountListener;
import com.mdvcraft.mdvmounts.mount.MountManager;
import com.mdvcraft.mdvmounts.storage.InvokerStorageManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class MDVMountsPlugin extends JavaPlugin {
    private PluginConfigFiles configFiles;
    private MountManager mountManager;
    private InvokerStorageManager storageManager;

    @Override
    public void onEnable() {
        configFiles = new PluginConfigFiles(this);
        configFiles.initialize();

        mountManager = new MountManager(this);
        storageManager = new InvokerStorageManager(this);

        getServer().getPluginManager().registerEvents(new InvokerStorageListener(storageManager), this);
        getServer().getPluginManager().registerEvents(new MountListener(this, mountManager), this);

        PluginCommand command = getCommand("mdvmounts");
        if (command != null) {
            MDVMountsCommand executor = new MDVMountsCommand(this, mountManager);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        mountManager.start();
        getLogger().info("MDVMounts 1.1.3 habilitado. Movilidad estable + almacenamiento por invocador.");
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

    public FileConfiguration getStorageConfig() {
        return configFiles.storageConfig();
    }

    public void reloadPluginConfig() {
        configFiles.reloadAndUpdate();
        mountManager.reloadSettings();
        storageManager.reloadSettings();
    }
}
