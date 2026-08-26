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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class MountListener implements Listener {
    private final MDVMountsPlugin plugin;
    private final MountManager mountManager;

    public MountListener(MDVMountsPlugin plugin,
                         MountManager mountManager) {
        this.plugin = plugin;
        this.mountManager = mountManager;
    }

    // Las monturas MDV se identifican por tags, así que procesamos también
    // interacciones que otro plugin haya marcado como canceladas.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEntityEvent event) {
        handleInteract(event);
    }

    // También atendemos INTERACT_AT para cubrir la variante precisa del click
    // sobre entidades sin duplicar sesiones.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        handleInteract(event);
    }

    private void handleInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        Entity clicked = event.getRightClicked();

        if (!mountManager.isMountCandidate(clicked)) {
            return;
        }

        event.setCancelled(true);

        // Some clients/plugins can cause both interaction variants for the
        // same click. If the player is already riding exactly this MDV mount,
        // do nothing instead of rebuilding the session.
        MountSession current = mountManager.getSession(player);
        if (current != null
                && current.mount().getUniqueId().equals(clicked.getUniqueId())
                && player.getVehicle() != null
                && player.getVehicle().getUniqueId().equals(clicked.getUniqueId())) {
            return;
        }

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

    // Immediate steering response for manual mounts. The normal 1-tick loop
    // still maintains movement while a key remains held, but direction/key
    // changes do not have to wait for the next scheduler pass.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInput(PlayerInputEvent event) {
        mountManager.handleInput(event.getPlayer(), event.getInput());
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

        mountManager.releaseAfterNaturalDismount(player);
    }

    /**
     * Some mounts protect their riders from fall damage. This is intentionally
     * event-driven: there is no repeating task and no entity scan. The mount
     * still receives its own fall damage normally; only the player's FALL
     * event is cancelled. This also works for a second passenger on a native
     * camel because both players report the camel as their vehicle.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRiderFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL
                || !(event.getEntity() instanceof Player player)) {
            return;
        }

        Entity vehicle = player.getVehicle();
        if (!mountManager.protectsRiderFromFall(vehicle)) {
            return;
        }

        event.setCancelled(true);
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
