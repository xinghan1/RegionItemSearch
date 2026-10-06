package com.xinghan.itemsearch;

import com.xinghan.itemsearch.Compat;
import com.xinghan.itemsearch.scan.WorldScanner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Filesystem discovery runs on the search worker, after world metadata was captured on the main thread. */
public final class WorldSources {
    public static final class Registered {
        private final String name;
        private final Path folder;
        public Registered(String name, Path folder) {
            this.name = name;
            this.folder = folder;
        }
        public String name() { return name; }
        public Path folder() { return folder; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Registered)) return false;
            Registered other = (Registered) value;
            return java.util.Objects.equals(name, other.name) && java.util.Objects.equals(folder, other.folder);
        }
        @Override public int hashCode() { return java.util.Objects.hash(name, folder); }
        @Override public String toString() { return "Registered[" + "name=" + name + ", " + "folder=" + folder + "]"; }
    }
    private WorldSources() {}

    public static List<WorldScanner.Source> discover(List<Registered> worlds, List<WorldScanner.Source> extras,
                                                     BooleanSupplier cancelled) throws IOException {
        List<WorldScanner.Source> candidates = new ArrayList<>(extras);
        for (WorldScanner.Source source : extras) {
            if (cancelled.getAsBoolean()) break;
            if (!Files.isDirectory(source.regionDirectory())) {
                throw new IOException(Language.tr("world.extra-region-unavailable", "world", source.world(), "directory", source.regionDirectory()));
            }
        }
        for (Registered world : worlds) {
            if (cancelled.getAsBoolean()) break;
            candidates.add(new WorldScanner.Source(world.name(), world.folder().resolve("region")));
            candidates.add(new WorldScanner.Source(world.name(), world.folder().resolve("DIM-1/region")));
            candidates.add(new WorldScanner.Source(world.name(), world.folder().resolve("DIM1/region")));
            Path dimensions = world.folder().resolve("dimensions");
            if (Files.isDirectory(dimensions)) {
                Files.walkFileTree(dimensions, Compat.set(), 16, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                        String name = directory.getFileName().toString();
                        if (name.equals("region")) {
                            candidates.add(new WorldScanner.Source(world.name() + "/" + dimensions.relativize(directory.getParent()), directory));
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return name.equals("poi") || name.equals("entities") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        return cancelled.getAsBoolean() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        List<WorldScanner.Source> result = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (WorldScanner.Source source : candidates) {
            if (cancelled.getAsBoolean()) break;
            if (Files.isDirectory(source.regionDirectory())) {
                Path real = source.regionDirectory().toRealPath();
                if (seen.add(real)) result.add(new WorldScanner.Source(source.world(), real));
            }
        }
        return Compat.listCopy(result);
    }
}
