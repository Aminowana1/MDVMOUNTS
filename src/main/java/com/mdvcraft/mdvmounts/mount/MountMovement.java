package com.mdvcraft.mdvmounts.mount;

import com.mdvcraft.mdvmounts.MDVMountsPlugin;

import org.bukkit.Input;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public final class MountMovement {

  private static final double DEFAULT_JUMP_STRENGTH = 0.42D;

  private static final double DEFAULT_FORWARD_MULTIPLIER = 1.00D;
  private static final double DEFAULT_BACKWARD_MULTIPLIER = 0.25D;
  private static final double DEFAULT_LATERAL_MULTIPLIER = 0.50D;

  private final MDVMountsPlugin plugin;

  private boolean pauseVanillaAi;
  private boolean rotateWithRider;

  // Cached config. These used to be read from YAML every movement tick.
  private boolean multiplyVerticalByJumpStrength;
  private double verticalSpeedMultiplier;
  private int attributeRefreshTicks;

  // Horse-like ground feel for every GROUND mount. STEP_HEIGHT is
  // applied once when the rider mounts, so one-block ledges are handled by
  // Minecraft's own collision engine with zero block scans/raytraces per tick.
  private boolean groundHorseFeelEnabled;
  private double groundStepHeight;
  private boolean immediateInputResponse;

  // Optional spider-like climbing for tagged GROUND mounts. Normal ground
  // mounts still do zero block probes. A climber checks only the blocks
  // directly in the requested movement direction while the rider is moving.
  private boolean wallClimbingEnabled;
  private double wallClimbVerticalSpeed;
  private double wallClimbProbeDistance;

  // One tiny fixed array per setting: no HashMap lookup and no YAML read in
  // the movement loop. Values are loaded only on /mdvmounts reload.
  private final double[] forwardMultipliers = new double[MountType.values().length];
  private final double[] backwardMultipliers = new double[MountType.values().length];
  private final double[] lateralMultipliers = new double[MountType.values().length];
  private final boolean[] normalizeDiagonal = new boolean[MountType.values().length];

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

    groundHorseFeelEnabled = plugin.getConfig().getBoolean(
        "control.ground-horse-feel.enabled",
        true);

    groundStepHeight = Math.max(0.0D, plugin.getConfig().getDouble(
        "control.ground-horse-feel.step-height",
        1.0D));

    immediateInputResponse = plugin.getConfig().getBoolean(
        "performance.immediate-input-response",
        true);

    wallClimbingEnabled = plugin.getConfig().getBoolean(
        "control.wall-climbing.enabled",
        true);

    wallClimbVerticalSpeed = Math.max(0.0D, plugin.getConfig().getDouble(
        "control.wall-climbing.vertical-speed",
        0.22D));

    wallClimbProbeDistance = Math.max(0.01D, plugin.getConfig().getDouble(
        "control.wall-climbing.probe-distance",
        0.12D));

    loadMovementProfiles();

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

    // Re-apply the one-time ground STEP_HEIGHT setting after /mdvmounts reload.
    for (MountSession session : activeSessions) {
      if (!session.mount().isValid()) {
        continue;
      }
      if (session.type() == MountType.GROUND) {
        if (groundHorseFeelEnabled) {
          applyGroundHorseFeel(session);
        } else {
          restoreGroundHorseFeel(session);
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

    if (session.type() == MountType.GROUND && groundHorseFeelEnabled) {
      applyGroundHorseFeel(session);
    }
    refreshNativeAttributes(session);

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
    restoreGroundHorseFeel(session);

    if (mount instanceof Mob mob && session.originalAware() != null) {
      mob.setAware(session.originalAware());
    }
  }

  public void tick(MountSession session) {
    Player player = session.player();
    LivingEntity mount = session.mount();

    if (!mount.isValid() || !player.isOnline()) {
      return;
    }

    if (session.shouldRefreshAttributes()) {
      refreshNativeAttributes(session);
    }

    // MythicMobs movement mechanics (for example lunge/dash) set their own
    // velocity. While the short preservation window is active, do not replace
    // that velocity with the regular WASD controller. Normal steering resumes
    // automatically when the window ends.
    if (session.skillVelocityOverrideActive()) {
      applyRiderRotation(session);
      session.tickSkillWindows();
      return;
    }

    applyInput(session, player.getCurrentInput());
    session.tickSkillWindows();
  }

  /**
   * Applies a newly received client input immediately instead of waiting for
   * the next scheduler tick. This only runs when the input state changes; the
   * normal tick loop then maintains movement while a key remains held.
   */
  public void inputChanged(MountSession session, Input input) {
    if (!immediateInputResponse
        || !session.acceptImmediateInput(input)) {
      return;
    }

    Player player = session.player();
    LivingEntity mount = session.mount();
    if (!player.isOnline() || !mount.isValid() || mount.isDead()) {
      return;
    }

    if (session.skillVelocityOverrideActive()) {
      applyRiderRotation(session);
      return;
    }

    applyInput(session, input);
  }

  private void applyInput(MountSession session, Input input) {
    Player player = session.player();
    LivingEntity mount = session.mount();

    applyRiderRotation(session);

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

  private void applyRiderRotation(MountSession session) {
    // A skill-aim window is explicit and should work even if normal
    // rotate-mount-with-rider is disabled. Outside that window we preserve
    // the existing rotation setting exactly.
    if (!rotateWithRider && !session.skillAimActive()) {
      return;
    }

    Player player = session.player();
    LivingEntity mount = session.mount();

    // Normal movement keeps the historic yaw-only behaviour. During the short
    // skill-aim window we additionally mirror rider pitch so MythicMobs
    // targeters such as @Forward{lockpitch=false} use the rider camera.
    float pitch = session.skillAimActive() ? player.getPitch() : 0.0F;
    if (session.shouldApplyRotation(player.getYaw(), pitch)) {
      mount.setRotation(player.getYaw(), pitch);
    }
  }

  private void applyGroundMovement(
      MountSession session,
      Input input,
      boolean autoJump) {

    LivingEntity mount = session.mount();
    leaveFreeMovement(session);

    Vector horizontal = horizontalVelocity(
        session,
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

    boolean climbWall = wallClimbingEnabled
        && session.wallClimber()
        && hasHorizontalInput
        && shouldClimbWall(mount, horizontal);

    // A normal ground jump still has priority. Once airborne and pressed
    // against a wall, the climber keeps gaining Y until it reaches the top.
    if (wantsJump && mount.isOnGround()) {
      velocity.setY(session.cachedJumpStrength());
    } else if (climbWall) {
      velocity.setY(Math.max(velocity.getY(), wallClimbVerticalSpeed));
      if (mount.getFallDistance() != 0.0F) {
        mount.setFallDistance(0.0F);
      }
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
        session,
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
    // We do not keep sending a full zero velocity every server tick.
    if (!hasInput) {
      if (session.manualInputActive()) {
        mount.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
        session.setManualInputActive(false);
        return;
      }

      // Flying mounts must hover at the exact altitude where the rider
      // released the controls. Some native flying entities (notably bees)
      // can reintroduce a tiny vertical velocity even with gravity disabled.
      // Only read the current velocity and correct Y when it actually moved:
      // no direction math, attribute lookup, block scan or redundant write.
      if (session.type() == MountType.FLYING) {
        Vector velocity = mount.getVelocity();
        if (Math.abs(velocity.getY()) > 0.0001D) {
          velocity.setY(0.0D);
          mount.setVelocity(velocity);
        }
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
   * Returns the requested horizontal velocity using the cached profile for the
   * session type. All MDV mount types use the same immediate manual input path.
   */
  private Vector horizontalVelocity(MountSession session, Input input, double speed) {
    if (speed <= 0.0D) {
      return null;
    }

    int profile = session.type().ordinal();
    double forwardInput = 0.0D;

    if (input.isForward() && !input.isBackward()) {
      forwardInput = forwardMultipliers[profile];
    } else if (input.isBackward() && !input.isForward()) {
      forwardInput = -backwardMultipliers[profile];
    }

    double strafeInput = 0.0D;

    if (input.isLeft() && !input.isRight()) {
      strafeInput = lateralMultipliers[profile];
    } else if (input.isRight() && !input.isLeft()) {
      strafeInput = -lateralMultipliers[profile];
    }

    if (forwardInput == 0.0D && strafeInput == 0.0D) {
      return null;
    }

    // Optional diagonal normalization. It preserves the strongest configured
    // axis percentage, so values above 100% still work as expected.
    if (normalizeDiagonal[profile] && forwardInput != 0.0D && strafeInput != 0.0D) {
      double magnitude = Math.hypot(forwardInput, strafeInput);
      double cap = Math.max(Math.abs(forwardInput), Math.abs(strafeInput));
      if (magnitude > cap && cap > 0.0D) {
        double scale = cap / magnitude;
        forwardInput *= scale;
        strafeInput *= scale;
      }
    }

    Player player = session.player();
    session.ensureDirectionCache(player.getYaw());
    double sin = session.cachedYawSin();
    double cos = session.cachedYawCos();

    double x = (-sin * forwardInput + cos * strafeInput) * speed;
    double z = ( cos * forwardInput + sin * strafeInput) * speed;

    return new Vector(x, 0.0D, z);
  }

  /**
   * Detects a real wall directly in the requested horizontal direction.
   *
   * On ground we require TWO blocked heights. That deliberately leaves a
   * one-block ledge to STEP_HEIGHT=1.0, so climbers still walk up normal
   * steps instead of visibly "climbing" them. Once already airborne against
   * the wall, the lower probe is enough so the entity can crest the top edge.
   *
   * Cost: at most two Block#isPassable checks per moving climber per tick.
   * There are no raytraces, nearby-entity scans or pathfinding calls.
   */
  private boolean shouldClimbWall(LivingEntity mount, Vector horizontal) {
    double lengthSquared = horizontal.getX() * horizontal.getX()
        + horizontal.getZ() * horizontal.getZ();

    if (lengthSquared < 1.0E-8D) {
      return false;
    }

    double inverseLength = 1.0D / Math.sqrt(lengthSquared);
    double dirX = horizontal.getX() * inverseLength;
    double dirZ = horizontal.getZ() * inverseLength;

    BoundingBox box = mount.getBoundingBox();
    double centerX = (box.getMinX() + box.getMaxX()) * 0.5D;
    double centerZ = (box.getMinZ() + box.getMaxZ()) * 0.5D;
    double halfX = (box.getMaxX() - box.getMinX()) * 0.5D;
    double halfZ = (box.getMaxZ() - box.getMinZ()) * 0.5D;

    // Distance from AABB centre to the first side hit by a ray travelling in
    // the requested movement direction, plus a tiny probe outside the hitbox.
    double edgeX = Math.abs(dirX) < 1.0E-8D
        ? Double.POSITIVE_INFINITY
        : halfX / Math.abs(dirX);
    double edgeZ = Math.abs(dirZ) < 1.0E-8D
        ? Double.POSITIVE_INFINITY
        : halfZ / Math.abs(dirZ);
    double leadingEdge = Math.min(edgeX, edgeZ) + wallClimbProbeDistance;

    double probeX = centerX + dirX * leadingEdge;
    double probeZ = centerZ + dirZ * leadingEdge;
    double baseY = box.getMinY();

    World world = mount.getWorld();
    int blockX = floorToBlock(probeX);
    int blockZ = floorToBlock(probeZ);

    boolean lowerBlocked = isBlocked(
        world,
        blockX,
        floorToBlock(baseY + 0.20D),
        blockZ);

    if (!lowerBlocked) {
      return false;
    }

    // While already climbing, keep moving upward until the lower collision is
    // cleared. This gives enough height to step onto the top of tall walls.
    if (!mount.isOnGround()) {
      return true;
    }

    boolean upperBlocked = isBlocked(
        world,
        blockX,
        floorToBlock(baseY + 1.05D),
        blockZ);

    return upperBlocked;
  }

  private boolean isBlocked(World world, int x, int y, int z) {
    if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
      return false;
    }
    return !world.getBlockAt(x, y, z).isPassable();
  }

  private int floorToBlock(double coordinate) {
    return (int) Math.floor(coordinate);
  }

  private void loadMovementProfiles() {
    loadMovementProfile(MountType.GROUND, "ground");
    loadMovementProfile(MountType.FLYING, "flying");
    loadMovementProfile(MountType.AQUATIC, "aquatic");
    loadMovementProfile(MountType.LAVA, "lava");
    loadMovementProfile(MountType.JUMPER, "jumper");
  }

  private void loadMovementProfile(MountType type, String key) {
    int index = type.ordinal();
    String base = "control.movement-percentages." + key + ".";

    forwardMultipliers[index] = percentToMultiplier(
        plugin.getConfig().getDouble(base + "forward", DEFAULT_FORWARD_MULTIPLIER * 100.0D));
    backwardMultipliers[index] = percentToMultiplier(
        plugin.getConfig().getDouble(base + "backward", DEFAULT_BACKWARD_MULTIPLIER * 100.0D));
    lateralMultipliers[index] = percentToMultiplier(
        plugin.getConfig().getDouble(base + "lateral", DEFAULT_LATERAL_MULTIPLIER * 100.0D));
    normalizeDiagonal[index] = plugin.getConfig().getBoolean(base + "normalize-diagonal", true);
  }

  private double percentToMultiplier(double percent) {
    return Math.max(0.0D, percent) / 100.0D;
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

  private void applyGroundHorseFeel(MountSession session) {
    if (session.type() != MountType.GROUND) {
      return;
    }

    AttributeInstance stepHeight = session.mount().getAttribute(Attribute.STEP_HEIGHT);
    if (stepHeight == null) {
      return;
    }

    // Never reduce an entity that already has a larger native step height.
    double desired = Math.max(stepHeight.getBaseValue(), groundStepHeight);
    if (Double.compare(stepHeight.getBaseValue(), desired) != 0) {
      stepHeight.setBaseValue(desired);
    }
  }

  private void restoreGroundHorseFeel(MountSession session) {
    Double original = session.originalStepHeightBase();
    if (original == null || !session.mount().isValid()) {
      return;
    }

    AttributeInstance stepHeight = session.mount().getAttribute(Attribute.STEP_HEIGHT);
    if (stepHeight != null && Double.compare(stepHeight.getBaseValue(), original) != 0) {
      stepHeight.setBaseValue(original);
    }
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
