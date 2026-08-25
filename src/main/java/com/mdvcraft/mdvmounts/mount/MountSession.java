package com.mdvcraft.mdvmounts.mount;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

public final class MountSession {
    private final Player player;
    private final LivingEntity mount;
    private final MountType type;
    private final boolean originalGravity;
    private final Boolean originalAware;

    private long lastDismountTapAt;
    private int dismountTapCount;

    public MountSession(Player player, LivingEntity mount, MountType type) {
        this.player = player;
        this.mount = mount;
        this.type = type;
        this.originalGravity = mount.hasGravity();
        this.originalAware = mount instanceof Mob mob ? mob.isAware() : null;
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
}
