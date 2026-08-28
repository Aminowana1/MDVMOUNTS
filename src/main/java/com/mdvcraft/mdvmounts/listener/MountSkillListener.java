package com.mdvcraft.mdvmounts.listener;

import com.mdvcraft.mdvmounts.skill.MountSkillManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
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
 * - configured control.mount-skills.input
 * - supports ITEM_SWAP in addition to PlayerInputEvent actions.
 *
 * Bedrock:
 * - RIGHT_CLICK while mounted, detected through Floodgate/Geyser.
 */
public final class MountSkillListener implements Listener {
    private final MountSkillManager skillManager;

    public MountSkillListener(MountSkillManager skillManager) {
        this.skillManager = skillManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInput(PlayerInputEvent event) {
        Player player = event.getPlayer();

        // Bedrock gets its own dedicated RIGHT_CLICK input.
        if (skillManager.isBedrockPlayer(player)) {
            return;
        }

        skillManager.handleInput(player, event.getInput());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onItemSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();

        if (skillManager.isBedrockPlayer(player)) {
            return;
        }

        if (skillManager.handleItemSwap(player)) {
            // ITEM_SWAP is an ability key on Java, not an actual inventory
            // operation when a mount skill successfully fires.
            event.setCancelled(true);
        }
    }

    /**
     * Handles Bedrock right-click against air or blocks. Entity right-click is
     * handled inside MountListener before normal mount interaction logic.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBedrockRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR
                && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (skillManager.handleBedrockRightClick(event.getPlayer())
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
