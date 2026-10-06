package com.xinghan.itemsearch.item;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LegacyItemCompatibilityTest {
    @Test void numericIdsAndDamageResolveVanillaVariants() {
        assertEquals("RED_WOOL", legacy(35, 14).material());
        assertEquals("GRANITE", legacy(1, 1).material());
        assertEquals("DARK_OAK_PLANKS", legacy(5, 5).material());
        assertEquals("CHARCOAL", legacy(263, 1).material());
        assertEquals("ENCHANTED_GOLDEN_APPLE", legacy(322, 1).material());
        assertEquals("PLAYER_HEAD", legacy(397, 3).material());
        assertEquals("CHIPPED_ANVIL", legacy(145, 1).material());
        assertEquals("BLUE_SHULKER_BOX", legacy(230, 0).material());
        assertEquals("DIAMOND_SHOVEL", legacy(277, 157).material());
        assertEquals("MUSIC_DISC_11", legacy(2266, 0).material());
        assertEquals("SPAWNER", legacy(52, 0).material());
        assertEquals("ENCHANTING_TABLE", legacy(116, 0).material());
        assertEquals("REDSTONE_LAMP", legacy(124, 0).material());
        assertEquals("DRAGON_BREATH", legacy(437, 0).material());
    }

    @Test void legacyMinecraftNamesAndBukkitNamesRetainTheirDistinctRegistryMeanings() {
        assertEquals("BRICK", old("minecraft:brick", 0).material());
        assertEquals("BRICKS", legacy(45, 0).material());
        assertEquals("MAP", old("minecraft:map", 0).material());
        assertEquals("FILLED_MAP", legacy(358, 0).material());
        assertEquals("MELON_SLICE", old("minecraft:melon", 0).material());
        assertEquals("NETHER_BRICK", old("minecraft:netherbrick", 0).material());
        assertEquals("NETHER_BRICKS", legacy(112, 0).material());
        assertEquals("RED_WOOL", old("minecraft:wool", 14).material());
        assertEquals("GOLDEN_SWORD", old("LEGACY_GOLD_SWORD", 20).material());
        assertEquals("DEAD_BUSH", old("minecraft:deadbush", 0).material());
        assertEquals("POWERED_RAIL", old("minecraft:golden_rail", 0).material());
        assertEquals("NOTE_BLOCK", old("minecraft:noteblock", 0).material());
        assertEquals("JACK_O_LANTERN", old("minecraft:lit_pumpkin", 0).material());
        assertEquals("OAK_PRESSURE_PLATE", old("minecraft:wooden_pressure_plate", 0).material());
        assertEquals("SLIME_BLOCK", old("minecraft:slime", 0).material());
        assertEquals("MUSIC_DISC_13", old("minecraft:record_13", 0).material());
        assertEquals("MUSIC_DISC_CAT", old("minecraft:record_cat", 0).material());
        assertEquals("COBBLESTONE_STAIRS", old("minecraft:stone_stairs", 0).material());
        assertEquals("INFESTED_STONE_BRICKS", old("minecraft:monster_egg", 2).material());
        assertEquals("ZOMBIFIED_PIGLIN_SPAWN_EGG", old("minecraft:spawn_egg", 57).material());
        assertEquals("CARROT", old("minecraft:carrot", 0).material());
        assertEquals("CARROTS", legacy(141, 0).material());
    }

    @Test void modernMaterialsAreNotReinterpretedAsLegacyAliasesOrSlabMetadata() {
        for (String id : List.of("brick", "map", "melon", "nether_brick", "stone_slab", "grass")) {
            assertEquals(id.toUpperCase(java.util.Locale.ROOT), ItemData.from(
                    Map.of("id", "minecraft:" + id, "count", 1), Collections.emptySet()).material());
        }
    }

    @Test void oldNumericEnchantmentsMatchNamedEnchantmentsAndDurabilityIsIgnored() {
        Map<String, Object> old = raw((short) 276, 52);
        old.put("tag", Map.of("ench", List.of(Map.of("id", (short) 16, "lvl", (short) 5))));
        Map<String, Object> modern = Map.of("id", "minecraft:diamond_sword", "count", 1,
                "components", Map.of("minecraft:enchantments", Map.of("minecraft:sharpness", 5)));
        assertEquals(ItemData.from(modern, Collections.emptySet()).fingerprint(),
                ItemData.from(old, Collections.emptySet()).fingerprint());
    }

    @Test void historicalBedColorAndPotionEffectsUseContainingDataVersion() {
        Map<String, Object> bed = raw((short) 355, 0);
        assertEquals("RED_BED", ItemData.from(bed, Collections.emptySet(), 0).material());
        assertEquals("WHITE_BED", ItemData.from(bed, Collections.emptySet(), 1343).material());
        bed.put("DataVersion", 1343);
        assertEquals("WHITE_BED", ItemData.from(bed, Collections.emptySet(), Integer.MAX_VALUE).material());
        Map<String, Object> first = raw((short) 373, 8193);
        Map<String, Object> second = raw((short) 373, 8194);
        assertNotEquals(ItemData.from(first, Collections.emptySet(), 0).fingerprint(),
                ItemData.from(second, Collections.emptySet(), 0).fingerprint());
        assertEquals("SPLASH_POTION", legacy(373, 16384 | 8193).material());
    }

    @Test void laterLegacySpawnEggsCanReadEntityTagAndUnknownNumericIdsStayDistinct() {
        Map<String, Object> egg = raw((short) 383, 0);
        egg.put("tag", Map.of("EntityTag", Map.of("id", "minecraft:cow")));
        assertEquals("COW_SPAWN_EGG", ItemData.from(egg, Collections.emptySet(), 1343).material());
        assertEquals("ZOMBIFIED_PIGLIN_SPAWN_EGG", legacy(383, 57).material());
        assertNotEquals(legacy(4094, 0).material(), legacy(4095, 0).material());
        assertFalse(LegacyItems.validId(1.5));
        assertFalse(LegacyItems.validId(-1));
    }

    private static ItemData legacy(int id, int damage) { return old((short) id, damage); }
    private static ItemData old(Object id, int damage) {
        return ItemData.from(raw(id, damage), Collections.emptySet());
    }
    private static Map<String, Object> raw(Object id, int damage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id); result.put("Count", (byte) 1); result.put("Damage", (short) damage);
        return result;
    }
}
