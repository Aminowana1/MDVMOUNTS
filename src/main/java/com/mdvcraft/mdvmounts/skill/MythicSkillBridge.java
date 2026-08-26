package com.mdvcraft.mdvmounts.skill;

import com.mdvcraft.mdvmounts.MDVMountsPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;

/**
 * Tiny reflection bridge to MythicMobs.
 *
 * MDVMounts stays buildable without a hard MythicMobs dependency while still
 * using the official Bukkit API helper when MythicMobs is present at runtime.
 * Reflection discovery happens once; skill activations only invoke the cached
 * method.
 */
public final class MythicSkillBridge {
    private final MDVMountsPlugin plugin;

    private Object apiHelper;
    private Method castWithTrigger;
    private Method castSimple;
    private boolean available;

    public MythicSkillBridge(MDVMountsPlugin plugin) {
        this.plugin = plugin;
        initialize();
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean cast(Entity caster, Entity trigger, String skillName, Location origin) {
        if (!available || caster == null || skillName == null || skillName.isBlank()) {
            return false;
        }

        Location effectiveOrigin = origin == null ? caster.getLocation() : origin;

        try {
            if (castWithTrigger != null) {
                Object result = castWithTrigger.invoke(
                        apiHelper,
                        caster,
                        skillName,
                        trigger,
                        effectiveOrigin,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        1.0F
                );
                return !(result instanceof Boolean bool) || bool;
            }

            if (castSimple != null) {
                Object result = castSimple.invoke(apiHelper, caster, skillName);
                return !(result instanceof Boolean bool) || bool;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning(
                    "No se pudo ejecutar la skill MythicMobs '" + skillName + "': " + exception.getMessage()
            );
        }

        return false;
    }

    private void initialize() {
        Plugin mythic = plugin.getServer().getPluginManager().getPlugin("MythicMobs");
        if (mythic == null || !mythic.isEnabled()) {
            available = false;
            return;
        }

        try {
            Class<?> mythicBukkitClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Method instMethod = mythicBukkitClass.getMethod("inst");
            Object mythicBukkit = instMethod.invoke(null);
            Method helperMethod = mythicBukkitClass.getMethod("getAPIHelper");
            apiHelper = helperMethod.invoke(mythicBukkit);

            Class<?> helperClass = apiHelper.getClass();

            // Preferred overload: the ridden mount is the caster and the
            // rider is MythicMobs' trigger. This keeps @trigger useful in
            // mount skills without forcing targets from Java.
            try {
                castWithTrigger = helperClass.getMethod(
                        "castSkill",
                        Entity.class,
                        String.class,
                        Entity.class,
                        Location.class,
                        Collection.class,
                        Collection.class,
                        float.class
                );
            } catch (NoSuchMethodException ignored) {
                castWithTrigger = null;
            }

            try {
                castSimple = helperClass.getMethod("castSkill", Entity.class, String.class);
            } catch (NoSuchMethodException ignored) {
                castSimple = null;
            }

            available = castWithTrigger != null || castSimple != null;
            if (!available) {
                plugin.getLogger().warning(
                        "MythicMobs está cargado, pero no se encontró BukkitAPIHelper.castSkill compatible. " +
                        "Las habilidades de montura quedan deshabilitadas."
                );
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            available = false;
            plugin.getLogger().warning(
                    "No se pudo conectar con la API de MythicMobs para habilidades de montura: " +
                    exception.getMessage()
            );
        }
    }
}
