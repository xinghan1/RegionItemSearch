package com.xinghan.itemsearch;

import com.xinghan.itemsearch.item.Query;
import com.xinghan.itemsearch.nbt.NbtWriter;
import com.xinghan.itemsearch.player.PlayerScanner;
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

class PlayerDirectoryCompatibilityTest {
    @TempDir Path level;

    @Test void classicWorldsRetainTheirIndependentPlayerdataPaths() {
        assertEquals(level.resolve("playerdata"), BukkitCompat.playerDataDirectory(level, false));
        Path nether=level.resolve("world_nether");
        assertEquals(nether.resolve("playerdata"), BukkitCompat.playerDataDirectory(nether, false));
    }

    @Test void modernDimensionsShareTheLevelPlayerStorageIncludingCustomDimensionPaths() {
        Path expected=level.resolve("players/data");
        assertEquals(expected, BukkitCompat.playerDataDirectory(level, true));
        for(String dimension: List.of("minecraft/overworld", "minecraft/the_nether", "minecraft/the_end", "example/dungeon/deep")) {
            assertEquals(expected, BukkitCompat.playerDataDirectory(level.resolve("dimensions").resolve(dimension), true));
        }
    }

    @Test void scannerFindsTheActualModernPlayerDirectoryAndDeduplicatesDimensionSources() throws Exception {
        Path directory=BukkitCompat.playerDataDirectory(level.resolve("dimensions/minecraft/overworld"), true);
        Files.createDirectories(directory);
        UUID uuid=UUID.randomUUID();
        Map<String,Object> item=Map.of("id", "minecraft:diamond", "count", 4, "Slot", (byte)0);
        try(GZIPOutputStream gzip=new GZIPOutputStream(Files.newOutputStream(directory.resolve(uuid+".dat")))) {
            gzip.write(NbtWriter.write(Map.of("Inventory", List.of(item), "EnderItems", List.of()), 1_000_000, 64));
        }
        List<PlayerScanner.Source> sources=List.of(new PlayerScanner.Source("world",directory),
                new PlayerScanner.Source("world_nether",BukkitCompat.playerDataDirectory(level.resolve("dimensions/minecraft/the_nether"),true)));
        Query query=new Query(Query.Mode.MATERIAL,Query.Match.EXACT,Query.LoreMatch.ALL,null,List.of("DIAMOND"),"DIAMOND");
        PlayerScanner.Summary result=new PlayerScanner().scan(sources,List.of(),null,query,
                new PlayerScanner.Settings(1_000_000,64,8,100,Set.of()),()->false,ignored->{});
        assertEquals(0,result.errors());
        assertEquals(1,result.offlinePlayers());
        assertEquals(1,result.matchedStacks());
        assertEquals(4,result.matchedItems());
    }
}