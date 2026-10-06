package com.xinghan.itemsearch.item;

import com.xinghan.itemsearch.Compat;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Converts both legacy JSON text and modern NBT text components into searchable text. */
public final class TextNormalizer {
    private static final Pattern HEX = Pattern.compile("(?i)(?:[§&]x(?:[§&][0-9a-f]){6}|[§&]#[0-9a-f]{6})");
    private static final Pattern COLOR = Pattern.compile("(?i)[§&][0-9a-fk-or]");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    private TextNormalizer() {}

    public static String plain(Object value) {
        return stripColors(text(decode(value)));
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(plain(value), Normalizer.Form.NFKC);
        return Compat.strip(SPACE.matcher(normalized).replaceAll(" ")).toLowerCase(Locale.ROOT);
    }

    static String stripColors(String value) {
        return COLOR.matcher(HEX.matcher(value).replaceAll("")).replaceAll("");
    }

    /** NBT's heterogeneous lists use an empty-key compound to wrap primitive elements. */
    static Object decode(Object value) {
        if (value instanceof Map<?, ?> && ((Map<?, ?>) value).size() == 1 && ((Map<?, ?>) value).containsKey("")) {
            Map<?, ?> map = (Map<?, ?>) value;
            return decode(map.get(""));
        }
        if (value instanceof String) {
            String string = (String) value;
            String trimmed = Compat.strip(string);
            if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                    || (trimmed.startsWith("[") && trimmed.endsWith("]"))
                    || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
                try {
                    return fromJson(JsonParser.parseString(trimmed));
                } catch (RuntimeException ignored) {
                    // Literal names may contain braces; malformed JSON remains literal text.
                }
            }
        }
        return value;
    }

    private static Object fromJson(JsonElement json) {
        if (json.isJsonNull()) return "";
        if (json.isJsonPrimitive()) {
            if (json.getAsJsonPrimitive().isBoolean()) return json.getAsBoolean();
            if (json.getAsJsonPrimitive().isNumber()) return json.getAsBigDecimal();
            return json.getAsString();
        }
        if (json.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            json.getAsJsonArray().forEach(element -> list.add(fromJson(element)));
            return list;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        json.getAsJsonObject().entrySet().forEach(entry -> map.put(entry.getKey(), fromJson(entry.getValue())));
        return map;
    }

    private static String text(Object original) {
        Object value = decode(original);
        if (value == null) return "";
        if (value instanceof String) return (String) value;
        if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            StringBuilder result = new StringBuilder();
            list.forEach(element -> result.append(text(element)));
            return result.toString();
        }
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            StringBuilder result = new StringBuilder();
            if (map.containsKey("text")) result.append(text(map.get("text")));
            else if (map.containsKey("translate")) {
                result.append(text(map.containsKey("fallback") ? map.get("fallback") : map.get("translate")));
                Object arguments = map.get("with");
                if (arguments instanceof List<?>) {
                    List<?> list = (List<?>) arguments;
                    for (Object argument : list) {
                        String argumentText = text(argument);
                        int placeholder = result.indexOf("%s");
                        if (placeholder >= 0) result.replace(placeholder, placeholder + 2, argumentText);
                        else result.append(' ').append(argumentText);
                    }
                }
            } else if (map.containsKey("keybind")) result.append(text(map.get("keybind")));
            else if (map.containsKey("selector")) result.append(text(map.get("selector")));
            else if (map.containsKey("nbt")) result.append(text(map.get("nbt")));
            else if (map.get("score") instanceof Map<?, ?>) {
                Map<?, ?> score = (Map<?, ?>) map.get("score");
                result.append(text(score.containsKey("value") ? score.get("value") : score.get("name")));
            }
            if (map.containsKey("extra")) result.append(text(map.get("extra")));
            return result.toString();
        }
        return String.valueOf(value);
    }
}
