package com.mdvcraft.mdvmounts.storage;

import com.mdvcraft.mdvmounts.MDVMountsPlugin;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Item-bound storage for selected mount invokers.
 *
 * The storage lives inside the physical invoker ItemStack through Paper's
 * minecraft:container data component. A tiny PDC UUID only identifies which
 * physical invoker summoned which nearby mount; no database is used.
 */
public final class InvokerStorageManager {
    private final MDVMountsPlugin plugin;

    private final NamespacedKey itemStorageIdKey;
    private final NamespacedKey itemProfileKey;
    private final NamespacedKey entityStorageIdKey;
    private final NamespacedKey entityProfileKey;

    private final Map<String, InvokerStorageProfile> profiles = new HashMap<>();
    private final Map<UUID, OpenStorageSession> openSessions = new HashMap<>();
    private final Map<UUID, PendingBinding> pendingBindings = new HashMap<>();

    private boolean enabled;
    private double defaultInteractionDistance;
    private double bindSearchRadius;
    private boolean preventNestedInvokers;
    private List<Long> bindAttemptDelays = List.of(1L, 2L, 4L);

    public InvokerStorageManager(MDVMountsPlugin plugin) {
        this.plugin = plugin;
        this.itemStorageIdKey = new NamespacedKey(plugin, "invoker_storage_id");
        this.itemProfileKey = new NamespacedKey(plugin, "invoker_storage_profile");
        this.entityStorageIdKey = new NamespacedKey(plugin, "bound_invoker_storage_id");
        this.entityProfileKey = new NamespacedKey(plugin, "bound_invoker_storage_profile");
        reloadSettings();
    }

    public void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("storage.enabled", true);
        defaultInteractionDistance = clamp(
                plugin.getConfig().getDouble("storage.default-interaction-distance", 3.0D),
                0.5D,
                12.0D);
        bindSearchRadius = clamp(
                plugin.getConfig().getDouble("storage.bind-search-radius", 4.5D),
                1.0D,
                16.0D);
        preventNestedInvokers = plugin.getConfig().getBoolean("storage.prevent-nested-invokers", true);

        List<Integer> configuredDelays = plugin.getConfig().getIntegerList("storage.bind-attempt-delays");
        if (configuredDelays.isEmpty()) {
            bindAttemptDelays = List.of(1L, 2L, 4L);
        } else {
            List<Long> parsed = new ArrayList<>();
            for (Integer delay : configuredDelays) {
                if (delay != null && delay >= 1 && delay <= 20) {
                    parsed.add(delay.longValue());
                }
            }
            bindAttemptDelays = parsed.isEmpty() ? List.of(1L, 2L, 4L) : List.copyOf(parsed);
        }

        profiles.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("storage.profiles");
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection profileSection = section.getConfigurationSection(key);
            if (profileSection == null) {
                continue;
            }

            boolean profileEnabled = profileSection.getBoolean("enabled", true);
            int slots = clampSlots(profileSection.getInt("slots", 27));
            String title = profileSection.getString("title", "&6Alforjas");
            double interactionDistance = clamp(
                    profileSection.getDouble("interaction-distance", defaultInteractionDistance),
                    0.5D,
                    12.0D);

            String materialName = profileSection.getString("invoker.material", "SADDLE");
            Material material = Material.matchMaterial(materialName == null ? "SADDLE" : materialName);
            if (material == null) {
                plugin.getLogger().warning("storage.profiles." + key + ": material inválido; perfil omitido.");
                continue;
            }

            String displayName = profileSection.getString("invoker.display-name", "");
            List<String> requiredTags = profileSection.getStringList("mount.required-tags");
            if (requiredTags.isEmpty()) {
                plugin.getLogger().warning("storage.profiles." + key
                        + ": mount.required-tags está vacío; perfil omitido para evitar enlazar la montura incorrecta.");
                continue;
            }

            profiles.put(key.toLowerCase(), new InvokerStorageProfile(
                    key.toLowerCase(),
                    profileEnabled,
                    slots,
                    title == null ? "&6Alforjas" : title,
                    interactionDistance,
                    material,
                    displayName == null ? "" : displayName,
                    List.copyOf(requiredTags)));
        }
    }

    public void shutdown() {
        for (UUID playerId : new ArrayList<>(openSessions.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                saveOpenSession(player, false);
            }
        }
        openSessions.clear();
        pendingBindings.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Tries to open the storage tied to the physical invoker currently used.
     * Returns true only when a matching bound mount is actually within range.
     */
    public boolean tryOpen(Player player, ItemStack item) {
        if (!enabled || player == null || item == null || item.isEmpty()) {
            return false;
        }

        InvokerStorageProfile profile = resolveProfile(item);
        if (profile == null || !profile.enabled()) {
            return false;
        }

        // An unstamped invoker cannot have a mount bound to this physical
        // ItemStack yet. Do not mutate first-use Crucible items before its
        // own ~onUse handler gets a chance to identify and execute them.
        UUID storageId = readStorageId(item);
        if (storageId == null) {
            return false;
        }

        Entity mount = findBoundMountNearby(player, profile, storageId, profile.interactionDistance());
        if (mount == null) {
            return false;
        }

        open(player, item, profile, storageId);
        return true;
    }

    /**
     * Arms a short, deduplicated post-click binding window. Crucible/MythicMobs
     * is still responsible for the actual summon. We simply tag the newly
     * spawned matching entity with the physical invoker UUID afterwards.
     */
    public void armBindingAfterInvocation(Player player, ItemStack item) {
        if (!enabled || player == null || item == null || item.isEmpty()) {
            return;
        }

        InvokerStorageProfile profile = resolveProfile(item);
        if (profile == null || !profile.enabled()) {
            return;
        }

        UUID storageId = readStorageId(item);
        if (storageId == null) {
            storageId = UUID.randomUUID();
        }

        Set<UUID> existing = collectMatchingEntityIds(player, profile, bindSearchRadius);
        long token = System.nanoTime();
        int inventorySlot = player.getInventory().getHeldItemSlot();
        PendingBinding pending = new PendingBinding(storageId, profile.key(), existing, token, inventorySlot);
        pendingBindings.put(player.getUniqueId(), pending);

        for (int i = 0; i < bindAttemptDelays.size(); i++) {
            final boolean last = i == bindAttemptDelays.size() - 1;
            long delay = bindAttemptDelays.get(i);
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> attemptBinding(player.getUniqueId(), token, last),
                    delay);
        }
    }

    public OpenStorageSession getOpenSession(Player player) {
        return openSessions.get(player.getUniqueId());
    }

    public boolean isActiveInvoker(ItemStack item, OpenStorageSession session) {
        if (item == null || item.isEmpty() || session == null) {
            return false;
        }
        UUID id = readStorageId(item);
        return id != null && id.equals(session.storageId());
    }

    public boolean isStorageInvoker(ItemStack item) {
        return item != null && !item.isEmpty() && resolveProfile(item) != null;
    }

    public boolean preventNestedInvokers() {
        return preventNestedInvokers;
    }

    public void saveOpenSession(Player player, boolean removeSession) {
        OpenStorageSession session = openSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        LocatedItem located = findItemByStorageId(player, session.storageId());
        if (located != null) {
            writeContents(located.item(), session.inventory(), session.profile().slots());
            located.writeBack();
        } else {
            // This should not normally happen because listeners prevent moving
            // or dropping the active invoker while its GUI is open.
            plugin.getLogger().warning("No se encontró el invocador " + session.storageId()
                    + " al cerrar su almacenamiento para " + player.getName() + ".");
        }

        if (removeSession) {
            openSessions.remove(player.getUniqueId());
        }
    }

    public void closeAndSave(Player player) {
        if (openSessions.containsKey(player.getUniqueId())) {
            saveOpenSession(player, true);
        }
    }

    public boolean isUsableTopSlot(OpenStorageSession session, int rawSlot) {
        return rawSlot >= 0 && rawSlot < session.profile().slots();
    }

    private void open(Player player,
                      ItemStack item,
                      InvokerStorageProfile profile,
                      UUID storageId) {
        OpenStorageSession alreadyOpen = openSessions.get(player.getUniqueId());
        if (alreadyOpen != null && alreadyOpen.storageId().equals(storageId)) {
            return;
        }

        if (alreadyOpen != null) {
            saveOpenSession(player, true);
        }

        int guiSize = guiSize(profile.slots());
        String title = color(profile.title());
        Inventory inventory = Bukkit.createInventory(null, guiSize, title);

        ItemContainerContents stored = item.getData(DataComponentTypes.CONTAINER);
        List<ItemStack> contents = stored == null ? List.of() : stored.contents();

        int copyCount = Math.min(profile.slots(), contents.size());
        for (int slot = 0; slot < copyCount; slot++) {
            ItemStack storedItem = contents.get(slot);
            if (storedItem != null && !storedItem.isEmpty()) {
                inventory.setItem(slot, storedItem.clone());
            }
        }

        // If an admin reduced the configured capacity, never silently delete
        // pre-existing items: return overflow to the current holder.
        if (contents.size() > profile.slots()) {
            boolean hadOverflow = false;
            for (int slot = profile.slots(); slot < contents.size(); slot++) {
                ItemStack overflow = contents.get(slot);
                if (overflow == null || overflow.isEmpty()) {
                    continue;
                }
                hadOverflow = true;
                giveOrDrop(player, overflow.clone());
            }
            if (hadOverflow) {
                // Truncate immediately after returning overflow so a crash
                // before GUI close cannot duplicate those returned items.
                writeContents(item, inventory, profile.slots());
                player.getInventory().setItemInMainHand(item);
                message(player, "messages.storage-overflow",
                        "&eLa capacidad de esta montura fue reducida; los objetos sobrantes fueron devueltos.");
            }
        }

        if (profile.slots() < guiSize) {
            ItemStack filler = lockedSlotItem();
            for (int slot = profile.slots(); slot < guiSize; slot++) {
                inventory.setItem(slot, filler);
            }
        }

        openSessions.put(player.getUniqueId(), new OpenStorageSession(
                player.getUniqueId(), storageId, profile, inventory));
        player.openInventory(inventory);
    }

    private void attemptBinding(UUID playerId, long token, boolean lastAttempt) {
        PendingBinding pending = pendingBindings.get(playerId);
        if (pending == null || pending.token() != token) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        InvokerStorageProfile profile = profiles.get(pending.profileKey());
        if (player == null || !player.isOnline() || profile == null || !profile.enabled()) {
            pendingBindings.remove(playerId);
            return;
        }

        Entity best = null;
        double bestDistance = Double.MAX_VALUE;

        Collection<Entity> nearby = player.getNearbyEntities(bindSearchRadius, bindSearchRadius, bindSearchRadius);
        for (Entity entity : nearby) {
            if (!matchesMountProfile(entity, profile)) {
                continue;
            }

            String alreadyBound = entity.getPersistentDataContainer().get(entityStorageIdKey, PersistentDataType.STRING);
            if (alreadyBound != null) {
                continue;
            }

            // Normally the candidate was not present before the item click.
            // ticksLived is a safe fallback for plugins that spawn during the
            // same event before our snapshot can see the pre-summon state.
            boolean newCandidate = !pending.existingEntityIds().contains(entity.getUniqueId());
            boolean justSpawned = entity.getTicksLived() <= 10;
            if (!newCandidate && !justSpawned) {
                continue;
            }

            double distance = entity.getLocation().distanceSquared(player.getLocation());
            if (distance < bestDistance) {
                best = entity;
                bestDistance = distance;
            }
        }

        if (best != null) {
            // Stamp the physical invoker only after a matching summon really
            // appeared. Failed/cooldown uses therefore do not touch Crucible's
            // item before it processes ~onUse.
            ItemStack invoker = player.getInventory().getItem(pending.inventorySlot());
            if (invoker == null || invoker.isEmpty()) {
                pendingBindings.remove(playerId);
                return;
            }

            InvokerStorageProfile currentProfile = resolveProfile(invoker);
            if (currentProfile == null || !currentProfile.key().equals(profile.key())) {
                pendingBindings.remove(playerId);
                return;
            }

            UUID currentId = readStorageId(invoker);
            if (currentId != null && !currentId.equals(pending.storageId())) {
                // The player swapped a different already-stamped invoker into
                // that slot during the tiny binding window. Never cross-link.
                pendingBindings.remove(playerId);
                return;
            }

            stampIdentity(invoker, profile, pending.storageId());
            player.getInventory().setItem(pending.inventorySlot(), invoker);

            PersistentDataContainer pdc = best.getPersistentDataContainer();
            pdc.set(entityStorageIdKey, PersistentDataType.STRING, pending.storageId().toString());
            pdc.set(entityProfileKey, PersistentDataType.STRING, profile.key());
            pendingBindings.remove(playerId);
            return;
        }

        if (lastAttempt) {
            pendingBindings.remove(playerId);
        }
    }

    private Entity findBoundMountNearby(Player player,
                                        InvokerStorageProfile profile,
                                        UUID storageId,
                                        double radius) {
        double maxDistanceSquared = radius * radius;
        Entity nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!matchesMountProfile(entity, profile)) {
                continue;
            }

            String boundId = entity.getPersistentDataContainer().get(entityStorageIdKey, PersistentDataType.STRING);
            if (!storageId.toString().equals(boundId)) {
                continue;
            }

            String boundProfile = entity.getPersistentDataContainer().get(entityProfileKey, PersistentDataType.STRING);
            if (boundProfile != null && !profile.key().equalsIgnoreCase(boundProfile)) {
                continue;
            }

            double distanceSquared = entity.getLocation().distanceSquared(player.getLocation());
            if (distanceSquared <= maxDistanceSquared && distanceSquared < nearestDistance) {
                nearest = entity;
                nearestDistance = distanceSquared;
            }
        }
        return nearest;
    }

    private Set<UUID> collectMatchingEntityIds(Player player,
                                               InvokerStorageProfile profile,
                                               double radius) {
        Set<UUID> ids = new HashSet<>();
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (matchesMountProfile(entity, profile)) {
                ids.add(entity.getUniqueId());
            }
        }
        return ids;
    }

    private boolean matchesMountProfile(Entity entity, InvokerStorageProfile profile) {
        if (entity == null || !entity.isValid()) {
            return false;
        }
        Set<String> tags = entity.getScoreboardTags();
        for (String required : profile.requiredMountTags()) {
            if (!tags.contains(required)) {
                return false;
            }
        }
        return true;
    }

    private InvokerStorageProfile resolveProfile(ItemStack item) {
        if (!enabled || item == null || item.isEmpty()) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String stampedProfile = meta.getPersistentDataContainer().get(itemProfileKey, PersistentDataType.STRING);
            if (stampedProfile != null) {
                InvokerStorageProfile profile = profiles.get(stampedProfile.toLowerCase());
                if (profile != null && profile.enabled()) {
                    return profile;
                }
            }
        }

        for (InvokerStorageProfile profile : profiles.values()) {
            if (!profile.enabled() || item.getType() != profile.material()) {
                continue;
            }
            if (!profile.displayName().isBlank()) {
                if (meta == null || !meta.hasDisplayName()) {
                    continue;
                }
                String expected = color(profile.displayName());
                if (!expected.equals(meta.getDisplayName())) {
                    continue;
                }
            }
            return profile;
        }

        return null;
    }

    private void stampIdentity(ItemStack item, InvokerStorageProfile profile, UUID storageId) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(itemStorageIdKey, PersistentDataType.STRING, storageId.toString());
        pdc.set(itemProfileKey, PersistentDataType.STRING, profile.key());
        item.setItemMeta(meta);
    }

    private UUID readStorageId(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return parseUuid(meta.getPersistentDataContainer().get(itemStorageIdKey, PersistentDataType.STRING));
    }

    private void writeContents(ItemStack invoker, Inventory inventory, int usableSlots) {
        List<ItemStack> contents = new ArrayList<>(usableSlots);
        for (int slot = 0; slot < usableSlots; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) {
                contents.add(ItemStack.empty());
            } else {
                contents.add(item.clone());
            }
        }
        invoker.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(contents));
    }

    private LocatedItem findItemByStorageId(Player player, UUID storageId) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            UUID found = readStorageIdSafe(item);
            if (storageId.equals(found)) {
                final int foundSlot = slot;
                return new LocatedItem(item, () -> inventory.setItem(foundSlot, item));
            }
        }

        ItemStack cursor = player.getItemOnCursor();
        if (storageId.equals(readStorageIdSafe(cursor))) {
            return new LocatedItem(cursor, () -> player.setItemOnCursor(cursor));
        }
        return null;
    }

    private UUID readStorageIdSafe(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        return readStorageId(item);
    }

    private ItemStack lockedSlotItem() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color("&8Slot no disponible"));
        item.setItemMeta(meta);
        return item;
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private void message(Player player, String path, String fallback) {
        String raw = plugin.getConfig().getString(path, fallback);
        if (raw == null || raw.isBlank()) {
            return;
        }
        player.sendMessage(color(raw));
    }

    private String color(String raw) {
        return ChatColor.translateAlternateColorCodes('&', raw == null ? "" : raw);
    }

    private int guiSize(int usableSlots) {
        return Math.max(9, Math.min(54, ((usableSlots + 8) / 9) * 9));
    }

    private int clampSlots(int slots) {
        return Math.max(1, Math.min(54, slots));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record PendingBinding(
            UUID storageId,
            String profileKey,
            Set<UUID> existingEntityIds,
            long token,
            int inventorySlot) {
    }

    private record LocatedItem(ItemStack item, Runnable writer) {
        void writeBack() {
            writer.run();
        }
    }
}
