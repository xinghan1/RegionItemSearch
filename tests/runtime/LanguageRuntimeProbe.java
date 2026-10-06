package com.xinghan.itemsearch.probe;

import com.xinghan.itemsearch.Language;
import com.xinghan.itemsearch.RegionItemSearchPlugin;
import com.xinghan.itemsearch.ResultChat;
import com.xinghan.itemsearch.item.ItemData;
import com.xinghan.itemsearch.player.PlayerScanner;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Test-server-only language reload verification; the original file and snapshot are always restored. */
public final class LanguageRuntimeProbe {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private LanguageRuntimeProbe() { }

    public static void run(RegionItemSearchPlugin plugin) throws Exception {
        check(Bukkit.isPrimaryThread(), "language runtime probe executes on the server thread");
        Path file = plugin.getDataFolder().toPath().resolve("lang.yml");
        check(Files.isRegularFile(file) && Files.size(file) > 0, "plugin generates its separate lang.yml on enable");
        byte[] original = Files.readAllBytes(file);
        String originalReload = Language.tr("messages.reload-success");
        String originalSave = Language.tr("editor.save-name");
        String originalTitle = Language.tr("editor.title");
        String originalChat = Language.tr("chat.player-identity-line");
        Exception failure = null;
        try {
            YamlConfiguration changed = new YamlConfiguration();
            changed.loadFromString(new String(original, StandardCharsets.UTF_8));
            check(changed.isString("messages.reload-success") && changed.isList("editor.help-lore")
                    && changed.isString("chat.player-inventory-button"), "generated file contains command, editor and chat language sections");
            changed.set("messages.reload-success", "§bLANG_RELOAD_OK");
            changed.set("editor.save-name", "&aStore {storage}");
            changed.set("editor.title", "&#12abef{player} / {storage} [{uuid}]");
            changed.set("editor.help-lore", List.of("§7Owner {player}", "&#55aaeeMove {storage}", "&eID {uuid}"));
            changed.set("chat.player-identity-line", "&8{ender} :: {identity} :: {inventory}{status}");
            changed.set("chat.player-inventory-button", "&a[LANG_INV]");
            changed.set("chat.player-ender-button", "&#ff88aa[LANG_ENDER]");
            changed.set("chat.player-inventory-hover", "§bLANG_INVENTORY_HOVER");
            changed.set("chat.player-ender-hover", "&eLANG_ENDER_HOVER");
            changed.set("chat.player-copy-name-hover", "&7LANG_COPY &f{uuid}");
            changed.set("chat.page-next", "&a[LANG_NEXT]");
            changed.set("chat.page-hover", "&#aa55ffJump {page}");
            Files.writeString(file, changed.saveToString(), StandardCharsets.UTF_8);

            reload(plugin);
            check(Language.tr("messages.reload-success").equals("§bLANG_RELOAD_OK")
                    && Language.plain("messages.reload-success").equals("LANG_RELOAD_OK"), "console reload activates changed command text");
            check(hasColor(Language.component("messages.reload-success"), NamedTextColor.AQUA), "section-sign colors survive command reload");
            verifyEditorValues();
            verifyInteractiveParameters();
            verifyResultChat();
            plugin.getLogger().info("PROBE PASS language: generated lang.yml, console reload, GUI strings, colors and interactive chat placeholders");

            Files.writeString(file, "messages:\n  reload-success: [unterminated\n", StandardCharsets.UTF_8);
            reload(plugin);
            check(Language.tr("messages.reload-success").equals("§bLANG_RELOAD_OK")
                            && Language.tr("editor.save-name").equals("&aStore {storage}")
                            && Language.tr("chat.player-inventory-button").equals("&a[LANG_INV]"),
                    "invalid YAML reload preserves the complete previous valid language snapshot");
            verifyResultChat();
            plugin.getLogger().info("PROBE PASS language: invalid YAML retains the active messages and click/hover components");
        } catch (Exception error) {
            failure = error;
            throw error;
        } finally {
            try {
                Files.write(file, original);
                reload(plugin);
                check(java.util.Arrays.equals(original, Files.readAllBytes(file)), "language probe restores exact original lang.yml bytes");
                check(Language.tr("messages.reload-success").equals(originalReload)
                                && Language.tr("editor.save-name").equals(originalSave)
                                && Language.tr("editor.title").equals(originalTitle)
                                && Language.tr("chat.player-identity-line").equals(originalChat),
                        "restoring lang.yml restores the original command/editor/chat language snapshot");
            } catch (Exception restoration) {
                if (failure != null) failure.addSuppressed(restoration);
                else throw restoration;
            }
        }
    }

    private static void reload(RegionItemSearchPlugin plugin) {
        check(plugin.onCommand(Bukkit.getConsoleSender(), plugin.getCommand("itemsearch"), "itemsearch", new String[]{"reload"}),
                "actual console itemsearch reload command is handled");
    }

    private static void verifyEditorValues() {
        UUID uuid = UUID.fromString("12345678-1234-4234-8234-123456789abc");
        Component literalName = Component.text("Lang{storage}&cProbe");
        Component title = Language.component("editor.title", "player", literalName, "storage", "BACKPACK", "uuid", uuid);
        check(PLAIN.serialize(title).equals("Lang{storage}&cProbe / BACKPACK [" + uuid + "]"),
                "editor title substitutes fields while literal component values are not interpreted again");
        check(hasColor(title, TextColor.color(0x12abef)), "editor title supports configured compact hex colors");
        Component save = Language.component("editor.save-name", "storage", "BACKPACK");
        check(PLAIN.serialize(save).equals("Store BACKPACK") && hasColor(save, NamedTextColor.GREEN),
                "editor save label reloads text, storage placeholder and configured color");
        List<String> lines = Language.lines("editor.help-lore", "player", "LangProbe", "storage", "BACKPACK", "uuid", uuid);
        check(lines.equals(List.of("§7Owner LangProbe", "&#55aaeeMove BACKPACK", "&eID " + uuid)),
                "editor help YAML list resolves all values without losing legacy color codes");
        List<Component> components = Language.components("editor.help-lore", "player", "LangProbe", "storage", "BACKPACK", "uuid", uuid);
        check(components.size() == 3 && PLAIN.serialize(components.get(1)).equals("Move BACKPACK")
                        && hasColor(components.get(0), NamedTextColor.GRAY)
                        && hasColor(components.get(1), TextColor.color(0x55aaee))
                        && hasColor(components.get(2), NamedTextColor.YELLOW),
                "editor help components preserve configured section, hex and ampersand colors");
    }

    private static void verifyInteractiveParameters() {
        String command = "/itemsearch results 12345678-1234-4234-8234-123456789abc 2";
        Component interactive = Component.text("SAFE_COMPONENT", NamedTextColor.WHITE)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("SAFE_HOVER", NamedTextColor.AQUA)));
        Component result = Language.component("editor.message", "reason", interactive);
        Component preserved = clicked(result, ClickEvent.Action.RUN_COMMAND, command);
        check(PLAIN.serialize(preserved).equals("SAFE_COMPONENT") && hoverText(preserved).equals("SAFE_HOVER"),
                "a component placeholder preserves its actual click payload and hover component");
    }

    private static void verifyResultChat() {
        UUID player = UUID.fromString("456789ab-1234-4234-8234-123456789abc");
        ItemData item = ItemData.from(Map.of("id", "minecraft:diamond", "count", 2), Set.of());
        String world = "world{inventory} &cliteral";
        var hit = new PlayerScanner.Hit(player, "LangProbe", world, false, PlayerScanner.Storage.INVENTORY,
                0, item, List.of("INVENTORY[0]", "SHULKER_BOX[7]"));
        List<Component> lines = ResultChat.playerHit(hit, 1);
        check(lines.size() == 3, "localized player results preserve the three-line layout");
        Component identity = lines.get(1);
        String identityText = PLAIN.serialize(identity);
        check(identityText.indexOf("[LANG_ENDER]") < identityText.indexOf("LangProbe")
                        && identityText.indexOf("LangProbe") < identityText.indexOf("[LANG_INV]"),
                "language templates can reorder interactive player buttons and identity");
        check(!identityText.contains(player.toString()), "localized named player line still shows a single identity");
        Component inventory = clicked(identity, ClickEvent.Action.RUN_COMMAND, "/itemsearch player inventory " + player);
        Component ender = clicked(identity, ClickEvent.Action.RUN_COMMAND, "/itemsearch player ender " + player);
        Component copied = clicked(identity, ClickEvent.Action.COPY_TO_CLIPBOARD, player.toString());
        check(PLAIN.serialize(inventory).equals("[LANG_INV]") && hoverText(inventory).equals("LANG_INVENTORY_HOVER")
                        && hasColor(inventory, NamedTextColor.GREEN), "translated inventory button retains its command, hover and color");
        check(PLAIN.serialize(ender).equals("[LANG_ENDER]") && hoverText(ender).equals("LANG_ENDER_HOVER")
                        && hasColor(ender, TextColor.color(0xff88aa)), "translated ender button retains its command, hover and hex color");
        check(PLAIN.serialize(copied).equals("LangProbe") && hoverText(copied).equals("LANG_COPY " + player),
                "translated player identity still copies the complete UUID and displays it in hover");
        check(PLAIN.serialize(lines.get(2)).contains(world), "world values remain literal despite placeholder braces and color-looking characters");

        UUID resultId = UUID.fromString("789abcde-1234-4234-8234-123456789abc");
        Component pagination = ResultChat.pagination(resultId, 1, 3, "/itemsearch player results");
        Component next = nodes(pagination).stream().filter(node -> PLAIN.serialize(node).equals("[LANG_NEXT]")
                && node.clickEvent() != null).findFirst().orElseThrow(() -> new IllegalStateException("localized next-page button missing"));
        check(next.clickEvent().equals(ClickEvent.runCommand("/itemsearch player results " + resultId + " 2"))
                        && hoverText(next).equals("Jump 2") && hasColor(next, NamedTextColor.GREEN),
                "translated pagination keeps its result token, command prefix, page and tooltip");
        check(next.hoverEvent() != null && next.hoverEvent().value() instanceof Component tooltip
                        && hasColor(tooltip, TextColor.color(0xaa55ff)), "translated pagination hover keeps configured hex color");
    }

    private static Component clicked(Component root, ClickEvent.Action action, String value) {
        return nodes(root).stream().filter(node -> node.clickEvent() != null && node.clickEvent().action() == action
                && node.clickEvent().value().equals(value)).findFirst()
                .orElseThrow(() -> new IllegalStateException("interactive language component lost payload: " + value));
    }

    private static String hoverText(Component component) {
        HoverEvent<?> hover = component.hoverEvent();
        check(hover != null && hover.action() == HoverEvent.Action.SHOW_TEXT && hover.value() instanceof Component,
                "interactive language component retains a text hover");
        return PLAIN.serialize((Component) hover.value());
    }

    private static boolean hasColor(Component root, TextColor color) {
        return nodes(root).stream().anyMatch(node -> color.equals(node.color()));
    }

    private static List<Component> nodes(Component root) {
        List<Component> result = new ArrayList<>();
        collect(root, result);
        return result;
    }

    private static void collect(Component component, List<Component> result) {
        result.add(component);
        component.children().forEach(child -> collect(child, result));
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new IllegalStateException(description);
    }
}
