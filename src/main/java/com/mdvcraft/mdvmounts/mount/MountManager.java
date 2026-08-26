package com.mdvcraft.mdvmounts.mount;

import com.mdvcraft.mdvmounts.MDVMountsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Camel;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class MountManager {
    private final MDVMountsPlugin plugin;
    private final MountMovement movement;
    private final Map<UUID, MountSession> sessions = new HashMap<>();

    private BukkitTask tickTask;

    private boolean requireBaseTag;
    private String baseTag;
    private String groundTag;
    private String flyingTag;
    private String aquaticTag;
    private String lavaTag;
    private String jumperTag;
    private String climberTag;
    private String camelNormalJumpTag;
    private String riderFallProtectionTag;

    private boolean camelNormalJumpEnabled;
    private double camelNormalJumpVelocity;

    // Maximum number of consecutive water blocks above the flying mount
    // before the rider is forced to dismount. Cached on reload so movement
    // ticks never read YAML. -1 disables the restriction.
    private int flyingMountMaxWaterDepth;

    // PlayerInputEvent can also fire when W/A/S/D changes while SPACE remains
    // held. Remember the rising edge so a tagged native camel jumps only once.
    private final Set<UUID> nativeCamelJumpHeld = new HashSet<>();

    // Tagged native camels are guarded for the entire ride once their driver
    // produces input. This avoids any release-timing race where vanilla could
    // arm a delayed dash after the old short guard window expired.
    //
    // There is still no world/entity scan: this map contains only active
    // drivers of tagged camels that have actually sent PlayerInputEvent.
    private final Map<UUID, UUID> nativeCamelDashGuardMounts = new HashMap<>();

    // A HorseJumpEvent arms a tiny 3-tick one-shot horizontal block. If
    // vanilla still tries to turn that jump into a camel dash, the next
    // EntityMoveEvent keeps only Y and discards the horizontal dash movement.
    // Entries expire automatically, so a camel that never moves is not left
    // with stale state.
    private static final int CAMEL_HARD_DASH_BLOCK_TICKS = 3;
    private final Map<UUID, Integer> nativeCamelHardDashBlockTicks = new HashMap<>();

    // Final safety net for Paper/Purpur's client-assisted camel dash:
    // any abnormally large horizontal movement is clamped by EntityMoveEvent.
    // Normal riding at MOVEMENT_SPEED is left untouched.
    private static final double CAMEL_MAX_HORIZONTAL_SPEED_MULTIPLIER = 1.75D;
    private static final double CAMEL_MIN_HORIZONTAL_SPEED_CAP = 0.45D;

    public MountManager(MDVMountsPlugin plugin) {
        this.plugin = plugin;
        this.movement = new MountMovement(plugin);
        reloadSettings();
    }

    public void start() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }

        for (MountSession session : sessions.values().toArray(MountSession[]::new)) {
            forceDismount(session.player());
        }
        sessions.clear();
        nativeCamelJumpHeld.clear();
        nativeCamelDashGuardMounts.clear();
        nativeCamelHardDashBlockTicks.clear();
    }

    public void reloadSettings() {
        requireBaseTag = plugin.getConfig().getBoolean("tags.require-base-tag", true);
        baseTag = plugin.getConfig().getString("tags.base", "mdv_mount");
        groundTag = plugin.getConfig().getString("tags.ground", "mdv_mount_ground");
        flyingTag = plugin.getConfig().getString("tags.flying", "mdv_mount_flying");
        aquaticTag = plugin.getConfig().getString("tags.aquatic", "mdv_mount_aquatic");
        lavaTag = plugin.getConfig().getString("tags.lava", "mdv_mount_lava");
        jumperTag = plugin.getConfig().getString("tags.jumper", "mdv_mount_jumper");
        climberTag = plugin.getConfig().getString("tags.climber", "mdv_mount_climber");
        camelNormalJumpTag = plugin.getConfig().getString(
                "tags.camel-normal-jump",
                "mdv_mount_camel_normal_jump");
        riderFallProtectionTag = plugin.getConfig().getString(
                "tags.rider-fall-protection",
                "mdv_mount_rider_fall_protection");

        camelNormalJumpEnabled = plugin.getConfig().getBoolean(
                "control.camel-normal-jump.enabled",
                true);
        camelNormalJumpVelocity = Math.max(0.0D, plugin.getConfig().getDouble(
                "control.camel-normal-jump.jump-velocity",
                0.55D));

        // Restored legacy flying-water safety. Read once on reload, never from
        // YAML in the per-tick loop. -1 keeps flying mounts unrestricted.
        flyingMountMaxWaterDepth = plugin.getConfig().getInt(
                "control.flying-mount-max-water-depth",
                4);

        for (MountSession session : sessions.values()) {
            session.setWallClimber(
                    session.type() == MountType.GROUND
                            && session.mount().getScoreboardTags().contains(climberTag));
        }

        movement.reloadSettings(sessions.values());
    }

    public Optional<MountType> resolveType(Entity entity) {
        if (!(entity instanceof LivingEntity)) {
            return Optional.empty();
        }

        Set<String> tags = entity.getScoreboardTags();
        if (requireBaseTag && !tags.contains(baseTag)) {
            return Optional.empty();
        }

        // Prioridad intencional para que una entidad con más de un tag pueda
        // escoger primero los modos tridimensionales y luego los terrestres.
        if (tags.contains(flyingTag))
            return Optional.of(MountType.FLYING);
        if (tags.contains(aquaticTag))
            return Optional.of(MountType.AQUATIC);
        if (tags.contains(lavaTag))
            return Optional.of(MountType.LAVA);
        if (tags.contains(jumperTag))
            return Optional.of(MountType.JUMPER);
        if (tags.contains(groundTag))
            return Optional.of(MountType.GROUND);

        return Optional.empty();
    }

    public boolean isMountCandidate(Entity entity) {
        return resolveType(entity).isPresent();
    }

    public MountSession getSession(Player player) {
        return sessions.get(player.getUniqueId());
    }

    /**
     * Event-driven fall protection for riders. No scheduler, nearby-entity
     * lookup or per-tick scan is involved: the scoreboard tag is checked only
     * when Bukkit is already processing fall damage for a mounted player.
     */
    public boolean protectsRiderFromFall(Entity vehicle) {
        return vehicle != null
                && riderFallProtectionTag != null
                && !riderFallProtectionTag.isBlank()
                && vehicle.getScoreboardTags().contains(riderFallProtectionTag);
    }

    /**
     * Fast path for key transitions. Paper fires PlayerInputEvent when the
     * client changes W/A/S/D/jump/etc.; applying that change immediately
     * removes up to one scheduler tick of perceived steering latency.
     */
    public void handleInput(Player player, Input input) {
        MountSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            handleNativeCamelInput(player, input);
            return;
        }

        LivingEntity mount = session.mount();
        Entity vehicle = player.getVehicle();
        if (!player.isOnline()
                || !mount.isValid()
                || mount.isDead()
                || vehicle == null
                || !vehicle.getUniqueId().equals(mount.getUniqueId())) {
            return;
        }

        movement.inputChanged(session, input);
    }

    public boolean tryMount(Player player, LivingEntity mount) {
        Optional<MountType> typeOptional = resolveType(mount);
        if (typeOptional.isEmpty()) {
            return false;
        }

        for (Entity passenger : mount.getPassengers()) {
            if (passenger instanceof Player other && !other.getUniqueId().equals(player.getUniqueId())) {
                return false;
            }
        }

        MountSession old = sessions.get(player.getUniqueId());
        if (old != null) {
            forceDismount(player);
        } else if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }

        boolean mounted = mount.addPassenger(player);
        if (!mounted) {
            return false;
        }

        MountSession session = new MountSession(
                player,
                mount,
                typeOptional.get(),
                typeOptional.get() == MountType.GROUND
                        && mount.getScoreboardTags().contains(climberTag));
        sessions.put(player.getUniqueId(), session);
        prepareForControl(session);
        return true;
    }

    public void releaseAfterNaturalDismount(Player player) {
        clearInputState(player);
        MountSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            restoreAfterControl(session);
        }
    }

    public void forceDismount(Player player) {
        clearInputState(player);
        MountSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }

        restoreAfterControl(session);

        if (player.getVehicle() != null) {
            // La sesión ya fue retirada del mapa, por lo que el listener de
            // desmontaje no interceptará este desmontaje programático.
            player.leaveVehicle();
        }
    }

    public void clearInputState(Player player) {
        UUID playerId = player.getUniqueId();
        nativeCamelJumpHeld.remove(playerId);
        nativeCamelDashGuardMounts.remove(playerId);
    }

    /**
     * Returns true when vanilla horse/camel jump handling must be cancelled for
     * a tagged native camel. The actual normal jump is injected from
     * PlayerInputEvent, so allowing the vanilla HorseJumpEvent would re-enable
     * the camel dash on some press/release timings.
     */
    public boolean shouldCancelVanillaCamelJump(Entity entity) {
        if (!camelNormalJumpEnabled
                || !(entity instanceof Camel camel)
                || !camel.getScoreboardTags().contains(camelNormalJumpTag)) {
            return false;
        }

        // Arm the movement-level one-shot before the event is cancelled. If
        // vanilla still applies a dash impulse later in the same/next tick,
        // EntityMoveEvent will preserve Y but discard X/Z for that attempt.
        nativeCamelHardDashBlockTicks.put(
                camel.getUniqueId(),
                CAMEL_HARD_DASH_BLOCK_TICKS);

        camel.setDashing(false);
        return true;
    }

    /**
     * Special handling for a real vanilla Camel that intentionally does NOT
     * use mdv_mount/mdv_mount_ground. Only the first passenger drives it.
     *
     * SPACE becomes a small normal vertical jump. The vanilla dash is blocked
     * in four layers:
     * 1) immediately on every PlayerInputEvent,
     * 2) every tick for the rest of the tagged-camel ride,
     * 3) HorseJumpEvent is cancelled by MountListener,
     * 4) EntityMoveEvent clamps any horizontal dash impulse that still slips
     *    through Paper/Purpur's client-assisted jump timing.
     *
     * There is no world/entity scan. The repeating guard only contains active
     * tagged-camel drivers, while the final clamp is event-driven.
     */
    private void handleNativeCamelInput(Player player, Input input) {
        UUID playerId = player.getUniqueId();
        Entity vehicle = player.getVehicle();

        if (!isTaggedNativeCamelDriver(player, vehicle)) {
            nativeCamelJumpHeld.remove(playerId);
            nativeCamelDashGuardMounts.remove(playerId);
            return;
        }

        Camel camel = (Camel) vehicle;
        boolean jumpPressed = input.isJump();
        boolean wasHeld = nativeCamelJumpHeld.contains(playerId);

        // Once this driver has produced input, keep the camel guarded for the
        // rest of the ride. Releasing SPACE no longer opens a timing window.
        nativeCamelDashGuardMounts.put(playerId, camel.getUniqueId());

        // Kill the vanilla dash state immediately on every input update.
        camel.setDashing(false);

        if (jumpPressed) {
            nativeCamelJumpHeld.add(playerId);
        } else {
            nativeCamelJumpHeld.remove(playerId);
        }

        // Rising edge only. Holding SPACE never repeats the custom jump.
        if (!jumpPressed || wasHeld || !camel.isOnGround()) {
            return;
        }

        Vector velocity = camel.getVelocity();
        velocity.setY(camelNormalJumpVelocity);
        camel.setVelocity(velocity);
    }

    private boolean isTaggedNativeCamelDriver(Player player, Entity vehicle) {
        if (!camelNormalJumpEnabled
                || !(vehicle instanceof Camel camel)
                || !camel.getScoreboardTags().contains(camelNormalJumpTag)
                || camel.getPassengers().isEmpty()) {
            return false;
        }

        return camel.getPassengers().getFirst().getUniqueId().equals(player.getUniqueId());
    }

    /**
     * Hard guard against delayed vanilla camel dash application.
     *
     * Cost: O(number of active tagged-camel drivers already seen through
     * PlayerInputEvent). There is no world scan: each entry is only a player
     * lookup + vehicle/tag check + setDashing(false). The one-shot hard block
     * map normally contains zero entries and expires after three ticks.
     */
    private void tickNativeCamelDashGuards() {
        if (!nativeCamelHardDashBlockTicks.isEmpty()) {
            Iterator<Map.Entry<UUID, Integer>> hardIterator =
                    nativeCamelHardDashBlockTicks.entrySet().iterator();
            while (hardIterator.hasNext()) {
                Map.Entry<UUID, Integer> entry = hardIterator.next();
                int remaining = entry.getValue() - 1;
                if (remaining <= 0) {
                    hardIterator.remove();
                } else {
                    entry.setValue(remaining);
                }
            }
        }

        if (nativeCamelDashGuardMounts.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<UUID, UUID>> iterator = nativeCamelDashGuardMounts.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, UUID> entry = iterator.next();
            UUID playerId = entry.getKey();
            UUID camelId = entry.getValue();

            Player player = Bukkit.getPlayer(playerId);
            Entity vehicle = player == null ? null : player.getVehicle();

            if (player == null
                    || !player.isOnline()
                    || !isTaggedNativeCamelDriver(player, vehicle)
                    || !vehicle.getUniqueId().equals(camelId)) {
                iterator.remove();
                nativeCamelJumpHeld.remove(playerId);
                continue;
            }

            // Permanent per-ride guard. Even if vanilla re-arms the dash while
            // SPACE is held, released, or spammed, the state is cleared again
            // on the next server tick.
            ((Camel) vehicle).setDashing(false);
        }
    }

    /**
     * Hard movement-level anti-dash safety net.
     *
     * Paper's camel dash is partly driven by client jump input. HorseJumpEvent
     * cancellation + setDashing(false) are normally enough, but a vanilla
     * horizontal impulse can still be applied in an unlucky ordering.
     *
     * This method is called only from Paper's EntityMoveEvent. For tagged
     * camels it:
     * - always clears the dashing state;
     * - preserves vertical motion (our normal jump);
     * - clamps only abnormal horizontal displacement.
     *
     * Normal vanilla riding is not touched because the cap is derived from
     * the camel's real MOVEMENT_SPEED attribute.
     */
    public boolean suppressNativeCamelDashMotion(Camel camel, Location from, Location to) {
        if (!camelNormalJumpEnabled
                || camelNormalJumpTag == null
                || camelNormalJumpTag.isBlank()
                || !camel.getScoreboardTags().contains(camelNormalJumpTag)) {
            return false;
        }

        boolean hardBlock = nativeCamelHardDashBlockTicks.remove(camel.getUniqueId()) != null;
        boolean wasDashing = camel.isDashing();
        camel.setDashing(false);

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        // Strongest path: if Paper emitted HorseJumpEvent or the camel actually
        // entered dashing state, this movement gets ZERO horizontal dash.
        // Vertical Y is intentionally left untouched so the custom normal jump
        // still works.
        if (hardBlock || wasDashing) {
            to.setX(from.getX());
            to.setZ(from.getZ());
            return true;
        }

        AttributeInstance movementSpeed = camel.getAttribute(Attribute.MOVEMENT_SPEED);
        double configuredSpeed = movementSpeed == null ? 0.30D : movementSpeed.getValue();
        double maxHorizontal = Math.max(
                CAMEL_MIN_HORIZONTAL_SPEED_CAP,
                configuredSpeed * CAMEL_MAX_HORIZONTAL_SPEED_MULTIPLIER);

        if (horizontalDistance <= maxHorizontal) {
            return false;
        }

        // Last fallback for a client-assisted dash whose flag was already
        // cleared before EntityMoveEvent: cap the horizontal displacement to
        // ordinary riding speed while preserving direction and Y.
        if (horizontalDistance > 0.0D) {
            double scale = maxHorizontal / horizontalDistance;
            to.setX(from.getX() + dx * scale);
            to.setZ(from.getZ() + dz * scale);
        }

        return true;
    }

    /**
     * Returns true when a FLYING mount is submerged at least the configured
     * number of water blocks. This preserves the old MDVMounts behaviour while
     * keeping it cheap: only active flying sessions already inside water run
     * at most N direct block checks (N=4 by default).
     */
    private boolean shouldDismountFlyingMountForWaterDepth(MountSession session) {
        if (session.type() != MountType.FLYING
                || flyingMountMaxWaterDepth < 0) {
            return false;
        }

        LivingEntity mount = session.mount();
        if (!mount.isInWater()) {
            return false;
        }

        // A value of 0 means any contact with water is enough to dismount.
        if (flyingMountMaxWaterDepth == 0) {
            return true;
        }

        Block currentBlock = mount.getLocation().getBlock();
        for (int i = 0; i < flyingMountMaxWaterDepth; i++) {
            if (!currentBlock.getRelative(0, i, 0).isLiquid()) {
                return false;
            }
        }

        return true;
    }

    public int activeCount() {
        return sessions.size();
    }

    private void prepareForControl(MountSession session) {
        movement.prepare(session);
    }

    private void restoreAfterControl(MountSession session) {
        movement.restore(session);
    }

    private void tick() {
        // Native tagged camels do not create a normal MDVMounts session, so
        // their anti-dash guard must run before the sessions-empty fast return.
        tickNativeCamelDashGuards();

        if (sessions.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<UUID, MountSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            MountSession session = iterator.next().getValue();

            Player player = session.player();
            LivingEntity mount = session.mount();
            Entity vehicle = player.getVehicle();

            if (!player.isOnline()
                    || !mount.isValid()
                    || mount.isDead()
                    || vehicle == null
                    || !vehicle.getUniqueId().equals(mount.getUniqueId())) {
                iterator.remove();
                restoreAfterControl(session);
                continue;
            }

            if (shouldDismountFlyingMountForWaterDepth(session)) {
                // Remove the session before leaveVehicle() so EntityDismountEvent
                // sees no active session and cannot perform duplicate cleanup.
                iterator.remove();
                clearInputState(player);
                restoreAfterControl(session);
                if (player.getVehicle() != null) {
                    player.leaveVehicle();
                }
                continue;
            }

            movement.tick(session);
        }
    }

}
