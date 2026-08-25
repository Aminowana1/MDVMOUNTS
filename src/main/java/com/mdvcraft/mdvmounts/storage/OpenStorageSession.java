package com.mdvcraft.mdvmounts.storage;

import org.bukkit.inventory.Inventory;

import java.util.UUID;

public record OpenStorageSession(
        UUID playerId,
        UUID storageId,
        UUID mountId,
        InvokerStorageProfile profile,
        Inventory inventory) {
}
