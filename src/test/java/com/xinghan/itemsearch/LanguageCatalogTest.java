package com.xinghan.itemsearch;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class LanguageCatalogTest {
    // Examine the first argument, including literal ternary branches, without counting constructed keys.
    private static final Pattern MESSAGE_CALL = Pattern.compile(
            "\\bLanguage\\.(tr|component|plain|lines|components|bool)\\s*\\(");
    private static final Pattern KEY_NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern PLACEHOLDER_NAME = Pattern.compile("[A-Za-z0-9_.-]+");

    @Test
    void everyLiteralMessageReferenceHasACompatibleBundledDefault() throws Exception {
        YamlConfiguration yaml = catalog();
        int references = 0;
        try (var files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (MessageReference reference : references(Files.readString(file, StandardCharsets.UTF_8))) {
                    references++;
                    String method = reference.method(), key = reference.key();
                    Object value = yaml.get(key);
                    String location = file + ": Language." + method + "(\"" + key + "\")";
                    assertNotNull(value, "Missing bundled language key at " + location);
                    if (method.equals("bool")) {
                        assertInstanceOf(Boolean.class, value, "Boolean language key required at " + location);
                    } else {
                        // The API accepts either one template or a list joined into one message.
                        assertTrue(value instanceof String || value instanceof List<?> list
                                        && list.stream().allMatch(String.class::isInstance),
                                "String or string-list language key required at " + location);
                    }
                }
            }
        }
        assertTrue(references >= 20, "The source scan must find the plugin's real message references");
        assertTrue(references("Language.tr(\"editor.\" + command)").isEmpty());
        assertTrue(references("Language.component(key)").isEmpty());
        assertEquals(List.of(new MessageReference("tr", "first.key"), new MessageReference("tr", "second.key")),
                references("Language.tr(condition.get() ? \"first.key\" : \"second.key\", \"argument\", value)"));
    }

    @Test
    void bundledDefaultsAreValidUtf8AndPreserveConfiguredValidationAndProgressTemplates() throws Exception {
        YamlConfiguration yaml = catalog();
        Language.resetDefaults();
        try {
            for (String key : List.of("command.missing-mode", "reference.stale", "scan.world-progress",
                    "scan.progress", "player-scan.progress")) {
                String configured = assertInstanceOf(String.class, yaml.get(key), "Expected message template: " + key);
                assertEquals(configured, Language.tr(key), "Bundled wording must be preserved: " + key);
                String expected = configured.replace("{world}", "测试世界")
                        .replace("{chunks}", "73").replace("{regions}", "4").replace("{containers}", "6")
                        .replace("{items}", "17").replace("{stacks}", "5").replace("{units}", "9")
                        .replace("{players}", "2").replace("{files}", "3");
                assertEquals(expected, Language.tr(key, "world", "测试世界", "chunks", 73, "regions", 4,
                        "containers", 6, "items", 17, "stacks", 5, "units", 9, "players", 2, "files", 3),
                        "Template values must expand independently of the translation wording: " + key);
            }
        } finally { Language.resetDefaults(); }
    }

    @Test
    void bundledMessagePlaceholdersAreBalancedAndHaveSupportedNames() throws Exception {
        YamlConfiguration yaml = catalog();
        for (var entry : yaml.getValues(true).entrySet()) {
            Object value = entry.getValue();
            if (value instanceof ConfigurationSection) continue;
            if (value instanceof String text) assertValidPlaceholders(entry.getKey(), text);
            if (value instanceof List<?> lines) {
                for (Object line : lines) {
                    assertInstanceOf(String.class, line, "Message lists contain only strings: " + entry.getKey());
                    assertValidPlaceholders(entry.getKey(), (String) line);
                }
            }
        }
    }

    private static void assertValidPlaceholders(String key, String text) {
        Matcher placeholders = PLACEHOLDER.matcher(text);
        while (placeholders.find()) {
            assertTrue(PLACEHOLDER_NAME.matcher(placeholders.group(1)).matches(),
                    "Unsupported placeholder in " + key + ": " + placeholders.group());
        }
        String outsidePlaceholders = PLACEHOLDER.matcher(text).replaceAll("");
        assertFalse(outsidePlaceholders.contains("{") || outsidePlaceholders.contains("}"),
                "Unbalanced placeholder braces in " + key);
    }

    private record MessageReference(String method, String key) { }

    private static List<MessageReference> references(String source) {
        List<MessageReference> references = new ArrayList<>();
        Matcher calls = MESSAGE_CALL.matcher(source);
        while (calls.find()) {
            List<String> literalKeys = new ArrayList<>();
            int depth = 0;
            boolean constructed = false;
            for (int i = calls.end(); i < source.length(); i++) {
                char character = source.charAt(i);
                if (character == '\'' || character == '"') {
                    int start = i + 1;
                    char quote = character;
                    for (i++; i < source.length() && source.charAt(i) != quote; i++) {
                        if (source.charAt(i) == '\\') i++;
                    }
                    if (i >= source.length()) break;
                    if (quote == '"' && depth == 0) {
                        String literal = source.substring(start, i);
                        if (KEY_NAME.matcher(literal).matches()) literalKeys.add(literal);
                    }
                } else if (character == '(') depth++;
                else if (character == ')') {
                    if (depth == 0) break;
                    depth--;
                } else if (character == ',' && depth == 0) break;
                else if (character == '+' && depth == 0) constructed = true;
            }
            if (!constructed) {
                for (String key : literalKeys) references.add(new MessageReference(calls.group(1), key));
            }
        }
        return references;
    }

    private static YamlConfiguration catalog() throws Exception {
        byte[] bytes;
        try (InputStream resource = LanguageCatalogTest.class.getResourceAsStream("/lang.yml")) {
            assertNotNull(resource, "The plugin must package its default lang.yml");
            bytes = resource.readAllBytes();
        }
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        assertFalse(yaml.getKeys(false).isEmpty(), "The default language catalog must not be empty");
        return yaml;
    }
}
