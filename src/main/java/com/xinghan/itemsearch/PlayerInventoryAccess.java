package com.xinghan.itemsearch;

import com.xinghan.itemsearch.nbt.NbtReader;
import com.xinghan.itemsearch.nbt.NbtWriter;
import com.xinghan.itemsearch.player.PlayerDataStore;
import com.xinghan.itemsearch.player.PlayerScanner;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Event;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Base64;
import java.util.Properties;
import java.nio.file.Files;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;

/** Inventory APIs stay on the server thread; offline file reads and commits run on the I/O worker. */
public final class PlayerInventoryAccess implements PlayerInventoryEditor.Backend, Listener, AutoCloseable {
    private final RegionItemSearchPlugin plugin;
    private final Function<UUID, Player> players;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "RegionItemSearch-InventoryIO"); thread.setDaemon(true); return thread;
    });
    private final Object loginLock = new Object();
    private final Map<UUID, Long> loggingIn = new HashMap<>();
    private final Map<UUID, Set<Object>> pendingConnections = new HashMap<>();
    private final Set<UUID> saving = new java.util.HashSet<>();
    private final Map<UUID, PendingSave> pendingSaves = new HashMap<>();
    private final Set<UUID> transferring = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, FoliaAttempt> onlinePending = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean closed;
    private final Method deathCancellation = optionalDeathCancellation();
    private PlayerInventoryEditor editor;
    private static final class OnlineToken {
        final Player player;
        OnlineToken(Player player) { this.player = player; }
        Player player() { return player; }
    }
    private static final class OfflineToken {
        final PlayerDataStore.Snapshot snapshot; final int maxBytes, maxDepth;
        OfflineToken(PlayerDataStore.Snapshot snapshot, int maxBytes, int maxDepth) {
            this.snapshot = snapshot; this.maxBytes = maxBytes; this.maxDepth = maxDepth;
        }
        PlayerDataStore.Snapshot snapshot() { return snapshot; }
        int maxBytes() { return maxBytes; }
        int maxDepth() { return maxDepth; }
    }
    private static final class Prepared {
        final OfflineToken token; final byte[][] items;
        Prepared(OfflineToken token, byte[][] items) { this.token = token; this.items = items; }
        OfflineToken token() { return token; }
        byte[][] items() { return items; }
    }
    private static final class PendingSave {
        final UUID target;
        PlayerDataStore.PreparedSave prepared;
        PendingSave(UUID target) { this.target = target; }
    }
    private static final class FoliaAttempt {
        final Player target, viewer;
        final PlayerInventoryEditor.Loaded original;
        final ItemStack[] before, after;
        final List<Integer> changed;
        final Properties recovery;
        final Consumer<String> failure;
        boolean mutated, finished;
        FoliaAttempt(Player target, Player viewer, PlayerInventoryEditor.Loaded original, ItemStack[] before,
                     ItemStack[] after, List<Integer> changed, Properties recovery, Consumer<String> failure) {
            this.target = target; this.viewer = viewer; this.original = original; this.before = before;
            this.after = after; this.changed = changed; this.recovery = recovery; this.failure = failure;
        }
    }

    public PlayerInventoryAccess(RegionItemSearchPlugin plugin) { this(plugin, Bukkit::getPlayer); }

    // Allows the runtime probe to use a simulated online player with actual server-created ItemStacks.
    PlayerInventoryAccess(RegionItemSearchPlugin plugin, Function<UUID, Player> players) {
        this.plugin = plugin;
        this.players = players;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerOptionalConnectionGuard();
        registerOptionalFreeze("org.bukkit.event.player.PlayerSwapHandItemsEvent", "getPlayer");
        registerOptionalFreeze("org.bukkit.event.entity.EntityPickupItemEvent", "getEntity");
        registerOptionalFreeze("org.bukkit.event.player.PlayerPickupItemEvent", "getPlayer");
        registerOptionalFreeze("org.bukkit.event.player.PlayerInteractEntityEvent", "getPlayer");
        registerOptionalFreeze("org.bukkit.event.player.PlayerInteractAtEntityEvent", "getPlayer");
    }

    void editor(PlayerInventoryEditor editor) { this.editor = editor; }

    @Override public void load(Player viewer, UUID target, PlayerScanner.Storage storage,
                               Consumer<PlayerInventoryEditor.Loaded> success, Consumer<String> failure) {
        if (!allowed(viewer)) { failure.accept(Language.tr("access.no-permission")); return; }
        Player online = players.apply(target);
        if (online != null && online.isOnline()) {
            plugin.scheduler().entity(online, () -> {
                if (players.apply(target) != online || !online.isOnline()) {
                    onViewer(viewer, () -> failure.accept(Language.tr("access.target-relogged")));
                    return;
                }
                PlayerInventoryEditor.Loaded loaded = new PlayerInventoryEditor.Loaded(target, online.getName(), true,
                        storage, contents(online, storage), new OnlineToken(online));
                onViewer(viewer, () -> success.accept(loaded));
            }, () -> onViewer(viewer, () -> failure.accept(Language.tr("access.target-relogged"))));
            return;
        }
        if (isLoggingIn(target)) { failure.accept(Language.tr("access.login-pending")); return; }
        org.bukkit.World primary = BukkitCompat.mainWorld();
        if (primary == null) { failure.accept(Language.tr("access.world-missing")); return; }
        Path directory = BukkitCompat.playerDataDirectory(primary);
        int maxBytes = plugin.bounded("player-search.max-player-bytes", 16777216, 65536, 67108864);
        int maxDepth = plugin.bounded("scan.max-nbt-depth", 128, 8, 256);
        final int dataVersion = BukkitCompat.dataVersion();
        io.submit(() -> {
            try {
                PlayerDataStore.Snapshot snapshot = new PlayerDataStore().load(directory, target, maxBytes, maxDepth);
                Map<String, Object> root = snapshot.root();
                Object version = root.get("DataVersion");
                if (dataVersion > 0 ? !(version instanceof Number) || ((Number) version).intValue() != dataVersion
                        : version instanceof Number && ((Number) version).intValue() > 0) {
                    throw new IOException(Language.tr("access.data-version-mismatch"));
                }
                byte[][] items = encodeStorage(root.get(key(storage)), storage, dataVersion, maxBytes, maxDepth);
                Prepared prepared = new Prepared(new OfflineToken(snapshot, maxBytes, maxDepth), items);
                onViewer(viewer, () -> decode(viewer, target, storage, prepared, new ItemStack[items.length], 0, success, failure));
            } catch (IOException | RuntimeException ex) {
                onViewer(viewer, () -> failure.accept(Language.tr("access.load-failed", "reason", concise(ex))));
            }
        });
    }

    private void decode(Player viewer, UUID target, PlayerScanner.Storage storage, Prepared prepared,
                        ItemStack[] items, int offset, Consumer<PlayerInventoryEditor.Loaded> success, Consumer<String> failure) {
        if (!allowed(viewer)) { failure.accept(Language.tr("access.load-cancelled")); return; }
        if (players.apply(target) != null || isLoggingIn(target)) { failure.accept(Language.tr("access.target-online")); return; }
        int end = Math.min(items.length, offset + 4);
        try {
            for (int i = offset; i < end; i++) if (prepared.items()[i] != null) {
                items[i] = BukkitCompat.deserializeItem(prepared.items()[i]);
                if (empty(items[i])) throw new IllegalArgumentException(Language.tr("access.invalid-item", "slot", i));
            }
        } catch (RuntimeException ex) { failure.accept(Language.tr("access.decode-failed", "reason", concise(ex))); return; }
        if (end < items.length) plugin.scheduler().entityLater(viewer, 1, () -> decode(viewer, target, storage, prepared, items, end, success, failure));
        else {
            String name = target.toString().substring(0, 8);
            success.accept(new PlayerInventoryEditor.Loaded(target, name, false, storage, items, prepared.token()));
        }
    }

    @Override public void save(Player viewer, PlayerInventoryEditor.Loaded original, ItemStack[] proposed,
                               Consumer<String> success, Consumer<String> failure) {
        saveTransfer(viewer, original, proposed, () -> true, success, failure);
    }

    @Override public void saveTransfer(Player viewer, PlayerInventoryEditor.Loaded original, ItemStack[] proposed,
                                       BooleanSupplier guard, Consumer<String> success, Consumer<String> failure) {
        InventoryTransferView.requireOwner(viewer);
        Objects.requireNonNull(guard, "guard");
        if (!allowed(viewer)) { failure.accept(Language.tr("access.no-permission")); return; }
        ItemStack[] before = original.items();
        if (proposed.length != before.length) { failure.accept(Language.tr("access.invalid-size")); return; }
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < before.length; i++) if (!same(before[i], proposed[i])) changed.add(i);
        if (original.token() instanceof OnlineToken) {
            OnlineToken token = (OnlineToken) original.token();
            if (plugin.scheduler().isFolia()) {
                saveOnlineFolia(viewer, original, proposed, before, changed, token, guard, success, failure);
                return;
            }
            Player current = players.apply(original.target());
            if (current != token.player() || !current.isOnline()) { failure.accept(Language.tr("access.target-relogged")); return; }
            ItemStack[] live = contents(current, original.storage());
            for (int index : changed) if (!same(live[index], before[index])) {
                failure.accept(Language.tr("access.slot-conflict")); return;
            }
            for (int index : changed) live[index] = cloneItem(proposed[index]);
            if (!allowed(viewer) || !guard.getAsBoolean()) { failure.accept(Language.tr("access.commit-cancelled")); return; }
            if (!changed.isEmpty()) apply(current, original.storage(), live);
            // The editor applies its staged administrator inventory from this same call stack.
            success.accept(Language.tr("access.saved-online", "count", changed.size(), "player", original.name(),
                    "uuid", original.target(), "storage", storageName(original.storage())));
            return;
        }
        if (!(original.token() instanceof OfflineToken)) { failure.accept(Language.tr("access.invalid-snapshot")); return; }
        OfflineToken token = (OfflineToken) original.token();
        if (players.apply(original.target()) != null || isLoggingIn(original.target())) { failure.accept(Language.tr("access.save-target-online")); return; }
        if (changed.isEmpty()) {
            if (!allowed(viewer) || !guard.getAsBoolean()) { failure.accept(Language.tr("access.commit-cancelled")); return; }
            success.accept(Language.tr("access.no-changes")); return;
        }
        Map<Integer, byte[]> replacements = new LinkedHashMap<>();
        try {
            for (int index : changed) replacements.put(index, empty(proposed[index]) ? null : BukkitCompat.serializeItem(proposed[index].clone()));
        } catch (RuntimeException ex) { failure.accept(Language.tr("access.serialize-failed", "reason", concise(ex))); return; }
        PendingSave pending = new PendingSave(original.target());
        synchronized (loginLock) {
            expireLogins();
            if (closed || loggingIn.containsKey(original.target()) || pendingConnections.containsKey(original.target()) || !saving.add(original.target())) {
                failure.accept(Language.tr("access.save-pending")); return;
            }
            pendingSaves.put(original.target(), pending);
        }
        Path backupDirectory = plugin.getDataFolder().toPath().resolve("player-backups");
        UUID actor = viewer.getUniqueId();
        try {
            io.submit(() -> {
                PlayerDataStore.PreparedSave prepared = null;
                try {
                    if (closed) throw new IOException(Language.tr("access.plugin-closed"));
                    List<Map<String, Object>> items = replaceStorage(token.snapshot().root().get(key(original.storage())), original.storage(), replacements, token.maxBytes(), token.maxDepth());
                    prepared = new PlayerDataStore().prepare(token.snapshot(), key(original.storage()), items, backupDirectory, token.maxBytes(), token.maxDepth());
                    synchronized (loginLock) {
                        if (closed || pendingSaves.get(pending.target) != pending) throw new IOException(Language.tr("access.prepare-cancelled"));
                        pending.prepared = prepared;
                    }
                    // Use an explicit scheduler submission: plugin.onMain may silently discard work
                    // during shutdown. Registered preparations are always cleaned by close().
                    plugin.scheduler().entity(viewer, () -> commitOffline(pending, viewer, original, guard,
                            changed.size(), actor, success, failure), () -> finishOffline(pending));
                    prepared = null; // Ownership has passed to the tracked main-thread commit.
                } catch (IOException | RuntimeException ex) {
                    finishOffline(pending);
                    closePrepared(prepared);
                    if (!closed) onViewer(viewer, () -> failure.accept(Language.tr("access.save-failed", "reason", concise(ex))));
                }
            });
        } catch (RuntimeException rejected) {
            finishOffline(pending);
            failure.accept(Language.tr("access.submit-failed"));
        }
    }

    private void commitOffline(PendingSave pending, Player viewer, PlayerInventoryEditor.Loaded original,
                               BooleanSupplier guard, int changed, UUID actor,
                               Consumer<String> success, Consumer<String> failure) {
        PlayerDataStore.PreparedSave prepared;
        synchronized (loginLock) {
            if (closed || pendingSaves.get(pending.target) != pending) return;
            prepared = pending.prepared;
        }
        boolean committed = false;
        try {
            if (!allowed(viewer) || players.apply(original.target()) != null || isLoggingIn(original.target())
                    || !guard.getAsBoolean()) {
                failure.accept(Language.tr("access.commit-state-changed")); return;
            }
            if (prepared == null) throw new IOException(Language.tr("access.prepare-unavailable"));
            // All expensive compression, backup and SHA-256 reads already ran on the I/O worker.
            // Final metadata check + rename and the editor's success callback cannot be interleaved
            // with main-thread player quit, GUI close or administrator inventory changes.
            Path backup = prepared.commit();
            committed = true;
            success.accept(Language.tr("access.saved-offline", "count", changed, "player", original.name(),
                    "uuid", original.target(), "storage", storageName(original.storage())));
            plugin.getLogger().info("Inventory edit by " + actor + " target=" + original.target()
                    + " storage=" + original.storage() + " changed=" + changed + " backup=" + backup);
        } catch (IOException | RuntimeException ex) {
            if (committed) {
                // Never report failure after mutation: that could make the editor discard a transfer
                // whose target side was already committed. Success callbacks must complete in place.
                plugin.getLogger().log(Level.SEVERE, "Inventory target committed but completion failed for " + pending.target, ex);
            } else failure.accept(Language.tr("access.save-failed", "reason", concise(ex)));
        } finally { finishOffline(pending); }
    }

    private void finishOffline(PendingSave pending) {
        PlayerDataStore.PreparedSave prepared;
        synchronized (loginLock) {
            if (!pendingSaves.remove(pending.target, pending)) return;
            saving.remove(pending.target);
            prepared = pending.prepared;
        }
        closePrepared(prepared);
    }

    private void onViewer(Player viewer, Runnable action) {
        plugin.scheduler().entity(viewer, action, () -> { });
    }

    /** Two owner-thread phases; a failed administrator phase conditionally restores the target. */
    private void saveOnlineFolia(final Player viewer, final PlayerInventoryEditor.Loaded original,
                                 ItemStack[] proposed, final ItemStack[] before, final List<Integer> changed,
                                 final OnlineToken token, final BooleanSupplier guard,
                                 final Consumer<String> success, final Consumer<String> failure) {
        if (!allowed(viewer) || !guard.getAsBoolean()) { failure.accept(Language.tr("access.commit-cancelled")); return; }
        // The old API remains linkable, but an unconfirmed inventory transfer needs a cancellable
        // death event because native drops are computed before Bukkit handlers run.
        if (deathCancellation == null) { failure.accept(Language.tr("access.folia-death-unsupported")); return; }
        final ItemStack[] after = cloneItems(proposed);
        final Properties recovery = new Properties();
        recovery.setProperty("target", original.target().toString());
        recovery.setProperty("actor", viewer.getUniqueId().toString());
        recovery.setProperty("storage", key(original.storage()));
        try {
            for (int index : changed) {
                recovery.setProperty("before." + nbtSlot(index, original.storage()), encoded(before[index]));
                recovery.setProperty("after." + nbtSlot(index, original.storage()), encoded(after[index]));
            }
        } catch (RuntimeException ex) { failure.accept(Language.tr("access.serialize-failed", "reason", concise(ex))); return; }
        final Player target = token.player();
        final FoliaAttempt attempt = new FoliaAttempt(target, viewer, original, before, after, changed, recovery, failure);
        if (onlinePending.putIfAbsent(original.target(), attempt) != null) { failure.accept(Language.tr("access.save-pending")); return; }
        plugin.scheduler().entity(target, () -> {
          synchronized (attempt) {
            if (attempt.finished) return;
            if (closed || players.apply(original.target()) != target || !target.isOnline()) {
                finishOnline(attempt);
                onViewer(viewer, () -> failure.accept(Language.tr("access.target-relogged"))); return;
            }
            if (!transferring.add(original.target())) {
                finishOnline(attempt);
                onViewer(viewer, () -> failure.accept(Language.tr("access.save-pending"))); return;
            }
            try {
                ItemStack[] live = contents(target, original.storage());
                for (int index : changed) if (!same(live[index], before[index])) {
                    finishOnline(attempt);
                    onViewer(viewer, () -> failure.accept(Language.tr("access.slot-conflict"))); return;
                }
                for (int index : changed) live[index] = cloneItem(after[index]);
                attempt.mutated = true;
                if (!changed.isEmpty()) apply(target, original.storage(), live);
            } catch (RuntimeException ex) {
                if (attempt.mutated) journal(recovery, "Target apply failed and may have partially changed slots: " + concise(ex));
                finishOnline(attempt);
                onViewer(viewer, () -> failure.accept(Language.tr(attempt.mutated ? "access.rollback-recovery" : "access.save-failed", "reason", concise(ex)))); return;
            }
            plugin.scheduler().entity(viewer, () -> {
              synchronized (attempt) {
                if (attempt.finished) return;
                if (!allowed(viewer) || !guard.getAsBoolean()) {
                    rollbackOnline(attempt, null); return;
                }
                try {
                    success.accept(Language.tr("access.saved-online", "count", changed.size(), "player", original.name(),
                            "uuid", original.target(), "storage", storageName(original.storage())));
                } catch (RuntimeException completion) {
                    // Administrator mutation may already have happened: do not guess and roll back one side.
                    journal(recovery, "Administrator completion threw after target commit: " + concise(completion));
                } finally { finishOnline(attempt); }
              }
            }, () -> rollbackOnline(attempt, null));
          }
        }, () -> {
            synchronized (attempt) { finishOnline(attempt); }
            onViewer(viewer, () -> failure.accept(Language.tr("access.target-relogged")));
        });
    }

    private void rollbackOnline(final FoliaAttempt attempt, final Runnable finished) {
        plugin.scheduler().entityCleanup(attempt.target, () -> {
          synchronized (attempt) {
            try {
                if (attempt.finished) return;
                if (!attempt.mutated) { finishOnline(attempt); return; }
                if (players.apply(attempt.original.target()) != attempt.target || !attempt.target.isOnline()) throw new IllegalStateException("Target retired before rollback");
                ItemStack[] live = contents(attempt.target, attempt.original.storage());
                for (int index : attempt.changed) if (!same(live[index], attempt.after[index])) throw new IllegalStateException("Target slot changed before rollback: " + index);
                for (int index : attempt.changed) live[index] = cloneItem(attempt.before[index]);
                if (!attempt.changed.isEmpty()) apply(attempt.target, attempt.original.storage(), live);
                if (!closed) onViewer(attempt.viewer, () -> attempt.failure.accept(Language.tr("access.commit-cancelled")));
            } catch (RuntimeException ex) {
                journal(attempt.recovery, "Conditional rollback failed: " + concise(ex));
                if (!closed) onViewer(attempt.viewer, () -> attempt.failure.accept(Language.tr("access.rollback-recovery")));
            } finally { finishOnline(attempt); if (finished != null) finished.run(); }
          }
        }, () -> {
          synchronized (attempt) {
            if (!attempt.finished && attempt.mutated) journal(attempt.recovery, "Target scheduler retired before conditional rollback");
            finishOnline(attempt);
            if (finished != null) finished.run();
            if (!closed) onViewer(attempt.viewer, () -> attempt.failure.accept(Language.tr("access.rollback-recovery")));
          }
        });
    }

    private void finishOnline(FoliaAttempt attempt) {
        attempt.finished = true;
        if (onlinePending.get(attempt.original.target()) == attempt) {
            transferring.remove(attempt.original.target());
            onlinePending.remove(attempt.original.target(), attempt);
        }
    }

    private static String encoded(ItemStack item) {
        return empty(item) ? "EMPTY" : Base64.getEncoder().encodeToString(BukkitCompat.serializeItem(item.clone()));
    }

    private void journal(final Properties data, final String reason) {
        data.setProperty("reason", reason);
        data.setProperty("created", Long.toString(System.currentTimeMillis()));
        Runnable write = () -> {
            Path destination = plugin.getDataFolder().toPath().resolve("recovery-journal").resolve(UUID.randomUUID() + ".properties");
            try {
                Files.createDirectories(destination.getParent());
                try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(destination,
                        java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                    data.store(java.nio.channels.Channels.newOutputStream(channel), "Unresolved inventory transfer; inspect before/after NBT before recovery");
                    channel.force(true);
                }
                plugin.getLogger().severe("Inventory transfer recovery required: " + destination + " (" + reason + ")");
            } catch (IOException ex) { plugin.getLogger().log(Level.SEVERE, "Cannot persist inventory recovery journal: " + data, ex); }
        };
        try { io.submit(write); }
        catch (java.util.concurrent.RejectedExecutionException shutdown) { write.run(); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void freezeClick(InventoryClickEvent event) { if (transferring.contains(event.getWhoClicked().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void freezeDrag(InventoryDragEvent event) { if (transferring.contains(event.getWhoClicked().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void freezeDrop(PlayerDropItemEvent event) { if (transferring.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void freezeConsume(PlayerItemConsumeEvent event) { if (transferring.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void freezeInteract(PlayerInteractEvent event) { if (transferring.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void freezeDamage(EntityDamageEvent event) { if (transferring.contains(event.getEntity().getUniqueId())) event.setCancelled(true); }

    private void closePrepared(PlayerDataStore.PreparedSave prepared) {
        if (prepared == null) return;
        try { prepared.close(); }
        catch (IOException ex) { plugin.getLogger().log(Level.WARNING, "Could not clean a prepared inventory replacement", ex); }
    }

    private static byte[][] encodeStorage(Object value, PlayerScanner.Storage storage, int version, int maxBytes, int maxDepth) throws IOException {
        int size = storage == PlayerScanner.Storage.INVENTORY ? 41 : 27;
        byte[][] result = new byte[size][];
        for (Map.Entry<Integer, Map<String, Object>> entry : storage(value, storage).entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>(entry.getValue());
            item.remove("Slot"); item.remove("slot");
            if (version > 0) item.put("DataVersion", version);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(NbtWriter.write(item, maxBytes, maxDepth)); }
            result[entry.getKey()] = bytes.toByteArray();
        }
        return result;
    }

    private static List<Map<String, Object>> replaceStorage(Object value, PlayerScanner.Storage storage, Map<Integer, byte[]> replacements,
                                                           int maxBytes, int maxDepth) throws IOException {
        Map<Integer, Map<String, Object>> slots = storage(value, storage);
        for (Map.Entry<Integer, byte[]> replacement : replacements.entrySet()) {
            if (replacement.getValue() == null) slots.remove(replacement.getKey());
            else {
                Map<String, Object> item = new LinkedHashMap<>(NbtReader.readAuto(replacement.getValue(), maxBytes, maxDepth));
                item.remove("DataVersion"); item.remove("slot");
                item.put("Slot", (byte) nbtSlot(replacement.getKey(), storage));
                slots.put(replacement.getKey(), item);
            }
        }
        return new ArrayList<>(slots.values());
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Map<String, Object>> storage(Object value, PlayerScanner.Storage storage) throws IOException {
        Map<Integer, Map<String, Object>> slots = new LinkedHashMap<>();
        if (value == null) return slots;
        if (!(value instanceof List<?>)) throw new IOException(Language.tr("access.invalid-storage-list"));
        List<?> list = (List<?>) value;
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?>)) throw new IOException(Language.tr("access.invalid-item-entry"));
            Map<?, ?> map = (Map<?, ?>) entry;
            Object raw = map.get("Slot");
            Number number = raw instanceof Number ? (Number) raw : null;
            int slot = raw instanceof Byte ? Byte.toUnsignedInt((Byte) raw) : number != null
                    && number.longValue() == number.intValue() && number.doubleValue() == number.intValue() ? number.intValue() : -1;
            int index = storage == PlayerScanner.Storage.ENDER_CHEST ? slot : slot >= 0 && slot < 36 ? slot : slot >= 100 && slot <= 103 ? slot - 64 : slot == 150 ? 40 : -1;
            int size = storage == PlayerScanner.Storage.INVENTORY ? 41 : 27;
            if (index < 0 || index >= size || storage == PlayerScanner.Storage.INVENTORY && index == 40 && !BukkitCompat.hasOffhand()
                    || slots.putIfAbsent(index, (Map<String, Object>) map) != null) throw new IOException(Language.tr("access.invalid-slot", "slot", slot));
        }
        return slots;
    }

    private static int nbtSlot(int index, PlayerScanner.Storage storage) {
        return storage == PlayerScanner.Storage.ENDER_CHEST || index < 36 ? index : index < 40 ? index + 64 : 150;
    }

    private static ItemStack[] contents(Player player, PlayerScanner.Storage storage) {
        if (storage == PlayerScanner.Storage.ENDER_CHEST) return cloneItems(player.getEnderChest().getContents());
        ItemStack[] result = new ItemStack[41];
        org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
        System.arraycopy(BukkitCompat.storageContents(player), 0, result, 0, 36);
        System.arraycopy(inventory.getArmorContents(), 0, result, 36, 4);
        result[40] = BukkitCompat.offhand(player);
        return cloneItems(result);
    }

    private static void apply(Player player, PlayerScanner.Storage storage, ItemStack[] items) {
        if (storage == PlayerScanner.Storage.ENDER_CHEST) player.getEnderChest().setContents(cloneItems(items));
        else {
            org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
            BukkitCompat.setStorageContents(player, java.util.Arrays.copyOfRange(items, 0, 36));
            inventory.setArmorContents(java.util.Arrays.copyOfRange(items, 36, 40));
            if (BukkitCompat.hasOffhand()) BukkitCompat.setOffhand(player, cloneItem(items[40]));
        }
    }

    private boolean allowed(Player viewer) { return !closed && viewer.isOnline() && viewer.hasPermission("itemsearch.admin") && viewer.hasPermission("itemsearch.edit"); }
    private static boolean empty(ItemStack item) { return BukkitCompat.air(item); }
    private static boolean same(ItemStack a, ItemStack b) { return empty(a) ? empty(b) : !empty(b) && a.equals(b); }
    private static ItemStack cloneItem(ItemStack item) { return empty(item) ? null : item.clone(); }
    private static ItemStack[] cloneItems(ItemStack[] items) { return java.util.Arrays.stream(items).map(PlayerInventoryAccess::cloneItem).toArray(ItemStack[]::new); }
    private static String key(PlayerScanner.Storage storage) { return storage == PlayerScanner.Storage.INVENTORY ? "Inventory" : "EnderItems"; }
    private static String storageName(PlayerScanner.Storage storage) {
        return Language.tr(storage == PlayerScanner.Storage.INVENTORY ? "editor.storage.inventory" : "editor.storage.ender-chest");
    }
    private static String concise(Exception ex) { return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }
    private boolean isLoggingIn(UUID target) {
        synchronized (loginLock) {
            expireLogins();
            return loggingIn.containsKey(target) || pendingConnections.containsKey(target);
        }
    }
    private void expireLogins() {
        long cutoff = System.nanoTime() - TimeUnit.MINUTES.toNanos(3);
        loggingIn.values().removeIf(start -> start < cutoff);
        // Login/resource-pack configuration can remain active for longer than three minutes.
        // Only disconnected connections expire; retain all attempts sharing one UUID.
        pendingConnections.values().forEach(connections -> connections.removeIf(connection -> !connected(connection)));
        pendingConnections.values().removeIf(Set::isEmpty);
    }
    private void pending(UUID target, Object connection) {
        if (connection != null) pendingConnections.computeIfAbsent(target,
                ignored -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>())).add(connection);
    }

    private static Object optional(Object object, String name) {
        if (object == null) return null;
        try {
            Method method = object.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(object);
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
    }

    private static boolean connected(Object connection) {
        Object connected = optional(connection, "isConnected");
        return !Boolean.FALSE.equals(connected); // An unknown connection is retained rather than clearing a live login gate.
    }

    private static String loginMessage(UUID uuid, String name) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(
                Language.component("access.login-saving", "uuid", uuid, "player", name));
    }

    @SuppressWarnings("unchecked")
    private void registerOptionalConnectionGuard() {
        try {
            Class<?> type = Class.forName("io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent",
                    false, Bukkit.getServer().getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(type)) return;
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) type, this, EventPriority.HIGHEST,
                    new EventExecutor() {
                        @Override public void execute(Listener listener, Event event) { validateConnection(event); }
                    }, plugin, false);
        } catch (ClassNotFoundException unavailable) {
            // Classic Bukkit admission is guarded by the two pre-login listeners and PlayerLoginEvent.
        }
    }

    @SuppressWarnings("unchecked")
    private void registerOptionalFreeze(String name, final String entityMethod) {
        try {
            Class<?> type = Class.forName(name, false, Bukkit.getServer().getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(type) || !org.bukkit.event.Cancellable.class.isAssignableFrom(type)) return;
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) type, this, EventPriority.HIGHEST,
                    new EventExecutor() {
                        @Override public void execute(Listener listener, Event event) {
                            Object entity = optional(event, entityMethod);
                            if (entity instanceof org.bukkit.entity.Entity && transferring.contains(((org.bukkit.entity.Entity) entity).getUniqueId()))
                                ((org.bukkit.event.Cancellable) event).setCancelled(true);
                        }
                    }, plugin, false);
        } catch (ClassNotFoundException unavailable) { }
    }

    private void kickOptional(Event event, UUID target, String name) {
        try {
            for (Method method : event.getClass().getMethods()) if (method.getName().equals("kickMessage") && method.getParameterTypes().length == 1) {
                Class<?> component = method.getParameterTypes()[0];
                Object message = component.getMethod("text", String.class).invoke(null,
                        Language.plain("access.login-saving", "uuid", target, "player", name));
                method.invoke(event, message);
                return;
            }
            throw new IllegalStateException("Optional connection event has no supported admission setter");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot guard a pending player-data commit", failure); }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void preLogin(AsyncPlayerPreLoginEvent event) {
        synchronized (loginLock) {
            expireLogins();
            if (saving.contains(event.getUniqueId())) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    loginMessage(event.getUniqueId(), event.getName()));
            // A second rejected attempt must not remove another connection's pending marker.
            // Keep unsuccessful attempts until expiry: a later listener may change admission.
            else {
                loggingIn.put(event.getUniqueId(), System.nanoTime());
                pending(event.getUniqueId(), optional(event, "getConnection"));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void finalPreLogin(AsyncPlayerPreLoginEvent event) {
        synchronized (loginLock) {
            if (saving.contains(event.getUniqueId())) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    loginMessage(event.getUniqueId(), event.getName()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void login(PlayerLoginEvent event) {
        synchronized (loginLock) {
            UUID target = event.getPlayer().getUniqueId();
            if (saving.contains(target)) event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    loginMessage(target, event.getPlayer().getName()));
            else if (event.getResult() == PlayerLoginEvent.Result.ALLOWED) loggingIn.put(target, System.nanoTime());
        }
    }

    private void validateConnection(Event event) {
        // Paper validates again after configuration/resource packs, immediately before loading
        // player data. A configuration phase can outlive the initial pending-login timeout.
        UUID target = null;
        String playerName = "";
        Object connection = optional(event, "getConnection");
        Object profile = optional(connection, "getProfile");
        if (profile == null) profile = optional(connection, "getAuthenticatedProfile");
        if (profile != null) {
            Object id = optional(profile, "getId"), name = optional(profile, "getName");
            if (id instanceof UUID) target = (UUID) id;
            if (name instanceof String) playerName = (String) name;
        }
        if (target == null) return;
        synchronized (loginLock) {
            if (saving.contains(target)) kickOptional(event, target, playerName);
            else if (Boolean.TRUE.equals(optional(event, "isAllowed"))) {
                loggingIn.put(target, System.nanoTime());
                pending(target, connection);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void joined(PlayerJoinEvent event) {
        synchronized (loginLock) { loggingIn.remove(event.getPlayer().getUniqueId()); }
        if (editor != null) editor.targetJoined(event.getPlayer().getUniqueId());
    }

    /** A departing target must restore an unconfirmed transfer before native quit persistence. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void quitting(PlayerQuitEvent event) {
        final Player target = event.getPlayer();
        final FoliaAttempt attempt = onlinePending.get(target.getUniqueId());
        if (attempt == null || attempt.target != target) return;
        synchronized (attempt) {
            if (attempt.finished) return; // The administrator phase may already have completed under this lock.
            try {
                if (attempt.mutated) {
                    restorePendingTarget(attempt);
                }
            } catch (RuntimeException failure) {
                journal(attempt.recovery, "Target quit conditional rollback failed: " + concise(failure));
            } finally {
                finishOnline(attempt); // A queued viewer confirmation now skips mutation.
            }
            if (!closed) onViewer(attempt.viewer, () -> attempt.failure.accept(Language.tr("access.commit-cancelled")));
        }
    }

    /** Native inventory drops were built from the unconfirmed state, so cancel this one death first. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void dying(PlayerDeathEvent event) {
        Player target = event.getEntity();
        final FoliaAttempt attempt = onlinePending.get(target.getUniqueId());
        if (attempt == null || attempt.target != target) return;
        synchronized (attempt) {
            if (attempt.finished) return;
            try {
                if (attempt.mutated) {
                    if (attempt.original.storage() == PlayerScanner.Storage.INVENTORY && !attempt.changed.isEmpty())
                        deathCancellation.invoke(event, true);
                    restorePendingTarget(attempt);
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                journal(attempt.recovery, "Target death conditional rollback failed: " + concise(failure));
            } finally { finishOnline(attempt); }
            if (!closed) onViewer(attempt.viewer, () -> attempt.failure.accept(Language.tr("access.commit-cancelled")));
        }
    }

    private void restorePendingTarget(FoliaAttempt attempt) {
        ItemStack[] live = contents(attempt.target, attempt.original.storage());
        for (int index : attempt.changed) if (!same(live[index], attempt.after[index]))
            throw new IllegalStateException("Target slot changed before lifecycle rollback: " + index);
        for (int index : attempt.changed) live[index] = cloneItem(attempt.before[index]);
        if (!attempt.changed.isEmpty()) apply(attempt.target, attempt.original.storage(), live);
    }

    private static Method optionalDeathCancellation() {
        try { return PlayerDeathEvent.class.getMethod("setCancelled", boolean.class); }
        catch (NoSuchMethodException unavailable) { return null; }
    }

    @Override public void close() {
        List<PlayerDataStore.PreparedSave> prepared;
        synchronized (loginLock) {
            closed = true;
            prepared = new ArrayList<>();
            for (PendingSave pending : pendingSaves.values()) if (pending.prepared != null) prepared.add(pending.prepared);
            pendingSaves.clear();
            saving.clear();
        }
        prepared.forEach(this::closePrepared);
        if (plugin.scheduler().isFolia()) {
            List<FoliaAttempt> attempts = new ArrayList<>(onlinePending.values());
            final java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(attempts.size());
            for (FoliaAttempt attempt : attempts) rollbackOnline(attempt, () -> finished.countDown());
            try {
                if (!finished.await(5, TimeUnit.SECONDS)) {
                    plugin.getLogger().severe("Folia inventory rollback cleanup timed out; unresolved transfers were journaled.");
                    for (FoliaAttempt attempt : attempts) synchronized (attempt) {
                        if (!attempt.finished && attempt.mutated) journal(attempt.recovery, "Disable cleanup timed out before conditional rollback");
                    }
                }
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
        io.shutdown();
        try { if (!io.awaitTermination(5, TimeUnit.SECONDS)) plugin.getLogger().warning("Inventory preparation is still cleaning up during plugin shutdown."); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
