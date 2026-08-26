package com.mdvcraft.mdvmounts.storage;

import com.mdvcraft.mdvmounts.MDVMountsPlugin;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

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
    // Hard lock by physical invoker UUID: one storage can have only one viewer,
    // regardless of which player currently holds the ItemStack.
    private final Map<UUID, UUID> storageViewers = new HashMap<>();
    private final Map<UUID, PendingBinding> pendingBindings = new HashMap<>();

    private boolean enabled;
    private double defaultInteractionDistance;
    private double bindSearchRadius;
    private boolean preventNestedInvokers;
    private StorageOpenInteraction openInteraction = StorageOpenInteraction.LEFT_CLICK;
    private BindingInteraction bindingInteraction = BindingInteraction.AUTO;
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
        FileConfiguration config = plugin.getStorageConfig();

        enabled = config.getBoolean("enabled", true);
        defaultInteractionDistance = clamp(
                config.getDouble("default-interaction-distance", 3.0D),
                0.5D,
                12.0D);
        bindSearchRadius = clamp(
                config.getDouble("bind-search-radius", 4.5D),
                1.0D,
                16.0D);
        preventNestedInvokers = config.getBoolean("prevent-nested-invokers", true);

        String configuredInteraction = plugin.getConfig().getString(
                "control.storage-open-interaction",
                "LEFT_CLICK");
        openInteraction = StorageOpenInteraction.parse(configuredInteraction);
        if (openInteraction == null) {
            plugin.getLogger().warning("config.yml control.storage-open-interaction='"
                    + configuredInteraction + "' no es válido. Se usará LEFT_CLICK.");
            openInteraction = StorageOpenInteraction.LEFT_CLICK;
        }

        String configuredBindingInteraction = plugin.getConfig().getString(
                "control.storage-invocation-interaction",
                "AUTO");
        bindingInteraction = BindingInteraction.parse(configuredBindingInteraction);
        if (bindingInteraction == null) {
            plugin.getLogger().warning("config.yml control.storage-invocation-interaction='"
                    + configuredBindingInteraction + "' no es válido. Se usará AUTO.");
            bindingInteraction = BindingInteraction.AUTO;
        }

        List<Integer> configuredDelays = config.getIntegerList("bind-attempt-delays");
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
        ConfigurationSection section = config.getConfigurationSection("profiles");
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
                plugin.getLogger().warning("storage.yml profiles." + key + ": material inválido; perfil omitido.");
                continue;
            }

            String displayName = profileSection.getString("invoker.display-name", "");
            List<String> requiredTags = profileSection.getStringList("mount.required-tags");
            if (requiredTags.isEmpty()) {
                plugin.getLogger().warning("storage.yml profiles." + key
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

        // One lightweight inventory pass on enable/reload so invokers that
        // already contained items before 1.1.3 also lose the vanilla
        // shulker-like container preview immediately. No repeating task.
        for (Player online : Bukkit.getOnlinePlayers()) {
            refreshContainerTooltip(online);
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
        storageViewers.clear();
        pendingBindings.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean shouldOpenFromLeftClick(Player player) {
        return player != null && openInteraction.matches(false, player.isSneaking());
    }

    public boolean shouldOpenFromRightClick(Player player) {
        return player != null && openInteraction.matches(true, player.isSneaking());
    }

    public boolean shouldArmBindingFromLeftClick(Player player) {
        return player != null && bindingInteraction.matches(false, player.isSneaking());
    }

    public boolean shouldArmBindingFromRightClick(Player player) {
        return player != null && bindingInteraction.matches(true, player.isSneaking());
    }

    /**
     * Applies the tooltip migration to a player's already-existing invokers.
     * This only scans the inventory when the player joins or configs reload.
     */
    public void refreshContainerTooltip(Player player) {
        if (!enabled || player == null) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty() || resolveProfile(item) == null) {
                continue;
            }
            if (item.getData(DataComponentTypes.CONTAINER) == null) {
                continue;
            }
            hideContainerTooltip(item);
            inventory.setItem(slot, item);
        }
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

        UUID viewerId = storageViewers.get(storageId);
        if (viewerId != null && !viewerId.equals(player.getUniqueId())) {
            OpenStorageSession lockedSession = openSessions.get(viewerId);
            if (lockedSession != null && lockedSession.storageId().equals(storageId)) {
                message(player, "messages.storage-in-use",
                        "&cEse almacenamiento ya está siendo utilizado por otra persona.");
                return true;
            }
            // Defensive stale-lock cleanup.
            storageViewers.remove(storageId, viewerId);
        }

        open(player, item, profile, storageId, mount.getUniqueId());
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

        // The UUID is only the live binding between THIS physical invoker and
        // the mount created by this invocation. Generate a fresh one for every
        // successful summon attempt instead of reusing a possibly duplicated
        // UUID copied from another ItemStack. The container contents remain on
        // the ItemStack and are not touched by this rotation.
        UUID previousStorageId = readStorageId(item);
        UUID bindingId = UUID.randomUUID();

        Set<UUID> existing = collectMatchingEntityIds(player, profile, bindSearchRadius);
        long token = System.nanoTime();
        int inventorySlot = player.getInventory().getHeldItemSlot();
        PendingBinding pending = new PendingBinding(
                bindingId, previousStorageId, profile.key(), existing, token, inventorySlot);
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

        // Prefer the exact hotbar slot that opened this GUI. This prevents a
        // duplicated/copy-pasted invoker UUID elsewhere in the inventory from
        // receiving the contents by mistake. The global UUID scan remains only
        // as a defensive fallback for external plugins that physically move
        // the item while the menu is open.
        LocatedItem located = findSessionInvoker(player, session);
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
            openSessions.remove(player.getUniqueId(), session);
            storageViewers.remove(session.storageId(), player.getUniqueId());
        }
    }

    public void closeAndSave(Player player) {
        if (openSessions.containsKey(player.getUniqueId())) {
            saveOpenSession(player, true);
        }
    }

    /**
     * Closes every GUI backed by the given summoned mount. There is normally
     * at most one because storage itself is single-viewer locked. This method
     * is event-driven (death/remove) and adds no polling task.
     */
    public void closeSessionsForMount(UUID mountId) {
        if (mountId == null || openSessions.isEmpty()) {
            return;
        }

        List<OpenStorageSession> affected = new ArrayList<>();
        for (OpenStorageSession session : openSessions.values()) {
            if (mountId.equals(session.mountId())) {
                affected.add(session);
            }
        }

        for (OpenStorageSession session : affected) {
            Player viewer = Bukkit.getPlayer(session.playerId());
            if (viewer != null) {
                saveOpenSession(viewer, true);
                if (viewer.getOpenInventory().getTopInventory() == session.inventory()) {
                    viewer.closeInventory();
                }
                message(viewer, "messages.storage-mount-gone",
                        "&eEl almacenamiento se cerró porque tu montura desapareció.");
            } else {
                openSessions.remove(session.playerId(), session);
                storageViewers.remove(session.storageId(), session.playerId());
            }
        }
    }

    public boolean isUsableTopSlot(OpenStorageSession session, int rawSlot) {
        return rawSlot >= 0 && rawSlot < session.profile().slots();
    }

    private void open(Player player,
                      ItemStack item,
                      InvokerStorageProfile profile,
                      UUID storageId,
                      UUID mountId) {
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

        storageViewers.put(storageId, player.getUniqueId());
        openSessions.put(player.getUniqueId(), new OpenStorageSession(
                player.getUniqueId(),
                storageId,
                mountId,
                profile,
                inventory,
                player.getInventory().getHeldItemSlot()));
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
            if (pending.previousStorageId() == null) {
                if (currentId != null) {
                    // A different already-stamped invoker was swapped into
                    // the hotbar slot during the short binding window.
                    pendingBindings.remove(playerId);
                    return;
                }
            } else if (!pending.previousStorageId().equals(currentId)) {
                // The item in the slot is no longer the one that triggered
                // this invocation. Never bind the spawned mount to it.
                pendingBindings.remove(playerId);
                return;
            }

            // Rotate the live binding UUID on every successful summon.
            // This is deliberate: two physical invokers that were duplicated
            // with the same PDC UUID must never keep sharing a live identity.
            // Their minecraft:container data stays attached to each ItemStack.
            stampIdentity(invoker, profile, pending.bindingId());
            player.getInventory().setItem(pending.inventorySlot(), invoker);

            PersistentDataContainer pdc = best.getPersistentDataContainer();
            pdc.set(entityStorageIdKey, PersistentDataType.STRING, pending.bindingId().toString());
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
        hideContainerTooltip(item);
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
        hideContainerTooltip(invoker);
    }

    private void hideContainerTooltip(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return;
        }

        TooltipDisplay existing = item.getData(DataComponentTypes.TOOLTIP_DISPLAY);
        Set<DataComponentType> hidden = new HashSet<>();
        boolean hideWholeTooltip = false;

        if (existing != null) {
            hidden.addAll(existing.hiddenComponents());
            hideWholeTooltip = existing.hideTooltip();
        }

        // Preserve every tooltip rule another plugin/item already had and only
        // add CONTAINER. This hides the vanilla list of stored items while the
        // custom name, lore and all normal visible lines remain untouched.
        if (!hidden.add(DataComponentTypes.CONTAINER) && existing != null) {
            return;
        }

        TooltipDisplay display = TooltipDisplay.tooltipDisplay()
                .hideTooltip(hideWholeTooltip)
                .hiddenComponents(Set.copyOf(hidden))
                .build();
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, display);
    }

    private LocatedItem findSessionInvoker(Player player, OpenStorageSession session) {
        if (player == null || session == null) {
            return null;
        }

        int slot = session.invokerSlot();
        PlayerInventory inventory = player.getInventory();
        if (slot >= 0 && slot < inventory.getSize()) {
            ItemStack exact = inventory.getItem(slot);
            if (session.storageId().equals(readStorageIdSafe(exact))) {
                return new LocatedItem(exact, () -> inventory.setItem(slot, exact));
            }
        }

        return findItemByStorageId(player, session.storageId());
    }

    private LocatedItem findItemByStorageId(Player player, UUID storageId) {
        LocatedItem preferred = findItemByStorageIdInPlayer(player, storageId);
        if (preferred != null) {
            return preferred;
        }

        // Normally impossible because the active invoker cannot be moved while
        // its GUI is open. Still, if another plugin transfers it, find the
        // current online holder so edits are written to the physical item that
        // actually changed hands. This scan only happens on save/close.
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            LocatedItem transferred = findItemByStorageIdInPlayer(online, storageId);
            if (transferred != null) {
                return transferred;
            }
        }
        return null;
    }

    private LocatedItem findItemByStorageIdInPlayer(Player player, UUID storageId) {
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
        String raw = plugin.getStorageConfig().getString(path, fallback);
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

    private enum StorageOpenInteraction {
        LEFT_CLICK(false, false),
        RIGHT_CLICK(true, false),
        SHIFT_LEFT_CLICK(false, true),
        SHIFT_RIGHT_CLICK(true, true);

        private final boolean rightClick;
        private final boolean requiresSneak;

        StorageOpenInteraction(boolean rightClick, boolean requiresSneak) {
            this.rightClick = rightClick;
            this.requiresSneak = requiresSneak;
        }

        boolean matches(boolean rightClick, boolean sneaking) {
            return this.rightClick == rightClick && this.requiresSneak == sneaking;
        }

        static StorageOpenInteraction parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return LEFT_CLICK;
            }
            String normalized = raw.trim().toUpperCase()
                    .replace('-', '_')
                    .replace(' ', '_');
            if (normalized.equals("SNEAK_LEFT_CLICK")) {
                normalized = "SHIFT_LEFT_CLICK";
            } else if (normalized.equals("SNEAK_RIGHT_CLICK")) {
                normalized = "SHIFT_RIGHT_CLICK";
            }
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    private enum BindingInteraction {
        AUTO(null, null),
        LEFT_CLICK(false, false),
        RIGHT_CLICK(true, false),
        SHIFT_LEFT_CLICK(false, true),
        SHIFT_RIGHT_CLICK(true, true);

        private final Boolean rightClick;
        private final Boolean requiresSneak;

        BindingInteraction(Boolean rightClick, Boolean requiresSneak) {
            this.rightClick = rightClick;
            this.requiresSneak = requiresSneak;
        }

        boolean matches(boolean rightClick, boolean sneaking) {
            if (this == AUTO) {
                return true;
            }
            return this.rightClick == rightClick && this.requiresSneak == sneaking;
        }

        static BindingInteraction parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return AUTO;
            }
            String normalized = raw.trim().toUpperCase()
                    .replace('-', '_')
                    .replace(' ', '_');
            if (normalized.equals("SNEAK_LEFT_CLICK")) {
                normalized = "SHIFT_LEFT_CLICK";
            } else if (normalized.equals("SNEAK_RIGHT_CLICK")) {
                normalized = "SHIFT_RIGHT_CLICK";
            } else if (normalized.equals("ANY") || normalized.equals("AUTOMATIC")) {
                normalized = "AUTO";
            }
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    private record PendingBinding(
            UUID bindingId,
            UUID previousStorageId,
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
