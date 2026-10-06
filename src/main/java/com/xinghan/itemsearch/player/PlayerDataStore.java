package com.xinghan.itemsearch.player;

import com.xinghan.itemsearch.nbt.NbtReader;
import com.xinghan.itemsearch.nbt.NbtWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Offline inventory persistence. Callers must separately prevent the player from logging in while saving. */
public final class PlayerDataStore {
    /** The accessor returns a deep copy, including primitive arrays, to preserve the original snapshot. */
    public static final class Snapshot {
        private final Path file;
        private final Map<String, Object> root;
        private final String sha256;
        public Snapshot(Path file, Map<String, Object> root, String sha256) {
            file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
            root = copyRoot(Objects.requireNonNull(root, "root"));
            Objects.requireNonNull(sha256, "sha256");
            if (!sha256.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Invalid snapshot SHA-256");
            this.file = file;
            this.root = root;
            this.sha256 = sha256;
        }
        public Path file() { return file; }
        public String sha256() { return sha256; }
        public Map<String, Object> root() { return copyRoot(root); }
        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Snapshot)) return false;
            Snapshot other = (Snapshot) value;
            return java.util.Objects.equals(file, other.file) && java.util.Objects.equals(root, other.root) && java.util.Objects.equals(sha256, other.sha256);
        }
        @Override public int hashCode() { return java.util.Objects.hash(file, root, sha256); }
        @Override public String toString() { return "Snapshot[" + "file=" + file + ", " + "root=" + root + ", " + "sha256=" + sha256 + "]"; }
    }

    public Snapshot load(Path playerDataDirectory, UUID uuid, int maxBytes, int maxDepth) throws IOException {
        Objects.requireNonNull(uuid, "uuid");
        limits(maxBytes, maxDepth);
        Path directory = directory(playerDataDirectory, false);
        Path file = directory.resolve(uuid.toString() + ".dat");
        requireFile(file);
        Stored original = readStable(file, maxBytes);
        Map<String, Object> root;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(original.bytes()))) {
            root = NbtReader.read(gzip, maxBytes, maxDepth);
        }
        Stored after = readStable(file, maxBytes);
        if (!original.stamp().equals(after.stamp()) || !original.sha256().equals(after.sha256())) {
            throw new IOException("Player data changed while loading; reopen the inventory");
        }
        return new Snapshot(file, root, original.sha256());
    }

    /** Replaces exactly one inventory, backing up the original compressed bytes before atomic replacement. */
    public Path save(Snapshot snapshot, String storageKey, List<Map<String, Object>> items,
                     Path backupDirectory, int maxBytes, int maxDepth) throws IOException {
        try (PreparedSave prepared = prepare(snapshot, storageKey, items, backupDirectory, maxBytes, maxDepth)) {
            return prepared.commit();
        }
    }

    /** Performs compression, exact-byte backup and full-content CAS without changing the player file. */
    public PreparedSave prepare(Snapshot snapshot, String storageKey, List<Map<String, Object>> items,
                                Path backupDirectory, int maxBytes, int maxDepth) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(items, "items");
        limits(maxBytes, maxDepth);
        if (!"Inventory".equals(storageKey) && !"EnderItems".equals(storageKey)) {
            throw new IOException("Only Inventory and EnderItems can be replaced");
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?>)) throw new IOException("Replacement inventories must contain item compounds");
        }
        Path file = snapshot.file();
        Path parent = directory(file.getParent(), false);
        if (!file.getParent().equals(parent)) throw new IOException("Player data path escaped its directory");
        UUID uuid = playerUuid(file);
        requireFile(file);
        Stored original = readStable(file, maxBytes);
        if (!original.sha256().equalsIgnoreCase(snapshot.sha256())) {
            throw new IOException("Player data changed since the inventory was opened; save refused");
        }
        Map<String, Object> replacement = new LinkedHashMap<>(snapshot.root());
        try {
            replacement.put(storageKey, copyValue(items, 0));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid replacement inventory", invalid);
        }
        byte[] raw = NbtWriter.write(replacement, maxBytes, maxDepth);
        byte[] compressed = gzip(raw, maxBytes);
        Path temporary = null;
        try {
            directory(parent, false);
            temporary = Files.createTempFile(parent, "." + uuid + "-itemsearch-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                output.write(compressed);
            }
            Path backups = directory(backupDirectory, true);
            String timestamp = Instant.now().toString().replace(":", "-");
            Path backup = backups.resolve(uuid + "-" + timestamp + "-" + UUID.randomUUID() + ".dat");
            try (OutputStream output = Files.newOutputStream(backup, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                output.write(original.bytes());
            }
            directory(parent, false);
            requireFile(file);
            Stored current = readStable(file, maxBytes);
            if (!current.sha256().equalsIgnoreCase(snapshot.sha256()) || !current.stamp().equals(original.stamp())) {
                throw new IOException("Player data changed during save; atomic replacement refused");
            }
            PreparedSave prepared = new PreparedSave(file, temporary, backup, current.stamp(), requireFile(temporary));
            temporary = null;
            return prepared;
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    /**
     * A prepared replacement is single-use. Commit only checks file metadata and performs the atomic
     * rename, so callers can run their final inventory guard and commit in one server-thread call.
     * Callers must keep player login and other normal file writers excluded until commit completes.
     * External writes preserving every recorded metadata field are outside this final metadata check.
     */
    public static final class PreparedSave implements AutoCloseable {
        private final Path file;
        private final Path backup;
        private final Stamp originalStamp;
        private final Stamp temporaryStamp;
        private Path temporary;
        private boolean closed;

        private PreparedSave(Path file, Path temporary, Path backup, Stamp originalStamp, Stamp temporaryStamp) {
            this.file = file;
            this.temporary = temporary;
            this.backup = backup;
            this.originalStamp = originalStamp;
            this.temporaryStamp = temporaryStamp;
        }

        public Path backup() { return backup; }

        public synchronized Path commit() throws IOException {
            if (closed || temporary == null) throw new IOException("Prepared player save is no longer available");
            directory(file.getParent(), false);
            if (!requireFile(file).equals(originalStamp)) {
                throw new IOException("Player data changed after save preparation; atomic replacement refused");
            }
            if (!requireFile(temporary).equals(temporaryStamp)) {
                throw new IOException("Prepared replacement changed; atomic replacement refused");
            }
            // Never fall back to a non-atomic move: the original remains readable on failure.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            closed = true;
            return backup;
        }

        @Override public synchronized void close() throws IOException {
            closed = true;
            if (temporary != null) {
                Files.deleteIfExists(temporary);
                temporary = null;
            }
        }
    }

    private static Stored readStable(Path file, int maxBytes) throws IOException {
        directory(file.getParent(), false);
        Stamp before = requireFile(file);
        if (before.size() > maxBytes) throw new IOException("Compressed player data exceeds the size limit");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) Math.min(before.size(), 8192));
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int count;
            long remaining = maxBytes;
            while ((count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining + 1))) != -1) {
                if (count > remaining) throw new IOException("Compressed player data exceeds the size limit");
                bytes.write(buffer, 0, count);
                remaining -= count;
            }
        }
        Stamp after = requireFile(file);
        if (!before.equals(after)) throw new IOException("Player data changed during read");
        byte[] data = bytes.toByteArray();
        return new Stored(data, hash(data), after);
    }

    private static Stamp requireFile(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile() || Files.isSymbolicLink(file)) {
            throw new IOException("Player data must be an existing regular file without symbolic links");
        }
        if (!file.toRealPath().equals(file.toAbsolutePath().normalize())) throw new IOException("Player data path escaped through a link");
        return new Stamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
    }

    private static Path directory(Path requested, boolean create) throws IOException {
        Objects.requireNonNull(requested, "directory");
        Path absolute = requested.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(current) || !attributes.isDirectory()) {
                    throw new IOException("Inventory directories must not contain links or non-directory components");
                }
            } else if (!create) {
                throw new IOException("Player data directory does not exist: " + absolute);
            }
        }
        if (create) Files.createDirectories(absolute);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) || !absolute.toRealPath().equals(absolute)) {
            throw new IOException("Inventory directory escaped its requested path");
        }
        return absolute;
    }

    private static UUID playerUuid(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (!name.endsWith(".dat")) throw new IOException("Player data filename must be UUID.dat");
        String text = name.substring(0, name.length() - 4);
        try {
            UUID uuid = UUID.fromString(text);
            if (!uuid.toString().equals(text)) throw new IllegalArgumentException();
            return uuid;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Player data filename must contain a canonical UUID", invalid);
        }
    }

    private static byte[] gzip(byte[] raw, int maxBytes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(raw.length, 8192));
        try (GZIPOutputStream gzip = new GZIPOutputStream(new OutputStream() {
            @Override public void write(int value) throws IOException {
                if (bytes.size() >= maxBytes) throw new IOException("Compressed replacement exceeds the size limit");
                bytes.write(value);
            }
            @Override public void write(byte[] values, int offset, int length) throws IOException {
                if (offset < 0 || length < 0 || offset > values.length - length) throw new IndexOutOfBoundsException();
                if (length > maxBytes - bytes.size()) throw new IOException("Compressed replacement exceeds the size limit");
                bytes.write(values, offset, length);
            }
        })) {
            gzip.write(raw);
        }
        return bytes.toByteArray();
    }

    private static String hash(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] hex = new char[digest.length * 2];
            char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 255;
                hex[i * 2] = digits[value >>> 4];
                hex[i * 2 + 1] = digits[value & 15];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 is unavailable", unavailable);
        }
    }

    private static void limits(int maxBytes, int maxDepth) {
        if (maxBytes < 4 || maxDepth < 1 || maxDepth > 512) throw new IllegalArgumentException("Invalid player-data limits");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyRoot(Map<String, Object> root) {
        return (Map<String, Object>) copyValue(root, 0);
    }

    private static Object copyValue(Object value, int depth) {
        if (depth > 512) throw new IllegalArgumentException("NBT copy nesting limit exceeded");
        if (value instanceof Map<?, ?>) {
            Map<?, ?> source = (Map<?, ?>) value;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("NBT keys must be strings");
                String key = (String) entry.getKey();
                copy.put(key, copyValue(entry.getValue(), depth + 1));
            }
            return copy;
        }
        if (value instanceof List<?>) {
            List<?> source = (List<?>) value;
            List<Object> copy = new ArrayList<>(source.size());
            for (Object entry : source) copy.add(copyValue(entry, depth + 1));
            return copy;
        }
        if (value instanceof byte[]) return ((byte[]) value).clone();
        if (value instanceof int[]) return ((int[]) value).clone();
        if (value instanceof long[]) return ((long[]) value).clone();
        if (value instanceof String || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double) return value;
        throw new IllegalArgumentException("Unsupported NBT snapshot value");
    }

    private static final class Stamp {
        private final long size;
        private final FileTime modified;
        private final Object fileKey;
        public Stamp(long size, FileTime modified, Object fileKey) {
            this.size = size;
            this.modified = modified;
            this.fileKey = fileKey;
        }
        public long size() { return size; }
        public FileTime modified() { return modified; }
        public Object fileKey() { return fileKey; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Stamp)) return false;
            Stamp other = (Stamp) value;
            return size == other.size && java.util.Objects.equals(modified, other.modified) && java.util.Objects.equals(fileKey, other.fileKey);
        }
        @Override public int hashCode() { return java.util.Objects.hash(size, modified, fileKey); }
        @Override public String toString() { return "Stamp[" + "size=" + size + ", " + "modified=" + modified + ", " + "fileKey=" + fileKey + "]"; }
    }
    private static final class Stored {
        private final byte[] bytes;
        private final String sha256;
        private final Stamp stamp;
        public Stored(byte[] bytes, String sha256, Stamp stamp) {
            this.bytes = bytes;
            this.sha256 = sha256;
            this.stamp = stamp;
        }
        public byte[] bytes() { return bytes; }
        public String sha256() { return sha256; }
        public Stamp stamp() { return stamp; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Stored)) return false;
            Stored other = (Stored) value;
            return java.util.Objects.equals(bytes, other.bytes) && java.util.Objects.equals(sha256, other.sha256) && java.util.Objects.equals(stamp, other.stamp);
        }
        @Override public int hashCode() { return java.util.Objects.hash(bytes, sha256, stamp); }
        @Override public String toString() { return "Stored[" + "bytes=" + bytes + ", " + "sha256=" + sha256 + ", " + "stamp=" + stamp + "]"; }
    }
}
