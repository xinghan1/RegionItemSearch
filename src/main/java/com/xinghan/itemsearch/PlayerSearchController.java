package com.xinghan.itemsearch;

import com.xinghan.itemsearch.item.Query;
import com.xinghan.itemsearch.nbt.NbtReader;
import com.xinghan.itemsearch.player.PlayerScanner;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Player search has its own commands, task, result token and report; it never loads offline players. */
final class PlayerSearchController {
    private final RegionItemSearchPlugin plugin;
    private final ExecutorService worker;
    private Job active;
    private volatile boolean closed;
    private PlayerScanner.Summary last;
    private UUID lastId;
    private int lastPageSize = 5;
    private static final class CapturedItem {
        private final int slot;
        private final byte[] nbt;
        CapturedItem(int slot, byte[] nbt) { this.slot = slot; this.nbt = nbt; }
        int slot() { return slot; }
        byte[] nbt() { return nbt; }
    }
    private static final class CapturedPlayer {
        private final UUID uuid;
        private final String name;
        private final List<CapturedItem> inventory;
        private final List<CapturedItem> ender;
        CapturedPlayer(UUID uuid, String name, List<CapturedItem> inventory, List<CapturedItem> ender) {
            this.uuid = uuid; this.name = name; this.inventory = Compat.listCopy(inventory); this.ender = Compat.listCopy(ender);
        }
        UUID uuid() { return uuid; }
        String name() { return name; }
        List<CapturedItem> inventory() { return inventory; }
        List<CapturedItem> ender() { return ender; }
    }
    private static final class CaptureResult {
        final CapturedPlayer player;
        final RuntimeException error;
        CaptureResult(CapturedPlayer player, RuntimeException error) { this.player = player; this.error = error; }
    }
    private static final class Job {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CommandSender sender;
        final long started = System.nanoTime();
        volatile Thread thread;
        volatile String progress = Language.tr("player.capture-preparing");
        long lastProgress;
        Job(CommandSender sender) { this.sender = sender; }
    }

    PlayerSearchController(RegionItemSearchPlugin plugin) {
        this.plugin = plugin;
        worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "RegionItemSearch-Players"); t.setDaemon(true); return t; });
    }

    boolean command(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            Language.components("player.help").forEach(line -> plugin.message(sender, line));
            return true;
        }
        String mode = args[0].toLowerCase(Locale.ROOT);
        switch (mode) {
            case "inventory": case "ender": {
                if (args.length != 2) error(sender, Language.tr("player.inventory-syntax", "command", mode));
                else plugin.openPlayerInventory(sender, args[1], mode.equals("inventory") ? PlayerScanner.Storage.INVENTORY : PlayerScanner.Storage.ENDER_CHEST);
                return true;
            }
            case "stop": {
                if (active == null) plugin.message(sender, Language.component("player.no-active"));
                else {
                    active.cancelled.set(true);
                    if (active.thread != null) active.thread.interrupt();
                    plugin.message(sender, Language.component("player.stop-requested"));
                }
                return true;
            }
            case "status": {
                plugin.message(sender, active == null ? Language.component("player.status-idle")
                        : Language.component("player.status", "progress", Language.render(active.progress),
                            "seconds", ((System.nanoTime() - active.started) / 1_000_000_000)));
                return true;
            }
            case "results": case "page": {
                try {
                    if (args.length > 3) throw new IllegalArgumentException(Language.tr("player.results-syntax"));
                    int page = args.length == 3 ? ResultReferences.page(lastId, args[1], args[2], pages())
                            : args.length == 2 ? Integer.parseInt(args[1]) : 1;
                    showPage(sender, page);
                } catch (NumberFormatException ex) { error(sender, Language.tr("player.invalid-page-number")); }
                catch (IllegalArgumentException ex) { error(sender, ex.getMessage() == null ? null : ex.getMessage().replace("/itemsearch results", "/itemsearch player results")); }
                return true;
            }
            default: break;
        }
        if (active != null) { plugin.message(sender, Language.component("player.already-running")); return true; }
        try { start(sender, CommandOptions.parse(args)); }
        catch (IllegalArgumentException | IOException ex) { error(sender, ex.getMessage()); }
        return true;
    }

    private void start(CommandSender sender, CommandOptions options) throws IOException {
        final PlayerScanner.Settings settings = new PlayerScanner.Settings(plugin.bounded("player-search.max-player-bytes", 16777216, 65536, 67108864),
                plugin.bounded("scan.max-nbt-depth", 128, 8, 256), plugin.bounded("scan.max-container-depth", 16, 1, 64),
                plugin.bounded("player-search.max-results", 1000, 1, 100000), Compat.setCopy(plugin.getConfig().getStringList("scan.ignored-keys")));
        Set<String> excluded = Compat.setCopy(plugin.getConfig().getStringList("worlds.excluded"));
        List<PlayerScanner.Source> sources = new ArrayList<>();
        if (plugin.getConfig().getBoolean("worlds.scan-registered", true)) for (org.bukkit.World world : Bukkit.getWorlds()) {
            if (!excluded.contains(world.getName())) sources.add(new PlayerScanner.Source(world.getName(), BukkitCompat.playerDataDirectory(world)));
        }
        for (Map<?, ?> entry : plugin.getConfig().getMapList("worlds.extra-player-data-directories")) {
            if (!(entry.get("world") instanceof String) || !(entry.get("path") instanceof String)
                    || Compat.isBlank((String) entry.get("world")) || Compat.isBlank((String) entry.get("path"))) throw new IllegalArgumentException(Language.tr("player.invalid-source"));
            String world = (String) entry.get("world"), path = (String) entry.get("path");
            if (!excluded.contains(world)) sources.add(new PlayerScanner.Source(world, Compat.path("").toAbsolutePath().resolve(path).normalize()));
        }
        final int perTick = plugin.bounded("player-search.online-players-per-tick", 2, 1, 20);
        final int pageSize = plugin.bounded("scan.page-size", 5, 1, 20);
        final int progressSeconds = plugin.bounded("scan.progress-interval-seconds", 10, 1, 300);
        final List<PlayerScanner.Source> sourceSnapshot = Compat.listCopy(sources);
        final Job job = new Job(sender);
        active = job;
        plugin.createQueryAsync(sender, options, settings.maxPlayerBytes(), settings.maxNbtDepth(), settings.ignoredKeys(), query -> {
            if (closed || active != job) return;
            if (job.cancelled.get()) { active = null; return; }
            plugin.message(sender, Language.component("player.started", "target", Component.text(query.label()),
                    "mode", query.mode(), "match", query.match()));
            plugin.message(sender, Language.component("player.snapshot-info"));
            List<Player> players = Compat.listCopy(Bukkit.getOnlinePlayers());
            capture(job, players, 0, perTick, new CapturedPlayer[players.size()], sourceSnapshot, query, settings, pageSize, progressSeconds);
        }, failure -> { if (active == job) { active = null; error(sender, failure.getMessage()); } });
    }

    private void capture(final Job job, final List<Player> players, int offset, final int perTick, final CapturedPlayer[] snapshots,
                         List<PlayerScanner.Source> sources, Query query, PlayerScanner.Settings settings, int pageSize, int progressSeconds) {
        if (closed || active != job) return;
        if (offset >= players.size() || job.cancelled.get()) {
            finishCapture(job, snapshots, sources, query, settings, pageSize, progressSeconds);
            return;
        }
        final int end = Math.min(players.size(), offset + perTick);
        final AtomicInteger remaining = new AtomicInteger(end - offset);
        for (int i = offset; i < end; i++) {
            final int index = i;
            final Player player = players.get(i);
            final AtomicBoolean delivered = new AtomicBoolean();
            final Consumer<CaptureResult> complete = result -> {
                if (!delivered.compareAndSet(false, true)) return;
                plugin.onMain(() -> {
                    if (closed || active != job) return;
                    if (result.error != null) {
                        active = null;
                        job.cancelled.set(true);
                        error(job.sender, Language.tr("player.capture-failed", "error", result.error.getMessage()));
                        return;
                    }
                    snapshots[index] = result.player;
                    if (remaining.decrementAndGet() != 0) return;
                    int count = 0;
                    for (CapturedPlayer captured : snapshots) if (captured != null) count++;
                    job.progress = Language.tr("player.capture-progress", "captured", count, "total", players.size());
                    if (end < players.size() && !job.cancelled.get()) {
                        plugin.scheduler().globalLater(1L, () -> capture(job, players, end, perTick, snapshots, sources, query, settings, pageSize, progressSeconds));
                    } else finishCapture(job, snapshots, sources, query, settings, pageSize, progressSeconds);
                });
            };
            Runnable retired = () -> complete.accept(new CaptureResult(null, null));
            boolean accepted = plugin.scheduler().entity(player, () -> {
                try {
                    if (job.cancelled.get() || !player.isOnline()) { retired.run(); return; }
                    List<CapturedItem> inventory = new ArrayList<>();
                    collect(inventory, BukkitCompat.storageContents(player), 0);
                    collect(inventory, player.getInventory().getArmorContents(), 100);
                    collect(inventory, new ItemStack[]{BukkitCompat.offhand(player)}, 150);
                    List<CapturedItem> ender = new ArrayList<>();
                    collect(ender, player.getEnderChest().getContents(), 0);
                    complete.accept(new CaptureResult(new CapturedPlayer(player.getUniqueId(), player.getName(), inventory, ender), null));
                } catch (RuntimeException failure) { complete.accept(new CaptureResult(null, failure)); }
            }, retired);
            if (!accepted) retired.run();
        }
    }

    private void finishCapture(Job job, CapturedPlayer[] snapshots, List<PlayerScanner.Source> sources, Query query,
                               PlayerScanner.Settings settings, int pageSize, int progressSeconds) {
        List<CapturedPlayer> captured = new ArrayList<>();
        for (CapturedPlayer player : snapshots) if (player != null) captured.add(player);
        final List<CapturedPlayer> immutable = Compat.listCopy(captured);
        worker.submit(() -> scan(job, immutable, sources, query, settings, pageSize, progressSeconds));
    }

    private static void collect(List<CapturedItem> items, ItemStack[] contents, int baseSlot) {
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!BukkitCompat.air(item)) items.add(new CapturedItem(baseSlot + slot, BukkitCompat.serializeItem(item.clone())));
        }
    }

    private void scan(Job job, List<CapturedPlayer> snapshots, List<PlayerScanner.Source> sources, Query query,
                      PlayerScanner.Settings settings, int pageSize, int progressSeconds) {
        job.thread = Thread.currentThread();
        try {
            List<String> snapshotWarnings = new ArrayList<>();
            List<PlayerScanner.Online> online = new ArrayList<>();
            for (CapturedPlayer player : snapshots) {
                if (job.cancelled.get()) break;
                online.add(new PlayerScanner.Online(player.uuid(), player.name(), decode(player.inventory(), settings, snapshotWarnings, player.name(), job.cancelled::get),
                        decode(player.ender(), settings, snapshotWarnings, player.name(), job.cancelled::get)));
            }
            PlayerScanner.Summary scanned = new PlayerScanner().scan(sources, online, Compat.path("usercache.json"), query, settings, job.cancelled::get, progress -> {
                job.progress = progress;
                long now = System.nanoTime();
                if (now - job.lastProgress >= progressSeconds * 1_000_000_000L) {
                    job.lastProgress = now;
                    plugin.onMain(() -> { if (active == job) plugin.message(job.sender, Language.component("player.progress", "progress", Language.render(progress))); });
                }
            });
            List<String> warnings = new ArrayList<>(scanned.warnings());
            warnings.addAll(snapshotWarnings);
            final PlayerScanner.Summary result = new PlayerScanner.Summary(scanned.files(), scanned.players(), scanned.onlinePlayers(), scanned.offlinePlayers(), scanned.items(),
                    scanned.matchedStacks(), scanned.matchedItems(), scanned.errors() + snapshotWarnings.size(), scanned.cancelled(), scanned.truncated(), scanned.hits(), warnings.stream().limit(32).collect(Collectors.toList()));
            Thread.interrupted();
            Path report = null;
            String reportError = null;
            try { report = report(query, result); }
            catch (IOException ex) { reportError = ex.getMessage(); }
            Path saved = report;
            String failedReport = reportError;
            plugin.onMain(() -> {
                if (active != job) return;
                active = null;
                last = result;
                lastId = UUID.randomUUID();
                lastPageSize = pageSize;
                Component outcome = Language.component(result.cancelled() ? "player.outcome-stopped"
                        : result.truncated() ? "player.outcome-limited" : "player.outcome-complete");
                plugin.message(job.sender, Language.component("player.summary", "outcome", outcome,
                        "stacks", result.matchedStacks(), "items", result.matchedItems()));
                plugin.message(job.sender, Language.component("player.statistics", "online", result.onlinePlayers(),
                        "offline", result.offlinePlayers(), "checked", result.items(), "errors", result.errors(),
                        "seconds", ((System.nanoTime() - job.started) / 1_000_000_000)));
                result.warnings().stream().limit(3).forEach(w -> error(job.sender, w));
                if (saved != null) plugin.message(job.sender, Language.component("player.report-saved", "path", Component.text(saved.toString())));
                if (failedReport != null) error(job.sender, Language.tr("player.report-failed", "error", failedReport));
                showPage(job.sender, 1);
            });
        } catch (Exception ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Player search failed", ex);
            plugin.onMain(() -> { if (active == job) { active = null; error(job.sender, Language.tr("player.search-failed", "error", ex.getMessage())); } });
        } finally { job.thread = null; Thread.interrupted(); }
    }

    private static List<Map<String, Object>> decode(List<CapturedItem> items, PlayerScanner.Settings settings, List<String> warnings, String player,
                                                   java.util.function.BooleanSupplier cancelled) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CapturedItem item : items) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) break;
            try {
                java.io.InputStream raw = new java.io.ByteArrayInputStream(item.nbt());
                java.io.InputStream decoded = item.nbt().length >= 2 && (item.nbt()[0] & 255) == 0x1f && (item.nbt()[1] & 255) == 0x8b
                        ? new java.util.zip.GZIPInputStream(raw) : raw;
                Map<String, Object> parsed;
                try (java.io.FilterInputStream input = new java.io.FilterInputStream(decoded) {
                    private void check() throws IOException {
                        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException(Language.plain("player.decode-stopped"));
                    }
                    @Override public int read() throws IOException { check(); return in.read(); }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException { check(); return in.read(bytes, offset, length); }
                }) {
                    parsed = NbtReader.read(input, settings.maxPlayerBytes(), settings.maxNbtDepth());
                }
                Map<String, Object> nbt = new LinkedHashMap<>(parsed);
                nbt.put("Slot", item.slot());
                result.add(nbt);
            } catch (IOException ex) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) break;
                warnings.add(Language.plain("player.decode-warning", "player", Component.text(player), "slot", item.slot(),
                        "error", Component.text(String.valueOf(ex.getMessage()))));
            }
        }
        return result;
    }

    private Path report(Query query, PlayerScanner.Summary result) throws IOException {
        Path folder = plugin.getDataFolder().toPath().resolve("player-reports");
        Files.createDirectories(folder);
        Path path = folder.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + UUID.randomUUID().toString().substring(0, 8) + ".txt");
        try (java.io.BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write(Language.plain("player.report-header", "target", Component.text(query.label()),
                    "mode", query.mode(), "match", query.match(), "lore-match", query.loreMatch()) + "\n");
            out.write(Language.plain("player.report-summary", "stacks", result.matchedStacks(), "items", result.matchedItems(),
                    "online", result.onlinePlayers(), "offline", result.offlinePlayers(), "cancelled", result.cancelled(),
                    "truncated", result.truncated(), "errors", result.errors()) + "\n");
            for (String warning : result.warnings()) out.write(Language.plain("player.report-warning", "warning", Component.text(warning)) + "\n");
            int number = 1;
            for (PlayerScanner.Hit hit : result.hits()) out.write("\n" + Language.plain("player.report-hit", "index", number++,
                    "name", Component.text(hit.item().name()), "material", Component.text(hit.item().material()), "count", hit.item().count(),
                    "player-name", Component.text(String.valueOf(hit.playerName())), "uuid", hit.player(),
                    "status", Language.component(hit.online() ? "player.report-online" : "player.report-offline"),
                    "world", Component.text(hit.sourceWorld()),
                    "storage", Language.component(hit.storage() == PlayerScanner.Storage.INVENTORY
                            ? "player.report-inventory" : "player.report-ender"),
                    "slot", hit.slot(), "path", Component.text(String.join(" -> ", hit.path()))) + "\n");
        }
        return path.toAbsolutePath();
    }

    private int pages() { return last == null ? 0 : Math.max(1, (last.hits().size() + lastPageSize - 1) / lastPageSize); }
    private void showPage(CommandSender sender, int page) {
        if (last == null) { plugin.message(sender, Language.component("player.no-results")); return; }
        int pages = pages();
        if (page < 1 || page > pages) { error(sender, Language.tr("player.page-range", "pages", pages)); return; }
        plugin.message(sender, Language.component("player.results-header", "header", ResultChat.header(page, pages)));
        int start = (page - 1) * lastPageSize;
        for (int i = start; i < Math.min(start + lastPageSize, last.hits().size()); i++) {
            ResultChat.playerHit(last.hits().get(i), i + 1).forEach(line -> plugin.message(sender, line));
        }
        if (last.hits().isEmpty()) plugin.message(sender, Language.component("player.no-matches"));
        plugin.message(sender, ResultChat.pagination(lastId, page, pages, "/itemsearch player results"));
    }
    private void error(CommandSender sender, String text) {
        plugin.message(sender, Language.component("player.error", "message",
                Language.render(text == null ? Language.tr("player.generic-error") : text)));
    }
    List<String> complete(String[] args) {
        List<String> candidates = args.length == 1 ? Compat.list("same", "name", "lore", "id", "material", "results", "stop", "status", "help", "inventory", "ender")
                : args.length == 2 && args[0].equalsIgnoreCase("lore") ? Compat.list("all", "any", "exact", "fuzzy")
                : args.length == 2 && (args[0].equalsIgnoreCase("name") || args[0].equalsIgnoreCase("id") || args[0].equalsIgnoreCase("material")) ? Compat.list("exact", "fuzzy") : Compat.list();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return candidates.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
    }
    void close() { closed = true; if (active != null) active.cancelled.set(true); worker.shutdownNow(); }
}
