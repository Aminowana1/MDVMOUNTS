package com.mdvcraft.mdvmounts.skill;

import org.bukkit.Input;

import java.util.Locale;

/**
 * Logical client inputs that Paper exposes through PlayerInputEvent.
 * These are actions, not physical keyboard keys. For example, SPRINT is
 * whatever key the player has bound to Minecraft's sprint action (Ctrl by
 * default on many clients).
 */
public enum MountSkillInput {
    SPRINT {
        @Override
        public boolean isPressed(Input input) {
            return input.isSprint();
        }
    },
    JUMP {
        @Override
        public boolean isPressed(Input input) {
            return input.isJump();
        }
    },
    SNEAK {
        @Override
        public boolean isPressed(Input input) {
            return input.isSneak();
        }
    },
    FORWARD {
        @Override
        public boolean isPressed(Input input) {
            return input.isForward();
        }
    },
    BACKWARD {
        @Override
        public boolean isPressed(Input input) {
            return input.isBackward();
        }
    },
    LEFT {
        @Override
        public boolean isPressed(Input input) {
            return input.isLeft();
        }
    },
    RIGHT {
        @Override
        public boolean isPressed(Input input) {
            return input.isRight();
        }
    };

    public abstract boolean isPressed(Input input);

    public static MountSkillInput parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SPRINT;
        }

        String normalized = raw.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');

        return switch (normalized) {
            // Friendly aliases for the default Minecraft bindings.
            case "CTRL", "CONTROL" -> SPRINT;
            case "SPACE", "ESPACIO" -> JUMP;
            case "SHIFT", "CROUCH" -> SNEAK;
            case "W" -> FORWARD;
            case "S" -> BACKWARD;
            case "A" -> LEFT;
            case "D" -> RIGHT;
            default -> {
                try {
                    yield valueOf(normalized);
                } catch (IllegalArgumentException ignored) {
                    yield SPRINT;
                }
            }
        };
    }
}
