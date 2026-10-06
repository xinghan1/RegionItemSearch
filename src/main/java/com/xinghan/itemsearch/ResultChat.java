package com.xinghan.itemsearch;

import com.xinghan.itemsearch.item.ItemData;
import com.xinghan.itemsearch.item.TextNormalizer;
import com.xinghan.itemsearch.player.PlayerScanner;
import com.xinghan.itemsearch.scan.WorldScanner;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Arrays;
import java.util.Collections;

/** Builds chat components without accessing Bukkit or loading a world. */
public final class ResultChat {
    private static final int WINDOW_SIZE = 7;

    private ResultChat() { }

    public static Component header(int page, int pages) {
        validatePage(page, pages);
        return Language.component("chat.header", "page", field("chat.page-current-value", page),
                "pages", field("chat.page-total-value", pages));
    }

    public static Component container(ContainerResults.Container result, int oneBasedIndex,
                                      UUID resultId, boolean canTeleport) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(resultId, "resultId");
        if (oneBasedIndex < 1) throw new IllegalArgumentException("Result index must be positive");
        WorldScanner.Hit hit = result.representative();

        String position = hit.x() + "," + hit.y() + "," + hit.z();
        Component tooltip = Language.component("chat.container-hover",
                "hint", Language.component(canTeleport ? "chat.teleport-hint" : "chat.teleport-unavailable-hint"),
                "world", Component.text(hit.world()), "position", Component.text(position));
        Component coordinates = field(canTeleport ? "chat.coordinates" : "chat.coordinates-unavailable", position)
                .hoverEvent(HoverEvent.showText(tooltip));
        if (canTeleport) {
            coordinates = coordinates.decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.runCommand("/itemsearch tp " + resultId + " " + (result.firstHitIndex() + 1)));
        }
        return Language.component("chat.container-line",
                "index", Language.component("chat.index", "index", oneBasedIndex).compact(),
                "world", field("chat.world", hit.world()), "container", field("chat.container", hit.container()),
                "coordinates", coordinates, "name", field("chat.item-name", result.label()),
                "amount", Language.component("chat.container-count", "count", result.matchedItems()).compact());
    }

    public static List<Component> playerHit(PlayerScanner.Hit hit, int oneBasedIndex) {
        Objects.requireNonNull(hit, "hit");
        if (oneBasedIndex < 1) throw new IllegalArgumentException("Result index must be positive");
        String uuid = Objects.requireNonNull(hit.player(), "player UUID").toString();
        String playerName = usableName(hit.playerName(), uuid);
        Component identityTooltip = Language.component(playerName.isEmpty()
                ? "chat.player-copy-hover" : "chat.player-copy-name-hover", "uuid", Component.text(uuid));
        Component playerId = field("chat.player-identity", playerName.isEmpty() ? uuid : playerName)
                .clickEvent(ClickEvent.copyToClipboard(uuid))
                .hoverEvent(HoverEvent.showText(identityTooltip));
        Component identity = Language.component("chat.player-identity-line",
                "status", Language.component(hit.online() ? "chat.player-online" : "chat.player-offline").compact(),
                "identity", playerId,
                "inventory", playerStorageButton("chat.player-inventory-button", "inventory", uuid, "chat.player-inventory-hover"),
                "ender", playerStorageButton("chat.player-ender-button", "ender", uuid, "chat.player-ender-hover"));
        boolean inventory = hit.storage() == PlayerScanner.Storage.INVENTORY;
        String equipment = inventory ? equipmentSlot(hit.slot()) : "";
        Component storageTooltip = Language.component("chat.player-storage-hover", "slot", hit.slot(),
                "equipment", strip(equipment).isEmpty() ? Component.empty() : Language.component("chat.player-equipment", "name", Language.render(equipment)),
                "path", pathLine(hit.path()));
        Component storage = Language.component("chat.player-storage-line",
                "storage", Language.component(inventory ? "chat.player-inventory-label" : "chat.player-ender-label").compact(),
                "world", field("chat.world", hit.sourceWorld()))
                .hoverEvent(HoverEvent.showText(storageTooltip));
        return Collections.unmodifiableList(Arrays.asList(itemLine(hit.item(), oneBasedIndex), identity, storage));
    }

    private static Component playerStorageButton(String labelKey, String action, String uuid,
                                                 String tooltipKey) {
        return Language.component(labelKey).compact()
                .clickEvent(ClickEvent.runCommand("/itemsearch player " + action + " " + uuid))
                .hoverEvent(HoverEvent.showText(Language.component(tooltipKey)));
    }

    private static String usableName(String name, String uuid) {
        if (name == null) return "";
        name = strip(name);
        if (name.isEmpty() || name.equalsIgnoreCase(uuid) || name.codePointCount(0, name.length()) > 16
                || !name.codePoints().allMatch(code -> Character.isLetterOrDigit(code) || code == '_')) return "";
        return name;
    }

    private static Component itemLine(ItemData item, int index) {
        String name = strip(TextNormalizer.plain(item.name()));
        if (name.isEmpty()) name = item.material();
        return Language.component("chat.player-item-line", "index", Language.component("chat.index", "index", index).compact(),
                "name", field("chat.item-name", name),
                "amount", Language.component("chat.item-count", "count", item.count()).compact());
    }

    private static Component pathLine(List<String> steps) {
        Component path = Language.component("chat.path-prefix");
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) path = path.append(Language.component("chat.path-separator"));
            path = path.append(field("chat.path-step", steps.get(i)));
        }
        return path;
    }

    private static String equipmentSlot(int slot) {
        switch (slot) {
            case 100: return Language.tr("chat.equipment.boots");
            case 101: return Language.tr("chat.equipment.leggings");
            case 102: return Language.tr("chat.equipment.chestplate");
            case 103: return Language.tr("chat.equipment.helmet");
            case 150: return Language.tr("chat.equipment.offhand");
            default: return "";
        }
    }

    public static Component pagination(UUID resultId, int currentPage, int pages) {
        return pagination(resultId, currentPage, pages, "/itemsearch results");
    }

    public static Component pagination(UUID resultId, int currentPage, int pages, String commandPrefix) {
        Objects.requireNonNull(resultId, "resultId");
        Objects.requireNonNull(commandPrefix, "commandPrefix");
        commandPrefix = strip(commandPrefix);
        if (!commandPrefix.startsWith("/") || commandPrefix.indexOf('\n') >= 0 || commandPrefix.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Invalid pagination command prefix");
        }
        List<Integer> window = pageWindow(currentPage, pages);
        Component navigation = Component.empty()
                .append(button(Language.component("chat.page-previous").compact(), resultId, currentPage - 1, currentPage > 1, commandPrefix))
                .append(Component.space());
        if (window.get(0) > 1) {
            navigation = navigation.append(button(Language.component("chat.page-first").compact(), resultId, 1, true, commandPrefix))
                    .append(Language.component("chat.page-ellipsis"));
        }
        for (int i = 0; i < window.size(); i++) {
            if (i > 0) navigation = navigation.append(Component.space());
            int page = window.get(i);
            Component number = page == currentPage
                    ? Language.component("chat.page-current", "page", page).compact()
                        .hoverEvent(HoverEvent.showText(Language.component("chat.page-current-hover")))
                    : button(Language.component("chat.page-number", "page", page).compact(), resultId, page, true, commandPrefix);
            navigation = navigation.append(number);
        }
        if (window.get(window.size() - 1) < pages) {
            navigation = navigation.append(Language.component("chat.page-ellipsis"))
                    .append(button(Language.component("chat.page-last").compact(), resultId, pages, true, commandPrefix));
        }
        navigation = navigation.append(Component.space())
                .append(button(Language.component("chat.page-next").compact(), resultId, currentPage == pages ? currentPage : currentPage + 1,
                        currentPage < pages, commandPrefix));
        return navigation;
    }

    /** Returns at most seven consecutive page numbers, centered whenever both ends permit it. */
    public static List<Integer> pageWindow(int current, int pages) {
        validatePage(current, pages);
        int size = Math.min(WINDOW_SIZE, pages);
        int start = Math.max(1, Math.min(current - size / 2, pages - size + 1));
        List<Integer> result = new ArrayList<>(size);
        for (int offset = 0; offset < size; offset++) result.add(start + offset);
        return Collections.unmodifiableList(new ArrayList<Integer>(result));
    }

    private static Component button(Component label, UUID resultId, int page, boolean enabled, String commandPrefix) {
        if (!enabled) return Language.component("chat.page-disabled", "label",
                Component.text(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(label))).compact();
        return label.clickEvent(ClickEvent.runCommand(commandPrefix + " " + resultId + " " + page))
                .hoverEvent(HoverEvent.showText(Language.component("chat.page-hover", "page", page)));
    }

    private static Component field(String key, Object value) {
        return Language.component(key, "value", Component.text(String.valueOf(value))).compact();
    }

    private static void validatePage(int page, int pages) {
        if (pages < 1 || page < 1 || page > pages) throw new IllegalArgumentException("Invalid result page");
    }

    private static String strip(String value) {
        int start = 0, end = value.length();
        while (start < end && Character.isWhitespace(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && Character.isWhitespace(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        return value.substring(start, end);
    }
}
