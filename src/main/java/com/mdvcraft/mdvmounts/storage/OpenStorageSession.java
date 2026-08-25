package com.mdvcraft.mdvmounts.storage;

import org.bukkit.inventory.Inventory;

import java.util.UUID;

public record OpenStorageSession(
        UUID playerId,
        UUID storageId,
        InvokerStorageProfile profile,
        Inventory inventory) {
}
