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

  // How quickly the mount reaches its target speed.
  private static final double GROUND_ACCELERATION = 0.35D;
  private static final double AIR_ACCELERATION = 0.20D;

  // How quickly the mount slows down when there is no input.
  private static final double GROUND_FRICTION = 0.65D;
  private static final double AIR_FRICTION = 0.85D;

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

    pauseVanillaAi = plugin.getConfig().getBoolean(
        "control.pause-vanilla-ai-while-ridden",
        true);

    rotateWithRider = plugin.getConfig().getBoolean(
        "control.rotate-mount-with-rider",
        true);

    if (previouslyPausedAi != pauseVanillaAi) {
      for (MountSession session : activeSessions) {
        if (!(session.mount() instanceof Mob mob)
            || session.originalAware() == null
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

    return session.registerDismountTap(
        System.currentTimeMillis(),
        verticalDismountTaps,
        verticalDismountWindowMs);
  }

  public void tick(MountSession session) {
    Player player = session.player();
    LivingEntity mount = session.mount();
    Input input = player.getCurrentInput();

    if (!mount.isValid() || !player.isOnline()) {
      return;
    }

    if (rotateWithRider) {
      mount.setRotation(player.getYaw(), 0.0F);
    }

    double speed = getNativeMovementSpeed(mount);
    Vector horizontal = horizontalInput(player, input);

    switch (session.type()) {
      case GROUND ->
        applyGroundMovement(session, input, horizontal, speed, false);

      case JUMPER ->
        applyGroundMovement(session, input, horizontal, speed, true);

      case FLYING ->
        applyFreeMovement(session, input, horizontal, speed);

      case AQUATIC -> {
        if (mount.isInWater()) {
          applyFreeMovement(session, input, horizontal, speed);
        } else {
          applyGroundMovement(session, input, horizontal, speed, false);
        }
      }

      case LAVA -> {
        if (mount.isInLava()) {
          applyFreeMovement(session, input, horizontal, speed);
        } else {
          applyGroundMovement(session, input, horizontal, speed, false);
        }
      }
    }
  }

  private void applyGroundMovement(
      MountSession session,
      Input input,
      Vector horizontal,
      double speed,
      boolean autoJump) {
    LivingEntity mount = session.mount();

    mount.setGravity(session.originalGravity());

    Vector velocity = mount.getVelocity();

    double targetX = 0.0D;
    double targetZ = 0.0D;

    if (horizontal.lengthSquared() > 0.0D && speed > 0.0D) {
      Vector desired = horizontal.clone().multiply(speed);

      targetX = desired.getX();
      targetZ = desired.getZ();
    }

    double acceleration = GROUND_ACCELERATION;
    double friction = GROUND_FRICTION;

    // Smoothly approach the desired horizontal velocity.
    double newX = velocity.getX()
        + (targetX - velocity.getX()) * acceleration;

    double newZ = velocity.getZ()
        + (targetZ - velocity.getZ()) * acceleration;

    // Apply friction when there is no input.
    if (horizontal.lengthSquared() == 0.0D) {
      newX *= friction;
      newZ *= friction;
    }

    double y = velocity.getY();

    boolean wantsJump = input.isJump()
        || (autoJump && horizontal.lengthSquared() > 0.0D);

    if (wantsJump && mount.isOnGround()) {
      y = getNativeJumpStrength(mount);
    }

    mount.setVelocity(new Vector(newX, y, newZ));
  }

  private void applyFreeMovement(
      MountSession session,
      Input input,
      Vector horizontal,
      double speed) {

    LivingEntity mount = session.mount();

    mount.setGravity(false);
    mount.setFallDistance(0.0F);

    Vector velocity = mount.getVelocity();

    // Horizontal movement
    double targetX = 0.0D;
    double targetZ = 0.0D;

    if (horizontal.lengthSquared() > 0.0D && speed > 0.0D) {
      Vector desired = horizontal.clone().multiply(speed);
      targetX = desired.getX();
      targetZ = desired.getZ();
    }

    double newX = velocity.getX()
        + (targetX - velocity.getX()) * AIR_ACCELERATION;

    double newZ = velocity.getZ()
        + (targetZ - velocity.getZ()) * AIR_ACCELERATION;

    if (horizontal.lengthSquared() == 0.0D) {
      newX *= AIR_FRICTION;
      newZ *= AIR_FRICTION;
    }

    // Vertical movement
    boolean multiplyByJumpStrength = plugin.getConfig().getBoolean(
        "vertical-speed.multiply-by-jump-strength",
        true);

    double verticalSpeedMultiplier = plugin.getConfig().getDouble(
        "vertical-speed.base-multiplier",
        1.5D);

    double baseVerticalSpeed = speed * verticalSpeedMultiplier;

    if (multiplyByJumpStrength) {
      baseVerticalSpeed *= getNativeJumpStrength(mount);
    }

    double targetY = 0.0D;

    if (input.isJump()) {
      // SPACE: maximum upward speed
      targetY = baseVerticalSpeed;
    } else if (input.isForward() || input.isBackward()) {
      float pitch = session.player().getPitch();

      // -90 = up, 0 = horizontal, +90 = down
      double verticalFactor = -Math.sin(Math.toRadians(pitch));

      // S reverses the vertical direction.
      if (input.isBackward()) {
        verticalFactor *= -1.0D;
      }

      targetY = verticalFactor * baseVerticalSpeed;
    }

    double newY = targetY;

    mount.setVelocity(new Vector(newX, newY, newZ));
  }

  private Vector horizontalInput(Player player, Input input) {
    double forwardInput = 0.0D;

    if (input.isForward()) {
      forwardInput += 1.0D;
    }

    if (input.isBackward()) {
      forwardInput -= 1.0D;
    }

    double strafeInput = 0.0D;

    if (input.isRight()) {
      strafeInput -= 1.0D;
    }

    if (input.isLeft()) {
      strafeInput += 1.0D;
    }

    if (forwardInput == 0.0D && strafeInput == 0.0D) {
      return new Vector();
    }

    double yaw = Math.toRadians(player.getYaw());

    Vector forward = new Vector(
        -Math.sin(yaw),
        0.0D,
        Math.cos(yaw));

    Vector right = new Vector(
        Math.cos(yaw),
        0.0D,
        Math.sin(yaw));

    Vector direction = forward
        .multiply(forwardInput)
        .add(right.multiply(strafeInput));

    if (direction.lengthSquared() > 1.0D) {
      direction.normalize();
    }

    return direction;
  }

  private double getNativeMovementSpeed(LivingEntity mount) {
    AttributeInstance attribute = mount.getAttribute(Attribute.MOVEMENT_SPEED);

    return attribute == null
        ? 0.0D
        : Math.max(0.0D, attribute.getValue());
  }

  private double getNativeJumpStrength(LivingEntity mount) {
    AttributeInstance attribute = mount.getAttribute(Attribute.JUMP_STRENGTH);

    return attribute == null
        ? DEFAULT_JUMP_STRENGTH
        : Math.max(0.0D, attribute.getValue());
  }
}