package com.mdvcraft.mdvmounts.listener;

import com.mdvcraft.mdvmounts.MDVMountsPlugin;
import com.mdvcraft.mdvmounts.mount.MountManager;
import com.mdvcraft.mdvmounts.mount.MountSession;
import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class MountListener implements Listener {
    private final MDVMountsPlugin plugin;
    private final MountManager mountManager;

    public MountListener(MDVMountsPlugin plugin, MountManager mountManager) {
        this.plugin = plugin;
        this.mountManager = mountManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        Entity clicked = event.getRightClicked();

        if (!mountManager.isMountCandidate(clicked)) {
            return;
        }

        event.setCancelled(true);

        if (!player.hasPermission("mdvmounts.use")) {
            message(player, "messages.no-permission", "&cNo tienes permiso para usar monturas.");
            return;
        }

        if (!(clicked instanceof LivingEntity living)) {
            message(player, "messages.invalid", "&cEsta entidad no es una montura MDV válida.");
            return;
        }

        for (Entity passenger : living.getPassengers()) {
            if (passenger instanceof Player other && !other.getUniqueId().equals(player.getUniqueId())) {
                message(player, "messages.occupied", "&cEsta montura ya tiene un jinete.");
                return;
            }
        }

        if (mountManager.tryMount(player, living)) {
            message(player, "messages.mounted", "&aMontura controlada.");
        } else {
            message(player, "messages.invalid", "&cEsta entidad no es una montura MDV válida.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        MountSession session = mountManager.getSession(player);
        if (session == null || !event.getDismounted().getUniqueId().equals(session.mount().getUniqueId())) {
            return;
        }

        // SHIFT se usa para bajar en vuelo/agua/lava. Sólo interceptamos el
        // desmontaje si realmente el input actual es sneak. Otros desmontajes
        // programáticos o por muerte no quedan atrapados.
        if (mountManager.isVerticalDismountInput(player) && event.isCancellable()) {
            boolean allowDismount = mountManager.registerVerticalDismountAttempt(player);
            if (!allowDismount) {
                event.setCancelled(true);
                return;
            }
        }

        mountManager.releaseAfterNaturalDismount(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        mountManager.forceDismount(event.getPlayer());
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Entity dead = event.getEntity();
        for (Entity passenger : dead.getPassengers()) {
            if (passenger instanceof Player player && mountManager.getSession(player) != null) {
                mountManager.forceDismount(player);
            }
        }
    }

    private void message(Player player, String path, String fallback) {
        String raw = plugin.getConfig().getString(path, fallback);
        if (raw == null || raw.isBlank()) {
            return;
        }
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', raw));
    }
}
