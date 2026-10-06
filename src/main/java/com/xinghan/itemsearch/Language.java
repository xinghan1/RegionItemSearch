package com.xinghan.itemsearch;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable language snapshots can be read by scan workers while the main thread reloads a new file. */
public final class Language {
    private static final int MAX_FILE_BYTES = 1_048_576;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexCharacter('#').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Map<String, Object> DEFAULTS = defaults();
    private static final AtomicReference<Map<String, Object>> CURRENT = new AtomicReference<>(DEFAULTS);

    private Language() { }

    /** A malformed file never replaces the previous valid snapshot; absent keys use shipped defaults. */
    public static void reload(Path file) throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("lang.yml exceeds 1 MiB");
        reloadText(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    static void reloadText(String yaml) throws IOException {
        Map<String, Object> overrides = parse(yaml);
        Map<String, Object> next = new LinkedHashMap<>(DEFAULTS);
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            Object fallback = DEFAULTS.get(entry.getKey());
            if (fallback == null) continue; // Keep future/custom keys in the file, without treating them as messages.
            Object value = entry.getValue();
            if (value == null) continue;
            boolean valid = fallback instanceof String && value instanceof String
                    || fallback instanceof List<?> && value instanceof List<?>
                    || fallback instanceof Boolean && value instanceof Boolean
                    || fallback instanceof Number && value instanceof Number;
            if (!valid) throw new IOException("lang.yml: wrong value type for " + entry.getKey());
            next.put(entry.getKey(), value);
        }
        CURRENT.set(Collections.unmodifiableMap(new LinkedHashMap<String, Object>(next)));
    }

    static void resetDefaults() { CURRENT.set(DEFAULTS); }

    public static String tr(String key, Object... pairs) {
        return substitute(template(key, CURRENT.get()), parameters(pairs));
    }

    public static List<String> lines(String key, Object... pairs) {
        Map<String, Object> snapshot = CURRENT.get();
        Map<String, Object> params = parameters(pairs);
        return Collections.unmodifiableList(templates(key, snapshot).stream().map(line -> substitute(line, params)).collect(Collectors.toList()));
    }

    /** Components used as placeholders retain their existing click/hover actions, even when reordered. */
    public static Component component(String key, Object... pairs) {
        return expand(template(key, CURRENT.get()), parameters(pairs));
    }

    public static List<Component> components(String key, Object... pairs) {
        Map<String, Object> snapshot = CURRENT.get();
        Map<String, Object> params = parameters(pairs);
        return Collections.unmodifiableList(templates(key, snapshot).stream().map(line -> expand(line, params)).collect(Collectors.toList()));
    }

    public static String plain(String key, Object... pairs) { return PLAIN.serialize(component(key, pairs)); }

    public static Component render(String text) {
        return LEGACY.deserialize(text == null ? "" : text.replace('§', '&'));
    }

    public static boolean bool(String key, boolean fallback) {
        Object value = CURRENT.get().get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static Component expand(String template, Map<String, Object> params) {
        return render(template).replaceText(TextReplacementConfig.builder().match(PLACEHOLDER)
                .replacement((match, builder) -> {
                    Object value = params.get(match.group(1));
                    return value == null ? builder.build() : value instanceof Component ? (Component) value : render(String.valueOf(value));
                }).build());
    }

    private static String substitute(String template, Map<String, Object> params) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            Object value = params.get(matcher.group(1));
            String replacement = value == null ? matcher.group()
                    : value instanceof Component ? PLAIN.serialize((Component) value) : String.valueOf(value);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        return matcher.appendTail(result).toString();
    }

    private static Map<String, Object> parameters(Object... pairs) {
        Objects.requireNonNull(pairs, "parameters");
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("Language parameters must be key/value pairs");
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (!(pairs[i] instanceof String)) throw new IllegalArgumentException("Language parameter names must be strings");
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    private static String template(String key, Map<String, Object> values) {
        Objects.requireNonNull(key, "key");
        Object value = values.get(key);
        if (value instanceof List<?>) return String.join("\n", ((List<?>) value).stream().map(String::valueOf).collect(Collectors.toList()));
        return value == null ? "[" + key + "]" : String.valueOf(value);
    }

    private static List<String> templates(String key, Map<String, Object> values) {
        Object value = values.get(key);
        if (value instanceof List<?>) return ((List<?>) value).stream().map(String::valueOf).collect(Collectors.toList());
        return Collections.singletonList(template(key, values));
    }

    private static Map<String, Object> defaults() {
        try (InputStream resource = Language.class.getResourceAsStream("/lang.yml")) {
            if (resource == null) throw new IOException("Bundled lang.yml is missing");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = resource.read(buffer)) >= 0) if (length != 0) bytes.write(buffer, 0, length);
            return parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }

    private static Map<String, Object> parse(String text) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(text); }
        catch (InvalidConfigurationException | RuntimeException invalid) { throw new IOException("Invalid lang.yml: " + invalid.getMessage(), invalid); }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : yaml.getValues(true).entrySet()) {
            Object value = entry.getValue();
            if (value == null || value instanceof ConfigurationSection) continue;
            if (value instanceof List<?>) {
                List<?> list = (List<?>) value;
                if (list.stream().anyMatch(item -> !(item instanceof String))) throw new IOException("lang.yml: list must contain strings at " + entry.getKey());
                values.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<Object>(list)));
            } else if (value instanceof String || value instanceof Boolean || value instanceof Number) values.put(entry.getKey(), value);
            else throw new IOException("lang.yml: unsupported value at " + entry.getKey());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(values));
    }
}

