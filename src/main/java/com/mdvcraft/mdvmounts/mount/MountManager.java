package com.mdvcraft.mdvmounts.mount;

import com.mdvcraft.mdvmounts.MDVMountsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
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
    private String riderFallProtectionTag;


    // Maximum number of consecutive water blocks above the flying mount
    // before the rider is forced to dismount. Cached on reload so movement
    // ticks never read YAML. -1 disables the restriction.
    private int flyingMountMaxWaterDepth;


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
        riderFallProtectionTag = plugin.getConfig().getString(
                "tags.rider-fall-protection",
                "mdv_mount_rider_fall_protection");


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
        MountSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            restoreAfterControl(session);
        }
    }

    public void forceDismount(Player player) {
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
