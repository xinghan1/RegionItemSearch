import com.xinghan.itemsearch.item.Query;
import com.xinghan.itemsearch.scan.WorldScanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Repeatable full scanner benchmark; supply the same copied Region directory to both JARs. */
public final class ScannerBenchmark {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        int threads = Integer.parseInt(args[1]);
        long delay = Long.parseLong(args[2]);
        int warmups = Integer.parseInt(args[3]);
        int runs = Integer.parseInt(args[4]);
        var settings = settings(threads, delay);
        var query = new Query(Query.Mode.NAME, Query.Match.FUZZY, Query.LoreMatch.ALL, null, List.of("亡灵"), "亡灵");
        List<WorldScanner.Source> sources = List.of(new WorldScanner.Source("benchmark", directory));
        long bytes;
        long files;
        try (var entries = Files.list(directory)) {
            var regions = entries.filter(p -> p.getFileName().toString().endsWith(".mca")).toList();
            files = regions.size();
            bytes = 0;
            for (Path file : regions) bytes += Files.size(file);
        }
        System.out.printf("threads=%d delay_ms=%d regions=%d bytes=%d%n", threads, delay, files, bytes);
        for (int i = 0; i < warmups; i++) new WorldScanner().scan(sources, query, settings, () -> false, ignored -> {});
        long[] durations = new long[runs];
        WorldScanner.Summary first = null;
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            var result = new WorldScanner().scan(sources, query, settings, () -> false, ignored -> {});
            durations[i] = System.nanoTime() - start;
            if (result.errors() != 0 || result.cancelled() || result.truncated()) throw new IllegalStateException("Incomplete scan: " + result);
            if (first == null) first = result;
            if (first.chunks() != result.chunks() || first.items() != result.items()
                    || first.matchedStacks() != result.matchedStacks() || first.matchedItems() != result.matchedItems()) {
                throw new IllegalStateException("Inconsistent result counts");
            }
            System.out.printf("run=%d ms=%.3f chunks=%d containers=%d items=%d matches=%d units=%d%n", i + 1,
                    durations[i] / 1_000_000.0, result.chunks(), result.containers(), result.items(), result.matchedStacks(), result.matchedItems());
        }
        Arrays.sort(durations);
        System.out.printf("median_ms=%.3f min_ms=%.3f max_ms=%.3f%n",
                durations[runs / 2] / 1_000_000.0, durations[0] / 1_000_000.0, durations[runs - 1] / 1_000_000.0);
    }

    private static WorldScanner.Settings settings(int threads, long delay) throws Exception {
        Set<String> ignored = Set.of("unique_uuid", "created_time", "owner_uuid");
        try {
            return WorldScanner.Settings.class.getConstructor(int.class, int.class, int.class, int.class,
                            long.class, Set.class, int.class)
                    .newInstance(16_777_216, 128, 16, 100_000, delay, ignored, threads);
        } catch (NoSuchMethodException baseline) {
            if (threads != 1) throw new IllegalArgumentException("Baseline scanner supports one reader only");
            return new WorldScanner.Settings(16_777_216, 128, 16, 100_000, delay, ignored);
        }
    }
}
