package com.mdvcraft.mdvmounts.skill;

import com.mdvcraft.mdvmounts.MDVMountsPlugin;
import com.mdvcraft.mdvmounts.mount.MountManager;
import com.mdvcraft.mdvmounts.mount.MountSession;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Executes MythicMobs skills from scoreboard tags on the currently ridden
 * mount. Nothing is scanned on a timer: tags are read only when the configured
 * input changes from released -> pressed.
 */
public final class MountSkillManager {
    private static final String DEFAULT_TAG_PREFIX = "mdv_mount_skill_";

    private final MDVMountsPlugin plugin;
    private final MountManager mountManager;
    private final MythicSkillBridge mythicBridge;
    private final Map<UUID, Boolean> pressedStates = new HashMap<>();

    private boolean enabled;
    private MountSkillInput activationInput;
    private String tagPrefix;
    private boolean useRiderLookDirection;
    private int riderLookSyncTicks;
    private int preserveSkillVelocityTicks;

    public MountSkillManager(MDVMountsPlugin plugin, MountManager mountManager) {
        this.plugin = plugin;
        this.mountManager = mountManager;
        this.mythicBridge = new MythicSkillBridge(plugin);
        reloadSettings();
    }

    public void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("control.mount-skills.enabled", true);

        String configuredInput = plugin.getConfig().getString("control.mount-skills.input", "SPRINT");
        activationInput = MountSkillInput.parse(configuredInput);

        tagPrefix = plugin.getConfig().getString("control.mount-skills.tag-prefix", DEFAULT_TAG_PREFIX);
        if (tagPrefix == null || tagPrefix.isBlank()) {
            tagPrefix = DEFAULT_TAG_PREFIX;
        } else {
            tagPrefix = tagPrefix.trim();
        }

        useRiderLookDirection = plugin.getConfig().getBoolean(
                "control.mount-skills.use-rider-look-direction", true);
        riderLookSyncTicks = Math.max(0, plugin.getConfig().getInt(
                "control.mount-skills.rider-look-sync-ticks", 10));
        preserveSkillVelocityTicks = Math.max(0, plugin.getConfig().getInt(
                "control.mount-skills.preserve-skill-velocity-ticks", 8));

        pressedStates.clear();
    }

    public void shutdown() {
        pressedStates.clear();
    }

    /**
     * Handles one Paper PlayerInputEvent. A skill fires only on the rising
     * edge of the configured input, so holding Ctrl/Sprint never repeats it
     * every tick or on every movement update.
     */
    public void handleInput(Player player, Input input) {
        if (!enabled || player == null || input == null) {
            return;
        }

        MountSession session = mountManager.getSession(player);
        if (session == null) {
            pressedStates.remove(player.getUniqueId());
            return;
        }

        boolean pressed = activationInput.isPressed(input);
        boolean wasPressed = Boolean.TRUE.equals(pressedStates.put(player.getUniqueId(), pressed));

        if (!pressed || wasPressed) {
            return;
        }

        LivingEntity mount = session.mount();
        if (!mount.isValid() || mount.isDead()) {
            return;
        }

        List<String> skillNames = resolveSkillNames(mount);
        if (skillNames.isEmpty()) {
            return;
        }

        if (!mythicBridge.isAvailable()) {
            return;
        }

        Location castOrigin = mount.getLocation().clone();
        if (useRiderLookDirection) {
            castOrigin.setYaw(player.getYaw());
            castOrigin.setPitch(player.getPitch());

            // @Forward and similar caster-facing targeters resolve immediately
            // from the mount, so mirror rider look before starting the cast.
            // MountMovement keeps this pitch synchronized only for the short
            // configured window, then automatically returns to yaw-only.
            session.beginSkillAim(riderLookSyncTicks);
            mount.setRotation(player.getYaw(), player.getPitch());
        }

        for (String skillName : skillNames) {
            Vector before = mount.getVelocity().clone();
            mythicBridge.cast(mount, player, skillName, castOrigin);
            Vector after = mount.getVelocity();

            // If MythicMobs changed the mount's velocity synchronously (lunge,
            // recoil, dash, etc.), preserve it briefly. Without this, the
            // regular WASD controller would replace the dash on the next tick
            // whenever the rider was already moving.
            if (preserveSkillVelocityTicks > 0 && velocityChanged(before, after)) {
                session.beginSkillVelocityOverride(preserveSkillVelocityTicks);
            }
        }
    }

    private boolean velocityChanged(Vector before, Vector after) {
        if (before == null || after == null) {
            return false;
        }
        return before.distanceSquared(after) > 1.0E-6D;
    }

    public void clear(Player player) {
        if (player != null) {
            pressedStates.remove(player.getUniqueId());
        }
    }

    private List<String> resolveSkillNames(LivingEntity mount) {
        Set<String> uniqueNames = new HashSet<>();

        for (String tag : mount.getScoreboardTags()) {
            if (!tag.startsWith(tagPrefix) || tag.length() <= tagPrefix.length()) {
                continue;
            }

            String skillName = tag.substring(tagPrefix.length()).trim();
            if (!skillName.isEmpty()) {
                uniqueNames.add(skillName);
            }
        }

        if (uniqueNames.isEmpty()) {
            return Collections.emptyList();
        }

        // Scoreboard tags are a Set with no useful ordering guarantee. Sort so
        // behaviour remains deterministic when a mount intentionally carries
        // more than one skill tag: all matching skills fire once per press.
        List<String> names = new ArrayList<>(uniqueNames);
        Collections.sort(names);
        return names;
    }
}
