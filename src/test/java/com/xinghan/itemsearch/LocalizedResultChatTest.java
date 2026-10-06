package com.xinghan.itemsearch;

import com.xinghan.itemsearch.item.ItemData;
import com.xinghan.itemsearch.item.TextNormalizer;
import com.xinghan.itemsearch.player.PlayerScanner;
import com.xinghan.itemsearch.scan.WorldScanner;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalizedResultChatTest {
    private static final UUID RESULT_ID = UUID.fromString("b4486dbb-ef47-4cf3-a2e2-8fa9e9187654");

    @BeforeEach void before() { Language.resetDefaults(); }
    @AfterEach void restoreDefaultLanguage() { Language.resetDefaults(); }

    @Test
    void configuredContainerLayoutAndHexColorsKeepOnlyCoordinatesClickable() throws Exception {
        Language.reloadText("""
                style:
                  neutral-colors: false
                  default-color: '#aaaaaa'
                chat:
                  container-line: '&b{coordinates} - {name} @ {world} {amount} {index}'
                  container-count: '&7 ×&f{count}'
                  index: '&f#{index} '
                  world: '&7{value}'
                  coordinates: '&#123456{value}'
                  item-name: '&a{value}'
                  teleport-hint: '&eClick to teleport'
                  container-hover: |-
                    {hint}
                    &bWorld: {world}
                    &fPosition: {position}
                """);
        String item = "<click:run_command:'/stop'>Sword</click>";
        Component line = ChatStyle.simplify(ResultChat.container(container(item), 3, RESULT_ID, true));
        assertEquals("100,64,-200 - " + item + " @ world  ×64 #3 ", plain(line));
        Component coordinates = text(line, "100,64,-200");
        assertEquals(TextColor.fromHexString("#123456"), coordinates.color());
        assertEquals(ClickEvent.runCommand("/itemsearch tp " + RESULT_ID + " 9"), coordinates.clickEvent());
        assertEquals(List.of(coordinates), nodes(line).stream().filter(node -> node.clickEvent() != null).toList());
        assertEquals(NamedTextColor.GREEN, text(line, item).color());
        assertNull(text(line, item).clickEvent());
        Component hover = assertInstanceOf(Component.class, coordinates.hoverEvent().value());
        assertTrue(plain(hover).contains("Click to teleport"));
        assertEquals(NamedTextColor.YELLOW, text(hover, "Click to teleport").color());
    }

    @Test
    void configuredPlayerLabelsAndLayoutRetainCopyAndInventoryActions() throws Exception {
        Language.reloadText("""
                style:
                  neutral-colors: false
                  default-color: '#aaaaaa'
                chat:
                  player-identity-line: '{identity} {inventory} {ender} {status}'
                  player-identity: '&a{value}'
                  player-online: '&bOnline '
                  player-copy-name-hover: '&eCopy UUID: {uuid}'
                  player-inventory-button: '&b[Inventory]'
                  player-ender-button: '&6[Ender]'
                  player-inventory-hover: '&aEdit inventory'
                  player-storage-line: '{world} - {storage}'
                  world: '&7{value}'
                  player-inventory-label: '&bInventory'
                  player-storage-hover: |-
                    &eSlot {slot}{equipment}
                    {path}
                  player-equipment: '&f ({name})'
                  equipment:
                    boots: 'Boots'
                """);
        UUID player = UUID.fromString("f34c2445-c5d2-48a8-9a07-fb4f8757d7c0");
        var hit = new PlayerScanner.Hit(player, "Player", "&cworld", true,
                PlayerScanner.Storage.INVENTORY, 100, hit("Sword").item(), List.of("INVENTORY[100]"));
        List<Component> lines = ResultChat.playerHit(hit, 1).stream().map(ChatStyle::simplify).toList();
        assertEquals("Player [Inventory] [Ender] Online ", plain(lines.get(1)));
        assertEquals("&cworld - Inventory", plain(lines.get(2)));
        assertPlayerActions(lines, player);
        assertEquals(NamedTextColor.GREEN, text(lines.get(1), "Player").color());
        assertEquals(NamedTextColor.AQUA, text(lines.get(1), "[Inventory]").color());
        assertEquals(NamedTextColor.GOLD, text(lines.get(1), "[Ender]").color());
        assertTrue(plain(assertInstanceOf(Component.class, lines.get(2).hoverEvent().value())).contains("Slot 100 (Boots)"));
        assertTrue(plain(assertInstanceOf(Component.class, text(lines.get(1), "Player").hoverEvent().value())).contains(player.toString()));
        assertFalse(lines.stream().map(LocalizedResultChatTest::plain).anyMatch(body -> body.contains(player.toString())));
    }

    @Test
    void configuredNavigationLabelsKeepFixedCanonicalPageCommands() throws Exception {
        Language.reloadText("""
                style:
                  neutral-colors: false
                  default-color: '#aaaaaa'
                chat:
                  page-previous: '&b[Prev]'
                  page-next: '&b[Next]'
                  page-first: '&b[First]'
                  page-last: '&b[Last]'
                  page-number: '&aPage {page}'
                  page-current: '&6Current {page}'
                  page-current-hover: '&eThis page'
                  page-hover: '&eGo to {page}'
                  page-disabled: '&8{label}'
                """);
        Component first = ResultChat.pagination(RESULT_ID, 1, 100, "/itemsearch player results");
        assertDisabled(text(first, "[Prev]"));
        assertNull(text(first, "Current 1").clickEvent());
        assertEquals(NamedTextColor.GOLD, text(first, "Current 1").color());
        assertEquals(ClickEvent.runCommand("/itemsearch player results " + RESULT_ID + " 2"), text(first, "Page 2").clickEvent());
        assertEquals(ClickEvent.runCommand("/itemsearch player results " + RESULT_ID + " 100"), text(first, "[Last]").clickEvent());
        assertTrue(plain(assertInstanceOf(Component.class, text(first, "[Next]").hoverEvent().value())).contains("Go to 2"));
    }

    @Test
    void optionalNeutralPaletteKeepsActionsAndNeutralizesHoverColors() throws Exception {
        Language.reloadText("""
                style:
                  neutral-colors: true
                  default-color: '#aaaaaa'
                chat:
                  container-line: '&7{coordinates} {name} {world} {container} {amount} {index}'
                  item-name: '&f{value}'
                  world: '&7{value}'
                  container: '&7{value}'
                  container-count: '&7 ×&f{count}'
                  index: '&f#{index}'
                  container-hover: '&7{hint} {world} {position}'
                  coordinates: '&a{value}'
                  teleport-hint: '&bTeleport'
                """);
        Component line = ChatStyle.simplify(ResultChat.container(container("Sword"), 1, RESULT_ID, true));
        Component coordinates = text(line, "100,64,-200");
        assertEquals(NamedTextColor.WHITE, coordinates.color());
        assertEquals(ClickEvent.runCommand("/itemsearch tp " + RESULT_ID + " 9"), coordinates.clickEvent());
        assertMonochrome(line);
    }

    private static WorldScanner.Hit hit(String name) {
        ItemData item = ItemData.from(Map.of("id", "minecraft:netherite_sword", "count", 16,
                "components", Map.of("minecraft:custom_name", name)), Set.of());
        return new WorldScanner.Hit("world", "CHEST", 100, 64, -200, 5,
                List.of("CHEST[5]", "BLUE_SHULKER_BOX[12]"), item);
    }

    private static ContainerResults.Container container(String name) {
        var hit = hit(name);
        String label = TextNormalizer.plain(hit.item().name()).strip();
        if (label.isBlank()) label = hit.item().material();
        return new ContainerResults.Container(hit, 8, 64, 4, label);
    }

    private static void assertPlayerActions(List<Component> lines, UUID player) {
        List<ClickEvent> events = lines.stream().flatMap(line -> nodes(line).stream())
                .map(Component::clickEvent).filter(Objects::nonNull).toList();
        assertEquals(List.of(ClickEvent.copyToClipboard(player.toString()),
                ClickEvent.runCommand("/itemsearch player inventory " + player),
                ClickEvent.runCommand("/itemsearch player ender " + player)), events);
        assertTrue(events.stream().filter(event -> event.action() == ClickEvent.Action.RUN_COMMAND)
                .noneMatch(event -> event.value().contains("/itemsearch tp")));
    }

    private static void assertDisabled(Component component) {
        assertEquals(NamedTextColor.DARK_GRAY, component.color());
        assertNull(component.clickEvent());
        assertNull(component.hoverEvent());
    }

    private static void assertMonochrome(Component component) {
        for (Component node : nodes(component)) {
            if (node.color() != null) assertTrue(Set.of(NamedTextColor.GRAY, NamedTextColor.DARK_GRAY,
                    NamedTextColor.WHITE).contains(node.color()), "Unexpected color: " + node.color());
            assertNotEquals(TextDecoration.State.TRUE, node.decoration(TextDecoration.BOLD));
            if (node.hoverEvent() != null && node.hoverEvent().value() instanceof Component hover) assertMonochrome(hover);
        }
    }

    private static Component text(Component component, String expected) {
        return nodes(component).stream().filter(node -> node instanceof TextComponent text && text.content().equals(expected))
                .findFirst().orElseThrow(() -> new AssertionError("Missing text component: " + expected));
    }

    private static List<Component> nodes(Component component) {
        List<Component> result = new ArrayList<>(); result.add(component);
        for (Component child : component.children()) result.addAll(nodes(child));
        return result;
    }

    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }
}
