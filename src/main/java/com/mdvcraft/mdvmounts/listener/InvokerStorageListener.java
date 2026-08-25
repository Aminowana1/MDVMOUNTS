package com.mdvcraft.mdvmounts.listener;

import com.mdvcraft.mdvmounts.storage.InvokerStorageManager;
import com.mdvcraft.mdvmounts.storage.OpenStorageSession;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
     * Runs before Crucible's normal item use. If the correct physical invoker
     * is beside its bound summoned mount, the click becomes "open storage".
     * Otherwise the event is left alone so the existing MythicMobs summon
     * skill can run exactly as before.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        if (storageManager.tryOpen(player, item)) {
            event.setCancelled(true);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            return;
        }

        // No matching bound mount nearby: do not interfere with Crucible.
        // We only prepare a very short post-click binding window so that, if
        // the existing summon skill creates the configured mob, that new mob
        // becomes linked to this exact physical ItemStack.
        storageManager.armBindingAfterInvocation(player, item);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onUseOnEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (storageManager.tryOpen(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onUseAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (storageManager.tryOpen(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
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

        // The physical invoker whose data backs this GUI must remain in the
        // player's inventory until the GUI closes, otherwise there is nowhere
        // safe to write the edited container component.
        if (storageManager.isActiveInvoker(current, session)
                || storageManager.isActiveInvoker(cursor, session)) {
            event.setCancelled(true);
            return;
        }

        // Number-key swaps can pull the active invoker from the hotbar or put
        // another storage invoker inside the container.
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

        // Exact capacities such as 10 or 20 slots use a chest GUI rounded up
        // to the next row. The extra visual slots are not usable storage.
        if (rawSlot >= 0 && rawSlot < topSize
                && !storageManager.isUsableTopSlot(session, rawSlot)) {
            event.setCancelled(true);
            return;
        }

        if (!storageManager.preventNestedInvokers()) {
            return;
        }

        // Prevent configured invokers (including other whistles) from being
        // nested inside mount storage. This avoids recursive container items
        // and keeps the physical-item ownership model simple and safe.
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

    // Save before Bukkit builds the death drops. The dropped invoker therefore
    // carries its container component and whoever loots it inherits the items.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        storageManager.closeAndSave(event.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        storageManager.closeAndSave(event.getPlayer());
    }
}
