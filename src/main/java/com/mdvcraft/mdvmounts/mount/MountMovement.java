package com.mdvcraft.mdvmounts.mount;

import com.mdvcraft.mdvmounts.MDVMountsPlugin;
import org.bukkit.Input;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public final class MountMovement {
  private static final double DEFAULT_JUMP_STRENGTH = 0.42D;

  private final MDVMountsPlugin plugin;
  private boolean pauseVanillaAi;
  private boolean rotateWithRider;
  private int verticalDismountTaps;
  private long verticalDismountWindowMs;

  public MountMovement(MDVMountsPlugin plugin) {
    this.plugin = plugin;
    reloadSettings();
  }

  public void reloadSettings() {
    reloadSettings(java.util.List.of());
  }

  public void reloadSettings(Iterable<MountSession> activeSessions) {
    boolean previouslyPausedAi = pauseVanillaAi;
    pauseVanillaAi = plugin.getConfig().getBoolean("control.pause-vanilla-ai-while-ridden", true);
    rotateWithRider = plugin.getConfig().getBoolean("control.rotate-mount-with-rider", true);
    verticalDismountTaps = Math.max(1, plugin.getConfig().getInt("control.vertical-mount-dismount-taps", 3));
    verticalDismountWindowMs = Math.max(100L,
        plugin.getConfig().getLong("control.vertical-mount-dismount-window-ms", 900L));

    if (previouslyPausedAi != pauseVanillaAi) {
      for (MountSession session : activeSessions) {
        if (!(session.mount() instanceof Mob mob) || session.originalAware() == null
            || !session.mount().isValid()) {
          continue;
        }
        if (pauseVanillaAi) {
          mob.getPathfinder().stopPathfinding();
          mob.setAware(false);
        } else {
          mob.setAware(session.originalAware());
        }
      }
    }
  }

  public void prepare(MountSession session) {
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

  public void restore(MountSession session) {
    LivingEntity mount = session.mount();
    if (!mount.isValid()) {
      return;
    }
    mount.setGravity(session.originalGravity());
    if (mount instanceof Mob mob && session.originalAware() != null) {
      mob.setAware(session.originalAware());
    }
  }

  public boolean registerDismountAttempt(MountSession session) {
    if (!session.type().sneakControlsVerticalMovement()) {
      return true;
    }
    return session.registerDismountTap(System.currentTimeMillis(), verticalDismountTaps, verticalDismountWindowMs);
  }

  public boolean isVerticalDismountInput(MountSession session) {
    return session.type().sneakControlsVerticalMovement()
        && session.player().getCurrentInput().isSneak();
  }

  public void tick(MountSession session) {
    Player player = session.player();
    LivingEntity mount = session.mount();
    Input input = player.getCurrentInput();
    if (pauseVanillaAi && mount instanceof Mob mob) {
      mob.getPathfinder().stopPathfinding();
      if (mob.isAware()) {
        mob.setAware(false);
      }
    }
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

    if (input.isForward())
      forwardInput += 1.0D;
    if (input.isBackward())
      forwardInput -= 1.0D;
    double strafeInput = 0.0D;
    if (input.isRight())
      strafeInput -= 1.0D;
    if (input.isLeft())
      strafeInput += 1.0D;
    if (forwardInput == 0.0D && strafeInput == 0.0D)
      return new Vector();

    double yaw = Math.toRadians(player.getYaw());
    Vector forward = new Vector(-Math.sin(yaw), 0.0D, Math.cos(yaw));
    Vector right = new Vector(Math.cos(yaw), 0.0D, Math.sin(yaw));
    Vector direction = forward.multiply(forwardInput).add(right.multiply(strafeInput));
    if (direction.lengthSquared() > 1.0D)
      direction.normalize();
    return direction;
  }

  private double nativeMovementSpeed(LivingEntity mount) {
    AttributeInstance attribute = mount.getAttribute(Attribute.MOVEMENT_SPEED);
    return attribute == null ? 0.0D : Math.max(0.0D, attribute.getValue());
  }

  private double nativeJumpStrength(LivingEntity mount) {
    AttributeInstance attribute = mount.getAttribute(Attribute.JUMP_STRENGTH);
    return attribute == null ? DEFAULT_JUMP_STRENGTH : Math.max(0.0D, attribute.getValue());
  }

}
