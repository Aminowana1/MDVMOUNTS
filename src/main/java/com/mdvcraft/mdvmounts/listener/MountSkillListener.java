package com.mdvcraft.mdvmounts.listener;

import com.mdvcraft.mdvmounts.skill.MountSkillManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Isolated input listener for mount abilities. It does not cancel movement or
 * interaction events and therefore does not alter the stable mount controller.
 */
public final class MountSkillListener implements Listener {
    private final MountSkillManager skillManager;

    public MountSkillListener(MountSkillManager skillManager) {
        this.skillManager = skillManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInput(PlayerInputEvent event) {
        skillManager.handleInput(event.getPlayer(), event.getInput());
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
