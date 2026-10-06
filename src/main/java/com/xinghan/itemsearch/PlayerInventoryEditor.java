package com.xinghan.itemsearch;

import com.xinghan.itemsearch.player.PlayerScanner;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Both sides of a transfer stay in temporary menu inventories until an explicit guarded save. */
public final class PlayerInventoryEditor implements Listener, AutoCloseable {
    public interface Backend {
        void load(Player viewer, UUID target, PlayerScanner.Storage storage,
                  Consumer<Loaded> success, Consumer<String> failure);
        void save(Player viewer, Loaded original, ItemStack[] proposed,
                  Consumer<String> success, Consumer<String> failure);
        /** Implementations must test guard immediately before mutation and invoke success in that same main-thread call. */
        default void saveTransfer(Player viewer, Loaded original, ItemStack[] proposed, BooleanSupplier guard,
                                  Consumer<String> success, Consumer<String> failure) {
            if (!guard.getAsBoolean()) { failure.accept(Language.tr("editor.commit-cancelled")); return; }
            save(viewer, original, proposed, success, failure);
        }
    }

    public static final class Loaded {
        private final UUID target;
        private final String name;
        private final boolean online;
        private final PlayerScanner.Storage storage;
        private final ItemStack[] items;
        private final Object token;
        public Loaded(UUID target, String name, boolean online, PlayerScanner.Storage storage,
                      ItemStack[] items, Object token) {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(storage, "storage");
            Objects.requireNonNull(items, "items");
            if (items.length != InventoryEditorLayout.sourceSize(storage)) throw new IllegalArgumentException("Invalid snapshot length");
            this.target = target;
            this.name = name == null || name.trim().isEmpty() ? target.toString() : name;
            this.online = online; this.storage = storage;
            this.items = cloneItems(items); this.token = token;
        }
        public UUID target() { return target; }
        public String name() { return name; }
        public boolean online() { return online; }
        public PlayerScanner.Storage storage() { return storage; }
        public ItemStack[] items() { return cloneItems(items); }
        public Object token() { return token; }
    }

    private final RegionItemSearchPlugin plugin;
    private final Backend backend;
    private final InventoryTransferView.Factory transfers;
    private final Map<UUID, Session> viewers = new ConcurrentHashMap<>();
    private final Map<UUID, Session> targets = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private static final class Session {
        final Player viewer;
        final UUID target;
        final PlayerScanner.Storage storage;
        volatile Loaded original;
        ItemStack[] adminBefore;
        ItemStack[] adminProposed;
        volatile Inventory current;
        InventoryTransferView transfer;
        volatile boolean saving;
        volatile boolean aborted;
        volatile boolean joinedWhileLoading;
        volatile boolean quitWhileLoading;
        Session(Player viewer, UUID target, PlayerScanner.Storage storage) {
            this.viewer = viewer;
            this.target = target;
            this.storage = storage;
        }
    }

    private static final class Menu implements InventoryHolder {
        final Session session;
        Inventory inventory;
        Menu(Session session) { this.session = session; }
        @Override public Inventory getInventory() { return inventory; }
    }

    public PlayerInventoryEditor(RegionItemSearchPlugin plugin, Backend backend) {
        this(plugin, backend, InventoryTransferView::attach);
    }

    public PlayerInventoryEditor(RegionItemSearchPlugin plugin, Backend backend, InventoryTransferView.Factory transfers) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player viewer, UUID target, PlayerScanner.Storage storage) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(storage, "storage");
        onViewer(viewer, () -> openMain(viewer, target, storage));
    }

    private void openMain(Player viewer, UUID target, PlayerScanner.Storage storage) {
        if (closed || !viewer.isOnline() || !permitted(viewer)) return;
        if (!plugin.scheduler().supportsEntityCleanup(viewer)) {
            plugin.getLogger().warning("This Folia entity scheduler has no safe detached-inventory shutdown adapter.");
            tell(viewer, Language.tr("editor.open-failed"));
            return;
        }
        Session previous = viewers.get(viewer.getUniqueId());
        if (previous != null && previous.saving) { tell(viewer, Language.tr("editor.previous-saving")); return; }
        Session owner = targets.get(target);
        if (owner != null && owner != previous) { tell(viewer, Language.tr("editor.target-locked")); return; }
        if (previous != null) abort(previous, null, true);
        if (!empty(viewer.getItemOnCursor())) { tell(viewer, Language.tr("editor.cursor-before-open")); return; }
        if (target.equals(viewer.getUniqueId()) && storage == PlayerScanner.Storage.INVENTORY) {
            tell(viewer, Language.tr("editor.own-inventory"));
            return;
        }
        Session session = new Session(viewer, target, storage);
        if (targets.putIfAbsent(target, session) != null) { tell(viewer, Language.tr("editor.target-locked")); return; }
        viewers.put(viewer.getUniqueId(), session);
        tell(viewer, Language.tr("editor.loading"));
        try {
            backend.load(viewer, target, storage,
                    loaded -> onViewer(viewer, () -> loaded(session, loaded)),
                    reason -> onViewer(viewer, () -> loadFailed(session, reason)));
        } catch (RuntimeException failure) { loadFailed(session, Language.tr("editor.load-failed")); }
    }

    private void loaded(Session session, Loaded loaded) {
        if (closed || !active(session)) return;
        if (!session.viewer.isOnline() || !permitted(session.viewer)) { abort(session, null, true); return; }
        if (loaded == null || !loaded.target().equals(session.target) || loaded.storage() != session.storage
                || (!loaded.online() && session.joinedWhileLoading) || (loaded.online() && session.quitWhileLoading)) {
            loadFailed(session, Language.tr("editor.target-changed"));
            return;
        }
        if (!empty(session.viewer.getItemOnCursor())) {
            loadFailed(session, Language.tr("editor.cursor-after-load"));
            return;
        }
        session.original = loaded;
        session.adminBefore = cloneItems(BukkitCompat.storageContents(session.viewer));
        Menu menu = new Menu(session);
        String storageName = storageName(session.storage);
        Inventory inventory = BukkitCompat.createInventory(menu, InventoryEditorLayout.size(session.storage),
                Language.component("editor.title", "player", loaded.name(), "uuid", session.target, "storage", storageName));
        menu.inventory = inventory;
        // Only reserve the control row and unused armor-row slots. Items keep their exact metadata.
        for (int i = 0; i < inventory.getSize(); i++) if (sourceSlot(session.storage, i) < 0) {
            inventory.setItem(i, button(material("GRAY_STAINED_GLASS_PANE", "STAINED_GLASS_PANE"), "editor.filler-name", null));
        }
        ItemStack[] items = loaded.items();
        for (int source = 0; source < items.length; source++) {
            if (source == 40 && session.storage == PlayerScanner.Storage.INVENTORY && !BukkitCompat.hasOffhand()) continue;
            inventory.setItem(InventoryEditorLayout.toGui(session.storage, source), cloneItem(items[source]));
        }
        inventory.setItem(InventoryEditorLayout.helpSlot(session.storage), button(Material.BOOK, "editor.help-name", "editor.help-lore",
                "storage", storageName, "player", loaded.name(), "uuid", session.target,
                "guide", Language.tr(session.storage == PlayerScanner.Storage.INVENTORY ? "editor.inventory-guide" : "editor.ender-guide")));
        updateControls(session, inventory);
        session.current = inventory;
        try {
            session.viewer.openInventory(inventory);
            Inventory opened = BukkitCompat.viewTop(session.viewer.getOpenInventory());
            // Older CraftBukkit wraps the same custom inventory in a fresh CraftInventory view.
            // The unique holder identifies our backing inventory without trusting a redirected menu.
            if (opened != inventory && (opened.getHolder() != menu || opened.getSize() != inventory.getSize()))
                throw new IllegalStateException("The editor window was cancelled or redirected");
            session.current = opened;
            menu.inventory = opened;
            session.transfer = transfers.attach(session.viewer, inventory.getSize(), session.adminBefore);
        } catch (Exception failure) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Unable to stage inventory transfer", failure);
            abort(session, Language.tr("editor.open-failed"), true);
            return;
        }
        tell(session.viewer, Language.tr("editor.opened", "storage", storageName, "player", loaded.name(), "uuid", session.target));
    }

    private void loadFailed(Session session, String reason) {
        if (closed || !active(session)) return;
        abort(session, null, true);
        tell(session.viewer, message(reason, Language.tr("editor.load-failed")));
    }

    private static void updateControls(Session session, Inventory inventory) {
        String storageName = storageName(session.storage);
        inventory.setItem(InventoryEditorLayout.saveSlot(session.storage), button(session.saving ? material("CLOCK", "WATCH") : Material.PAPER,
                session.saving ? "editor.saving-name" : "editor.save-name",
                session.saving ? "editor.saving-lore" : "editor.save-lore",
                "storage", storageName, "player", session.original.name(), "uuid", session.target));
        inventory.setItem(InventoryEditorLayout.cancelSlot(session.storage), button(Material.BARRIER, "editor.cancel-name", "editor.cancel-lore",
                "storage", storageName, "player", session.original.name(), "uuid", session.target));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player viewer = (Player) event.getWhoClicked();
        Session session = session(viewer, BukkitCompat.viewTop(event.getView()));
        if (session == null) return;
        boolean alreadyCancelled = event.isCancelled();
        int raw = event.getRawSlot();
        Inventory top = BukkitCompat.viewTop(event.getView());
        if (event instanceof InventoryCreativeEvent || closed || !active(session) || session.current != top
                || session.aborted || session.original == null || !viewer.isOnline() || !permitted(viewer)) {
            event.setCancelled(true);
            return;
        }
        if (raw == InventoryEditorLayout.cancelSlot(session.storage)) {
            event.setCancelled(true);
            if (!alreadyCancelled) plugin.scheduler().entityLater(viewer, 1, () -> { if (active(session) && session.current == top) abort(session, Language.tr("editor.cancelled"), true); });
            return;
        }
        if (session.saving) { event.setCancelled(true); return; }
        if (raw == InventoryEditorLayout.saveSlot(session.storage)) {
            event.setCancelled(true);
            if (!alreadyCancelled) plugin.scheduler().entityLater(viewer, 1, () -> { if (active(session) && session.current == top) save(session); });
            return;
        }
        if (!editable(session, raw)) { event.setCancelled(true); return; }
        ClickType click = event.getClick();
        if ((click == ClickType.LEFT || click == ClickType.RIGHT)
                && (bundle(viewer.getItemOnCursor()) || bundle(stagedItem(session, event.getView(), raw)))) {
            event.setCancelled(true);
            if (!alreadyCancelled) {
                if (click == ClickType.LEFT) bundleTransfer(session, event.getView(), raw);
                else tell(viewer, Language.tr("editor.bundle-left-only"));
            }
            return;
        }
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            event.setCancelled(true);
            if (!alreadyCancelled && empty(viewer.getItemOnCursor())) quickTransfer(session, event.getView(), raw);
            return;
        }
        // Native pickup/place and drag now operate on two temporary inventories.
        // Swaps/drops/pickup-all consult the real player inventory or can leak temporary items.
        if (click != ClickType.LEFT && click != ClickType.RIGHT) event.setCancelled(true);
    }

    private static boolean editable(Session session, int raw) {
        int topSize = InventoryEditorLayout.size(session.storage);
        return raw >= 0 && (raw < topSize ? sourceSlot(session.storage, raw) >= 0 : raw < topSize + 36);
    }

    /** Bundle primary/secondary overrides can insert items or mutate selection; transfer the intact item instead. */
    private static void bundleTransfer(Session session, InventoryView view, int raw) {
        ItemStack slot = cloneItem(stagedItem(session, view, raw));
        ItemStack cursor = cloneItem(session.viewer.getItemOnCursor());
        if (empty(cursor)) {
            stagedItem(session, view, raw, null);
            session.viewer.setItemOnCursor(slot);
            return;
        }
        int limit = Math.min(cursor.getMaxStackSize(), 64);
        if (empty(slot) || cursor.isSimilar(slot)) {
            int count = Math.min(Math.max(0, limit - (empty(slot) ? 0 : slot.getAmount())), cursor.getAmount());
            if (count == 0) return;
            ItemStack placed = empty(slot) ? cursor.clone() : slot.clone();
            placed.setAmount((empty(slot) ? 0 : slot.getAmount()) + count);
            stagedItem(session, view, raw, placed);
            cursor.setAmount(cursor.getAmount() - count);
            session.viewer.setItemOnCursor(cloneItem(cursor));
        } else if (cursor.getAmount() <= limit) {
            stagedItem(session, view, raw, cursor);
            session.viewer.setItemOnCursor(slot);
        }
    }

    private static void quickTransfer(Session session, InventoryView view, int raw) {
        ItemStack source = stagedItem(session, view, raw);
        if (empty(source)) return;
        ItemStack remaining = source.clone();
        int topSize = BukkitCompat.viewTop(view).getSize();
        boolean fromTop = raw < topSize;
        // Put into storage/hotbar first; do not auto-equip armor or the offhand.
        int destinationSize = fromTop ? 36 : session.storage == PlayerScanner.Storage.INVENTORY ? 36 : 27;
        for (int pass = 0; pass < 2 && remaining.getAmount() > 0; pass++) {
            for (int i = 0; i < destinationSize && remaining.getAmount() > 0; i++) {
                int dest = fromTop ? topSize + i : i;
                ItemStack existing = stagedItem(session, view, dest);
                int capacity;
                if (pass == 0) {
                    if (empty(existing) || !remaining.isSimilar(existing)) continue;
                    capacity = Math.min(existing.getMaxStackSize(), 64) - existing.getAmount();
                } else {
                    if (!empty(existing)) continue;
                    capacity = Math.min(remaining.getMaxStackSize(), 64);
                }
                int count = Math.min(Math.max(0, capacity), remaining.getAmount());
                if (count == 0) continue;
                ItemStack changed = empty(existing) ? remaining.clone() : existing.clone();
                changed.setAmount((empty(existing) ? 0 : existing.getAmount()) + count);
                stagedItem(session, view, dest, changed);
                remaining.setAmount(remaining.getAmount() - count);
            }
        }
        stagedItem(session, view, raw, remaining.getAmount() <= 0 ? null : remaining);
    }

    private static ItemStack stagedItem(Session session, InventoryView view, int raw) {
        int topSize = BukkitCompat.viewTop(view).getSize();
        return raw < topSize ? BukkitCompat.viewTop(view).getItem(raw) : session.transfer.getItem((raw - topSize + 9) % 36);
    }

    private static void stagedItem(Session session, InventoryView view, int raw, ItemStack item) {
        int topSize = BukkitCompat.viewTop(view).getSize();
        if (raw < topSize) BukkitCompat.viewTop(view).setItem(raw, item);
        else session.transfer.setItem((raw - topSize + 9) % 36, item);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player viewer = (Player) event.getWhoClicked();
        Session session = session(viewer, BukkitCompat.viewTop(event.getView()));
        if (session == null) return;
        if (closed || !active(session) || session.current != BukkitCompat.viewTop(event.getView()) || session.saving
                || session.aborted || !permitted(viewer) || event.getRawSlots().stream().anyMatch(raw -> !editable(session, raw))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player viewer = (Player) event.getWhoClicked();
        Session current = viewers.get(viewer.getUniqueId());
        if (session(viewer, BukkitCompat.viewTop(event.getView())) != null
                || current != null && current.current != null && BukkitCompat.viewTop(viewer.getOpenInventory()) == current.current) {
            event.setCancelled(true);
        }
    }

    private void save(Session session) {
        if (!permitted(session.viewer) || session.saving || session.aborted || session.transfer == null) return;
        if (!empty(session.viewer.getItemOnCursor())) { tell(session.viewer, Language.tr("editor.cursor-before-save")); return; }
        if (!adminUnchanged(session)) {
            abort(session, Language.tr("editor.admin-changed"), true);
            return;
        }
        ItemStack[] proposed = new ItemStack[InventoryEditorLayout.sourceSize(session.storage)];
        for (int i = 0; i < proposed.length; i++) {
            if (i == 40 && session.storage == PlayerScanner.Storage.INVENTORY && !BukkitCompat.hasOffhand()) continue;
            proposed[i] = cloneItem(session.current.getItem(InventoryEditorLayout.toGui(session.storage, i)));
        }
        session.adminProposed = session.transfer.contents();
        if (session.adminProposed.length != 36) { abort(session, Language.tr("editor.invalid-staged-inventory"), true); return; }
        if (!conserved(session.original.items(), session.adminBefore, proposed, session.adminProposed)) {
            abort(session, Language.tr("editor.item-count-changed"), true);
            return;
        }
        session.saving = true;
        updateControls(session, session.current);
        try {
            backend.saveTransfer(session.viewer, session.original, proposed, () -> validCommit(session),
                    result -> onViewer(session.viewer, () -> saved(session, true, result)),
                    reason -> onViewer(session.viewer, () -> saved(session, false, reason)));
        } catch (RuntimeException failure) { saved(session, false, Language.tr("editor.save-failed")); }
    }

    private static boolean conserved(ItemStack[] beforeTarget, ItemStack[] beforeAdmin, ItemStack[] target, ItemStack[] admin) {
        Map<ItemStack, Long> totals = new HashMap<>();
        count(totals, beforeTarget, 1);
        count(totals, beforeAdmin, 1);
        count(totals, target, -1);
        count(totals, admin, -1);
        return totals.values().stream().allMatch(count -> count == 0);
    }

    private static void count(Map<ItemStack, Long> totals, ItemStack[] items, int sign) {
        for (ItemStack item : items) if (!empty(item)) {
            ItemStack identity = item.clone();
            identity.setAmount(1);
            totals.merge(identity, (long) item.getAmount() * sign, Long::sum);
        }
    }

    private boolean validCommit(Session session) {
        return InventoryTransferView.owns(session.viewer) && !closed && active(session) && session.saving && !session.aborted
                && session.viewer.isOnline() && session.viewer.hasPermission("itemsearch.admin") && session.viewer.hasPermission("itemsearch.edit")
                && session.current != null && BukkitCompat.viewTop(session.viewer.getOpenInventory()) == session.current
                && empty(session.viewer.getItemOnCursor()) && adminUnchanged(session);
    }

    private static boolean adminUnchanged(Session session) {
        ItemStack[] live = BukkitCompat.storageContents(session.viewer);
        if (live.length != session.adminBefore.length) return false;
        for (int i = 0; i < live.length; i++) if (!same(live[i], session.adminBefore[i])) return false;
        return true;
    }

    private void saved(Session session, boolean success, String result) {
        if (success) {
            // Backend's final guard and target mutation run immediately before this callback.
            BukkitCompat.setStorageContents(session.viewer, cloneItems(session.adminProposed));
            session.saving = false;
            release(session);
            closeWindow(session);
            tell(session.viewer, message(result, Language.tr("editor.saved")));
            return;
        }
        session.saving = false;
        if (closed || !active(session) || session.aborted || !session.viewer.isOnline()) {
            release(session);
            closeWindow(session);
        } else if (!permitted(session.viewer) || !adminUnchanged(session)) {
            abort(session, Language.tr("editor.save-cancelled"), true);
        } else {
            updateControls(session, session.current);
            tell(session.viewer, Language.tr("editor.save-failed-retry", "reason", message(result, Language.tr("editor.save-failed"))));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player viewer = (Player) event.getPlayer();
        Session session = viewers.get(viewer.getUniqueId());
        if (session == null) session = session(viewer, event.getInventory());
        if (session == null || session.current != event.getInventory()) return;
        // Vanilla returns carried items after this event. Our carried item belongs to the temporary menu.
        viewer.setItemOnCursor(null);
        session.current = null;
        abort(session, Language.tr("editor.closed"), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Session viewerSession = viewers.get(uuid);
        if (viewerSession == null) viewerSession = session(player, BukkitCompat.viewTop(player.getOpenInventory()));
        if (viewerSession != null) player.setItemOnCursor(null);
        Session targetSession = targets.get(uuid);
        if (targetSession != null) {
            if (targetSession.original == null) targetSession.quitWhileLoading = true;
            else if (targetSession.original.online()) abortFromTarget(targetSession, Language.tr("editor.target-quit"));
        }
        if (viewerSession != null) { viewerSession.viewer.setItemOnCursor(null); abort(viewerSession, null, false); }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        Session target = targets.get(uuid);
        if (target != null && target.original != null && target.original.online()) {
            abortFromTarget(target, Language.tr("editor.target-died"));
        }
        Session viewer = viewers.get(uuid);
        if (viewer == null) viewer = session(event.getEntity(), BukkitCompat.viewTop(event.getEntity().getOpenInventory()));
        if (viewer != null) abort(viewer, null, true);
    }

    public void targetJoined(UUID target) {
        Objects.requireNonNull(target, "target");
        if (closed) return;
        Session session = targets.get(target);
        if (session == null) return;
        if (session.original == null) session.joinedWhileLoading = true;
        else if (!session.original.online()) abortFromTarget(session, Language.tr("editor.target-joined"));
    }

    private boolean active(Session session) {
        return viewers.get(session.viewer.getUniqueId()) == session && targets.get(session.target) == session;
    }

    private Session session(Player viewer, Inventory inventory) {
        if (!(inventory.getHolder() instanceof Menu)) return null;
        Menu menu = (Menu) inventory.getHolder();
        if (menu.session.viewer != viewer) return null;
        return menu.session;
    }

    private void abort(Session session, String reason, boolean closeWindow) {
        session.aborted = true;
        release(session); // A pending backend save is rejected by its final commit guard.
        if (closeWindow) closeWindow(session);
        if (reason != null) tell(session.viewer, reason);
    }

    private void release(Session session) {
        viewers.remove(session.viewer.getUniqueId(), session);
        targets.remove(session.target, session);
        if (session.transfer != null) { session.transfer.close(); session.transfer = null; }
    }

    private static void closeWindow(Session session) {
        Inventory previous = session.current;
        session.current = null;
        if (previous != null && BukkitCompat.viewTop(session.viewer.getOpenInventory()) == previous) {
            session.viewer.setItemOnCursor(null);
            if (session.viewer.isOnline()) session.viewer.closeInventory();
        }
    }

    private boolean permitted(Player viewer) {
        if (viewer.hasPermission("itemsearch.admin") && viewer.hasPermission("itemsearch.edit")) return true;
        tell(viewer, Language.tr("editor.no-permission"));
        return false;
    }

    private void tell(Player viewer, String text) { BukkitCompat.send(viewer, Language.component("editor.message", "reason", text)); }
    private void onViewer(Player viewer, Runnable action) { plugin.scheduler().entity(viewer, action, () -> retireViewer(viewer)); }
    private void retireViewer(Player viewer) {
        Session session = viewers.get(viewer.getUniqueId());
        if (session != null) { session.aborted = true; release(session); }
    }
    private void abortFromTarget(Session session, String reason) {
        session.aborted = true; // Publish before scheduling back to the administrator's region.
        if (InventoryTransferView.owns(session.viewer)) abort(session, reason, true);
        else onViewer(session.viewer, () -> abort(session, reason, true));
    }
    private static String message(String supplied, String fallback) { return supplied == null || supplied.trim().isEmpty() ? fallback : supplied; }
    private static ItemStack[] cloneItems(ItemStack[] items) {
        ItemStack[] result = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) result[i] = cloneItem(items[i]);
        return result;
    }
    private static ItemStack cloneItem(ItemStack item) { return empty(item) ? null : item.clone(); }
    private static boolean bundle(ItemStack item) { return !empty(item) && (item.getType().name().equals("BUNDLE") || item.getType().name().endsWith("_BUNDLE")); }
    private static boolean empty(ItemStack item) { return BukkitCompat.air(item); }
    private static boolean same(ItemStack a, ItemStack b) { return empty(a) ? empty(b) : !empty(b) && a.equals(b); }

    private static String storageName(PlayerScanner.Storage storage) {
        return Language.tr(storage == PlayerScanner.Storage.INVENTORY ? "editor.storage.inventory" : "editor.storage.ender-chest");
    }

    private static ItemStack button(Material material, String nameKey, String loreKey, Object... pairs) {
        ItemStack item = new ItemStack(material);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        BukkitCompat.displayName(meta, Language.component(nameKey, pairs).decoration(TextDecoration.ITALIC, false));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        if (loreKey != null) for (net.kyori.adventure.text.Component line : Language.components(loreKey, pairs)) lore.add(line.decoration(TextDecoration.ITALIC, false));
        BukkitCompat.lore(meta, lore);
        item.setItemMeta(meta);
        return item;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        List<Session> sessions = new ArrayList<>(viewers.values());
        if (plugin.scheduler().isFolia()) {
            final CountDownLatch finished = new CountDownLatch(sessions.size());
            for (Session session : sessions) {
                session.aborted = true;
                plugin.scheduler().entityCleanup(session.viewer, () -> {
                    try { abort(session, null, true); }
                    finally { finished.countDown(); }
                }, () -> {
                    try { release(session); }
                    finally { finished.countDown(); }
                });
            }
            try {
                if (!finished.await(5, TimeUnit.SECONDS)) plugin.getLogger().severe(
                        "Folia inventory cleanup timed out with " + finished.getCount()
                                + " player regions pending; their staged menu cursors must be checked before reconnecting.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                plugin.getLogger().severe("Folia inventory cleanup was interrupted before all player windows closed.");
            }
        } else for (Session session : sessions) abort(session, null, true);
        HandlerList.unregisterAll(this);
    }

    private static int sourceSlot(PlayerScanner.Storage storage, int gui) {
        return InventoryEditorLayout.fromGui(storage, gui, BukkitCompat.hasOffhand());
    }

    private static Material material(String modern, String legacy) {
        Material result = Material.matchMaterial(modern);
        if (result == null) result = Material.matchMaterial(legacy);
        if (result == null) throw new IllegalStateException("Missing compatible editor material: " + modern);
        return result;
    }
}

