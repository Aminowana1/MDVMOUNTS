package com.mdvcraft.mdvmounts.listener;

import com.mdvcraft.mdvmounts.skill.MountSkillManager;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Input listener for active mount abilities.
 *
 * Java:
 * - Configured control.mount-skills.input
 * - Supports ITEM_SWAP in addition to PlayerInputEvent actions.
 *
 * Bedrock:
 * - Attack while mounted, detected through Floodgate/Geyser.
 */
public final class MountSkillListener implements Listener {

    private final MountSkillManager skillManager;

    public MountSkillListener(MountSkillManager skillManager) {
        this.skillManager = skillManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInput(PlayerInputEvent event) {
        Player player = event.getPlayer();

        // Bedrock uses Attack for mount abilities.
        if (skillManager.isBedrockPlayer(player)) {
            return;
        }

        skillManager.handleJavaInput(player, event.getInput());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onItemSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();

        if (skillManager.isBedrockPlayer(player)) {
            return;
        }

        if (skillManager.handleJavaItemSwap(player)) {
            // ITEM_SWAP is an ability key on Java, not an actual inventory
            // operation when a mount skill successfully fires.
            event.setCancelled(true);
        }
    }

    /**
     * Handles Bedrock attack against air or blocks.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBedrockAttack(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();

        if (action != Action.LEFT_CLICK_AIR
                && action != Action.LEFT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();

        if (!skillManager.isBedrockPlayer(player)) {
            return;
        }

        if (skillManager.handleBedrockAttack(player)
                && skillManager.cancelBedrockInteractionOnCast()) {
            event.setCancelled(true);
        }
    }

    /**
     * Handles Bedrock attack against an entity.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBedrockEntityAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }

        if (!skillManager.isBedrockPlayer(player)) {
            return;
        }

        if (skillManager.handleBedrockAttack(player)
                && skillManager.cancelBedrockInteractionOnCast()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player) {
            skillManager.clear(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        skillManager.clear(event.getPlayer());
    }
}