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

  // Vanilla-like rider input factors used by Minecraft mounted movement:
  // backwards is noticeably slower and strafing is reduced.
  private static final double BACKWARD_MULTIPLIER = 0.25D;
  private static final double STRAFE_MULTIPLIER = 0.50D;

  private final MDVMountsPlugin plugin;

  private boolean pauseVanillaAi;
  private boolean rotateWithRider;
  private int verticalDismountTaps;
  private long verticalDismountWindowMs;

  // Cached config. These used to be read from YAML every movement tick.
  private boolean multiplyVerticalByJumpStrength;
  private double verticalSpeedMultiplier;
  private int attributeRefreshTicks;

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

    // Read once on reload, not every tick.
    multiplyVerticalByJumpStrength = plugin.getConfig().getBoolean(
        "control.vertical-speed.multiply-by-jump-strength",
        true);

    verticalSpeedMultiplier = plugin.getConfig().getDouble(
        "control.vertical-speed.base-multiplier",
        1.5D);

    attributeRefreshTicks = Math.max(1, plugin.getConfig().getInt(
        "performance.attribute-refresh-ticks",
        10));

    verticalDismountTaps = Math.max(1, plugin.getConfig().getInt(
        "control.vertical-dismount.taps",
        3));

    verticalDismountWindowMs = Math.max(1L, plugin.getConfig().getLong(
        "control.vertical-dismount.window-ms",
        900L));

    if (previouslyPausedAi != pauseVanillaAi) {
      for (MountSession session : activeSessions) {
        if (!(session.mount() instanceof Mob mob)
            || session.originalAware() == null
            || !session.mount().isValid()) {
          continue;
        }

        // Ground horses are delegated to Minecraft's native mounted control.
        // Their vanilla AI state must remain untouched while ridden.
        if (session.nativeGroundSteering()) {
          mob.setAware(session.originalAware());
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

    // Real ground horses use native Minecraft steering: zero custom velocity
    // work, exact vanilla WASD feel.
    if (!session.nativeGroundSteering()
        && pauseVanillaAi
        && mount instanceof Mob mob) {
      mob.getPathfinder().stopPathfinding();
      mob.setAware(false);
    }

    if (!session.nativeGroundSteering()) {
      refreshNativeAttributes(session);
    }

    if (session.type() == MountType.FLYING) {
      enterFreeMovement(session);
    }
  }

  public void restore(MountSession session) {
    LivingEntity mount = session.mount();

    if (!mount.isValid()) {
      return;
    }

    mount.setGravity(session.originalGravity());
    session.setFreeMovementMode(false);
    session.setManualInputActive(false);

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
    // Exact vanilla control for actual HORSE/DONKEY/etc. ground mounts.
    // Minecraft itself consumes the rider input, so MDVMounts has nothing to
    // calculate here.
    if (session.nativeGroundSteering()) {
      return;
    }

    Player player = session.player();
    LivingEntity mount = session.mount();

    if (!mount.isValid() || !player.isOnline()) {
      return;
    }

    if (session.shouldRefreshAttributes()) {
      refreshNativeAttributes(session);
    }

    Input input = player.getCurrentInput();

    if (rotateWithRider && session.shouldApplyYaw(player.getYaw())) {
      mount.setRotation(player.getYaw(), 0.0F);
    }

    switch (session.type()) {
      case GROUND ->
        applyGroundMovement(session, input, false);

      case JUMPER ->
        applyGroundMovement(session, input, true);

      case FLYING ->
        applyFreeMovement(session, input);

      case AQUATIC -> {
        if (mount.isInWater()) {
          applyFreeMovement(session, input);
        } else {
          applyGroundMovement(session, input, false);
        }
      }

      case LAVA -> {
        if (mount.isInLava()) {
          applyFreeMovement(session, input);
        } else {
          applyGroundMovement(session, input, false);
        }
      }
    }
  }

  private void applyGroundMovement(
      MountSession session,
      Input input,
      boolean autoJump) {

    LivingEntity mount = session.mount();
    leaveFreeMovement(session);

    Vector horizontal = horizontalVelocity(
        session.player(),
        input,
        session.cachedMovementSpeed());

    boolean hasHorizontalInput = horizontal != null;
    boolean wantsJump = input.isJump() || (autoJump && hasHorizontalInput);

    // No input: let Minecraft's own ground drag/friction slow the entity.
    // This avoids writing velocity every idle tick and feels much closer to
    // vanilla than forcing a custom friction curve.
    if (!hasHorizontalInput && !wantsJump) {
      session.setManualInputActive(false);
      return;
    }

    Vector velocity = mount.getVelocity();

    if (hasHorizontalInput) {
      // Immediate response: no acceleration interpolation, no input delay.
      velocity.setX(horizontal.getX());
      velocity.setZ(horizontal.getZ());
    }

    if (wantsJump && mount.isOnGround()) {
      velocity.setY(session.cachedJumpStrength());
    }

    mount.setVelocity(velocity);
    session.setManualInputActive(true);
  }

  private void applyFreeMovement(
      MountSession session,
      Input input) {

    LivingEntity mount = session.mount();
    enterFreeMovement(session);

    if (mount.getFallDistance() != 0.0F) {
      mount.setFallDistance(0.0F);
    }

    Vector horizontal = horizontalVelocity(
        session.player(),
        input,
        session.cachedMovementSpeed());

    double baseVerticalSpeed = session.cachedMovementSpeed() * verticalSpeedMultiplier;

    if (multiplyVerticalByJumpStrength) {
      baseVerticalSpeed *= session.cachedJumpStrength();
    }

    double targetY = 0.0D;

    if (input.isJump()) {
      // Preserve the friend's current flight behaviour: SPACE climbs.
      targetY = baseVerticalSpeed;
    } else if (input.isForward() || input.isBackward()) {
      float pitch = session.player().getPitch();

      // -90 = up, 0 = horizontal, +90 = down.
      double verticalFactor = -Math.sin(Math.toRadians(pitch));

      // S reverses the vertical direction, exactly as in the current pull.
      if (input.isBackward()) {
        verticalFactor *= -1.0D;
      }

      targetY = verticalFactor * baseVerticalSpeed;
    }

    boolean hasInput = horizontal != null || input.isJump();

    // When the player releases everything, stop once and then stay idle.
    // We do not keep sending a zero velocity every server tick.
    if (!hasInput) {
      if (session.manualInputActive()) {
        mount.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
        session.setManualInputActive(false);
      }
      return;
    }

    double x = horizontal == null ? 0.0D : horizontal.getX();
    double z = horizontal == null ? 0.0D : horizontal.getZ();

    // Immediate WASD response. The previous acceleration interpolation was the
    // source of the delayed/heavy feeling.
    mount.setVelocity(new Vector(x, targetY, z));
    session.setManualInputActive(true);
  }

  /**
   * Returns the requested horizontal velocity using vanilla-like rider input
   * factors. Returns null when there is no horizontal input, avoiding a Vector
   * allocation on idle ticks.
   */
  private Vector horizontalVelocity(Player player, Input input, double speed) {
    if (speed <= 0.0D) {
      return null;
    }

    double forwardInput = 0.0D;

    if (input.isForward() && !input.isBackward()) {
      forwardInput = 1.0D;
    } else if (input.isBackward() && !input.isForward()) {
      forwardInput = -BACKWARD_MULTIPLIER;
    }

    double strafeInput = 0.0D;

    if (input.isLeft() && !input.isRight()) {
      strafeInput = STRAFE_MULTIPLIER;
    } else if (input.isRight() && !input.isLeft()) {
      strafeInput = -STRAFE_MULTIPLIER;
    }

    if (forwardInput == 0.0D && strafeInput == 0.0D) {
      return null;
    }

    // Clamp diagonal input to avoid an artificial diagonal speed boost.
    double inputLengthSquared = forwardInput * forwardInput + strafeInput * strafeInput;
    if (inputLengthSquared > 1.0D) {
      double scale = 1.0D / Math.sqrt(inputLengthSquared);
      forwardInput *= scale;
      strafeInput *= scale;
    }

    double yaw = Math.toRadians(player.getYaw());
    double sin = Math.sin(yaw);
    double cos = Math.cos(yaw);

    double x = (-sin * forwardInput + cos * strafeInput) * speed;
    double z = ( cos * forwardInput + sin * strafeInput) * speed;

    return new Vector(x, 0.0D, z);
  }

  private void enterFreeMovement(MountSession session) {
    if (session.freeMovementMode()) {
      return;
    }

    LivingEntity mount = session.mount();
    mount.setGravity(false);
    mount.setFallDistance(0.0F);
    session.setFreeMovementMode(true);
  }

  private void leaveFreeMovement(MountSession session) {
    if (!session.freeMovementMode()) {
      return;
    }

    session.mount().setGravity(session.originalGravity());
    session.setFreeMovementMode(false);
    session.setManualInputActive(false);
  }

  private void refreshNativeAttributes(MountSession session) {
    LivingEntity mount = session.mount();

    session.cacheAttributes(
        getNativeMovementSpeed(mount),
        getNativeJumpStrength(mount),
        attributeRefreshTicks);
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
