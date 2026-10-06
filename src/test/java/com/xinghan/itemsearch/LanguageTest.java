package com.xinghan.itemsearch;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class LanguageTest {
    @TempDir Path directory;
    @BeforeEach void startWithDefaults() { Language.resetDefaults(); }
    @AfterEach void restore() { Language.resetDefaults(); }

    @Test void missingKeysUseBundledDefaultsAndAnEmptyStringCanHideAMessage() throws Exception {
        String bundledPermission = Language.plain("messages.permission-denied");
        List<String> bundledHelp = Language.lines("help.main");
        Language.reloadText("messages:\n  permission-denied: 'Previous override'\nhelp:\n  main: ['Previous help']\n");
        assertEquals("Previous override", Language.plain("messages.permission-denied"));
        assertEquals(List.of("Previous help"), Language.lines("help.main"));

        Language.reloadText("messages:\n  stop-idle: ''\n");
        assertEquals("", Language.tr("messages.stop-idle"));
        assertEquals(bundledPermission, Language.plain("messages.permission-denied"));
        assertEquals(bundledHelp, Language.lines("help.main"));
    }

    @Test void utf8ReloadChangesMessagesWithoutReplacingTheBundledFallback() throws Exception {
        String bundledNoName = Language.tr("messages.template-no-name");
        Path file = directory.resolve("lang.yml");
        Files.writeString(file, "messages:\n  stop-idle: '&f没有查询任务'\n", StandardCharsets.UTF_8);
        Language.reload(file);
        assertEquals("没有查询任务", Language.plain("messages.stop-idle"));
        assertEquals(NamedTextColor.WHITE, Language.component("messages.stop-idle").compact().color());
        assertEquals(bundledNoName, Language.tr("messages.template-no-name"));
    }

    @Test void malformedYamlKeepsThePreviousEffectiveLanguage() throws Exception {
        Language.reloadText("messages:\n  stop-idle: 'Idle'\n");
        assertThrows(IOException.class, () -> Language.reloadText("messages: [broken\n"));
        assertEquals("Idle", Language.tr("messages.stop-idle"));
    }

    @Test void wrongTypesKeepThePreviousSnapshotAndRejectMixedLoreLists() throws Exception {
        Language.reloadText("messages:\n  stop-idle: 'Idle'\n");
        assertThrows(IOException.class, () -> Language.reloadText("messages:\n  stop-idle: [1, 2]\n"));
        assertThrows(IOException.class, () -> Language.reloadText("messages:\n  stop-idle: false\n"));
        assertThrows(IOException.class, () -> Language.reloadText("help:\n  main: ['Help', 3]\n"));
        assertEquals("Idle", Language.tr("messages.stop-idle"));
    }

    @Test void parametersPreserveDollarBackslashAndBracesWithoutRecursiveExpansion() throws Exception {
        Language.reloadText("messages:\n  search-failed: '{reason} / {reason}'\n");
        String reason = "$5\\data\\{reason}";
        assertEquals(reason + " / " + reason, Language.tr("messages.search-failed", "reason", reason));
        assertEquals(reason + " / " + reason, Language.plain("messages.search-failed", "reason", reason));
        assertThrows(IllegalArgumentException.class, () -> Language.tr("messages.search-failed", "reason"));
    }

    @Test void reorderedInteractivePlaceholdersRetainEventsAndLiteralItemNames() throws Exception {
        Language.reloadText("chat:\n  header: '&7{player} | {button}'\n");
        Component button = Component.text("[9]").clickEvent(ClickEvent.runCommand("/itemsearch results token 9"))
                .hoverEvent(HoverEvent.showText(Component.text("Go")));
        Component output = Language.component("chat.header", "player", Component.text("&cLiteral"), "button", button);
        assertEquals("&cLiteral | [9]", text(output));
        assertTrue(nodes(output).stream().anyMatch(node -> ClickEvent.runCommand("/itemsearch results token 9").equals(node.clickEvent())));
        assertTrue(nodes(output).stream().anyMatch(node -> node.hoverEvent() != null
                && node.hoverEvent().value() instanceof Component hover && text(hover).equals("Go")));
    }

    @Test void legacySectionAmpersandAndHexColorsAreSupportedWithoutForcingGray() throws Exception {
        Language.reloadText("style:\n  neutral-colors: false\n  default-color: '#aaaaaa'\nmessages:\n  stop-idle: '&#12AB34中文 §f完成'\n");
        Component content = ChatStyle.simplify(Language.component("messages.stop-idle"));
        assertEquals("中文 完成", text(content));
        assertTrue(nodes(content).stream().anyMatch(node -> TextColor.color(0x12ab34).equals(node.color())));
        assertTrue(nodes(content).stream().anyMatch(node -> NamedTextColor.WHITE.equals(node.color())));
        assertEquals("中文 完成", Language.plain("messages.stop-idle"));
    }

    @Test void multilineHelpAndGuiLoreUseTheSameSnapshotAndParameterRules() throws Exception {
        Language.reloadText("help:\n  main: ['&f{player}', '&7Count {count}']\n");
        assertEquals(List.of("&fAlex", "&7Count 8"), Language.lines("help.main", "player", "Alex", "count", 8));
        assertEquals(List.of("Alex", "Count 8"), Language.components("help.main", "player", "Alex", "count", 8).stream().map(LanguageTest::text).toList());
    }

    @Test void fileSizeLimitsRejectReloadWithoutDiscardingCurrentSettings() throws Exception {
        Language.reloadText("messages:\n  stop-idle: 'Current'\n");
        Path file = directory.resolve("lang.yml");
        Files.writeString(file, "#".repeat(1_048_577), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> Language.reload(file));
        assertEquals("Current", Language.tr("messages.stop-idle"));
    }

    @Test void concurrentScannersObserveWholeImmutableLoreSnapshots() throws Exception {
        Language.reloadText("help:\n  main: ['A {count}', 'A {count}']\n");
        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 2000; i++) {
                List<String> lines = Language.lines("help.main", "count", i);
                assertEquals(2, lines.size());
                assertEquals(lines.get(0), lines.get(1));
            }
        });
        for (int i = 0; i < 30; i++) Language.reloadText("help:\n  main: ['" + (i % 2 == 0 ? "B" : "A") + " {count}', '" + (i % 2 == 0 ? "B" : "A") + " {count}']\n");
        reader.join();
    }

    private static String text(Component value) { return PlainTextComponentSerializer.plainText().serialize(value); }
    private static List<Component> nodes(Component root) {
        List<Component> result = new ArrayList<>(); result.add(root);
        for (Component child : root.children()) result.addAll(nodes(child));
        return result;
    }
}

