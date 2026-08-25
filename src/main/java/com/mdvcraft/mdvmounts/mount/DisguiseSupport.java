package com.mdvcraft.mdvmounts.mount;

import org.bukkit.entity.Entity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Optional LibsDisguises bridge without adding a hard dependency.
 *
 * MythicMobs disguises are normally backed by LibsDisguises. A real horse
 * disguised as a non-horse can still be mounted, but vanilla horse steering
 * disappears on the client. We only need to know whether a disguise exists,
 * once when the riding session is created, so reflection here has no tick cost.
 */
final class DisguiseSupport {
    private static final Method IS_DISGUISED = findIsDisguisedMethod();

    private DisguiseSupport() {
    }

    static boolean isDisguised(Entity entity) {
        if (IS_DISGUISED == null || entity == null) {
            return false;
        }

        try {
            return Boolean.TRUE.equals(IS_DISGUISED.invoke(null, entity));
        } catch (IllegalAccessException | InvocationTargetException | LinkageError ignored) {
            // If LibsDisguises changes its API or is unavailable, fail open:
            // non-disguised horses continue using native steering as before.
            return false;
        }
    }

    private static Method findIsDisguisedMethod() {
        try {
            Class<?> api = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            return api.getMethod("isDisguised", Entity.class);
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError ignored) {
            return null;
        }
    }
}
