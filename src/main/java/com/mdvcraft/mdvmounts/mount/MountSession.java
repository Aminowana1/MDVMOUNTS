package com.mdvcraft.mdvmounts.mount;

import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

public final class MountSession {
    private final Player player;
    private final LivingEntity mount;
    private final MountType type;
    private final boolean originalGravity;
    private final Boolean originalAware;
    private final boolean nativeGroundSteering;

    private long lastDismountTapAt;
    private int dismountTapCount;

    // Cached native attributes. They are refreshed periodically instead of
    // being queried every server tick.
    private double cachedMovementSpeed;
    private double cachedJumpStrength;
    private int attributeRefreshCountdown;

    // Small runtime state used to avoid redundant Bukkit calls.
    private float lastAppliedYaw = Float.NaN;
    private float cachedDirectionYaw = Float.NaN;
    private double cachedYawSin;
    private double cachedYawCos = 1.0D;
    private boolean freeMovementMode;
    private boolean manualInputActive;

    public MountSession(Player player, LivingEntity mount, MountType type) {
        this.player = player;
        this.mount = mount;
        this.type = type;
        this.originalGravity = mount.hasGravity();
        this.originalAware = mount instanceof Mob mob ? mob.isAware() : null;

        // A real horse already has Minecraft's native mounted controls.
        // Delegating ground horses to vanilla gives the best possible WASD
        // feel and avoids custom velocity calculations entirely for them.
        this.nativeGroundSteering = type == MountType.GROUND && mount instanceof AbstractHorse;
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

    public boolean nativeGroundSteering() {
        return nativeGroundSteering;
    }

    public boolean registerDismountTap(long now, int requiredTaps, long windowMillis) {
        if (now - lastDismountTapAt > windowMillis) {
            dismountTapCount = 0;
        }

        lastDismountTapAt = now;
        dismountTapCount++;

        if (dismountTapCount >= requiredTaps) {
            dismountTapCount = 0;
            return true;
        }
        return false;
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

    public boolean shouldApplyYaw(float yaw) {
        if (Float.compare(lastAppliedYaw, yaw) == 0) {
            return false;
        }
        lastAppliedYaw = yaw;
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

    public boolean manualInputActive() {
        return manualInputActive;
    }

    public void setManualInputActive(boolean manualInputActive) {
        this.manualInputActive = manualInputActive;
    }
}
