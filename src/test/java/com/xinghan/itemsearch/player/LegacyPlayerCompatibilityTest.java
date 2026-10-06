package com.xinghan.itemsearch.player;

import com.xinghan.itemsearch.item.Query;
import com.xinghan.itemsearch.nbt.NbtWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LegacyPlayerCompatibilityTest {
    @TempDir Path directory;

    @Test void legacyPlayerInventoryArmorAndNestedEnderItemsScanWithoutBukkit() throws Exception {
        UUID uuid = UUID.randomUUID();
        Map<String, Object> sword = item(276, 0, 1);
        Map<String, Object> helmet = item(310, 100, 1);
        Map<String, Object> box = Map.of("id", (short) 230, "Damage", (short) 0, "Count", (byte) 1,
                "Slot", (byte) 7, "tag", Map.of("BlockEntityTag", Map.of("Items", List.of(item(276, 12, 2)))));
        write(uuid, Map.of("DataVersion", 1343, "Inventory", List.of(sword, helmet), "EnderItems", List.of(box)));
        Query query = new Query(Query.Mode.MATERIAL, Query.Match.FUZZY, Query.LoreMatch.ALL, null, List.of("diamond"), "diamond");
        PlayerScanner.Summary result = new PlayerScanner().scan(List.of(new PlayerScanner.Source("world", directory)),
                List.of(), null, query, new PlayerScanner.Settings(1_000_000, 64, 8, 100, Set.of()), () -> false, ignored -> {});
        assertEquals(0, result.errors()); assertEquals(1, result.offlinePlayers());
        assertEquals(3, result.matchedStacks()); assertEquals(4, result.matchedItems());
        assertTrue(result.hits().stream().anyMatch(hit -> hit.slot() == 100 && hit.item().material().equals("DIAMOND_HELMET")));
        assertTrue(result.hits().stream().anyMatch(hit -> hit.path().equals(List.of("ENDER_CHEST[7]", "BLUE_SHULKER_BOX[12]"))));
    }

    @Test void editingLegacyPlayerDataPreservesUnrelatedNbtAndExactOriginalBackup() throws Exception {
        UUID uuid = UUID.randomUUID();
        Map<String, Object> original = Map.of("Inventory", List.of(item(276, 0, 1)), "EnderItems", List.of(item(35, 7, 4)),
                "Pos", List.of(12.5d, 64d, -100.5d), "customByteArray", new byte[]{0, -1, 127}, "customLongArray", new long[]{Long.MIN_VALUE, Long.MAX_VALUE});
        write(uuid, original);
        Path file = directory.resolve(uuid + ".dat"); byte[] before = Files.readAllBytes(file);
        PlayerDataStore store = new PlayerDataStore();
        PlayerDataStore.Snapshot snapshot = store.load(directory, uuid, 1_000_000, 64);
        Path backup = store.save(snapshot, "Inventory", List.of(item(276, 0, 2)), directory.resolve("backups"), 1_000_000, 64);
        Map<String, Object> after = store.load(directory, uuid, 1_000_000, 64).root();
        assertArrayEquals(before, Files.readAllBytes(backup));
        assertEquals(original.get("EnderItems"), after.get("EnderItems")); assertEquals(original.get("Pos"), after.get("Pos"));
        assertArrayEquals((byte[]) original.get("customByteArray"), (byte[]) after.get("customByteArray"));
        assertArrayEquals((long[]) original.get("customLongArray"), (long[]) after.get("customLongArray"));
        assertEquals((byte) 2, ((Map<?, ?>) ((List<?>) after.get("Inventory")).get(0)).get("Count"));
    }

    private static Map<String, Object> item(int id, int slot, int count) {
        return Map.of("id", (short) id, "Damage", (short) 0, "Count", (byte) count, "Slot", (byte) slot);
    }
    private void write(UUID uuid, Map<String, Object> root) throws Exception {
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(directory.resolve(uuid + ".dat")))) {
            gzip.write(NbtWriter.write(root, 1_000_000, 64));
        }
    }
}
