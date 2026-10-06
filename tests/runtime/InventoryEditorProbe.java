package com.xinghan.itemsearch.probe;

import com.xinghan.itemsearch.PlayerInventoryAccess;
import com.xinghan.itemsearch.PlayerInventoryEditor;
import com.xinghan.itemsearch.InventoryTransferView;
import com.xinghan.itemsearch.RegionItemSearchPlugin;
import com.xinghan.itemsearch.nbt.NbtWriter;
import com.xinghan.itemsearch.player.PlayerDataStore;
import com.xinghan.itemsearch.player.PlayerScanner;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

/** Real-server regression probe. Only player transport and inventory views are simulated. */
public final class InventoryEditorProbe {
    private static final int MAX_BYTES = 16_777_216;
    private static final int MAX_DEPTH = 128;

    private InventoryEditorProbe() { }

    public static void run(RegionItemSearchPlugin plugin, UUID offline, Runnable done, Consumer<Exception> fail) {
        Runnable start = () -> {
            try { new Probe(plugin, offline, done, fail).start(); }
            catch (Exception error) { fail.accept(error); }
        };
        if (Bukkit.isPrimaryThread()) start.run();
        else Bukkit.getScheduler().runTask(plugin, start);
    }

    private record Disk(PlayerDataStore.Snapshot snapshot, byte[] compressed, Map<Path, byte[]> backups,
                        Set<Path> temporaries) { }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    @FunctionalInterface private interface Read<T> { T get() throws Exception; }
    @FunctionalInterface private interface Accept<T> { void accept(T value) throws Exception; }

    private static final class Probe {
        final RegionItemSearchPlugin plugin;
        final UUID offline;
        final Runnable done;
        final Consumer<Exception> failure;
        final Path directory;
        final Path backupDirectory;
        final int dataVersion;
        final Viewer viewer;
        final List<BukkitTask> tasks = new ArrayList<>();
        final List<PlayerInventoryEditor> auxiliaryEditors = new ArrayList<>();
        Disk initial;
        Disk afterInventory;
        Disk afterEnder;
        ItemStack expectedSword;
        int adminSwordSlot;
        PlayerInventoryAccess injected;
        PlayerInventoryAccess commandAccess;
        PlayerInventoryEditor originalEditor;
        PlayerInventoryEditor commandEditor;
        boolean finished;
        String stage = "initialization";

        Probe(RegionItemSearchPlugin plugin, UUID offline, Runnable done, Consumer<Exception> failure) {
            this.plugin = plugin;
            this.offline = offline;
            this.done = done;
            this.failure = failure;
            var world = Bukkit.getWorld(Bukkit.getUnsafe().getMainLevelName());
            check(world != null, "main world exists");
            directory = world.getWorldFolder().toPath().resolve("playerdata");
            backupDirectory = plugin.getDataFolder().toPath().resolve("player-backups");
            dataVersion = Bukkit.getUnsafe().getDataVersion();
            viewer = new Viewer("ProbeInventoryAdmin", UUID.randomUUID());
        }

        void start() {
            tasks.add(Bukkit.getScheduler().runTaskLater(plugin,
                    () -> fail(new IllegalStateException("inventory probe timeout at " + stage + "; chat=" + viewer.chat)), 1_200));
            readDisk(disk -> {
                initial = disk;
                check(disk.snapshot().root().get("ProbeSentinel").equals("keep-other-player-data"), "fixture sentinel");
                check(equal(disk.snapshot().root().get("Pos"), List.of(123.25d, 80.0d, -456.5d)), "fixture position");
                expectedSword = deserialize(item(disk.snapshot().root(), "Inventory", 0));
                check(expectedSword.getType() == Material.NETHERITE_SWORD && expectedSword.getAmount() == 1
                        && expectedSword.getMaxStackSize() == 64, "custom sword fixture amount and max stack");
                check(expectedSword.getItemMeta().getPersistentDataContainer().getKeys().size() >= 2, "fixture has PDC identity");
                viewer.items[0] = expectedSword.clone();
                viewer.items[9] = new ItemStack(Material.STONE, 4);
                commandAccess = (PlayerInventoryAccess) field(plugin, "inventoryAccess");
                originalEditor = (PlayerInventoryEditor) field(plugin, "inventoryEditor");
                HandlerList.unregisterAll(originalEditor);
                commandEditor = new PlayerInventoryEditor(plugin, commandAccess, viewer::attach);
                setField(plugin, "inventoryEditor", commandEditor);
                bindEditor(commandAccess, commandEditor);
                command("inventory");
                await("open offline backpack", () -> viewer.menu(54), this::nativeMoveAndCancel);
            });
        }

        void nativeMoveAndCancel() {
            check(viewer.top().getItem(27).equals(expectedSword), "backpack slot 0 maps to GUI 27 without metadata changes");
            check(empty(viewer.top().getItem(0)), "empty target slots contain no placeholder items");
            blockedMovement();
            nativeClick(27, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            check(empty(viewer.top().getItem(27)) && viewer.cursor.equals(expectedSword), "ordinary left click picks up the actual staged item");
            check(viewer.items[0].equals(expectedSword), "native pickup leaves real administrator inventory unchanged");
            int messages = viewer.chat.size();
            click(49);
            later(() -> {
                check(viewer.menu(54) && viewer.chat.size() > messages, "saving with a held cursor item is refused");
                nativeClick(27, ClickType.LEFT, InventoryAction.PLACE_ALL);
                nativeClick(viewer.rawBottom(9), ClickType.RIGHT, InventoryAction.PICKUP_HALF);
                check(viewer.cursor.getAmount() == 2 && viewer.bottom.getItem(9).getAmount() == 2,
                        "right click splits a staged administrator stack");
                nativeDrag(Map.of(0, new ItemStack(Material.STONE), viewer.rawBottom(10), new ItemStack(Material.STONE)), null, false);
                check(viewer.top().getItem(0).getAmount() == 1 && viewer.bottom.getItem(10).getAmount() == 1 && empty(viewer.cursor),
                        "valid drag spans target and virtual administrator slots");
                check(viewer.items[9].getAmount() == 4 && empty(viewer.items[10]), "drag does not write the real administrator inventory");
                nativeClick(27, ClickType.LEFT, InventoryAction.PICKUP_ALL);
                viewer.close();
                later(() -> readDisk(disk -> {
                    check(empty(viewer.cursor), "closing cancels and clears the staged cursor");
                    check(viewer.items[0].equals(expectedSword) && viewer.items[9].getAmount() == 4 && empty(viewer.items[10]),
                            "cancel restores the exact administrator baseline");
                    check(Arrays.equals(initial.compressed(), disk.compressed()) && initial.backups().keySet().equals(disk.backups().keySet()),
                            "native move cancellation leaves target bytes and backups unchanged");
                    log("native left/right/drag staging, unchanged item metadata, cursor-save refusal and cancel isolation");
                    command("inventory");
                    await("reopen backpack for transfer", () -> viewer.menu(54), this::editInventory);
                }));
            });
        }

        void editInventory() {
            check(viewer.top().getItem(27).equals(expectedSword), "reopen shows original target metadata");
            shift(viewer.rawBottom(0));
            check(viewer.top().getItem(27).getAmount() == 2 && viewer.top().getItem(27).isSimilar(expectedSword),
                    "shift merges the administrator sword into the target stack");
            check(empty(viewer.bottom.getItem(0)) && viewer.items[0].equals(expectedSword),
                    "shift consumes only the administrator staged copy before save");
            click(49);
            await("commit offline backpack", () -> !viewer.active, () -> readDisk(disk -> {
                afterInventory = disk;
                ItemStack saved = deserialize(item(disk.snapshot().root(), "Inventory", 0));
                check(saved.getAmount() == 2 && saved.isSimilar(expectedSword), "saved transfer retains full item metadata and PDC");
                check(empty(viewer.items[0]) && viewer.items[9].getAmount() == 4, "save commits administrator transfer and preserves other slots");
                check(equal(initial.snapshot().root().get("EnderItems"), disk.snapshot().root().get("EnderItems")), "backpack save preserves raw ender items");
                assertOtherData(disk);
                assertBackup(initial, disk);
                log("offline backpack shift transfer, administrator commit, PDC, backup and unrelated NBT");
                editEnder();
            }));
        }

        void editEnder() {
            command("ender");
            await("open offline ender chest", () -> viewer.menu(36), () -> {
                check(viewer.top().getItem(7).equals(expectedSword), "ender slot 7 contains unchanged metadata");
                shift(7);
                adminSwordSlot = swordSlot(viewer.bottom.getContents());
                check(empty(viewer.top().getItem(7)) && viewer.bottom.getItem(adminSwordSlot).equals(expectedSword), "shift takes ender item into administrator staging");
                check(empty(viewer.items[0]), "ender pickup does not touch administrator storage until save");
                click(31);
                await("commit offline ender chest", () -> !viewer.active, () -> readDisk(disk -> {
                    afterEnder = disk;
                    check(disk.snapshot().root().get("EnderItems") instanceof List<?> items && items.isEmpty(), "ender transfer removes the saved item");
                    check(viewer.items[adminSwordSlot].equals(expectedSword), "ender save gives administrator the transferred original item");
                    check(equal(afterInventory.snapshot().root().get("Inventory"), disk.snapshot().root().get("Inventory")), "ender save preserves raw backpack items");
                    check(deserialize(item(disk.snapshot().root(), "Inventory", 0)).getAmount() == 2, "backpack remains amount 2");
                    assertOtherData(disk);
                    assertBackup(afterInventory, disk);
                    log("offline ender direct transfer, separate storage and exact pre-save backup");
                    cancelUnsaved();
                }));
            });
        }

        void cancelUnsaved() {
            command("inventory");
            await("reopen backpack", () -> viewer.menu(54), () -> {
                nativeClick(27, ClickType.LEFT, InventoryAction.PICKUP_ALL);
                check(viewer.cursor.getAmount() == 2, "unsaved pickup takes the complete target stack");
                click(53);
                await("explicit cancel closes transfer menu", () -> !viewer.active, () -> readDisk(disk -> {
                    check(empty(viewer.cursor) && viewer.items[adminSwordSlot].equals(expectedSword), "cancel button clears cursor and keeps administrator baseline");
                    check(Arrays.equals(afterEnder.compressed(), disk.compressed()), "cancelled transfer preserves exact player file bytes");
                    check(disk.backups().keySet().equals(afterEnder.backups().keySet()), "cancelled transfer creates no backup");
                    log("cancel button discards unsaved transfer and cursor");
                    dirtyAdministratorRefused();
                }));
            });
        }

        void dirtyAdministratorRefused() {
            command("inventory");
            await("open before administrator inventory conflict", () -> viewer.menu(54), () -> {
                shift(viewer.rawBottom(adminSwordSlot));
                check(viewer.top().getItem(27).getAmount() == 3, "conflict test stages a genuine transfer");
                viewer.items[adminSwordSlot] = new ItemStack(Material.DIAMOND);
                click(49);
                later(() -> readDisk(disk -> {
                    check(!viewer.active && viewer.items[adminSwordSlot].getType() == Material.DIAMOND,
                            "administrator baseline conflict rejects save and retains live change");
                    check(Arrays.equals(afterEnder.compressed(), disk.compressed()) && disk.backups().keySet().equals(afterEnder.backups().keySet()),
                            "administrator conflict cannot save target or create a backup");
                    viewer.items[adminSwordSlot] = expectedSword.clone();
                    log("administrator baseline conflict rejects the complete transfer");
                    joinedTargetCancels();
                }));
            });
        }

        void joinedTargetCancels() throws Exception {
            command("inventory");
            await("open before target joins", () -> viewer.menu(54), () -> {
                ((PlayerInventoryEditor) field(plugin, "inventoryEditor")).targetJoined(offline);
                await("target join closes offline edit", () -> !viewer.active, () -> readDisk(disk -> {
                    check(Arrays.equals(afterEnder.compressed(), disk.compressed()), "target join cancellation leaves disk unchanged");
                    log("target joining invalidates the offline edit");
                    deniedCommand();
                }));
            });
        }

        void deniedCommand() {
            int opens = viewer.opens;
            int messages = viewer.chat.size();
            viewer.editAllowed = false;
            command("inventory");
            later(() -> {
                check(!viewer.active && viewer.opens == opens, "edit permission denies command GUI");
                check(viewer.chat.size() > messages, "denied command reports permission failure");
                viewer.editAllowed = true;
                preparingSessions();
                bundleSessions();
            });
        }

        void preparingSessions() {
            final class Delayed implements PlayerInventoryEditor.Backend {
                Consumer<PlayerInventoryEditor.Loaded> pending;
                @Override public void load(Player player, UUID target, PlayerScanner.Storage storage,
                                           Consumer<PlayerInventoryEditor.Loaded> success, Consumer<String> failure) {
                    pending = success;
                }
                @Override public void save(Player player, PlayerInventoryEditor.Loaded original, ItemStack[] proposed,
                                           Consumer<String> success, Consumer<String> failure) {
                    throw new IllegalStateException("preparation probe must never save");
                }
            }
            Viewer waiting = new Viewer("ProbePreparingAdmin", UUID.randomUUID());
            Delayed backend = new Delayed();
            HandlerList.unregisterAll(commandEditor);
            try (PlayerInventoryEditor editor = new PlayerInventoryEditor(plugin, backend, waiting::attach)) {
                UUID target = UUID.randomUUID();
                editor.open(waiting.player, target, PlayerScanner.Storage.INVENTORY);
                check(backend.pending != null && waiting.opens == 0, "loading holds no inventory GUI");
                editor.targetJoined(target);
                backend.pending.accept(new PlayerInventoryEditor.Loaded(target, "ProbePreparingTarget", false,
                        PlayerScanner.Storage.INVENTORY, new ItemStack[41], null));
                check(waiting.opens == 0 && !waiting.active, "offline target joining while preparing discards delayed snapshot");

                Viewer quittingTarget = new Viewer("ProbeQuittingTarget", target);
                editor.open(waiting.player, target, PlayerScanner.Storage.INVENTORY);
                Bukkit.getPluginManager().callEvent(new PlayerQuitEvent(quittingTarget.player, Component.empty()));
                backend.pending.accept(new PlayerInventoryEditor.Loaded(target, quittingTarget.name, true,
                        PlayerScanner.Storage.INVENTORY, new ItemStack[41], null));
                check(waiting.opens == 0 && !waiting.active, "online target quitting while preparing cannot reopen a stale GUI");

                target = UUID.randomUUID();
                editor.open(waiting.player, target, PlayerScanner.Storage.INVENTORY);
                waiting.online = false;
                Bukkit.getPluginManager().callEvent(new PlayerQuitEvent(waiting.player, Component.empty()));
                backend.pending.accept(new PlayerInventoryEditor.Loaded(target, "ProbeCancelledLoad", false,
                        PlayerScanner.Storage.INVENTORY, new ItemStack[41], null));
                check(waiting.opens == 0 && !waiting.active, "administrator quit cancels an in-flight load");
                waiting.online = true;

                target = UUID.randomUUID();
                editor.open(waiting.player, target, PlayerScanner.Storage.INVENTORY);
                waiting.cursor = new ItemStack(Material.STONE);
                backend.pending.accept(new PlayerInventoryEditor.Loaded(target, "ProbeCursorLoad", false,
                        PlayerScanner.Storage.INVENTORY, new ItemStack[41], null));
                check(waiting.opens == 0 && waiting.cursor.equals(new ItemStack(Material.STONE)),
                        "cursor acquired during loading prevents opening without discarding the real item");
                waiting.cursor = null;

                target = UUID.randomUUID();
                editor.open(waiting.player, target, PlayerScanner.Storage.INVENTORY);
                ItemStack[] deathFixture = new ItemStack[41];
                deathFixture[0] = expectedSword.clone();
                backend.pending.accept(new PlayerInventoryEditor.Loaded(target, "ProbeLockedTarget", false,
                        PlayerScanner.Storage.INVENTORY, deathFixture, null));
                check(waiting.menu(54), "released preparation sessions permit a fresh editor");
                Viewer competing = new Viewer("ProbeCompetingAdmin", UUID.randomUUID());
                editor.open(competing.player, target, PlayerScanner.Storage.INVENTORY);
                check(!competing.active && competing.opens == 0 && !competing.chat.isEmpty(), "one target has only one administrator editor");
                var pickup = new InventoryClickEvent(waiting.view, InventoryType.SlotType.CONTAINER, 27,
                        ClickType.LEFT, InventoryAction.PICKUP_ALL);
                Bukkit.getPluginManager().callEvent(pickup);
                check(!pickup.isCancelled(), "death probe permits native staged pickup");
                waiting.cursor = copy(waiting.top().getItem(27));
                waiting.top().setItem(27, null);
                ItemStack[] baseline = copies(waiting.items);
                Bukkit.getPluginManager().callEvent(new PlayerDeathEvent(waiting.player,
                        org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(),
                        new ArrayList<>(), 0, Component.empty(), false));
                check(!waiting.active && empty(waiting.cursor) && Arrays.equals(baseline, copies(waiting.items)),
                        "administrator death closes the editor and clears staged cursor without changing real storage");
                editor.open(waiting.player, waiting.uuid, PlayerScanner.Storage.ENDER_CHEST);
                ItemStack[] ownEnder = new ItemStack[27];
                ownEnder[3] = expectedSword.clone();
                waiting.ender.setItem(3, expectedSword.clone());
                backend.pending.accept(new PlayerInventoryEditor.Loaded(waiting.uuid, waiting.name, true,
                        PlayerScanner.Storage.ENDER_CHEST, ownEnder, null));
                check(waiting.menu(36), "own ender chest may be opened for transfers");
                var ownPickup = new InventoryClickEvent(waiting.view, InventoryType.SlotType.CONTAINER, 3,
                        ClickType.LEFT, InventoryAction.PICKUP_ALL);
                Bukkit.getPluginManager().callEvent(ownPickup);
                check(!ownPickup.isCancelled(), "own ender allows staged pickup");
                waiting.cursor = copy(waiting.top().getItem(3));
                waiting.top().setItem(3, null);
                waiting.online = false;
                Bukkit.getPluginManager().callEvent(new PlayerQuitEvent(waiting.player, Component.empty()));
                check(empty(waiting.cursor) && waiting.ender.getItem(3).equals(expectedSword)
                                && Arrays.equals(baseline, copies(waiting.items)),
                        "own ender quit clears staged carried even after isOnline becomes false");
                waiting.online = true;
                waiting.close();

            } finally {
                plugin.getServer().getPluginManager().registerEvents(commandEditor, plugin);
            }
            check(!waiting.active && empty(waiting.cursor), "closing editor releases its staged window and cursor");
            log("delayed load join/quit/cursor cancellation, per-target lock and administrator death cleanup");
        }

        void cancelOfflinePreparation() {
            readDisk(before -> {
                ItemStack[] administrator = copies(viewer.items);
                stage = "load real offline snapshot before cancelled asynchronous save";
                commandAccess.load(viewer.player, offline, PlayerScanner.Storage.INVENTORY, loaded -> guard(() -> {
                    check(!loaded.online(), "asynchronous guard probe uses actual offline Access backend");
                    ItemStack[] proposed = loaded.items();
                    check(proposed[0].getAmount() == 2, "cancelled save begins from final backpack fixture");
                    proposed[0].setAmount(3);
                    AtomicBoolean allowed = new AtomicBoolean(true);
                    AtomicBoolean finalGuardSeen = new AtomicBoolean(false);
                    AtomicReference<String> succeeded = new AtomicReference<>();
                    AtomicReference<String> rejected = new AtomicReference<>();
                    commandAccess.saveTransfer(viewer.player, loaded, proposed, () -> {
                        check(Bukkit.isPrimaryThread(), "offline final commit guard executes on the main thread");
                        if (!allowed.get()) finalGuardSeen.set(true);
                        return allowed.get();
                    }, succeeded::set, rejected::set);
                    check(succeeded.get() == null && rejected.get() == null, "offline save reaches asynchronous preparation before cancellation");
                    allowed.set(false);
                    await("offline prepared save rejects revoked final guard", () -> rejected.get() != null || succeeded.get() != null,
                            () -> {
                                check(succeeded.get() == null && rejected.get() != null && finalGuardSeen.get(),
                                        "prepared save rejects the revoked final guard instead of reporting success");
                                check(rejected.get().contains("会话") && rejected.get().contains("取消"),
                                        "cancellation failure originates from the final commit guard: " + rejected.get());
                                readDisk(after -> {
                                    check(Arrays.equals(before.compressed(), after.compressed()), "revoked final guard preserves exact target player file bytes");
                                    check(Arrays.equals(administrator, copies(viewer.items)), "revoked final guard preserves real administrator inventory baseline");
                                    check(after.temporaries().equals(before.temporaries()), "cancelled asynchronous preparation cleans its temporary replacement");
                                    assertBackup(before, after);
                                    log("real offline asynchronous preparation, main-thread final guard, unchanged inventories and temporary cleanup");
                                    onlineBackend();
                                });
                            });
                }), reason -> fail(new IllegalStateException("Cannot load offline guard fixture: " + reason)));
            });
        }

        void bundleSessions() {
            ItemStack bundle = new ItemStack(Material.BUNDLE);
            BundleMeta meta = (BundleMeta) bundle.getItemMeta();
            meta.setItems(List.of(expectedSword.clone(), new ItemStack(Material.DIAMOND, 3)));
            meta.displayName(Component.text("Probe intact bundle"));
            meta.lore(List.of(Component.text("Preserve contained item metadata")));
            meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "probe_bundle"),
                    org.bukkit.persistence.PersistentDataType.STRING, "keep-bundle-custom-data");
            bundle.setItemMeta(meta);
            ItemStack originalBundle = bundle.clone();
            UUID target = UUID.randomUUID();
            final class Recorder implements PlayerInventoryEditor.Backend {
                ItemStack[] stored = new ItemStack[41];
                int saves;
                Recorder() { stored[0] = originalBundle.clone(); }
                @Override public void load(Player player, UUID requested, PlayerScanner.Storage storage,
                                           Consumer<PlayerInventoryEditor.Loaded> success, Consumer<String> failure) {
                    check(requested.equals(target) && storage == PlayerScanner.Storage.INVENTORY, "bundle recorder receives intended target");
                    success.accept(new PlayerInventoryEditor.Loaded(target, "ProbeBundleTarget", true, storage, stored, this));
                }
                @Override public void save(Player player, PlayerInventoryEditor.Loaded original, ItemStack[] proposed,
                                           Consumer<String> success, Consumer<String> failure) {
                    check(Bukkit.isPrimaryThread(), "bundle recorder commits on the main thread");
                    stored = copies(proposed);
                    saves++;
                    success.accept("Bundle transfer saved");
                }
            }
            Recorder recorder = new Recorder();
            Viewer admin = new Viewer("ProbeBundleAdmin", UUID.randomUUID());
            admin.items[9] = new ItemStack(Material.STONE, 2);
            ItemStack[] realBaseline = copies(admin.items);
            HandlerList.unregisterAll(commandEditor);
            PlayerInventoryEditor editor = new PlayerInventoryEditor(plugin, recorder, admin::attach);
            auxiliaryEditors.add(editor);
            editor.open(admin.player, target, PlayerScanner.Storage.INVENTORY);
            check(admin.menu(54) && admin.top().getItem(27).equals(originalBundle), "bundle GUI starts with exact contents and custom metadata");

            manualClick(admin, 27, ClickType.RIGHT, InventoryAction.PICKUP_HALF);
            check(empty(admin.cursor) && admin.top().getItem(27).equals(originalBundle), "right click cannot extract or select bundle contents");
            manualClick(admin, 27, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            check(admin.cursor.equals(originalBundle) && empty(admin.top().getItem(27)), "left pickup moves the intact bundle to cursor");
            manualClick(admin, admin.rawBottom(9), ClickType.RIGHT, InventoryAction.PLACE_ONE);
            check(admin.cursor.equals(originalBundle) && admin.bottom.getItem(9).equals(new ItemStack(Material.STONE, 2)),
                    "right click cannot insert an occupied slot into a carried bundle");
            manualClick(admin, admin.rawBottom(9), ClickType.LEFT, InventoryAction.SWAP_WITH_CURSOR);
            check(admin.bottom.getItem(9).equals(originalBundle) && admin.cursor.equals(new ItemStack(Material.STONE, 2)),
                    "left click swaps whole bundle and whole ordinary stack");
            manualClick(admin, admin.rawBottom(9), ClickType.RIGHT, InventoryAction.PLACE_ONE);
            check(admin.bottom.getItem(9).equals(originalBundle) && admin.cursor.equals(new ItemStack(Material.STONE, 2)),
                    "right click cannot recombine a carried ordinary stack into a slotted bundle");
            manualClick(admin, admin.rawBottom(9), ClickType.LEFT, InventoryAction.SWAP_WITH_CURSOR);
            manualClick(admin, admin.rawBottom(0), ClickType.LEFT, InventoryAction.PLACE_ALL);
            check(empty(admin.cursor) && admin.bottom.getItem(0).equals(originalBundle), "left place retains embedded items in an empty staged slot");
            manualClick(admin, admin.rawBottom(0), ClickType.LEFT, InventoryAction.PICKUP_ALL);
            manualClick(admin, 27, ClickType.LEFT, InventoryAction.PLACE_ALL);
            check(empty(admin.cursor) && admin.top().getItem(27).equals(originalBundle), "returning a bundle to target preserves its entire metadata");
            manualClick(admin, 27, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            check(empty(admin.top().getItem(27)), "shift transfers the whole bundle from target");
            int received = -1;
            for (int index = 0; index < 36; index++) if (originalBundle.equals(admin.bottom.getItem(index))) received = index;
            check(received >= 0 && recorder.stored[0].equals(originalBundle) && recorder.saves == 0
                            && Arrays.equals(realBaseline, copies(admin.items)),
                    "bundle pickup, swaps and shift change only staged inventories before save");
            int receivedSlot = received;
            manualClick(admin, 49, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            await("save intact bundle transfer", () -> !admin.active, () -> {
                check(recorder.saves == 1 && empty(recorder.stored[0]), "bundle conservation accepts a whole-item transfer and commits target removal");
                check(admin.items[receivedSlot].equals(originalBundle)
                                && admin.items[9].equals(new ItemStack(Material.STONE, 2)) && empty(admin.cursor),
                        "save gives administrator the unchanged bundle and preserves the swapped ordinary stack");
                BundleMeta savedMeta = (BundleMeta) admin.items[receivedSlot].getItemMeta();
                check(savedMeta.getItems().equals(((BundleMeta) originalBundle.getItemMeta()).getItems()),
                        "embedded custom sword and diamond stack survive intact bundle transfer");
                editor.close();
                auxiliaryEditors.remove(editor);
                plugin.getServer().getPluginManager().registerEvents(commandEditor, plugin);
                log("bundle left pickup/place/swap, blocked inner-content mutations, whole-item shift and conserved save");
                redirectedGui();
                cancelOfflinePreparation();
            });
        }

        void redirectedGui() {
            Viewer admin = new Viewer("ProbeRedirectedAdmin", UUID.randomUUID());
            admin.items[0] = new ItemStack(Material.EMERALD, 2);
            ItemStack[] realBaseline = copies(admin.items);
            Inventory replacement = Bukkit.createInventory(null, 54, Component.text("Probe other plugin window"));
            admin.redirectTop = replacement;
            AtomicBoolean attached = new AtomicBoolean(false);
            PlayerInventoryEditor.Backend backend = new PlayerInventoryEditor.Backend() {
                @Override public void load(Player player, UUID target, PlayerScanner.Storage storage,
                                           Consumer<PlayerInventoryEditor.Loaded> success, Consumer<String> failure) {
                    success.accept(new PlayerInventoryEditor.Loaded(target, "ProbeRedirectedTarget", true,
                            storage, new ItemStack[41], null));
                }
                @Override public void save(Player player, PlayerInventoryEditor.Loaded original, ItemStack[] proposed,
                                           Consumer<String> success, Consumer<String> failure) {
                    throw new IllegalStateException("redirected GUI must never save");
                }
            };
            try (PlayerInventoryEditor editor = new PlayerInventoryEditor(plugin, backend, (player, size, items) -> {
                attached.set(true);
                throw new IllegalStateException("redirected GUI must never attach temporary storage");
            })) {
                editor.open(admin.player, UUID.randomUUID(), PlayerScanner.Storage.INVENTORY);
                check(admin.top() == replacement && replacement.getSize() == 54 && !attached.get(),
                        "same-size replacement window is rejected by identity before temporary inventory attachment");
                check(Arrays.equals(realBaseline, copies(admin.items)) && empty(admin.cursor),
                        "redirected editor leaves real administrator storage and cursor intact");
            }
            admin.close();
            log("same-size GUI redirection rejects attachment before touching administrator inventory");
        }

        void manualClick(Viewer admin, int raw, ClickType type, InventoryAction action) {
            var event = new InventoryClickEvent(admin.view, InventoryType.SlotType.CONTAINER, raw, type, action);
            Bukkit.getPluginManager().callEvent(event);
            check(event.isCancelled(), "manual bundle/control click is cancelled before native handling: " + type + " raw=" + raw);
        }

        void onlineBackend() throws Exception {
            Viewer target = new Viewer("ProbeOnlineTarget", UUID.randomUUID());
            target.items[0] = expectedSword.clone();
            target.items[5] = new ItemStack(Material.STONE);
            target.items[36] = new ItemStack(Material.DIAMOND_BOOTS);
            target.items[40] = new ItemStack(Material.SHIELD);
            var constructor = PlayerInventoryAccess.class.getDeclaredConstructor(RegionItemSearchPlugin.class, Function.class);
            constructor.setAccessible(true);
            injected = constructor.newInstance(plugin, (Function<UUID, Player>) id -> id.equals(target.uuid) ? target.player : null);
            var loaded = load(target, PlayerScanner.Storage.INVENTORY);
            check(loaded.online() && loaded.items().length == 41, "live backpack snapshot includes armor and offhand");
            ItemStack[] proposed = loaded.items();
            proposed[0].setAmount(2);
            target.items[5] = new ItemStack(Material.EMERALD, 3);
            save(loaded, proposed, true);
            check(target.items[0].getAmount() == 2 && target.items[0].isSimilar(expectedSword), "online dirty slot receives staged item");
            check(target.items[5].equals(new ItemStack(Material.EMERALD, 3)), "online save preserves unrelated live slot changes");
            check(target.items[36].getType() == Material.DIAMOND_BOOTS && target.items[40].getType() == Material.SHIELD, "online save retains armor and offhand");

            loaded = load(target, PlayerScanner.Storage.INVENTORY);
            proposed = loaded.items();
            proposed[0].setAmount(3);
            target.items[0].setAmount(4);
            save(loaded, proposed, false);
            check(target.items[0].getAmount() == 4 && target.items[5].getAmount() == 3, "dirty-slot conflict rejects entire online commit");

            target.ender.setItem(7, expectedSword.clone());
            var ender = load(target, PlayerScanner.Storage.ENDER_CHEST);
            ItemStack[] enderProposed = ender.items();
            enderProposed[7].setAmount(2);
            target.ender.setItem(6, new ItemStack(Material.DIAMOND, 4));
            save(ender, enderProposed, true);
            check(target.ender.getItem(7).getAmount() == 2 && target.ender.getItem(6).getAmount() == 4, "live ender save also preserves unrelated slots");

            viewer.editAllowed = false;
            AtomicReference<String> denied = new AtomicReference<>();
            injected.load(viewer.player, target.uuid, PlayerScanner.Storage.INVENTORY,
                    ignored -> { throw new IllegalStateException("unauthorized backend load succeeded"); }, denied::set);
            check(denied.get() != null, "backend load enforces edit permission");
            save(loaded, proposed, false);
            check(target.items[0].getAmount() == 4, "permission denial leaves live inventory unchanged");
            viewer.editAllowed = true;
            log("online inventory/ender dirty-slot merge, armor/offhand, conflicts and permission checks");
            loginGate();
        }

        void loginGate() throws Exception {
            UUID gateId = UUID.randomUUID();
            Connection first = new Connection(gateId, "ProbeLogin");
            Connection second = new Connection(gateId, "ProbeRetry");
            Object lock = field(injected, "loginLock");
            @SuppressWarnings("unchecked") Set<UUID> saving = (Set<UUID>) field(injected, "saving");
            synchronized (lock) { saving.add(gateId); }
            async(() -> {
                try {
                    var event = first.preLogin();
                    // Deliver real events to these registered handlers without a fabricated network connection.
                    injected.preLogin(event);
                    check(event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "pending commit rejects pre-login");
                    event.allow();
                    injected.finalPreLogin(event);
                    check(event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "highest-priority pre-login rechecks save gate");
                    return gateId;
                } finally { synchronized (lock) { saving.remove(gateId); } }
            }, id -> {
                Viewer gateTarget = new Viewer("ProbeLoginTarget", id);
                synchronized (lock) { saving.add(id); }
                try {
                    var login = new PlayerLoginEvent(gateTarget.player, "localhost", InetAddress.getLoopbackAddress());
                    injected.login(login);
                    check(login.getResult() == PlayerLoginEvent.Result.KICK_OTHER, "main-thread login rejects pending commit");
                    var configuration = new PlayerConnectionValidateLoginEvent(first.configuration, null);
                    injected.validateConnection(configuration);
                    check(!configuration.isAllowed() && configuration.getKickMessage() != null,
                            "final configuration validation rejects pending commit");
                } finally { synchronized (lock) { saving.remove(id); } }

                injected.preLogin(first.preLogin());
                var configuration = new PlayerConnectionValidateLoginEvent(first.configuration, null);
                injected.validateConnection(configuration);
                check(configuration.isAllowed(), "configuration can continue when there is no save");
                var retry = second.preLogin();
                retry.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("Probe rejected duplicate attempt"));
                injected.preLogin(retry);
                check(retry.getLoginResult() == AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "rejected retry stays rejected");
                @SuppressWarnings("unchecked") Map<UUID, Set<PlayerConnection>> pending =
                        (Map<UUID, Set<PlayerConnection>>) field(injected, "pendingConnections");
                @SuppressWarnings("unchecked") Map<UUID, Long> legacy = (Map<UUID, Long>) field(injected, "loggingIn");
                synchronized (lock) {
                    check(pending.get(id).contains(first.login) && pending.get(id).contains(second.login)
                                    && pending.get(id).contains(first.configuration),
                            "each pending connection survives another rejected attempt for the same UUID");
                    legacy.put(id, System.nanoTime() - TimeUnit.MINUTES.toNanos(4));
                }
                second.connected.set(false);
                AtomicReference<String> refused = new AtomicReference<>();
                injected.load(viewer.player, id, PlayerScanner.Storage.INVENTORY,
                        ignored -> { throw new IllegalStateException("pending login accepted offline load"); }, refused::set);
                check(refused.get() != null && refused.get().contains("上线"), "pending login prevents offline snapshot read");
                synchronized (lock) {
                    check(!legacy.containsKey(id) && pending.get(id).contains(first.login)
                                    && pending.get(id).contains(first.configuration) && !pending.get(id).contains(second.login),
                            "alive login/configuration remains blocked beyond legacy timeout; disconnected retry is pruned");
                }
                first.connected.set(false);
                Method isLoggingIn = PlayerInventoryAccess.class.getDeclaredMethod("isLoggingIn", UUID.class);
                isLoggingIn.setAccessible(true);
                check(Boolean.FALSE.equals(isLoggingIn.invoke(injected, id)), "all disconnected attempts release the UUID gate");
                synchronized (lock) { check(!pending.containsKey(id), "disconnected connection set is removed"); }
                log("pre-login/login/configuration commit gates, multiple attempts, live connections beyond timeout and disconnected cleanup");
                finish();
            });
        }

        PlayerInventoryEditor.Loaded load(Viewer target, PlayerScanner.Storage storage) {
            AtomicReference<PlayerInventoryEditor.Loaded> loaded = new AtomicReference<>();
            AtomicReference<String> failed = new AtomicReference<>();
            injected.load(viewer.player, target.uuid, storage, loaded::set, failed::set);
            check(failed.get() == null && loaded.get() != null, "online load callback: " + failed.get());
            return loaded.get();
        }

        void save(PlayerInventoryEditor.Loaded loaded, ItemStack[] proposed, boolean expectedSuccess) {
            AtomicReference<String> succeeded = new AtomicReference<>();
            AtomicReference<String> failed = new AtomicReference<>();
            injected.save(viewer.player, loaded, proposed, succeeded::set, failed::set);
            check(expectedSuccess ? succeeded.get() != null && failed.get() == null : failed.get() != null && succeeded.get() == null,
                    "expected online save " + expectedSuccess + ", success=" + succeeded.get() + ", failure=" + failed.get());
        }

        void blockedMovement() {
            InventoryView view = viewer.view;
            for (ClickType type : ClickType.values()) {
                // The help control is not a source slot, so accepted left/right callbacks do not edit it.
                var event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 45, type, InventoryAction.NOTHING, 2);
                Bukkit.getPluginManager().callEvent(event);
                check(event.isCancelled(), "editor protects control from click type " + type);
            }
            for (var specification : List.of(
                    new Object[]{27, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP},
                    new Object[]{27, ClickType.DROP, InventoryAction.DROP_ONE_SLOT},
                    new Object[]{27, ClickType.CONTROL_DROP, InventoryAction.DROP_ALL_SLOT},
                    new Object[]{27, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR},
                    new Object[]{27, ClickType.MIDDLE, InventoryAction.CLONE_STACK},
                    new Object[]{27, ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP},
                    new Object[]{-999, ClickType.WINDOW_BORDER_LEFT, InventoryAction.NOTHING})) {
                var event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                        (int) specification[0], (ClickType) specification[1], (InventoryAction) specification[2], 2);
                Bukkit.getPluginManager().callEvent(event);
                check(event.isCancelled(), "editor blocks " + specification[1]);
            }
            var creative = new InventoryCreativeEvent(view, InventoryType.SlotType.CONTAINER, 27, new ItemStack(Material.DIAMOND));
            Bukkit.getPluginManager().callEvent(creative);
            check(creative.isCancelled(), "creative injection is cancelled");
            var drag = new InventoryDragEvent(view, new ItemStack(Material.DIAMOND), new ItemStack(Material.DIAMOND, 2),
                    false, Map.of(49, new ItemStack(Material.DIAMOND)));
            Bukkit.getPluginManager().callEvent(drag);
            check(drag.isCancelled(), "drag cannot overwrite save control");
            var mixedDrag = new InventoryDragEvent(view, new ItemStack(Material.DIAMOND), new ItemStack(Material.DIAMOND, 3),
                    true, Map.of(0, new ItemStack(Material.DIAMOND), 53, new ItemStack(Material.DIAMOND)));
            Bukkit.getPluginManager().callEvent(mixedDrag);
            check(mixedDrag.isCancelled(), "drag touching a control cancels the whole distribution");
            check(viewer.top().getItem(27).equals(expectedSword) && viewer.items[0].equals(expectedSword),
                    "prohibited events preserve target metadata and administrator storage");
        }

        void shift(int rawSlot) {
            var event = new InventoryClickEvent(viewer.view, InventoryType.SlotType.CONTAINER,
                    rawSlot, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            Bukkit.getPluginManager().callEvent(event);
            check(event.isCancelled(), "shift is cancelled and transferred by editor at raw slot " + rawSlot);
        }

        void nativeClick(int rawSlot, ClickType type, InventoryAction action) {
            check(type == ClickType.LEFT || type == ClickType.RIGHT, "probe only simulates permitted native click kinds");
            var event = new InventoryClickEvent(viewer.view, InventoryType.SlotType.CONTAINER, rawSlot, type, action);
            Bukkit.getPluginManager().callEvent(event);
            check(!event.isCancelled(), "ordinary " + type + " click reaches native menu handling at raw slot " + rawSlot);
            // Bukkit fires before vanilla mutates slots. Our fake network transport applies that result here.
            ItemStack slot = copy(viewer.view.getItem(rawSlot));
            ItemStack held = copy(viewer.cursor);
            if (empty(held)) {
                check(!empty(slot), "native pickup starts from an occupied slot");
                int count = type == ClickType.RIGHT ? (slot.getAmount() + 1) / 2 : slot.getAmount();
                held = slot.clone();
                held.setAmount(count);
                slot.setAmount(slot.getAmount() - count);
            } else if (empty(slot)) {
                slot = held.clone();
                int count = type == ClickType.RIGHT ? 1 : held.getAmount();
                slot.setAmount(count);
                held.setAmount(held.getAmount() - count);
            } else if (slot.isSimilar(held)) {
                int count = Math.min(slot.getMaxStackSize() - slot.getAmount(), type == ClickType.RIGHT ? 1 : held.getAmount());
                slot.setAmount(slot.getAmount() + count);
                held.setAmount(held.getAmount() - count);
            } else {
                ItemStack swap = slot;
                slot = held;
                held = swap;
            }
            viewer.view.setItem(rawSlot, empty(slot) ? null : slot);
            viewer.cursor = empty(held) ? null : held;
        }

        void nativeDrag(Map<Integer, ItemStack> changed, ItemStack newCursor, boolean right) {
            ItemStack oldCursor = copy(viewer.cursor);
            check(!empty(oldCursor), "native drag starts with a held item");
            var event = new InventoryDragEvent(viewer.view, copy(newCursor), oldCursor, right, changed);
            Bukkit.getPluginManager().callEvent(event);
            check(!event.isCancelled(), "drag limited to data slots reaches native menu handling");
            changed.forEach((raw, value) -> viewer.view.setItem(raw, copy(value)));
            viewer.cursor = copy(newCursor);
        }

        void click(int rawSlot) {
            var event = new InventoryClickEvent(viewer.view, InventoryType.SlotType.CONTAINER,
                    rawSlot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            Bukkit.getPluginManager().callEvent(event);
            check(event.isCancelled(), "button click " + rawSlot + " is cancelled");
        }

        void command(String storage) {
            check(plugin.onCommand(viewer.player, plugin.getCommand("itemsearch"), "itemsearch",
                    new String[]{"player", storage, offline.toString()}), "player edit command handled");
        }

        int swordSlot(ItemStack[] items) {
            for (int index = 0; index < items.length; index++) if (!empty(items[index]) && items[index].isSimilar(expectedSword)) return index;
            throw new IllegalStateException("transferred sword is absent from administrator staging");
        }

        void assertOtherData(Disk disk) {
            var before = initial.snapshot().root();
            var after = disk.snapshot().root();
            check(before.keySet().equals(after.keySet()), "save preserves root field names");
            for (String key : before.keySet()) if (!key.equals("Inventory") && !key.equals("EnderItems"))
                check(equal(before.get(key), after.get(key)), "save preserves unrelated NBT field " + key);
        }

        void assertBackup(Disk before, Disk after) {
            var added = new LinkedHashMap<>(after.backups());
            before.backups().keySet().forEach(added::remove);
            check(added.size() == 1 && Arrays.equals(added.values().iterator().next(), before.compressed()), "exact original gzip bytes backed up once");
        }

        void readDisk(Accept<Disk> callback) {
            async(() -> {
                var snapshot = new PlayerDataStore().load(directory, offline, MAX_BYTES, MAX_DEPTH);
                var backups = new LinkedHashMap<Path, byte[]>();
                if (Files.isDirectory(backupDirectory)) try (var files = Files.list(backupDirectory)) {
                    for (Path path : files.filter(p -> p.getFileName().toString().startsWith(offline + "-")
                            && p.getFileName().toString().endsWith(".dat")).toList()) backups.put(path, Files.readAllBytes(path));
                }
                Set<Path> temporaries;
                try (var files = Files.list(directory)) {
                    temporaries = Set.copyOf(files.filter(path -> path.getFileName().toString().startsWith("." + offline + "-itemsearch-")
                            && path.getFileName().toString().endsWith(".tmp")).toList());
                }
                return new Disk(snapshot, Files.readAllBytes(snapshot.file()), backups, temporaries);
            }, callback);
        }

        ItemStack deserialize(Map<String, Object> compound) throws Exception {
            check(Bukkit.isPrimaryThread(), "ItemStack decoding stays on the server thread");
            var normalized = new LinkedHashMap<>(compound);
            normalized.remove("Slot"); normalized.remove("slot"); normalized.put("DataVersion", dataVersion);
            var bytes = new ByteArrayOutputStream();
            try (var output = new GZIPOutputStream(bytes)) { output.write(NbtWriter.write(normalized, MAX_BYTES, MAX_DEPTH)); }
            return ItemStack.deserializeBytes(bytes.toByteArray());
        }

        void await(String description, BooleanSupplier ready, Checked continuation) {
            stage = description;
            int[] attempts = {0};
            BukkitTask[] timer = {null};
            timer[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (finished) { timer[0].cancel(); return; }
                guard(() -> {
                    if (ready.getAsBoolean()) { timer[0].cancel(); continuation.run(); }
                    else check(++attempts[0] < 300, "timeout at " + description + "; chat=" + viewer.chat);
                });
            }, 1, 1);
            tasks.add(timer[0]);
        }

        <T> void async(Read<T> reader, Accept<T> callback) {
            tasks.add(Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    T value = reader.get();
                    Bukkit.getScheduler().runTask(plugin, () -> guard(() -> callback.accept(value)));
                } catch (Exception error) { Bukkit.getScheduler().runTask(plugin, () -> fail(error)); }
            }));
        }

        void later(Checked continuation) {
            tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> guard(continuation), 3));
        }

        void guard(Checked action) {
            if (finished) return;
            try { action.run(); }
            catch (Exception error) { fail(error); }
        }

        void log(String description) { plugin.getLogger().info("PROBE PASS inventory editor: " + description); }

        void finish() {
            if (finished) return;
            finished = true;
            cleanup();
            done.run();
        }

        void fail(Exception error) {
            if (finished) return;
            finished = true;
            cleanup();
            failure.accept(error);
        }

        void cleanup() {
            tasks.forEach(BukkitTask::cancel);
            viewer.close();
            auxiliaryEditors.forEach(PlayerInventoryEditor::close);
            auxiliaryEditors.clear();
            if (originalEditor != null) {
                if (commandEditor != null) commandEditor.close();
                try {
                    setField(plugin, "inventoryEditor", originalEditor);
                    bindEditor(commandAccess, originalEditor);
                    plugin.getServer().getPluginManager().registerEvents(originalEditor, plugin);
                }
                catch (ReflectiveOperationException error) { plugin.getLogger().warning("Cannot restore probe editor: " + error); }
            }
            if (injected != null) { HandlerList.unregisterAll(injected); injected.close(); }
        }
    }

    /** Login and configuration connection identities share one transport liveness flag. */
    private static final class Connection {
        final AtomicBoolean connected = new AtomicBoolean(true);
        final com.destroystokyo.paper.profile.PlayerProfile profile;
        final PlayerLoginConnection login;
        final PlayerConfigurationConnection configuration;

        Connection(UUID uuid, String name) {
            profile = Bukkit.createProfile(uuid, name);
            login = (PlayerLoginConnection) Proxy.newProxyInstance(InventoryEditorProbe.class.getClassLoader(),
                    new Class<?>[]{PlayerLoginConnection.class}, this::call);
            configuration = (PlayerConfigurationConnection) Proxy.newProxyInstance(InventoryEditorProbe.class.getClassLoader(),
                    new Class<?>[]{PlayerConfigurationConnection.class}, this::call);
        }

        Object call(Object proxy, Method method, Object[] args) {
            Object identity = objectCall(proxy, method, args, "ProbeConnection(" + profile.getId() + ")");
            if (identity != NO_VALUE) return identity;
            return switch (method.getName()) {
                case "isConnected" -> connected.get();
                case "getProfile", "getAuthenticatedProfile", "getUnsafeProfile" -> profile;
                case "getAudience" -> net.kyori.adventure.audience.Audience.empty();
                case "getAddress", "getClientAddress", "getVirtualHost", "getHAProxyAddress" ->
                        new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
                case "disconnect" -> { connected.set(false); yield null; }
                default -> defaultValue(method.getReturnType());
            };
        }

        AsyncPlayerPreLoginEvent preLogin() {
            return new AsyncPlayerPreLoginEvent(profile.getName(), InetAddress.getLoopbackAddress(), InetAddress.getLoopbackAddress(),
                    profile.getId(), false, profile, "localhost", login);
        }
    }

    /** Actual server-created inventories and ItemStacks; proxies provide a connected player's transport. */
    private static final class Viewer {
        final UUID uuid;
        final String name;
        final ItemStack[] items = new ItemStack[41];
        final Inventory bottom = Bukkit.createInventory(null, 36, Component.text("Probe player inventory"));
        final Inventory ender = Bukkit.createInventory(null, 27, Component.text("Probe ender inventory"));
        final List<String> chat = new ArrayList<>();
        final Player player;
        final PlayerInventory inventory;
        InventoryView view;
        Inventory redirectTop;
        boolean active;
        boolean online = true;
        boolean editAllowed = true;
        int opens;
        ItemStack cursor;

        Viewer(String name, UUID uuid) {
            this.name = name;
            this.uuid = uuid;
            player = (Player) Proxy.newProxyInstance(InventoryEditorProbe.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, args) -> playerCall(proxy, method, args));
            inventory = (PlayerInventory) Proxy.newProxyInstance(InventoryEditorProbe.class.getClassLoader(), new Class<?>[]{PlayerInventory.class},
                    (proxy, method, args) -> inventoryCall(proxy, method, args));
            view = makeView(Bukkit.createInventory(null, 9, Component.text("Probe closed window")));
        }

        Object playerCall(Object proxy, Method method, Object[] args) {
            Object identity = objectCall(proxy, method, args, name);
            if (identity != NO_VALUE) return identity;
            return switch (method.getName()) {
                case "getUniqueId" -> uuid;
                case "getName", "getDisplayName", "getPlayerListName" -> name;
                case "isOnline", "isValid" -> online;
                case "isOp" -> true;
                case "getGameMode" -> org.bukkit.GameMode.SURVIVAL;
                case "hasPermission" -> !(args[0] instanceof String permission) || !permission.equals("itemsearch.edit") || editAllowed;
                case "getInventory" -> inventory;
                case "getEnderChest" -> ender;
                case "getOpenInventory" -> view;
                case "getServer" -> Bukkit.getServer();
                case "getWorld" -> Bukkit.getWorlds().getFirst();
                case "getLocation" -> Bukkit.getWorlds().getFirst().getSpawnLocation();
                case "getType" -> org.bukkit.entity.EntityType.PLAYER;
                case "getItemOnCursor" -> cursor;
                case "setItemOnCursor" -> { cursor = copy((ItemStack) args[0]); yield null; }
                case "openInventory" -> { if (args[0] instanceof Inventory top) open(top); else open((InventoryView) args[0]); yield method.getReturnType() == void.class ? null : view; }
                case "closeInventory" -> { close(); yield null; }
                case "sendMessage" -> { if (args != null) for (Object arg : args) chat.add(String.valueOf(arg)); yield null; }
                default -> defaultValue(method.getReturnType());
            };
        }

        Object inventoryCall(Object proxy, Method method, Object[] args) {
            Object identity = objectCall(proxy, method, args, name + "Inventory");
            if (identity != NO_VALUE) return identity;
            return switch (method.getName()) {
                case "getStorageContents" -> Arrays.copyOf(items, 36);
                case "getArmorContents" -> Arrays.copyOfRange(items, 36, 40);
                case "getContents" -> items.clone();
                case "getItemInMainHand", "getItemInHand" -> items[0];
                case "getItemInOffHand" -> items[40];
                case "setStorageContents" -> { System.arraycopy(copies((ItemStack[]) args[0]), 0, items, 0, 36); yield null; }
                case "setArmorContents" -> { System.arraycopy(copies((ItemStack[]) args[0]), 0, items, 36, 4); yield null; }
                case "setItemInOffHand" -> { items[40] = (ItemStack) args[0]; yield null; }
                case "setItemInMainHand", "setItemInHand" -> { items[0] = (ItemStack) args[0]; yield null; }
                case "getItem" -> args[0] instanceof Integer index ? items[index] : null;
                case "setItem" -> { if (args[0] instanceof Integer index) items[index] = (ItemStack) args[1]; yield null; }
                case "getHeldItemSlot" -> 0;
                case "getSize" -> 41;
                case "getMaxStackSize" -> 64;
                case "getType" -> InventoryType.PLAYER;
                case "getHolder" -> player;
                case "getViewers" -> List.of(player);
                case "iterator" -> Arrays.asList(items).listIterator(args == null || args.length == 0 ? 0 : (int) args[0]);
                default -> defaultValue(method.getReturnType());
            };
        }

        InventoryView makeView(Inventory top) {
            return (InventoryView) Proxy.newProxyInstance(InventoryEditorProbe.class.getClassLoader(), new Class<?>[]{InventoryView.class},
                    (proxy, method, args) -> {
                        Object identity = objectCall(proxy, method, args, "ProbeView(" + top.getSize() + ")");
                        if (identity != NO_VALUE) return identity;
                        return switch (method.getName()) {
                            case "getTopInventory" -> top;
                            case "getBottomInventory" -> bottom;
                            case "getPlayer" -> player;
                            case "getType" -> top.getType();
                            case "getTitle", "getOriginalTitle" -> "Probe inventory";
                            case "title" -> Component.text("Probe inventory");
                            case "getCursor" -> cursor;
                            case "setCursor" -> { cursor = copy((ItemStack) args[0]); yield null; }
                            case "countSlots" -> top.getSize() + bottom.getSize();
                            case "convertSlot" -> (int) args[0] < top.getSize() ? args[0] : bottomIndex((int) args[0], top.getSize());
                            case "getInventory" -> (int) args[0] < 0 || (int) args[0] >= top.getSize() + 36 ? null : (int) args[0] < top.getSize() ? top : bottom;
                            case "getSlotType" -> (int) args[0] < 0 ? InventoryType.SlotType.OUTSIDE : InventoryType.SlotType.CONTAINER;
                            case "getItem" -> (int) args[0] < 0 ? null : (int) args[0] < top.getSize() ? top.getItem((int) args[0]) : bottom.getItem(bottomIndex((int) args[0], top.getSize()));
                            case "setItem" -> { int raw = (int) args[0]; if (raw >= 0) { if (raw < top.getSize()) top.setItem(raw, copy((ItemStack) args[1])); else bottom.setItem(bottomIndex(raw, top.getSize()), copy((ItemStack) args[1])); } yield null; }
                            case "close" -> { close(); yield null; }
                            default -> defaultValue(method.getReturnType());
                        };
                    });
        }

        void open(Inventory top) {
            if (redirectTop != null) {
                Inventory redirected = redirectTop;
                redirectTop = null;
                open(makeView(redirected));
                return;
            }
            open(makeView(top));
        }

        void open(InventoryView next) {
            InventoryView previous = view;
            boolean wasActive = active;
            view = next;
            active = true;
            opens++;
            if (wasActive) Bukkit.getPluginManager().callEvent(new InventoryCloseEvent(previous));
        }

        void close() {
            if (!active) return;
            InventoryView previous = view;
            active = false;
            view = makeView(Bukkit.createInventory(null, 9, Component.text("Probe closed window")));
            Bukkit.getPluginManager().callEvent(new InventoryCloseEvent(previous));
        }

        Inventory top() { return view.getTopInventory(); }
        boolean menu(int size) { return active && top().getSize() == size && top().getHolder() != null; }

        int rawBottom(int adminIndex) {
            check(adminIndex >= 0 && adminIndex < 36, "administrator storage index is in range");
            return top().getSize() + (adminIndex < 9 ? adminIndex + 27 : adminIndex - 9);
        }

        InventoryTransferView attach(Player attached, int topSize, ItemStack[] admin) {
            check(attached == player && topSize == top().getSize() && admin.length == 36, "detached transport attaches to the opened inventory");
            bottom.setContents(copies(admin));
            return new InventoryTransferView() {
                @Override public ItemStack[] contents() { return copies(bottom.getContents()); }
                @Override public ItemStack getItem(int index) { return copy(bottom.getItem(index)); }
                @Override public void setItem(int index, ItemStack item) { bottom.setItem(index, copy(item)); }
            };
        }

        private static int bottomIndex(int raw, int topSize) {
            int local = raw - topSize;
            return local < 27 ? local + 9 : local - 27;
        }
    }

    private static final Object NO_VALUE = new Object();

    private static boolean empty(ItemStack item) { return item == null || item.getType().isAir() || item.getAmount() <= 0; }

    private static ItemStack copy(ItemStack item) { return empty(item) ? null : item.clone(); }

    private static ItemStack[] copies(ItemStack[] items) {
        ItemStack[] result = new ItemStack[items.length];
        for (int index = 0; index < items.length; index++) result[index] = copy(items[index]);
        return result;
    }

    private static Object objectCall(Object proxy, Method method, Object[] args, String name) {
        return switch (method.getName()) {
            case "toString" -> name;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> NO_VALUE;
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        if (type == char.class) return '\0';
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> item(Map<String, Object> root, String key, int slot) {
        if (root.get(key) instanceof List<?> entries) for (Object entry : entries) if (entry instanceof Map<?, ?> compound) {
            Object raw = compound.get("Slot");
            int stored = raw instanceof Byte b ? Byte.toUnsignedInt(b) : raw instanceof Number number ? number.intValue() : -1;
            if (stored == slot) return (Map<String, Object>) compound;
        }
        throw new IllegalStateException("missing persisted " + key + " slot " + slot);
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void setField(Object object, String name, Object value) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static void bindEditor(PlayerInventoryAccess access, PlayerInventoryEditor editor) throws ReflectiveOperationException {
        Method method = PlayerInventoryAccess.class.getDeclaredMethod("editor", PlayerInventoryEditor.class);
        method.setAccessible(true);
        method.invoke(access, editor);
    }

    private static boolean equal(Object first, Object second) {
        if (first instanceof Map<?, ?> a && second instanceof Map<?, ?> b) {
            if (!a.keySet().equals(b.keySet())) return false;
            return a.entrySet().stream().allMatch(entry -> equal(entry.getValue(), b.get(entry.getKey())));
        }
        if (first instanceof List<?> a && second instanceof List<?> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!equal(a.get(i), b.get(i))) return false;
            return true;
        }
        return java.util.Objects.deepEquals(first, second);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new IllegalStateException(description);
    }
}
