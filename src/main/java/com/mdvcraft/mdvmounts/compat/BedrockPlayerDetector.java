package com.mdvcraft.mdvmounts.compat;

import com.mdvcraft.mdvmounts.MDVMountsPlugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Optional Bedrock detector.
 *
 * No hard Floodgate/Geyser dependency is required at compile time. The API is
 * discovered lazily and cached the first time MDVMounts needs to classify a
 * player. Floodgate is preferred; Geyser API is used as a fallback.
 */
public final class BedrockPlayerDetector {
    private final MDVMountsPlugin plugin;

    private boolean initialized;
    private Object apiInstance;
    private Method isBedrockMethod;
    private String provider = "none";
    private boolean warned;

    public BedrockPlayerDetector(MDVMountsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isBedrockPlayer(UUID uuid) {
        if (uuid == null) {
            return false;
        }

        ensureInitialized();

        if (apiInstance == null || isBedrockMethod == null) {
            return false;
        }

        try {
            Object result = isBedrockMethod.invoke(apiInstance, uuid);
            return result instanceof Boolean bool && bool;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning(
                        "No se pudo consultar " + provider
                                + " para detectar jugador Bedrock: "
                                + exception.getMessage());
            }
            return false;
        }
    }

    public String provider() {
        ensureInitialized();
        return provider;
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;

        if (tryFloodgate()) {
            return;
        }

        tryGeyser();
    }

    private boolean tryFloodgate() {
        try {
            Class<?> apiClass = Class.forName(
                    "org.geysermc.floodgate.api.FloodgateApi");

            Method getInstance = apiClass.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            if (instance == null) {
                return false;
            }

            Method method = apiClass.getMethod(
                    "isFloodgatePlayer",
                    UUID.class);

            apiInstance = instance;
            isBedrockMethod = method;
            provider = "Floodgate";
            plugin.getLogger().info(
                    "Detección Bedrock conectada mediante Floodgate.");
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return false;
        }
    }

    private boolean tryGeyser() {
        try {
            Class<?> apiClass = Class.forName(
                    "org.geysermc.geyser.api.GeyserApi");

            Method apiMethod = apiClass.getMethod("api");
            Object instance = apiMethod.invoke(null);
            if (instance == null) {
                return false;
            }

            Method method = apiClass.getMethod(
                    "isBedrockPlayer",
                    UUID.class);

            apiInstance = instance;
            isBedrockMethod = method;
            provider = "Geyser";
            plugin.getLogger().info(
                    "Detección Bedrock conectada mediante Geyser API.");
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return false;
        }
    }
}
