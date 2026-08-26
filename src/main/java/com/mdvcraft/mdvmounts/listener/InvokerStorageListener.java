package com.mdvcraft.mdvmounts.listener;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import com.mdvcraft.mdvmounts.storage.InvokerStorageManager;
import com.mdvcraft.mdvmounts.storage.OpenStorageSession;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class InvokerStorageListener implements Listener {
    private final InvokerStorageManager storageManager;

    public InvokerStorageListener(InvokerStorageManager storageManager) {
        this.storageManager = storageManager;
    }

    /**
     * Storage opening is configurable through:
     * control.storage-open-interaction
     *
     * Supported values:
     * LEFT_CLICK, RIGHT_CLICK, SHIFT_LEFT_CLICK, SHIFT_RIGHT_CLICK.
     *
     * A right click that is not consumed by an actual storage opening remains
     * available to Crucible/MythicMobs for normal mount invocation.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            if (storageManager.shouldOpenFromLeftClick(event.getPlayer())
                    && storageManager.tryOpen(event.getPlayer(), item)) {
                cancelUse(event);
            }
            return;
        }

        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            if (storageManager.shouldOpenFromRightClick(event.getPlayer())
                    && storageManager.tryOpen(event.getPlayer(), item)) {
                cancelUse(event);
                return;
            }

            // Storage did not open: preserve Crucible/MythicMobs right-click
            // invocation exactly as before and only arm the post-summon link.
            storageManager.armBindingAfterInvocation(event.getPlayer(), item);
        }
    }

    // Right-clicking an entity can also be a Crucible use. If RIGHT_CLICK or
    // SHIFT_RIGHT_CLICK is configured, storage gets first chance to consume the
    // click only when the exact bound mount is already nearby.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onUseOnEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (storageManager.shouldOpenFromRightClick(player)
                && storageManager.tryOpen(player, item)) {
            event.setCancelled(true);
            return;
        }
        storageManager.armBindingAfterInvocation(player, item);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onUseAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (storageManager.shouldOpenFromRightClick(player)
                && storageManager.tryOpen(player, item)) {
            event.setCancelled(true);
            return;
        }
        storageManager.armBindingAfterInvocation(player, item);
    }

    // A direct left click on an entity is represented as an attack event. Only
    // consume it when the configured interaction is a left-click variant and a
    // valid bound mount is actually in range.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onLeftClickEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!storageManager.shouldOpenFromLeftClick(player)) {
            return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (storageManager.tryOpen(player, item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        storageManager.refreshContainerTooltip(event.getPlayer());
    }

    private void cancelUse(PlayerInteractEvent event) {
        event.setCancelled(true);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        OpenStorageSession session = storageManager.getOpenSession(player);
        if (session == null || event.getView().getTopInventory() != session.inventory()) {
            return;
        }

        int topSize = session.inventory().getSize();
        int rawSlot = event.getRawSlot();
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        // The physical invoker whose data backs this GUI must remain with the
        // viewer until close so changes can be written to that exact ItemStack.
        if (storageManager.isActiveInvoker(current, session)
                || storageManager.isActiveInvoker(cursor, session)) {
            event.setCancelled(true);
            return;
        }

        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            ItemStack offhand = player.getInventory().getItemInOffHand();
            if (storageManager.isActiveInvoker(offhand, session)
                    || (rawSlot >= 0 && rawSlot < topSize
                        && storageManager.preventNestedInvokers()
                        && storageManager.isStorageInvoker(offhand))) {
                event.setCancelled(true);
                return;
            }
        }

        int hotbarButton = event.getHotbarButton();
        if (hotbarButton >= 0) {
            ItemStack hotbar = player.getInventory().getItem(hotbarButton);
            if (storageManager.isActiveInvoker(hotbar, session)) {
                event.setCancelled(true);
                return;
            }
            if (rawSlot >= 0 && rawSlot < topSize
                    && storageManager.preventNestedInvokers()
                    && storageManager.isStorageInvoker(hotbar)) {
                event.setCancelled(true);
                return;
            }
        }

        if (rawSlot >= 0 && rawSlot < topSize
                && !storageManager.isUsableTopSlot(session, rawSlot)) {
            event.setCancelled(true);
            return;
        }

        if (!storageManager.preventNestedInvokers()) {
            return;
        }

        if (rawSlot >= 0 && rawSlot < topSize && storageManager.isStorageInvoker(cursor)) {
            event.setCancelled(true);
            return;
        }

        if (event.isShiftClick()
                && rawSlot >= topSize
                && storageManager.isStorageInvoker(current)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        OpenStorageSession session = storageManager.getOpenSession(player);
        if (session == null || event.getView().getTopInventory() != session.inventory()) {
            return;
        }

        int topSize = session.inventory().getSize();
        boolean touchesTop = event.getRawSlots().stream().anyMatch(slot -> slot < topSize);
        if (!touchesTop) {
            return;
        }

        if (storageManager.isActiveInvoker(event.getOldCursor(), session)
                || (storageManager.preventNestedInvokers()
                    && storageManager.isStorageInvoker(event.getOldCursor()))) {
            event.setCancelled(true);
            return;
        }

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize && !storageManager.isUsableTopSlot(session, rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            OpenStorageSession session = storageManager.getOpenSession(player);
            if (session != null && event.getInventory() == session.inventory()) {
                storageManager.saveOpenSession(player, true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        OpenStorageSession session = storageManager.getOpenSession(event.getPlayer());
        if (session != null && storageManager.isActiveInvoker(event.getItemDrop().getItemStack(), session)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        OpenStorageSession session = storageManager.getOpenSession(event.getPlayer());
        if (session == null) {
            return;
        }
        if (storageManager.isActiveInvoker(event.getMainHandItem(), session)
                || storageManager.isActiveInvoker(event.getOffHandItem(), session)) {
            event.setCancelled(true);
        }
    }

    // Save before Bukkit builds death drops, so the physical invoker carries
    // the latest container contents if another player later loots it.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        storageManager.closeAndSave(event.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        storageManager.closeAndSave(event.getPlayer());
    }

    // If the bound animal dies, its menu is closed immediately.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMountDeath(EntityDeathEvent event) {
        storageManager.closeSessionsForMount(event.getEntity().getUniqueId());
    }

    // Covers MythicMobs remove/despawn, plugin removals and chunk/world
    // removal. This makes the GUI lifecycle follow the summoned entity rather
    // than relying on a polling task.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMountRemoved(EntityRemoveFromWorldEvent event) {
        storageManager.closeSessionsForMount(event.getEntity().getUniqueId());
    }
}
