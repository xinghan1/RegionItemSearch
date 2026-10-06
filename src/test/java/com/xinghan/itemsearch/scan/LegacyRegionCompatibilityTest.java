package com.xinghan.itemsearch.scan;

import com.xinghan.itemsearch.item.Query;
import com.xinghan.itemsearch.nbt.NbtReader;
import com.xinghan.itemsearch.nbt.NbtWriter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LegacyRegionCompatibilityTest {
    @Test void negativeCoordinatesAndOldNumericBlockArraysDistinguishTrappedChest() throws Exception {
        byte[] blocks = new byte[4096]; blocks[4095] = (byte) 146;
        byte[] data = new byte[2048]; data[2047] = (byte) 0x50;
        Map<String, Object> section = Map.of("Y", (byte) 2, "Blocks", blocks, "Data", data);
        Map<String, Object> item = Map.of("id", (short) 35, "Damage", (short) 14, "Count", (byte) 3, "Slot", (byte) 13);
        Map<String, Object> entity = Map.of("id", "Chest", "x", -1, "y", 47, "z", -1, "Items", List.of(item));
        Map<String, Object> chunk = Map.of("Level", Map.of("Sections", List.of(section), "TileEntities", List.of(entity)));
        byte[] saved = NbtWriter.write(chunk, 1_000_000, 64);
        Map<String, Object> loaded = NbtReader.read(new ByteArrayInputStream(saved), 1_000_000, 64);
        WorldScanner.Summary summary = scan(loaded, "RED_WOOL");
        assertEquals(0, summary.errors()); assertEquals(1, summary.matchedStacks());
        assertEquals(3, summary.matchedItems()); assertEquals("TRAPPED_CHEST", summary.hits().get(0).container());
        assertEquals(List.of("TRAPPED_CHEST[13]"), summary.hits().get(0).path());
    }

    @Test void addHighNibblePreventsModdedBlockBeingMistakenForVanillaChest() {
        byte[] blocks = new byte[4096]; blocks[0] = 54;
        byte[] add = new byte[2048]; add[0] = 1;
        Map<String, Object> chunk = Map.of("Level", Map.of("Sections", List.of(Map.of("Y", (byte) 0, "Blocks", blocks, "Add", add))));
        assertNull(WorldScanner.blockAt(chunk, 0, 0, 0));
        add[0] = 0;
        assertEquals("chest", WorldScanner.blockAt(chunk, 0, 0, 0));
        assertNull(WorldScanner.blockAt(Map.of("Level", Map.of("Sections", List.of(Map.of("Y", (byte) 0, "Blocks", blocks, "Data", new byte[1])))), 0, 0, 0));
    }

    @Test void legacyBlockEntityAliasesAndNestedNumericShulkersRetainTheOuterSlot() {
        Map<String, Object> child = Map.of("id", (short) 276, "Damage", (short) 16, "Count", (byte) 2, "Slot", (byte) 12);
        Map<String, Object> box = Map.of("id", (short) 230, "Damage", (short) 0, "Count", (byte) 1,
                "Slot", (byte) 5, "tag", Map.of("BlockEntityTag", Map.of("Items", List.of(child))));
        Map<String, Object> chunk = Map.of("DataVersion", 1343, "Level", Map.of("TileEntities", List.of(
                Map.of("id", "Trap", "x", 0, "y", 64, "z", 0, "Items", List.of(box)))));
        WorldScanner.Summary summary = scan(chunk, "DIAMOND_SWORD");
        assertEquals(0, summary.errors()); assertEquals(1, summary.matchedStacks());
        assertEquals("DISPENSER", summary.hits().get(0).container());
        assertEquals(5, summary.hits().get(0).outerSlot());
        assertEquals(List.of("DISPENSER[5]", "BLUE_SHULKER_BOX[12]"), summary.hits().get(0).path());
    }

    @Test void modernPalettesRemainReadable() {
        Map<String, Object> chunk = Map.of("sections", List.of(Map.of("Y", 4,
                "block_states", Map.of("palette", List.of(Map.of("Name", "minecraft:barrel"))))));
        assertEquals("barrel", WorldScanner.blockAt(chunk, 1, 64, 3));
    }

    private static WorldScanner.Summary scan(Map<String, Object> chunk, String material) {
        Query query = new Query(Query.Mode.MATERIAL, Query.Match.EXACT, Query.LoreMatch.ALL, null, List.of(material), material);
        return WorldScanner.scanChunkForTest("world", chunk, query,
                new WorldScanner.Settings(1_000_000, 64, 8, 100, 0, Set.of()), () -> false);
    }
}
