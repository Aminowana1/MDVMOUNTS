package com.mdvcraft.mdvmounts;

import com.mdvcraft.mdvmounts.command.MDVMountsCommand;
import com.mdvcraft.mdvmounts.compat.ProtocolLibCamelJumpBlocker;
import com.mdvcraft.mdvmounts.config.PluginConfigFiles;
import com.mdvcraft.mdvmounts.listener.InvokerStorageListener;
import com.mdvcraft.mdvmounts.listener.MountListener;
import com.mdvcraft.mdvmounts.listener.MountSkillListener;
import com.mdvcraft.mdvmounts.mount.MountManager;
import com.mdvcraft.mdvmounts.storage.InvokerStorageManager;
import com.mdvcraft.mdvmounts.skill.MountSkillManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class MDVMountsPlugin extends JavaPlugin {
    private PluginConfigFiles configFiles;
    private MountManager mountManager;
    private InvokerStorageManager storageManager;
    private MountSkillManager mountSkillManager;
    private ProtocolLibCamelJumpBlocker camelJumpPacketBlocker;

    @Override
    public void onEnable() {
        configFiles = new PluginConfigFiles(this);
        configFiles.initialize();

        mountManager = new MountManager(this);
        storageManager = new InvokerStorageManager(this);
        mountSkillManager = new MountSkillManager(this, mountManager);

        getServer().getPluginManager().registerEvents(new InvokerStorageListener(storageManager), this);
        getServer().getPluginManager().registerEvents(new MountListener(this, mountManager), this);
        getServer().getPluginManager().registerEvents(new MountSkillListener(mountSkillManager), this);

        PluginCommand command = getCommand("mdvmounts");
        if (command != null) {
            MDVMountsCommand executor = new MDVMountsCommand(this, mountManager);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        mountManager.start();

        if (getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            try {
                camelJumpPacketBlocker = new ProtocolLibCamelJumpBlocker(this, mountManager);
                camelJumpPacketBlocker.start();
                getLogger().info("Anti-dash de camello: bloqueo de paquetes ProtocolLib activo.");
            } catch (Throwable throwable) {
                camelJumpPacketBlocker = null;
                getLogger().severe("No se pudo activar el bloqueo de paquetes del camello: "
                        + throwable.getMessage());
            }
        } else {
            getLogger().warning("ProtocolLib no está instalado. El camello usará solo las capas "
                    + "Bukkit de respaldo; para bloquear el dash vanilla antes de que Minecraft "
                    + "lo procese, instala ProtocolLib 5.4.0 o superior.");
        }

        getLogger().info("MDVMounts 1.1.12 habilitado. Anti-dash por paquete, trepado, agua y protección de caída.");
    }

    @Override
    public void onDisable() {
        if (camelJumpPacketBlocker != null) {
            camelJumpPacketBlocker.stop();
            camelJumpPacketBlocker = null;
        }
        if (mountSkillManager != null) {
            mountSkillManager.shutdown();
        }
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
        mountSkillManager.reloadSettings();
    }
}
