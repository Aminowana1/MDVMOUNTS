package com.mdvcraft.mdvmounts.storage;

import org.bukkit.Material;

import java.util.List;

public record InvokerStorageProfile(
        String key,
        boolean enabled,
        int slots,
        String title,
        double interactionDistance,
        Material material,
        String displayName,
        List<String> requiredMountTags) {
}
