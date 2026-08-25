package com.mdvcraft.mdvmounts.mount;

public enum MountType {
    GROUND(false),
    FLYING(true),
    AQUATIC(true),
    LAVA(true),
    JUMPER(false);

    private final boolean sneakControlsVerticalMovement;

    MountType(boolean sneakControlsVerticalMovement) {
        this.sneakControlsVerticalMovement = sneakControlsVerticalMovement;
    }

    public boolean sneakControlsVerticalMovement() {
        return sneakControlsVerticalMovement;
    }
}
