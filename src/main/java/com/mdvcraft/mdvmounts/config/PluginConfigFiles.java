package com.mdvcraft.mdvmounts.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps MDVMounts configuration files current without overwriting existing
 * custom values. Missing keys from bundled defaults are appended on startup
 * and reload. Legacy storage settings from config.yml are migrated once into
 * storage.yml.
 */
public final class PluginConfigFiles {
    private final JavaPlugin plugin;
    private final File storageFile;
    private FileConfiguration storageConfig;

    public PluginConfigFiles(JavaPlugin plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "storage.yml");
    }

    public void initialize() {
        plugin.saveDefaultConfig();
        ensureStorageFile();
        reloadAndUpdate();
    }

    public void reloadAndUpdate() {
        plugin.reloadConfig();
        storageConfig = YamlConfiguration.loadConfiguration(storageFile);

        boolean mainChanged = false;
        boolean storageChanged = false;

        // One-time migration from MDVMounts 1.1.0 and older layouts.
        ConfigurationSection legacyStorage = plugin.getConfig().getConfigurationSection("storage");
        if (legacyStorage != null) {
            copySection(legacyStorage, storageConfig, "", true);
            plugin.getConfig().set("storage", null);
            mainChanged = true;
            storageChanged = true;
            plugin.getLogger().info("Migrado storage: config.yml -> storage.yml.");
        }

        // Storage-specific messages also moved to storage.yml. Preserve custom
        // values from existing installations instead of replacing them.
        ConfigurationSection messages = plugin.getConfig().getConfigurationSection("messages");
        if (messages != null) {
            List<String> legacyMessageKeys = new ArrayList<>();
            for (String key : messages.getKeys(false)) {
                if (key.startsWith("storage-")) {
                    Object value = messages.get(key);
                    if (value != null) {
                        storageConfig.set("messages." + key, value);
                        legacyMessageKeys.add(key);
                        storageChanged = true;
                    }
                }
            }
            for (String key : legacyMessageKeys) {
                messages.set(key, null);
                mainChanged = true;
            }
        }

        YamlConfiguration mainDefaults = loadBundledYaml("config.yml");
        YamlConfiguration storageDefaults = loadBundledYaml("storage.yml");

        mainChanged |= mergeMissing(plugin.getConfig(), mainDefaults);
        storageChanged |= mergeMissing(storageConfig, storageDefaults);

        if (mainChanged) {
            plugin.saveConfig();
        }
        if (storageChanged) {
            saveStorageConfig();
        }
    }

    public FileConfiguration storageConfig() {
        return storageConfig;
    }

    public void saveStorageConfig() {
        if (storageConfig == null) {
            return;
        }
        try {
            storageConfig.save(storageFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("No se pudo guardar storage.yml: " + exception.getMessage());
        }
    }

    private void ensureStorageFile() {
        if (!storageFile.exists()) {
            plugin.saveResource("storage.yml", false);
        }
    }

    private YamlConfiguration loadBundledYaml(String resourceName) {
        InputStream stream = plugin.getResource(resourceName);
        if (stream == null) {
            throw new IllegalStateException("Recurso faltante: " + resourceName);
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo leer " + resourceName, exception);
        }
    }

    private boolean mergeMissing(ConfigurationSection target, ConfigurationSection defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            ConfigurationSection defaultSection = defaults.getConfigurationSection(key);
            if (defaultSection != null) {
                ConfigurationSection targetSection = target.getConfigurationSection(key);
                if (targetSection == null) {
                    target.set(key, null);
                    targetSection = target.createSection(key);
                    changed = true;
                }
                changed |= mergeMissing(targetSection, defaultSection);
                continue;
            }

            if (!target.contains(key)) {
                target.set(key, defaults.get(key));
                changed = true;
            }
        }
        return changed;
    }

    private void copySection(ConfigurationSection source,
                             ConfigurationSection target,
                             String targetPrefix,
                             boolean overwrite) {
        for (String key : source.getKeys(false)) {
            String targetPath = targetPrefix.isEmpty() ? key : targetPrefix + "." + key;
            ConfigurationSection child = source.getConfigurationSection(key);
            if (child != null) {
                copySection(child, target, targetPath, overwrite);
                continue;
            }
            if (overwrite || !target.contains(targetPath)) {
                target.set(targetPath, source.get(key));
            }
        }
    }
}
