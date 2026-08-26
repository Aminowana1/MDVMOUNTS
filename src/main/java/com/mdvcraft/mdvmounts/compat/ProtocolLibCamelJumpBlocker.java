package com.mdvcraft.mdvmounts.compat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.mdvcraft.mdvmounts.MDVMountsPlugin;
import com.mdvcraft.mdvmounts.mount.MountManager;

/**
 * Cancels vanilla camel riding-jump command packets before Minecraft's camel
 * code receives them.
 *
 * This is deliberately tiny and event-driven: for the two relevant packet
 * actions it performs only an enum comparison and a thread-safe UUID set
 * lookup. No Bukkit entity/world access is done from ProtocolLib's packet
 * thread.
 */
public final class ProtocolLibCamelJumpBlocker {
    private final MDVMountsPlugin plugin;
    private final MountManager mountManager;
    private PacketListener listener;

    public ProtocolLibCamelJumpBlocker(MDVMountsPlugin plugin, MountManager mountManager) {
        this.plugin = plugin;
        this.mountManager = mountManager;
    }

    public void start() {
        if (listener != null) {
            return;
        }

        listener = new PacketAdapter(
                plugin,
                ListenerPriority.HIGHEST,
                PacketType.Play.Client.ENTITY_ACTION) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                EnumWrappers.PlayerAction action;
                try {
                    action = event.getPacket().getPlayerActions().read(0);
                } catch (RuntimeException ex) {
                    return;
                }

                if (action != EnumWrappers.PlayerAction.START_RIDING_JUMP
                        && action != EnumWrappers.PlayerAction.STOP_RIDING_JUMP) {
                    return;
                }

                if (mountManager.shouldBlockNativeCamelJumpPacket(
                        event.getPlayer().getUniqueId())) {
                    event.setCancelled(true);
                }
            }
        };

        ProtocolLibrary.getProtocolManager().addPacketListener(listener);
    }

    public void stop() {
        if (listener == null) {
            return;
        }

        ProtocolManager manager = ProtocolLibrary.getProtocolManager();
        manager.removePacketListener(listener);
        listener = null;
    }
}
