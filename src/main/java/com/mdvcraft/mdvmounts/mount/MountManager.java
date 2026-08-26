package com.mdvcraft.mdvmounts.mount;

import com.mdvcraft.mdvmounts.MDVMountsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Input;
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

    // PlayerInputEvent can also fire when W/A/S/D changes while SPACE remains
    // held. Remember the rising edge so a tagged native camel jumps only once.
    private final Set<UUID> nativeCamelJumpHeld = new HashSet<>();

    // Vanilla camels can apply their dash a little later than PlayerInputEvent.
    // Keep a tiny guard only for players currently holding SPACE and for a few
    // ticks after release. This is NOT a global entity scan: it contains only
    // active tagged-camel drivers that have actually used the jump key.
    private static final int CAMEL_DASH_RELEASE_GUARD_TICKS = 8;
    private final Map<UUID, UUID> nativeCamelDashGuardMounts = new HashMap<>();
    private final Map<UUID, Integer> nativeCamelDashReleaseTicks = new HashMap<>();

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
        nativeCamelDashReleaseTicks.clear();
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
        nativeCamelDashReleaseTicks.remove(playerId);
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

        camel.setDashing(false);
        return true;
    }

    /**
     * Special handling for a real vanilla Camel that intentionally does NOT
     * use mdv_mount/mdv_mount_ground. Only the first passenger drives it.
     *
     * SPACE becomes a small normal vertical jump. The vanilla dash is blocked
     * in three layers:
     * 1) immediately on every PlayerInputEvent,
     * 2) every tick while SPACE is held + a short release window,
     * 3) HorseJumpEvent is cancelled by MountListener for tagged camels.
     *
     * The repeating guard is extremely small: it only iterates players that
     * are actively using SPACE on a tagged camel; there is no world/entity scan.
     */
    private void handleNativeCamelInput(Player player, Input input) {
        UUID playerId = player.getUniqueId();
        Entity vehicle = player.getVehicle();

        if (!isTaggedNativeCamelDriver(player, vehicle)) {
            nativeCamelJumpHeld.remove(playerId);
            nativeCamelDashGuardMounts.remove(playerId);
            nativeCamelDashReleaseTicks.remove(playerId);
            return;
        }

        Camel camel = (Camel) vehicle;
        boolean jumpPressed = input.isJump();
        boolean wasHeld = nativeCamelJumpHeld.contains(playerId);

        nativeCamelDashGuardMounts.put(playerId, camel.getUniqueId());
        camel.setDashing(false);

        if (jumpPressed) {
            nativeCamelJumpHeld.add(playerId);
            // Keep this armed as long as SPACE remains held.
            nativeCamelDashReleaseTicks.put(playerId, CAMEL_DASH_RELEASE_GUARD_TICKS);
        } else {
            nativeCamelJumpHeld.remove(playerId);
            // Vanilla may process the release after the input event. Keep
            // suppressing for a few ticks so a late dash cannot slip through.
            nativeCamelDashReleaseTicks.put(playerId, CAMEL_DASH_RELEASE_GUARD_TICKS);
        }

        // Rising edge only: holding SPACE never repeats normal jumps in mid-air.
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
     * Cost: O(number of tagged camel drivers currently holding/recently
     * releasing SPACE). Normally this is zero, and even with several players
     * it is only a UUID lookup + vehicle/tag check + setDashing(false).
     */
    private void tickNativeCamelDashGuards() {
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
                nativeCamelDashReleaseTicks.remove(playerId);
                continue;
            }

            Camel camel = (Camel) vehicle;
            boolean held = nativeCamelJumpHeld.contains(playerId);
            int releaseTicks = nativeCamelDashReleaseTicks.getOrDefault(playerId, 0);

            if (!held && releaseTicks <= 0) {
                iterator.remove();
                nativeCamelDashReleaseTicks.remove(playerId);
                continue;
            }

            // Do this every tick while SPACE is held and during the short
            // release guard. This prevents the native dash state from surviving
            // any ordering difference between client input and camel ticking.
            camel.setDashing(false);

            if (held) {
                nativeCamelDashReleaseTicks.put(playerId, CAMEL_DASH_RELEASE_GUARD_TICKS);
            } else {
                nativeCamelDashReleaseTicks.put(playerId, releaseTicks - 1);
            }
        }
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

            movement.tick(session);
        }
    }

}
