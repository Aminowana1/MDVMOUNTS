package com.mdvcraft.mdvmounts.mount;

import com.mdvcraft.mdvmounts.MDVMountsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class MountManager {
    private static final double DEFAULT_JUMP_STRENGTH = 0.42D;

    private final MDVMountsPlugin plugin;
    private final Map<UUID, MountSession> sessions = new HashMap<>();

    private BukkitTask tickTask;

    private boolean requireBaseTag;
    private String baseTag;
    private String groundTag;
    private String flyingTag;
    private String aquaticTag;
    private String lavaTag;
    private String jumperTag;

    private boolean pauseVanillaAi;
    private boolean rotateWithRider;
    private int verticalDismountTaps;
    private long verticalDismountWindowMs;

    public MountManager(MDVMountsPlugin plugin) {
        this.plugin = plugin;
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
        boolean previouslyPausedAi = pauseVanillaAi;

        requireBaseTag = plugin.getConfig().getBoolean("tags.require-base-tag", true);
        baseTag = plugin.getConfig().getString("tags.base", "mdv_mount");
        groundTag = plugin.getConfig().getString("tags.ground", "mdv_mount_ground");
        flyingTag = plugin.getConfig().getString("tags.flying", "mdv_mount_flying");
        aquaticTag = plugin.getConfig().getString("tags.aquatic", "mdv_mount_aquatic");
        lavaTag = plugin.getConfig().getString("tags.lava", "mdv_mount_lava");
        jumperTag = plugin.getConfig().getString("tags.jumper", "mdv_mount_jumper");

        pauseVanillaAi = plugin.getConfig().getBoolean("control.pause-vanilla-ai-while-ridden", true);
        rotateWithRider = plugin.getConfig().getBoolean("control.rotate-mount-with-rider", true);
        verticalDismountTaps = Math.max(1, plugin.getConfig().getInt("control.vertical-mount-dismount-taps", 3));
        verticalDismountWindowMs = Math.max(100L,
                plugin.getConfig().getLong("control.vertical-mount-dismount-window-ms", 900L));

        // Si se cambia esta opción con /mdvmounts reload mientras hay
        // monturas activas, aplicamos el nuevo estado sin dejar mobs colgados.
        if (previouslyPausedAi != pauseVanillaAi && !sessions.isEmpty()) {
            for (MountSession session : sessions.values()) {
                if (!(session.mount() instanceof Mob mob) || session.originalAware() == null) {
                    continue;
                }
                if (pauseVanillaAi) {
                    mob.getPathfinder().stopPathfinding();
                    mob.setAware(false);
                } else if (session.mount().isValid()) {
                    mob.setAware(session.originalAware());
                }
            }
        }
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
        if (tags.contains(flyingTag)) return Optional.of(MountType.FLYING);
        if (tags.contains(aquaticTag)) return Optional.of(MountType.AQUATIC);
        if (tags.contains(lavaTag)) return Optional.of(MountType.LAVA);
        if (tags.contains(jumperTag)) return Optional.of(MountType.JUMPER);
        if (tags.contains(groundTag)) return Optional.of(MountType.GROUND);

        return Optional.empty();
    }

    public boolean isMountCandidate(Entity entity) {
        return resolveType(entity).isPresent();
    }

    public MountSession getSession(Player player) {
        return sessions.get(player.getUniqueId());
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
        if (session == null || !session.type().sneakControlsVerticalMovement()) {
            return true;
        }

        return session.registerDismountTap(
                System.currentTimeMillis(),
                verticalDismountTaps,
                verticalDismountWindowMs
        );
    }

    public int activeCount() {
        return sessions.size();
    }

    private void prepareForControl(MountSession session) {
        LivingEntity mount = session.mount();

        if (pauseVanillaAi && mount instanceof Mob mob) {
            mob.getPathfinder().stopPathfinding();
            mob.setAware(false);
        }

        if (session.type() == MountType.FLYING) {
            mount.setGravity(false);
            mount.setFallDistance(0.0F);
        }
    }

    private void restoreAfterControl(MountSession session) {
        LivingEntity mount = session.mount();
        if (!mount.isValid()) {
            return;
        }

        mount.setGravity(session.originalGravity());

        if (mount instanceof Mob mob && session.originalAware() != null) {
            // Restauramos siempre el estado original, incluso si la config se
            // recargó mientras la montura estaba siendo usada.
            mob.setAware(session.originalAware());
        }
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

            if (!player.isOnline()
                    || !mount.isValid()
                    || mount.isDead()
                    || player.getVehicle() == null
                    || !player.getVehicle().getUniqueId().equals(mount.getUniqueId())) {
                iterator.remove();
                restoreAfterControl(session);
                continue;
            }

            if (pauseVanillaAi && mount instanceof Mob mob) {
                // No se toca el registro de skills de MythicMobs. Sólo se evita
                // que el pathfinder vanilla intente pelear contra el control WASD.
                mob.getPathfinder().stopPathfinding();
                if (mob.isAware()) {
                    mob.setAware(false);
                }
            }

            applyControl(session, player.getCurrentInput());
        }
    }

    private void applyControl(MountSession session, Input input) {
        Player player = session.player();
        LivingEntity mount = session.mount();

        if (rotateWithRider) {
            mount.setRotation(player.getYaw(), 0.0F);
        }

        double speed = nativeMovementSpeed(mount);
        Vector horizontal = horizontalInput(player, input);

        switch (session.type()) {
            case GROUND -> applyGround(session, input, horizontal, speed, false);
            case JUMPER -> applyGround(session, input, horizontal, speed, true);
            case FLYING -> applyFreeMovement(session, input, horizontal, speed);
            case AQUATIC -> {
                if (mount.isInWater()) {
                    applyFreeMovement(session, input, horizontal, speed);
                } else {
                    applyGround(session, input, horizontal, speed, false);
                }
            }
            case LAVA -> {
                if (mount.isInLava()) {
                    applyFreeMovement(session, input, horizontal, speed);
                } else {
                    applyGround(session, input, horizontal, speed, false);
                }
            }
        }
    }

    private void applyGround(MountSession session, Input input, Vector horizontal, double speed, boolean autoJump) {
        LivingEntity mount = session.mount();
        mount.setGravity(session.originalGravity());

        Vector current = mount.getVelocity();
        double x = 0.0D;
        double z = 0.0D;

        if (horizontal.lengthSquared() > 0.0D && speed > 0.0D) {
            Vector desired = horizontal.multiply(speed);
            x = desired.getX();
            z = desired.getZ();
        }

        double y = current.getY();
        boolean wantsJump = input.isJump() || (autoJump && horizontal.lengthSquared() > 0.0D);
        if (wantsJump && mount.isOnGround()) {
            y = nativeJumpStrength(mount);
        }

        mount.setVelocity(new Vector(x, y, z));
    }

    private void applyFreeMovement(MountSession session, Input input, Vector horizontal, double speed) {
        LivingEntity mount = session.mount();
        mount.setGravity(false);
        mount.setFallDistance(0.0F);

        double x = 0.0D;
        double z = 0.0D;
        if (horizontal.lengthSquared() > 0.0D && speed > 0.0D) {
            Vector desired = horizontal.multiply(speed);
            x = desired.getX();
            z = desired.getZ();
        }

        double vertical = 0.0D;
        if (input.isJump() && !input.isSneak()) {
            vertical = speed;
        } else if (input.isSneak() && !input.isJump()) {
            vertical = -speed;
        }

        mount.setVelocity(new Vector(x, vertical, z));
    }

    private Vector horizontalInput(Player player, Input input) {
        double forwardInput = 0.0D;
        if (input.isForward()) forwardInput += 1.0D;
        if (input.isBackward()) forwardInput -= 1.0D;

        double strafeInput = 0.0D;
        if (input.isRight()) strafeInput += 1.0D;
        if (input.isLeft()) strafeInput -= 1.0D;

        if (forwardInput == 0.0D && strafeInput == 0.0D) {
            return new Vector();
        }

        double yaw = Math.toRadians(player.getYaw());
        Vector forward = new Vector(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        Vector right = new Vector(Math.cos(yaw), 0.0D, Math.sin(yaw));

        Vector direction = forward.multiply(forwardInput).add(right.multiply(strafeInput));
        if (direction.lengthSquared() > 1.0D) {
            direction.normalize();
        }
        return direction;
    }

    private double nativeMovementSpeed(LivingEntity mount) {
        AttributeInstance attribute = mount.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attribute == null) {
            return 0.0D;
        }
        return Math.max(0.0D, attribute.getValue());
    }

    private double nativeJumpStrength(LivingEntity mount) {
        AttributeInstance attribute = mount.getAttribute(Attribute.JUMP_STRENGTH);
        if (attribute == null) {
            return DEFAULT_JUMP_STRENGTH;
        }
        return Math.max(0.0D, attribute.getValue());
    }
}
