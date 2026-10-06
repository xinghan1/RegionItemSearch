package com.xinghan.itemsearch.item;

import com.xinghan.itemsearch.Compat;
import com.google.gson.Gson;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Immutable search view of an item read directly from chunk NBT. */
public final class ItemData {
    private static final Gson JSON = new Gson();
    private final String material;
    private final String name;
    private final List<String> lore;
    private final String mmoType;
    private final String mmoId;
    private final int count;
    private final String fingerprint;

    private ItemData(String material, String name, List<String> lore, String mmoType,
                     String mmoId, int count, String fingerprint) {
        this.material = material;
        this.name = name;
        this.lore = Compat.listCopy(lore);
        this.mmoType = mmoType;
        this.mmoId = mmoId;
        this.count = count;
        this.fingerprint = fingerprint;
    }

    public static ItemData from(Map<String, Object> raw, Set<String> ignoredKeys) {
        return from(raw, ignoredKeys, number(raw.get("DataVersion"), raw.containsKey("Damage") ? 0 : Integer.MAX_VALUE));
    }

    /** The containing chunk/player DataVersion disambiguates pre-1.12 red beds. */
    public static ItemData from(Map<String, Object> raw, Set<String> ignoredKeys, int dataVersion) {
        Map<String, Object> tag = compound(raw.get("tag"));
        Map<String, Object> components = compound(raw.get("components"));
        Map<String, Object> display = compound(tag.get("display"));
        Object customName = component(components, "custom_name");
        if (customName == null) customName = display.get("Name");
        if (customName == null) customName = component(components, "item_name");
        Object rawLore = component(components, "lore");
        if (rawLore == null) rawLore = display.get("Lore");
        List<?> loreValues = list(rawLore);
        List<String> lore = loreValues.stream().map(TextNormalizer::plain).collect(java.util.stream.Collectors.toList());
        boolean legacy = raw.containsKey("Damage") || raw.get("id") instanceof Number;
        int damage = number(raw.get("Damage"), 0);
        int effectiveVersion = number(raw.get("DataVersion"), dataVersion);
        if (legacy && effectiveVersion == Integer.MAX_VALUE) effectiveVersion = 0;
        String material = LegacyItems.normalizedMaterial(raw.get("id"), damage, legacy);
        if (legacy && material.equals("WHITE_BED") && effectiveVersion < 1139) material = "RED_BED";
        if (material.startsWith("LEGACY_SPAWN_EGG_")) {
            Object entityId = compound(tag.get("EntityTag")).get("id");
            if (entityId instanceof String) material = LegacyItems.spawnEggName((String) entityId);
        }
        int count = number(raw.containsKey("count") ? raw.get("count") : raw.get("Count"), 1);

        Map<String, Object> custom = new LinkedHashMap<>(compound(component(components, "custom_data")));
        // Old item tags contain custom data alongside the pre-component vanilla fields.
        tag.forEach((key, value) -> {
            if (!Compat.set("display", "CustomModelData", "Enchantments", "ench", "StoredEnchantments",
                    "AttributeModifiers", "Damage", "BlockEntityTag").contains(key)) custom.putIfAbsent(key, value);
        });
        String type = identity(custom, true);
        String id = identity(custom, false);

        Map<String, Object> stable = new TreeMap<>();
        stable.put("material", material);
        // Pre-1.9 potion effects live in item Damage instead of tag.Potion.
        if (legacy && effectiveVersion < 169 && (material.equals("POTION") || material.equals("SPLASH_POTION"))) {
            stable.put("legacy_potion", damage & 16383);
        }
        stable.put("name", styledText(customName));
        stable.put("lore", loreValues.stream().map(ItemData::styledText).collect(java.util.stream.Collectors.toList()));
        Object model = component(components, "custom_model_data");
        stable.put("model", customModelData(model == null ? tag.get("CustomModelData") : model));
        Object enchantments = component(components, "enchantments");
        if (enchantments == null) enchantments = tag.containsKey("Enchantments") ? tag.get("Enchantments") : tag.get("ench");
        stable.put("enchantments", enchantments(enchantments));
        Object stored = component(components, "stored_enchantments");
        stable.put("stored_enchantments", enchantments(stored == null ? tag.get("StoredEnchantments") : stored));
        Object attributes = component(components, "attribute_modifiers");
        stable.put("attributes", unordered(attributes == null ? tag.get("AttributeModifiers") : attributes));
        stable.put("mmo_type", type);
        stable.put("mmo_id", id);
        stable.put("custom", canonical(filter(custom, "", ignoredKeys == null ? Compat.set() : ignoredKeys), true));
        return new ItemData(material, TextNormalizer.plain(customName), lore, type, id,
                Math.max(0, count), JSON.toJson(canonical(stable, false)));
    }

    public String material() { return material; }
    public String name() { return name; }
    public List<String> lore() { return lore; }
    public String mmoType() { return mmoType; }
    public String mmoId() { return mmoId; }
    public int count() { return count; }
    public String fingerprint() { return fingerprint; }

    private static String material(Object raw) {
        String material = raw == null ? "AIR" : String.valueOf(raw);
        if (material.regionMatches(true, 0, "minecraft:", 0, 10)) material = material.substring(10);
        return material.toUpperCase(Locale.ROOT);
    }

    private static Object component(Map<String, Object> components, String key) {
        return components.containsKey("minecraft:" + key) ? components.get("minecraft:" + key) : components.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> compound(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Compat.map();
    }

    private static List<?> list(Object value) {
        Object decoded = TextNormalizer.decode(value);
        return decoded instanceof List<?> ? (List<?>) decoded : Compat.list();
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static String identity(Object value, boolean type) {
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                String suffix = key.substring(key.lastIndexOf(':') + 1);
                boolean matches = suffix.equals(type ? "mmoitems_item_type" : "mmoitems_item_id")
                        || key.equals(type ? "mmoitems:type" : "mmoitems:id")
                        || key.equals(type ? "mmoitems:item_type" : "mmoitems:item_id");
                if (matches && entry.getValue() != null) return Compat.strip(TextNormalizer.plain(entry.getValue()));
            }
            for (Object nested : map.values()) {
                String found = identity(nested, type);
                if (!Compat.isBlank(found)) return found;
            }
        } else if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            for (Object nested : list) {
                String found = identity(nested, type);
                if (!Compat.isBlank(found)) return found;
            }
        }
        return "";
    }

    private static Object styledText(Object original) {
        Object value = TextNormalizer.decode(original);
        if (value == null) return "";
        if (value instanceof String) {
            String string = (String) value;
            // The two legacy color prefixes represent the same styling.
            return Compat.map("text", string.replaceAll("(?i)&(?=[0-9a-fk-orx])", "§"));
        }
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, entry) -> {
                String property = String.valueOf(key);
                Object decoded = TextNormalizer.decode(entry);
                if (Compat.set("bold", "italic", "underlined", "strikethrough", "obfuscated").contains(property)
                        && decoded instanceof Number) decoded = ((Number) decoded).byteValue() != 0;
                result.put(property, Compat.set("extra", "with").contains(property)
                        ? styledList(decoded) : canonical(decoded, false));
            });
            return result;
        }
        if (value instanceof List<?>) {
            List<?> values = (List<?>) value;
            if (values.size() == 1) return styledText(values.get(0));
            return values.stream().map(ItemData::styledText).collect(java.util.stream.Collectors.toList());
        }
        return canonical(value, false);
    }

    private static Object styledList(Object value) {
        return list(value).stream().map(ItemData::styledText).collect(java.util.stream.Collectors.toList());
    }

    private static Object customModelData(Object value) {
        if (value == null) return null;
        // DataFixer converts the old integer into the modern floats list.
        if (value instanceof Number) {
            Number number = (Number) value;
            return Compat.map("floats", Compat.list(canonical(number, false)),
                    "flags", Compat.list(), "strings", Compat.list(), "colors", Compat.list());
        }
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<String, Object> result = new TreeMap<>();
            for (String field : Compat.list("floats", "flags", "strings", "colors")) {
                Object entries = map.get(field);
                if (field.equals("flags") && entries instanceof List<?>) {
                    List<?> list = (List<?>) entries;
                    result.put(field, list.stream().map(entry -> entry instanceof Number
                            ? ((Number) entry).byteValue() != 0 : entry).collect(java.util.stream.Collectors.toList()));
                } else result.put(field, entries == null ? Compat.list() : canonical(entries, false));
            }
            map.forEach((key, entry) -> result.putIfAbsent(String.valueOf(key), canonical(entry, false)));
            return result;
        }
        return canonical(value, false);
    }

    private static Object enchantments(Object original) {
        if (original == null) return Compat.map();
        if (original instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) original;
            Object levels = map.get("levels");
            Map<?, ?> source = levels instanceof Map<?, ?> ? (Map<?, ?>) levels : map;
            Map<String, Object> result = new TreeMap<>();
            source.forEach((key, value) -> {
                if (!String.valueOf(key).equals("show_in_tooltip")) result.put(namespace(String.valueOf(key)), canonical(value, false));
            });
            return result;
        }
        Map<String, Object> result = new TreeMap<>();
        for (Object entry : list(original)) {
            Map<String, Object> enchant = compound(entry);
            if (enchant.containsKey("id")) result.put(LegacyItems.normalizedEnchantment(enchant.get("id")), canonical(enchant.get("lvl"), false));
        }
        return result;
    }

    private static String namespace(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static Object unordered(Object value) {
        if (value == null) return Compat.list();
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.containsKey("modifiers")) return unordered(map.get("modifiers"));
            Map<String, Object> normalized = new TreeMap<>();
            map.forEach((key, entry) -> normalized.put(String.valueOf(key),
                    String.valueOf(key).equals("modifiers") ? unordered(entry) : canonical(entry, false)));
            return normalized;
        }
        if (value instanceof Collection<?>) {
            Collection<?> collection = (Collection<?>) value;
            List<Object> values = new ArrayList<>();
            collection.forEach(entry -> values.add(canonical(entry, false)));
            values.sort(Comparator.comparing(JSON::toJson));
            return values;
        }
        return canonical(value, false);
    }

    private static Object filter(Object value, String path, Set<String> ignored) {
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<String, Object> result = new TreeMap<>();
            map.forEach((rawKey, entry) -> {
                String key = String.valueOf(rawKey);
                String entryPath = path.isEmpty() ? key : path + "." + key;
                if (!ignored(key, entryPath, ignored)) result.put(key, filter(entry, entryPath, ignored));
            });
            return result;
        }
        if (value instanceof List<?>) return ((List<?>) value).stream().map(entry -> filter(entry, path, ignored)).collect(java.util.stream.Collectors.toList());
        return value;
    }

    private static boolean ignored(String key, String path, Set<String> ignored) {
        String normalizedPath = path.toLowerCase(Locale.ROOT).replace('/', '.');
        String normalizedKey = key.toLowerCase(Locale.ROOT);
        String suffix = normalizedKey.substring(normalizedKey.lastIndexOf(':') + 1);
        for (String ignore : ignored) {
            String rule = ignore.toLowerCase(Locale.ROOT).replace('/', '.');
            if (normalizedKey.equals(rule) || suffix.equals(rule) || normalizedPath.equals(rule)
                    || normalizedPath.endsWith("." + rule)) return true;
        }
        return false;
    }

    private static Object canonical(Object value, boolean typed) {
        if (value == null) return null;
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, entry) -> result.put(String.valueOf(key), canonical(entry, typed)));
            return result;
        }
        if (value instanceof Collection<?>) return ((Collection<?>) value).stream().map(entry -> canonical(entry, typed)).collect(java.util.stream.Collectors.toList());
        if (value.getClass().isArray()) {
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) values.add(canonical(Array.get(value, i), typed));
            return typed ? Compat.map("$array", value.getClass().getComponentType().getName(), "values", values) : values;
        }
        if (value instanceof Number) {
            Number number = (Number) value;
            String normalized;
            try { normalized = new BigDecimal(number.toString()).stripTrailingZeros().toPlainString(); }
            catch (NumberFormatException ignored) { normalized = number.toString(); }
            return typed ? Compat.map("$number", number.getClass().getSimpleName(), "value", normalized) : normalized;
        }
        return value;
    }
}
