package com.mdvcraft.mdvmounts.storage;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

public final class OpenStorageSession {
    private final UUID playerId;
    private final UUID storageId;
    private final UUID mountId;
    private final InvokerStorageProfile profile;
    private final List<ItemStack> contents;
    private final int invokerSlot;

    private Inventory inventory;
    private int currentPage;

    public OpenStorageSession(UUID playerId,
                              UUID storageId,
                              UUID mountId,
                              InvokerStorageProfile profile,
                              List<ItemStack> contents,
                              Inventory inventory,
                              int invokerSlot,
                              int currentPage) {
        this.playerId = playerId;
        this.storageId = storageId;
        this.mountId = mountId;
        this.profile = profile;
        this.contents = contents;
        this.inventory = inventory;
        this.invokerSlot = invokerSlot;
        this.currentPage = currentPage;
    }

    public UUID playerId() {
        return playerId;
    }

    public UUID storageId() {
        return storageId;
    }

    public UUID mountId() {
        return mountId;
    }

    public InvokerStorageProfile profile() {
        return profile;
    }

    public List<ItemStack> contents() {
        return contents;
    }

    public Inventory inventory() {
        return inventory;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public int invokerSlot() {
        return invokerSlot;
    }

    public int currentPage() {
        return currentPage;
    }

    public void currentPage(int currentPage) {
        this.currentPage = currentPage;
    }
}
