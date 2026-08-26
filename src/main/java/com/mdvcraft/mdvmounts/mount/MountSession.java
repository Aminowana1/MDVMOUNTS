package com.mdvcraft.mdvmounts.mount;

import org.bukkit.Input;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

public final class MountSession {
    private final Player player;
    private final LivingEntity mount;
    private final MountType type;
    private final boolean originalGravity;
    private final Boolean originalAware;
    private final Double originalStepHeightBase;
    private boolean wallClimber;


    // Cached native attributes. They are refreshed periodically instead of
    // being queried every server tick.
    private double cachedMovementSpeed;
    private double cachedJumpStrength;
    private int attributeRefreshCountdown;

    // Small runtime state used to avoid redundant Bukkit calls.
    private float lastAppliedYaw = Float.NaN;
    private float lastAppliedPitch = Float.NaN;
    private float cachedDirectionYaw = Float.NaN;
    private double cachedYawSin;
    private double cachedYawCos = 1.0D;
    private boolean freeMovementMode;
    private boolean manualInputActive;
    private int lastImmediateInputMask = Integer.MIN_VALUE;

    // Short-lived windows used only after an active mount skill is fired.
    // They let MythicMobs aim from the rider camera and preserve velocity
    // changes such as lunge/dash without changing normal steering.
    private int skillAimTicksRemaining;
    private int skillVelocityOverrideTicksRemaining;

    public MountSession(Player player,
                        LivingEntity mount,
                        MountType type,
                        boolean wallClimber) {
        this.player = player;
        this.mount = mount;
        this.type = type;
        this.wallClimber = wallClimber;
        this.originalGravity = mount.hasGravity();
        this.originalAware = mount instanceof Mob mob ? mob.isAware() : null;

        AttributeInstance stepHeight = mount.getAttribute(Attribute.STEP_HEIGHT);
        this.originalStepHeightBase = stepHeight == null ? null : stepHeight.getBaseValue();
    }

    public Player player() {
        return player;
    }

    public LivingEntity mount() {
        return mount;
    }

    public MountType type() {
        return type;
    }

    public boolean originalGravity() {
        return originalGravity;
    }

    public Boolean originalAware() {
        return originalAware;
    }

    public Double originalStepHeightBase() {
        return originalStepHeightBase;
    }

    public boolean wallClimber() {
        return wallClimber;
    }

    public void setWallClimber(boolean wallClimber) {
        this.wallClimber = wallClimber;
    }

    public double cachedMovementSpeed() {
        return cachedMovementSpeed;
    }

    public double cachedJumpStrength() {
        return cachedJumpStrength;
    }

    public void cacheAttributes(double movementSpeed, double jumpStrength, int refreshTicks) {
        cachedMovementSpeed = movementSpeed;
        cachedJumpStrength = jumpStrength;
        attributeRefreshCountdown = Math.max(1, refreshTicks);
    }

    public boolean shouldRefreshAttributes() {
        if (attributeRefreshCountdown <= 1) {
            return true;
        }
        attributeRefreshCountdown--;
        return false;
    }

    public boolean shouldApplyRotation(float yaw, float pitch) {
        if (Float.compare(lastAppliedYaw, yaw) == 0
                && Float.compare(lastAppliedPitch, pitch) == 0) {
            return false;
        }
        lastAppliedYaw = yaw;
        lastAppliedPitch = pitch;
        return true;
    }


    /**
     * Cache sin/cos for horizontal steering. If the rider keeps the same yaw,
     * manual mounts avoid two trigonometric calculations every server tick.
     */
    public void ensureDirectionCache(float yaw) {
        if (Float.compare(cachedDirectionYaw, yaw) == 0) {
            return;
        }
        cachedDirectionYaw = yaw;
        double radians = Math.toRadians(yaw);
        cachedYawSin = Math.sin(radians);
        cachedYawCos = Math.cos(radians);
    }

    public double cachedYawSin() {
        return cachedYawSin;
    }

    public double cachedYawCos() {
        return cachedYawCos;
    }
    public boolean freeMovementMode() {
        return freeMovementMode;
    }

    public void setFreeMovementMode(boolean freeMovementMode) {
        this.freeMovementMode = freeMovementMode;
    }


    /**
     * Returns true only when the client input state really changed.
     * PlayerInputEvent can be used for immediate response without doing
     * duplicate velocity writes if the same state is reported again.
     */
    public boolean acceptImmediateInput(Input input) {
        int mask = 0;
        if (input.isForward()) mask |= 1;
        if (input.isBackward()) mask |= 1 << 1;
        if (input.isLeft()) mask |= 1 << 2;
        if (input.isRight()) mask |= 1 << 3;
        if (input.isJump()) mask |= 1 << 4;
        if (input.isSneak()) mask |= 1 << 5;
        if (input.isSprint()) mask |= 1 << 6;

        if (mask == lastImmediateInputMask) {
            return false;
        }

        lastImmediateInputMask = mask;
        return true;
    }


    public void beginSkillAim(int ticks) {
        skillAimTicksRemaining = Math.max(skillAimTicksRemaining, Math.max(0, ticks));
    }

    public boolean skillAimActive() {
        return skillAimTicksRemaining > 0;
    }

    public void beginSkillVelocityOverride(int ticks) {
        skillVelocityOverrideTicksRemaining = Math.max(
                skillVelocityOverrideTicksRemaining,
                Math.max(0, ticks));
    }

    public boolean skillVelocityOverrideActive() {
        return skillVelocityOverrideTicksRemaining > 0;
    }

    /**
     * Advances the short skill windows exactly once from the normal mount tick.
     * PlayerInputEvent can fire more often, so it must not decrement them.
     */
    public void tickSkillWindows() {
        if (skillAimTicksRemaining > 0) {
            skillAimTicksRemaining--;
        }
        if (skillVelocityOverrideTicksRemaining > 0) {
            skillVelocityOverrideTicksRemaining--;
        }
    }

    public boolean manualInputActive() {
        return manualInputActive;
    }

    public void setManualInputActive(boolean manualInputActive) {
        this.manualInputActive = manualInputActive;
    }
}
