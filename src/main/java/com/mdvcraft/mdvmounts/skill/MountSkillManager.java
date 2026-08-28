package com.mdvcraft.mdvmounts.skill;

import com.mdvcraft.mdvmounts.MDVMountsPlugin;
import com.mdvcraft.mdvmounts.compat.BedrockPlayerDetector;
import com.mdvcraft.mdvmounts.mount.MountManager;
import com.mdvcraft.mdvmounts.mount.MountSession;

import org.bukkit.Bukkit;
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
 * ignored and Attack is used as the dedicated ability input.
 */
public final class MountSkillManager {

    private static final String DEFAULT_TAG_PREFIX = "mdv_mount_skill_";

    private final MDVMountsPlugin plugin;
    private final MountManager mountManager;
    private final MythicSkillBridge mythicBridge;
    private final BedrockPlayerDetector bedrockDetector;

    private final Map<UUID, Boolean> pressedStates = new HashMap<>();

    private boolean enabled;
    private MountSkillInput activationInput;
    private String tagPrefix;

    private boolean useRiderLookDirection;
    private int riderLookSyncTicks;
    private int preserveSkillVelocityTicks;

    // Bedrock
    private boolean bedrockAttackEnabled;
    private boolean bedrockAttackWeaponFilter;
    private boolean cancelBedrockInteractionOnCast;
    private int bedrockAttackDebounceTicks;

    private final Map<UUID, Integer> lastBedrockAttackTick = new HashMap<>();

    public MountSkillManager(
            MDVMountsPlugin plugin,
            MountManager mountManager) {

        this.plugin = plugin;
        this.mountManager = mountManager;
        this.mythicBridge = new MythicSkillBridge(plugin);
        this.bedrockDetector = new BedrockPlayerDetector(plugin);

        reloadSettings();
    }

    public void reloadSettings() {
        enabled = plugin.getConfig().getBoolean(
                "control.mount-skills.enabled",
                true);

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

        bedrockAttackEnabled = plugin.getConfig().getBoolean(
                "control.mount-skills.bedrock.attack-enabled",
                true);

        bedrockAttackWeaponFilter = plugin.getConfig().getBoolean(
                "control.mount-skills.bedrock.attack-weapon-filter",
                true);

        cancelBedrockInteractionOnCast = plugin.getConfig().getBoolean(
                "control.mount-skills.bedrock.cancel-interaction-on-cast",
                true);

        bedrockAttackDebounceTicks = Math.max(0, plugin.getConfig().getInt(
                "control.mount-skills.bedrock.debounce-ticks",
                2));

        pressedStates.clear();
        lastBedrockAttackTick.clear();
    }

    public void shutdown() {
        pressedStates.clear();
        lastBedrockAttackTick.clear();
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
    public void handleJavaInput(Player player, Input input) {
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
     * Java hand-swap activation.
     * Returns true only when the configured input is ITEM_SWAP and at least
     * one MythicMobs mount skill was successfully cast.
     */
    public boolean handleJavaItemSwap(Player player) {
        if (!enabled
                || activationInput != MountSkillInput.ITEM_SWAP
                || player == null) {
            return false;
        }

        return castMountedSkill(player);
    }

    /**
     * Bedrock activation.
     *
     * Bedrock riders use Attack as their dedicated ability input.
     * A short debounce prevents duplicate input events from causing
     * multiple casts from a single physical action.
     *
     * Returns true only when at least one MythicMobs mount skill was cast.
     */
    public boolean handleBedrockAttack(Player player) {
        if (!enabled
                || !bedrockAttackEnabled
                || player == null
                || !isBedrockPlayer(player)) {
            return false;
        }

        MountSession session = mountManager.getSession(player);
        if (session == null) {
            return false;
        }

        // Filter out weapons if enabled
        if (bedrockAttackWeaponFilter
                && playerIsHoldingWeapon(player)) {

            Bukkit.getConsoleSender().sendMessage("Bedrock attack cancelled (player was holding a weapon)!");

            return false;
        }

        int now = player.getTicksLived();
        UUID uuid = player.getUniqueId();

        Integer previous = lastBedrockAttackTick.get(uuid);

        if (previous != null
                && bedrockAttackDebounceTicks > 0
                && now - previous <= bedrockAttackDebounceTicks) {

            return false;
        }

        boolean cast = castMountedSkill(player);

        if (cast) {
            lastBedrockAttackTick.put(uuid, now);
        }

        return cast;
    }

    private boolean playerIsHoldingWeapon(Player player) {
        if (player == null) {
            return false;
        }

        String materialName = player.getInventory()
                .getItemInMainHand()
                .getType()
                .name();

        return materialName.endsWith("_SWORD")
                || materialName.endsWith("_AXE")
                || materialName.endsWith("_SPEAR")
                || materialName.equals("BOW")
                || materialName.equals("CROSSBOW")
                || materialName.equals("TRIDENT")
                || materialName.equals("MACE");
    }

    /**
     * Casts every skill tag on the mount once.
     * Returns true when at least one MythicMobs cast reports success.
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

            mount.setRotation(
                    player.getYaw(),
                    player.getPitch());
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
        lastBedrockAttackTick.remove(uuid);
    }

    private List<String> resolveSkillNames(LivingEntity mount) {
        Set<String> uniqueNames = new HashSet<>();

        for (String tag : mount.getScoreboardTags()) {
            if (!tag.startsWith(tagPrefix)
                    || tag.length() <= tagPrefix.length()) {
                continue;
            }

            String skillName = tag.substring(
                    tagPrefix.length()).trim();

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