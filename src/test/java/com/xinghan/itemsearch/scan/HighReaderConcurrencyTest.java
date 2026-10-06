package com.xinghan.itemsearch.scan;

import com.xinghan.itemsearch.item.Query;
import com.xinghan.itemsearch.nbt.NbtWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Real region files exercise scheduling, shared result budgets and reader cleanup together. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class HighReaderConcurrencyTest {
    private static final int REGION_COUNT = 32;
    private static final Query DIAMONDS = new Query(Query.Mode.MATERIAL, Query.Match.EXACT,
            Query.LoreMatch.ALL, null, List.of("DIAMOND"), "Diamond");

    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(ints = {8, 16, 24, 64})
    void parallelReadersReturnEveryRegionHitAndTheSameCountsAsOneReader(int readers) throws Exception {
        Path regions = fixtures();
        WorldScanner.Summary serial = scan(regions, settings(1, 128), () -> false);
        WorldScanner.Summary parallel = scan(regions, settings(readers, 128), () -> false);

        assertComplete(serial);
        assertComplete(parallel);
        assertEquals(signatures(serial), signatures(parallel));
        assertEquals(REGION_COUNT, signatures(parallel).size(), "Each distinct region must contribute exactly one hit");
        Set<Integer> expectedCoordinates = java.util.stream.IntStream.range(0, REGION_COUNT)
                .map(region -> region * 512 + 5).boxed().collect(Collectors.toSet());
        assertEquals(expectedCoordinates, parallel.hits().stream().map(WorldScanner.Hit::x).collect(Collectors.toSet()));
        assertTrue(parallel.hits().stream().allMatch(hit -> hit.sourceIdentity().equals(regions.toAbsolutePath().normalize().toString())));
    }

    @ParameterizedTest
    @ValueSource(ints = {24, 64})
    void sharedBudgetStopsAtThreeStacksWithoutReportingUserCancellation(int readers) throws Exception {
        WorldScanner.Summary result = scan(fixtures(), settings(readers, 3), () -> false);

        assertEquals(3, result.hits().size());
        assertEquals(3, result.matchedStacks());
        assertEquals(6, result.matchedItems());
        assertEquals(3, signatures(result).size());
        assertTrue(result.truncated());
        assertFalse(result.cancelled(), "Stopping other readers at the result cap is not a user cancellation");
        assertEquals(0, result.errors());
        assertTrue(result.warnings().isEmpty());
        assertTrue(result.items() >= 3 && result.items() <= REGION_COUNT);
        assertEquals(result.matchedItems(), result.hits().stream().mapToLong(hit -> hit.item().count()).sum());
    }

    @Test
    void cancellingTwentyFourReadersAfterSixteenOpenedFilesJoinsEveryReaderThread() throws Exception {
        Path regions = fixtures();
        Set<Thread> observedReaders = ConcurrentHashMap.newKeySet();
        Set<Thread> readersInsideFile = ConcurrentHashMap.newKeySet();
        Map<Thread, AtomicInteger> checks = new ConcurrentHashMap<>();
        CountDownLatch sixteenReading = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        BooleanSupplier cancellation = () -> {
            Thread thread = Thread.currentThread();
            if (!thread.getName().startsWith("RegionItemSearch-Reader-")) return cancelled.get();
            observedReaders.add(thread);
            int check = checks.computeIfAbsent(thread, ignored -> new AtomicInteger()).incrementAndGet();
            // The first checks occur before opening the file. The third follows the header read.
            // Rendezvous here so cancellation is exercised with real open files, without timing assumptions.
            if (check >= 3 && !cancelled.get()) {
                if (readersInsideFile.add(thread) && readersInsideFile.size() >= 16) {
                    cancelled.set(true);
                    sixteenReading.countDown();
                }
                try {
                    assertTrue(sixteenReading.await(5, TimeUnit.SECONDS), "Sixteen independent readers must reach the region header");
                } catch (InterruptedException interrupted) {
                    thread.interrupt();
                    return true;
                }
            }
            return cancelled.get();
        };

        WorldScanner.Summary result = scan(regions, settings(24, 128), cancellation);

        assertTrue(readersInsideFile.size() >= 16);
        assertTrue(result.cancelled());
        assertFalse(result.truncated());
        assertEquals(0, result.errors(), "An ordinary stop must not turn open region reads into corruption errors");
        assertTrue(result.warnings().isEmpty());
        assertEquals(result.hits().size(), result.matchedStacks());
        assertEquals(result.matchedItems(), result.hits().stream().mapToLong(hit -> hit.item().count()).sum());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (Thread reader : observedReaders) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) reader.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            assertFalse(reader.isAlive(), "Cancelled scan left a reader thread running: " + reader.getName());
        }
        // A subsequent complete scan must not inherit the cancelled scan's stop state or result budget.
        assertComplete(scan(regions, settings(8, 128), () -> false));
    }

    @Test
    void requestedHighParallelismIsAcceptedAndValuesOutsideTheSupportedRangeAreRejected() {
        for (int readers : List.of(16, 24, 64)) {
            assertEquals(readers, assertDoesNotThrow(() -> settings(readers, 128)).readerThreads());
        }
        assertThrows(IllegalArgumentException.class, () -> settings(0, 128));
        assertThrows(IllegalArgumentException.class, () -> settings(65, 128));
    }

    private WorldScanner.Summary scan(Path regions, WorldScanner.Settings settings,
                                      BooleanSupplier cancelled) throws IOException {
        Thread coordinator = Thread.currentThread();
        return new WorldScanner().scan(List.of(new WorldScanner.Source("world", regions)), DIAMONDS,
                settings, cancelled, progress -> assertSame(coordinator, Thread.currentThread(),
                        "Progress must be reported by the coordinator, not a region reader"));
    }

    private static WorldScanner.Settings settings(int readers, int results) {
        return new WorldScanner.Settings(1_048_576, 32, 8, results, 0, Set.of(), readers);
    }

    private Path fixtures() throws IOException {
        Path regions = Files.createDirectory(directory.resolve("region"));
        for (int region = 0; region < REGION_COUNT; region++) {
            Map<String, Object> chunk = Map.of("DataVersion", 4671, "xPos", region * 32, "zPos", 0,
                    "block_entities", List.of(Map.of("id", "minecraft:chest", "x", region * 512 + 5,
                            "y", 64, "z", 11, "Items", List.of(Map.of("Slot", (byte) 5,
                                    "id", "minecraft:diamond", "count", 2)))));
            byte[] nbt = NbtWriter.write(chunk, 1_048_576, 32);
            int sectors = (5 + nbt.length + 4095) / 4096;
            ByteBuffer file = ByteBuffer.allocate(8192 + sectors * 4096);
            file.putInt((2 << 8) | sectors); // Local chunk 0, data begins after both header sectors.
            file.putInt(4096, 1); // Timestamp table, also used by the stable-reader checks.
            file.position(8192);
            file.putInt(nbt.length + 1).put((byte) 3).put(nbt); // Raw NBT compression type 3.
            Files.write(regions.resolve("r." + region + ".0.mca"), file.array());
        }
        return regions;
    }

    private static void assertComplete(WorldScanner.Summary result) {
        assertEquals(REGION_COUNT, result.regions());
        assertEquals(REGION_COUNT, result.chunks());
        assertEquals(REGION_COUNT, result.containers());
        assertEquals(REGION_COUNT, result.items());
        assertEquals(REGION_COUNT, result.matchedStacks());
        assertEquals(REGION_COUNT * 2L, result.matchedItems());
        assertEquals(REGION_COUNT, result.hits().size());
        assertEquals(0, result.errors());
        assertEquals(0, result.lootTables());
        assertEquals(0, result.depthSkipped());
        assertTrue(result.warnings().isEmpty());
        assertFalse(result.cancelled());
        assertFalse(result.truncated());
    }

    private record Signature(String world, String container, int x, int y, int z, int outerSlot,
                             List<String> path, String material, int count, String fingerprint, String source) { }

    private static Set<Signature> signatures(WorldScanner.Summary result) {
        return result.hits().stream().map(hit -> new Signature(hit.world(), hit.container(), hit.x(), hit.y(), hit.z(),
                hit.outerSlot(), hit.path(), hit.item().material(), hit.item().count(), hit.item().fingerprint(),
                hit.sourceIdentity())).collect(Collectors.toSet());
    }
}
