package com.mdvcraft.mdvmounts.skill;

import com.mdvcraft.mdvmounts.MDVMountsPlugin;
import com.mdvcraft.mdvmounts.compat.BedrockPlayerDetector;
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
 * mount.
 *
 * Java:
 * - PlayerInputEvent inputs (SPRINT/JUMP/etc.) use rising-edge detection.
 * - ITEM_SWAP uses PlayerSwapHandItemsEvent and is consumed when a skill fires.
 *
 * Bedrock:
 * - When Floodgate/Geyser identifies the rider as Bedrock, Java inputs are
 *   ignored and RIGHT_CLICK is used instead.
 */
public final class MountSkillManager {
    private static final String DEFAULT_TAG_PREFIX = "mdv_mount_skill_";

    private final MDVMountsPlugin plugin;
    private final MountManager mountManager;
    private final MythicSkillBridge mythicBridge;
    private final BedrockPlayerDetector bedrockDetector;

    private final Map<UUID, Boolean> pressedStates = new HashMap<>();
    private final Map<UUID, Integer> lastBedrockRightClickTick = new HashMap<>();

    private boolean enabled;
    private MountSkillInput activationInput;
    private String tagPrefix;
    private boolean useRiderLookDirection;
    private int riderLookSyncTicks;
    private int preserveSkillVelocityTicks;

    private boolean bedrockRightClickEnabled;
    private boolean cancelBedrockInteractionOnCast;
    private int bedrockRightClickDebounceTicks;

    public MountSkillManager(MDVMountsPlugin plugin, MountManager mountManager) {
        this.plugin = plugin;
        this.mountManager = mountManager;
        this.mythicBridge = new MythicSkillBridge(plugin);
        this.bedrockDetector = new BedrockPlayerDetector(plugin);
        reloadSettings();
    }

    public void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("control.mount-skills.enabled", true);

        String configuredInput = plugin.getConfig().getString(
                "control.mount-skills.input",
                "SPRINT");
        activationInput = MountSkillInput.parse(configuredInput);

        tagPrefix = plugin.getConfig().getString(
                "control.mount-skills.tag-prefix",
                DEFAULT_TAG_PREFIX);
        if (tagPrefix == null || tagPrefix.isBlank()) {
            tagPrefix = DEFAULT_TAG_PREFIX;
        } else {
            tagPrefix = tagPrefix.trim();
        }

        useRiderLookDirection = plugin.getConfig().getBoolean(
                "control.mount-skills.use-rider-look-direction",
                true);
        riderLookSyncTicks = Math.max(0, plugin.getConfig().getInt(
                "control.mount-skills.rider-look-sync-ticks",
                10));
        preserveSkillVelocityTicks = Math.max(0, plugin.getConfig().getInt(
                "control.mount-skills.preserve-skill-velocity-ticks",
                8));

        bedrockRightClickEnabled = plugin.getConfig().getBoolean(
                "control.mount-skills.bedrock.right-click-enabled",
                true);
        cancelBedrockInteractionOnCast = plugin.getConfig().getBoolean(
                "control.mount-skills.bedrock.cancel-interaction-on-cast",
                true);
        bedrockRightClickDebounceTicks = Math.max(0, plugin.getConfig().getInt(
                "control.mount-skills.bedrock.debounce-ticks",
                2));

        pressedStates.clear();
        lastBedrockRightClickTick.clear();
    }

    public void shutdown() {
        pressedStates.clear();
        lastBedrockRightClickTick.clear();
    }

    public boolean isBedrockPlayer(Player player) {
        return player != null
                && bedrockDetector.isBedrockPlayer(player.getUniqueId());
    }

    public MountSkillInput activationInput() {
        return activationInput;
    }

    public boolean cancelBedrockInteractionOnCast() {
        return cancelBedrockInteractionOnCast;
    }

    /**
     * Handles one Paper PlayerInputEvent for Java riders.
     * A skill fires only on released -> pressed.
     */
    public void handleInput(Player player, Input input) {
        if (!enabled || player == null || input == null) {
            return;
        }

        // ITEM_SWAP is delivered through PlayerSwapHandItemsEvent.
        if (activationInput == MountSkillInput.ITEM_SWAP) {
            pressedStates.remove(player.getUniqueId());
            return;
        }

        MountSession session = mountManager.getSession(player);
        if (session == null) {
            pressedStates.remove(player.getUniqueId());
            return;
        }

        boolean pressed = activationInput.isPressed(input);
        boolean wasPressed = Boolean.TRUE.equals(
                pressedStates.put(player.getUniqueId(), pressed));

        if (!pressed || wasPressed) {
            return;
        }

        castMountedSkill(player);
    }

    /**
     * Java hand-swap activation. Returns true only when the configured input is
     * ITEM_SWAP and at least one MythicMobs mount skill was successfully cast.
     */
    public boolean handleItemSwap(Player player) {
        if (!enabled
                || activationInput != MountSkillInput.ITEM_SWAP
                || player == null) {
            return false;
        }
        return castMountedSkill(player);
    }

    /**
     * Bedrock activation. Bedrock riders intentionally do not use Java's
     * SPRINT/ITEM_SWAP mapping; RIGHT_CLICK is their dedicated ability input.
     */
    public boolean handleBedrockRightClick(Player player) {
        if (!enabled
                || !bedrockRightClickEnabled
                || player == null
                || !isBedrockPlayer(player)) {
            return false;
        }

        MountSession session = mountManager.getSession(player);
        if (session == null) {
            return false;
        }

        int now = player.getTicksLived();
        Integer previous = lastBedrockRightClickTick.get(player.getUniqueId());
        if (previous != null
                && bedrockRightClickDebounceTicks > 0
                && now - previous <= bedrockRightClickDebounceTicks) {
            return false;
        }

        boolean cast = castMountedSkill(player);
        if (cast) {
            lastBedrockRightClickTick.put(player.getUniqueId(), now);
        }
        return cast;
    }

    /**
     * Casts every skill tag on the mount once. Returns true when at least one
     * MythicMobs cast reports success.
     */
    public boolean castMountedSkill(Player player) {
        if (!enabled || player == null) {
            return false;
        }

        MountSession session = mountManager.getSession(player);
        if (session == null) {
            return false;
        }

        LivingEntity mount = session.mount();
        if (!mount.isValid() || mount.isDead()) {
            return false;
        }

        List<String> skillNames = resolveSkillNames(mount);
        if (skillNames.isEmpty() || !mythicBridge.isAvailable()) {
            return false;
        }

        Location castOrigin = mount.getLocation().clone();
        if (useRiderLookDirection) {
            castOrigin.setYaw(player.getYaw());
            castOrigin.setPitch(player.getPitch());

            session.beginSkillAim(riderLookSyncTicks);
            mount.setRotation(player.getYaw(), player.getPitch());
        }

        boolean castAny = false;

        for (String skillName : skillNames) {
            Vector before = mount.getVelocity().clone();

            boolean cast = mythicBridge.cast(
                    mount,
                    player,
                    skillName,
                    castOrigin);
            castAny |= cast;

            Vector after = mount.getVelocity();

            if (preserveSkillVelocityTicks > 0
                    && velocityChanged(before, after)) {
                session.beginSkillVelocityOverride(
                        preserveSkillVelocityTicks);
            }
        }

        return castAny;
    }

    private boolean velocityChanged(Vector before, Vector after) {
        if (before == null || after == null) {
            return false;
        }
        return before.distanceSquared(after) > 1.0E-6D;
    }

    /**
     * Utility player-cast used by logout cooldown cleanup.
     */
    public boolean castPlayerSkill(Player player, String skillName) {
        if (player == null || skillName == null || skillName.isBlank()) {
            return false;
        }
        return mythicBridge.cast(
                player,
                player,
                skillName.trim(),
                player.getLocation());
    }

    public void clear(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        pressedStates.remove(uuid);
        lastBedrockRightClickTick.remove(uuid);
    }

    private List<String> resolveSkillNames(LivingEntity mount) {
        Set<String> uniqueNames = new HashSet<>();

        for (String tag : mount.getScoreboardTags()) {
            if (!tag.startsWith(tagPrefix)
                    || tag.length() <= tagPrefix.length()) {
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

        List<String> names = new ArrayList<>(uniqueNames);
        Collections.sort(names);
        return names;
    }
}
