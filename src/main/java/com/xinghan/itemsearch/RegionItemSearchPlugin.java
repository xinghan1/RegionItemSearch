package com.xinghan.itemsearch;

import com.xinghan.itemsearch.item.ItemData;
import com.xinghan.itemsearch.item.Query;
import com.xinghan.itemsearch.item.TextNormalizer;
import com.xinghan.itemsearch.nbt.NbtReader;
import com.xinghan.itemsearch.scan.WorldScanner;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Shared search state lives on the global scheduler; player data is captured on its entity owner. */
public final class RegionItemSearchPlugin extends JavaPlugin implements TabExecutor {
    private SchedulerCompat scheduler;
    private ExecutorService worker;
    private volatile boolean shuttingDown;
    private volatile Job active;
    private WorldScanner.Summary lastResult;
    private List<ContainerResults.Container> lastContainers = Compat.list();
    private Path lastReport;
    private int lastPageSize = 5;
    private volatile UUID lastResultId;
    private Map<String, UUID> lastTeleportWorlds = Compat.map();
    private ResultTeleport teleports;
    private PlayerSearchController playerSearch;
    private PlayerInventoryAccess inventoryAccess;
    private PlayerInventoryEditor inventoryEditor;
    private static final class WorldReference {
        private final UUID id;
        private final Path activeRegion;
        WorldReference(UUID id, Path activeRegion) { this.id = id; this.activeRegion = activeRegion; }
        UUID id() { return id; }
        Path activeRegion() { return activeRegion; }
    }

    private static final class Job {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CommandSender sender;
        final long started = System.nanoTime();
        volatile String progress = Language.tr("messages.discovering");
        volatile Thread thread;
        long lastProgress;
        Job(CommandSender sender) { this.sender = sender; }
    }

    @Override public void onEnable() {
        scheduler = new SchedulerCompat(this);
        saveDefaultConfig();
        int configVersion = getConfig().isSet("config-version") ? getConfig().getInt("config-version") : 0;
        if (configVersion < 3) {
            int oldDelay = getConfig().getInt("scan.delay-per-chunk-ms", 0);
            if (configVersion == 2 && oldDelay == 1 || configVersion < 2 && oldDelay == 2) getConfig().set("scan.delay-per-chunk-ms", 0);
            getConfig().set("config-version", 3);
            getConfig().options().copyDefaults(true);
            saveConfig();
        }
        try { loadLanguage(); }
        catch (IOException error) { getLogger().log(Level.WARNING, "Could not load lang.yml; bundled messages remain active", error); }
        BukkitCompat.initialize(this);
        teleports = new ResultTeleport(this);
        playerSearch = new PlayerSearchController(this);
        inventoryAccess = new PlayerInventoryAccess(this);
        inventoryEditor = new PlayerInventoryEditor(this, inventoryAccess);
        inventoryAccess.editor(inventoryEditor);
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "RegionItemSearch-IO");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        Objects.requireNonNull(getCommand("itemsearch")).setExecutor(this);
        Objects.requireNonNull(getCommand("itemsearch")).setTabCompleter(this);
        applyCommandLanguage();
        getLogger().info("Offline region search enabled. Results reflect saved disk data; no chunks are loaded.");
    }

    @Override public void onDisable() {
        shuttingDown = true;
        Job job = active;
        if (job != null) job.cancelled.set(true);
        if (worker != null) worker.shutdownNow();
        if (playerSearch != null) playerSearch.close();
        if (inventoryEditor != null) inventoryEditor.close();
        if (inventoryAccess != null) inventoryAccess.close();
        BukkitCompat.close();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("itemsearch.admin")) { message(sender, Language.tr("messages.permission-denied")); return true; }
        final String[] request = args.clone();
        onMain(() -> commandGlobal(sender, request));
        return true;
    }

    private boolean commandGlobal(CommandSender sender, String[] args) {
        if (shuttingDown) return true;
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { help(sender); return true; }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("player") || action.equals("players")) {
            return playerSearch.command(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
        }
        switch (action) {
            case "tp": {
                if (!(sender instanceof Player)) { message(sender, Language.tr("messages.teleport-player-only")); return true; }
                final Player player = (Player) sender;
                try {
                    if (args.length != 3) throw new IllegalArgumentException(Language.tr("messages.teleport-click"));
                    int index = ResultReferences.index(lastResultId, args[1], args[2], lastResult == null ? 0 : lastResult.hits().size());
                    WorldScanner.Hit hit = lastResult.hits().get(index);
                    UUID resultId = lastResultId;
                    teleports.teleport(player, hit, lastTeleportWorlds.get(hit.world()), () -> Objects.equals(resultId, lastResultId));
                } catch (IllegalArgumentException ex) { message(sender, ex instanceof NumberFormatException ? Language.component("messages.invalid-page-number") : Language.render(ex.getMessage())); }
                return true;
            }
            case "stop": {
                Job job = active;
                if (job == null) message(sender, Language.tr("messages.stop-idle"));
                else {
                    job.cancelled.set(true);
                    if (job.thread != null) job.thread.interrupt();
                    message(sender, Language.tr("messages.stop-requested"));
                }
                return true;
            }
            case "status": {
                Job job = active;
                message(sender, job == null ? Language.tr("messages.status-idle")
                        : Language.tr("messages.status-active", "progress", job.progress, "seconds", (System.nanoTime() - job.started) / 1_000_000_000));
                return true;
            }
            case "results": case "page": {
                try {
                    if (args.length > 3) throw new IllegalArgumentException(Language.tr("messages.results-syntax"));
                    int page = args.length == 3 ? ResultReferences.page(lastResultId, args[1], args[2], pageCount())
                            : args.length == 2 ? Integer.parseInt(args[1]) : 1;
                    showPage(sender, page);
                } catch (IllegalArgumentException ex) { message(sender, ex instanceof NumberFormatException ? Language.component("messages.invalid-page-number") : Language.render(ex.getMessage())); }
                return true;
            }
            case "reload": {
                reloadConfig();
                try { loadLanguage(); message(sender, Language.component("messages.reload-success")); }
                catch (IOException error) { message(sender, Language.component("messages.reload-failed", "reason", error.getMessage())); }
                return true;
            }
            default: break;
        }
        if (active != null) { message(sender, Language.tr("messages.scan-busy")); return true; }
        try { start(sender, CommandOptions.parse(args)); }
        catch (IllegalArgumentException | IOException ex) { message(sender, ex.getMessage()); }
        return true;
    }

    private void start(CommandSender sender, CommandOptions options) throws IOException {
        final WorldScanner.Settings settings = new WorldScanner.Settings(bounded("scan.max-chunk-bytes", 16777216, 65536, 67108864),
                bounded("scan.max-nbt-depth", 128, 8, 256), bounded("scan.max-container-depth", 16, 1, 64),
                bounded("scan.max-results", 1000, 1, 100000), bounded("scan.delay-per-chunk-ms", 0, 0, 1000),
                Compat.setCopy(getConfig().getStringList("scan.ignored-keys")), bounded("scan.reader-threads", 2, 1, WorldScanner.MAX_READER_THREADS));
        Set<String> excluded = Compat.setCopy(getConfig().getStringList("worlds.excluded"));
        final List<WorldSources.Registered> worlds = getConfig().getBoolean("worlds.scan-registered", true)
                ? Bukkit.getWorlds().stream().filter(w -> !excluded.contains(w.getName()))
                    .map(w -> new WorldSources.Registered(w.getName(), w.getWorldFolder().toPath())).collect(Collectors.toList()) : Compat.list();
        List<WorldScanner.Source> extras = new ArrayList<>();
        Map<String, WorldReference> references = new java.util.HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            Path folder = world.getWorldFolder().toPath();
            String dimension;
            switch (world.getEnvironment()) { case NETHER: dimension = "DIM-1/region"; break; case THE_END: dimension = "DIM1/region"; break; default: dimension = "region"; }
            references.put(world.getName(), new WorldReference(world.getUID(), folder.resolve(dimension)));
        }
        Path serverRoot = Compat.path("").toAbsolutePath();
        for (Map<?, ?> entry : getConfig().getMapList("worlds.extra-region-directories")) {
            Object world = entry.get("world"), directory = entry.get("path");
            if (!(world instanceof String) || !(directory instanceof String)
                    || Compat.isBlank((String) world) || Compat.isBlank((String) directory)) throw new IllegalArgumentException(Language.tr("messages.extra-regions-invalid"));
            String name = (String) world, directoryName = (String) directory;
            if (!excluded.contains(name)) extras.add(new WorldScanner.Source(name, serverRoot.resolve(directoryName).normalize()));
        }
        final int pageSize = bounded("scan.page-size", 5, 1, 20);
        final long progressSeconds = bounded("scan.progress-interval-seconds", 10, 1, 300);
        final Job job = new Job(sender);
        active = job;
        final List<WorldScanner.Source> extraSnapshot = Compat.listCopy(extras);
        final Map<String, WorldReference> referenceSnapshot = Compat.mapCopy(references);
        createQueryAsync(sender, options, settings.maxChunkBytes(), settings.maxNbtDepth(), settings.ignoredKeys(), query -> {
            if (active != job || shuttingDown) return;
            if (job.cancelled.get()) { active = null; return; }
            message(sender, Language.component("messages.search-start", "target", Component.text(query.label()), "mode", options.mode().name().toLowerCase(Locale.ROOT),
                    "details", options.mode() == Query.Mode.SAME ? Language.tr("messages.same-details") : Language.tr("messages.match-details", "match", options.match().name().toLowerCase(Locale.ROOT))));
            message(sender, Language.tr("messages.search-scope"));
            worker.submit(() -> runSearch(job, worlds, extraSnapshot, referenceSnapshot, query, settings, pageSize, progressSeconds));
        }, error -> { if (active == job) { active = null; message(sender, error.getMessage()); } });
    }

    Query createQuery(CommandSender sender, CommandOptions options, int maxBytes, int maxDepth, Set<String> ignoredKeys) throws IOException {
        ItemData template = null;
        List<String> terms = Compat.list();
        if (Compat.isBlank(options.keyword())) {
            if (!(sender instanceof Player)) throw new IllegalArgumentException(Language.tr("messages.console-keyword"));
            if (scheduler.isFolia() && !scheduler.owns((Player) sender)) throw new IllegalStateException("Held query must be captured on its owning player thread");
            ItemStack held = BukkitCompat.held((Player) sender);
            if (BukkitCompat.air(held)) throw new IllegalArgumentException(Language.tr("messages.template-required"));
            template = ItemData.from(NbtReader.readAuto(BukkitCompat.serializeItem(held.clone()), maxBytes, maxDepth), ignoredKeys);
            switch (options.mode()) {
                case NAME: {
                    if (Compat.isBlank(TextNormalizer.normalize(template.name()))) throw new IllegalArgumentException(Language.tr("messages.template-no-name"));
                    break;
                }
                case LORE: {
                    if (template.lore().stream().allMatch(s -> Compat.isBlank(TextNormalizer.normalize(s)))) throw new IllegalArgumentException(Language.tr("messages.template-no-lore"));
                    break;
                }
                case ID: {
                    if (Compat.isBlank(template.mmoType()) || Compat.isBlank(template.mmoId())) throw new IllegalArgumentException(Language.tr("messages.template-no-mmo-id"));
                    break;
                }
                default: break;
            }
        } else {
            if (Compat.isBlank(TextNormalizer.normalize(options.keyword()))) throw new IllegalArgumentException(Language.tr("messages.keyword-empty"));
            terms = Compat.list(options.keyword());
            if (options.mode() == Query.Mode.ID && options.keyword().contains("/")) {
                String[] pair = options.keyword().split("/", -1);
                if (pair.length != 2 || Compat.isBlank(pair[0]) || Compat.isBlank(pair[1])) throw new IllegalArgumentException(Language.tr("messages.mmo-id-syntax"));
            }
        }
        String target = template == null ? options.keyword() : (options.mode() == Query.Mode.ID
                ? template.mmoType() + "/" + template.mmoId()
                : options.mode() == Query.Mode.MATERIAL ? template.material()
                : Compat.isBlank(template.name()) ? template.material() : TextNormalizer.plain(template.name()));
        return new Query(options.mode(), options.match(), options.loreMatch(), template, terms, target);
    }

    /** Success/failure are delivered on the global scheduler; held items are read on their owning entity. */
    void createQueryAsync(final CommandSender sender, final CommandOptions options, final int maxBytes, final int maxDepth,
                          final Set<String> ignoredKeys, final Consumer<Query> success, final Consumer<Exception> failure) {
        final AtomicBoolean completed = new AtomicBoolean();
        final Runnable retired = () -> {
            if (completed.compareAndSet(false, true)) onMain(() -> failure.accept(
                    new IllegalArgumentException(Language.tr("messages.template-required"))));
        };
        Runnable capture = () -> {
            try {
                if (sender instanceof Player && (!((Player) sender).isOnline() || !sender.hasPermission("itemsearch.admin"))) {
                    throw new IllegalArgumentException(Language.tr("messages.permission-denied"));
                }
                final Query query = createQuery(sender, options, maxBytes, maxDepth, ignoredKeys);
                if (completed.compareAndSet(false, true)) onMain(() -> success.accept(query));
            } catch (final Exception error) {
                if (completed.compareAndSet(false, true)) onMain(() -> failure.accept(error));
            }
        };
        if (sender instanceof Player) {
            if (!scheduler.entity((Player) sender, capture, retired)) retired.run();
        } else capture.run();
    }

    int bounded(String key, int fallback, int min, int max) {
        return Compat.clamp(getConfig().getInt(key, fallback), min, max);
    }

    private void runSearch(Job job, List<WorldSources.Registered> worlds, List<WorldScanner.Source> extras, Map<String, WorldReference> references, Query query,
                           WorldScanner.Settings settings, int pageSize, long progressSeconds) {
        job.thread = Thread.currentThread();
        try {
            List<WorldScanner.Source> sources = WorldSources.discover(worlds, extras, job.cancelled::get);
            Map<String, UUID> teleportWorlds = teleportWorlds(sources, references);
            if (sources.isEmpty() && !job.cancelled.get()) throw new IOException(Language.tr("messages.no-region-directories"));
            final WorldScanner.Summary summary = new WorldScanner().scan(sources, query, settings, job.cancelled::get, progress -> {
                job.progress = progress;
                long now = System.nanoTime();
                if (now - job.lastProgress >= progressSeconds * 1_000_000_000L) {
                    job.lastProgress = now;
                    onMain(() -> { if (active == job) message(job.sender, progress); });
                }
            });
            // Clear a stop interrupt so completed partial results can be written to a report.
            Thread.interrupted();
            final List<ContainerResults.Container> containers = ContainerResults.group(summary.hits());
            Path report = null;
            String reportError = null;
            try { report = writeReport(query, summary, containers); }
            catch (IOException ex) { reportError = ex.getMessage(); getLogger().log(Level.WARNING, "Could not write search report", ex); }
            Path saved = report;
            String warning = reportError;
            onMain(() -> {
                if (active != job) return;
                active = null;
                lastResult = summary;
                lastContainers = containers;
                lastResultId = UUID.randomUUID();
                lastTeleportWorlds = teleportWorlds;
                lastReport = saved;
                lastPageSize = pageSize;
                message(job.sender, Language.component("messages.scan-summary", "outcome", summary.cancelled() ? Language.tr("messages.scan-stopped") : summary.truncated() ? Language.tr("messages.scan-limited") : Language.tr("messages.scan-complete"),
                        "containers", containers.size(), "items", summary.matchedItems(), "stacks", summary.matchedStacks()));
                message(job.sender, Language.component("messages.scan-statistics", "regions", summary.regions(), "chunks", summary.chunks(), "items", summary.items(),
                        "seconds", (System.nanoTime() - job.started) / 1_000_000_000));
                if (summary.errors() > 0 || summary.depthSkipped() > 0 || summary.lootTables() > 0) {
                    message(job.sender, Language.component("messages.scan-incomplete", "errors", summary.errors(), "depth", summary.depthSkipped(), "loot", summary.lootTables()));
                    summary.warnings().stream().limit(3).forEach(w -> message(job.sender, w));
                }
                if (saved != null) message(job.sender, Language.component("messages.report-saved", "path", Component.text(saved.toString())));
                if (warning != null) message(job.sender, Language.component("messages.report-failed", "reason", warning));
                if (!summary.hits().isEmpty()) showPage(job.sender, 1);
            });
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "Region search failed", ex);
            onMain(() -> {
                if (active == job) { active = null; message(job.sender, Language.component("messages.search-failed", "reason", ex.getMessage())); }
            });
        } finally {
            job.thread = null;
            Thread.interrupted();
        }
    }

    void onMain(Runnable action) {
        if (shuttingDown) return;
        try { scheduler.global(action); }
        catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { /* Plugin disabled between check and enqueue. */ }
    }

    public SchedulerCompat scheduler() { return scheduler; }

    private Map<String, UUID> teleportWorlds(List<WorldScanner.Source> sources, Map<String, WorldReference> references) {
        Map<String, UUID> result = new java.util.HashMap<>();
        Set<String> ambiguous = new java.util.HashSet<>();
        for (WorldScanner.Source source : sources) {
            WorldReference reference = references.get(source.world());
            try {
                if (reference == null || !source.regionDirectory().toRealPath().equals(reference.activeRegion().toRealPath())) {
                    ambiguous.add(source.world());
                } else result.put(source.world(), reference.id());
            } catch (IOException ex) { ambiguous.add(source.world()); }
        }
        ambiguous.forEach(result::remove);
        return Compat.mapCopy(result);
    }

    private Path writeReport(Query query, WorldScanner.Summary summary, List<ContainerResults.Container> containers) throws IOException {
        Path directory = getDataFolder().toPath().resolve("reports");
        Files.createDirectories(directory);
        Path report = directory.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8) + ".txt");
        try (BufferedWriter out = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            out.write(Language.plain("report.region.header", "target", Component.text(query.label()), "mode", query.mode(), "match", query.match(), "lore", query.loreMatch()));
            out.write(Language.plain("report.region.summary", "containers", containers.size(), "stacks", summary.matchedStacks(), "items", summary.matchedItems(),
                    "stopped", summary.cancelled(), "limited", summary.truncated(), "errors", summary.errors(), "depth", summary.depthSkipped(), "loot", summary.lootTables()));
            for (String warning : summary.warnings()) out.write(Language.plain("report.region.warning", "reason", warning));
            int index = 1;
            for (ContainerResults.Container container : containers) {
                WorldScanner.Hit hit = container.representative();
                out.write(Language.plain("report.region.entry", "index", index++, "world", Component.text(hit.world()), "container", hit.container(),
                        "x", hit.x(), "y", hit.y(), "z", hit.z(), "item", Component.text(container.label()), "count", container.matchedItems()));
            }
        }
        return report.toAbsolutePath();
    }

    private void showPage(CommandSender sender, int page) {
        if (lastResult == null) { message(sender, Language.tr("messages.no-results")); return; }
        int pages = pageCount();
        if (page < 1 || page > pages) { message(sender, Language.tr("messages.page-range", "pages", pages)); return; }
        message(sender, ResultChat.header(page, pages));
        int start = (page - 1) * lastPageSize;
        for (int i = start; i < Math.min(start + lastPageSize, lastContainers.size()); i++) {
            ContainerResults.Container container = lastContainers.get(i);
            WorldScanner.Hit hit = container.representative();
            boolean canTeleport = sender instanceof Player && lastTeleportWorlds.containsKey(hit.world());
            message(sender, ResultChat.container(container, i + 1, lastResultId, canTeleport));
        }
        message(sender, ResultChat.pagination(lastResultId, page, pages));
        if (lastResult.hits().isEmpty()) message(sender, Language.tr("messages.no-matches"));
    }

    private int pageCount() { return lastResult == null ? 0 : Math.max(1, (lastContainers.size() + lastPageSize - 1) / lastPageSize); }

    void message(CommandSender sender, String text) { message(sender, Language.render(text)); }

    void openPlayerInventory(CommandSender sender, String uuidText, com.xinghan.itemsearch.player.PlayerScanner.Storage storage) {
        if (!(sender instanceof Player)) { message(sender, Language.tr("messages.inventory-player-only")); return; }
        final Player player = (Player) sender;
        try {
            UUID target = UUID.fromString(uuidText);
            if (!target.toString().equalsIgnoreCase(uuidText)) throw new IllegalArgumentException(Language.tr("messages.uuid-required"));
            scheduler.entity(player, () -> inventoryEditor.open(player, target, storage));
        } catch (IllegalArgumentException ex) { message(sender, Language.component("messages.uuid-required")); }
    }

    void message(CommandSender sender, Component content) {
        if (net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(content).isEmpty()) return;
        final Component rendered = ChatStyle.simplify(Language.component("messages.prefix").append(content));
        if (sender instanceof Player) {
            final Player player = (Player) sender;
            scheduler.entity(player, () -> { if (player.isOnline()) BukkitCompat.send(sender, rendered); });
        } else scheduler.global(() -> BukkitCompat.send(sender, rendered));
    }

    private void loadLanguage() throws IOException {
        Path file = getDataFolder().toPath().resolve("lang.yml");
        if (!Files.exists(file)) saveResource("lang.yml", false);
        Language.reload(file);
        applyCommandLanguage();
    }

    private void applyCommandLanguage() {
        Objects.requireNonNull(getCommand("itemsearch")).setPermissionMessage(BukkitCompat.legacy(ChatStyle.simplify(
                Language.component("messages.prefix").append(Language.component("messages.permission-denied")))));
    }

    private void help(CommandSender sender) {
        Language.components("help.main").forEach(line -> message(sender, line));
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("itemsearch.admin")) return Compat.list();
        if (args.length > 1 && (args[0].equalsIgnoreCase("player") || args[0].equalsIgnoreCase("players"))) {
            return playerSearch.complete(java.util.Arrays.copyOfRange(args, 1, args.length));
        }
        List<String> options = args.length == 1 ? Compat.list("same", "name", "lore", "id", "material", "player", "stop", "status", "results", "reload", "help")
                : args.length == 2 && args[0].equalsIgnoreCase("lore") ? Compat.list("any", "all", "exact", "fuzzy")
                : args.length == 2 && (args[0].equalsIgnoreCase("name") || args[0].equalsIgnoreCase("id") || args[0].equalsIgnoreCase("material")) ? Compat.list("exact", "fuzzy") : Compat.list();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
    }
}
