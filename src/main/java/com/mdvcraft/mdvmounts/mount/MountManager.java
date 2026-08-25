package com.mdvcraft.mdvmounts.mount;

import com.mdvcraft.mdvmounts.MDVMountsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Input;
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
    private long tickCounter;
    private int nativeSafetyCheckTicks;

    private boolean requireBaseTag;
    private String baseTag;
    private String groundTag;
    private String flyingTag;
    private String aquaticTag;
    private String lavaTag;
    private String jumperTag;

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

        nativeSafetyCheckTicks = Math.max(1, plugin.getConfig().getInt(
                "performance.native-session-safety-check-ticks", 20));

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
     * Fast path for key transitions. Paper fires PlayerInputEvent when the
     * client changes W/A/S/D/jump/etc.; applying that change immediately
     * removes up to one scheduler tick of perceived steering latency.
     */
    public void handleInput(Player player, Input input) {
        MountSession session = sessions.get(player.getUniqueId());
        if (session == null || session.nativeGroundSteering()) {
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

        MountSession session = new MountSession(player, mount, typeOptional.get());
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

    public boolean registerVerticalDismountAttempt(Player player) {
        MountSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return true;
        }
        return movement.registerDismountAttempt(session);
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

        tickCounter++;
        boolean nativeSafetyCheck = tickCounter % nativeSafetyCheckTicks == 0L;

        Iterator<Map.Entry<UUID, MountSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            MountSession session = iterator.next().getValue();

            // Non-disguised real ground horses are controlled 100% by Minecraft. Their
            // normal dismount/quit/death paths are event-driven, so they only
            // need a low-frequency safety validation instead of work every tick.
            if (session.nativeGroundSteering() && !nativeSafetyCheck) {
                continue;
            }

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
