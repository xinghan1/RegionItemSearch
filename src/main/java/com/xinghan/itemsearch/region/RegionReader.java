package com.xinghan.itemsearch.region;

import com.xinghan.itemsearch.nbt.NbtReader;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Reads .mca sectors and optional .mcc sidecars without opening any game chunks. */
public final class RegionReader {
    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_BYTES = SECTOR_BYTES * 2;
    private static final Pattern FILE_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private final int maxChunkBytes;
    private final int maxNbtDepth;
    private final long maxEncodedBytes;

    public RegionReader(int maxChunkBytes, int maxNbtDepth) {
        if (maxChunkBytes < 4 || maxNbtDepth < 1 || maxNbtDepth > 512) {
            throw new IllegalArgumentException("Invalid Region/NBT reader limits");
        }
        this.maxChunkBytes = maxChunkBytes;
        this.maxNbtDepth = maxNbtDepth;
        this.maxEncodedBytes = Math.min(Integer.MAX_VALUE - 8L,
                maxChunkBytes + maxChunkBytes / 255L + 65_536L);
    }

    public static final class Chunk {
        private final int chunkX;
        private final int chunkZ;
        private final Map<String, Object> data;
        public Chunk(int chunkX, int chunkZ, Map<String, Object> data) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.data = data;
        }
        public int chunkX() { return chunkX; }
        public int chunkZ() { return chunkZ; }
        public Map<String, Object> data() { return data; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Chunk)) return false;
            Chunk other = (Chunk) value;
            return chunkX == other.chunkX && chunkZ == other.chunkZ && java.util.Objects.equals(data, other.data);
        }
        @Override public int hashCode() { return java.util.Objects.hash(chunkX, chunkZ, data); }
        @Override public String toString() { return "Chunk[" + "chunkX=" + chunkX + ", " + "chunkZ=" + chunkZ + ", " + "data=" + data + "]"; }
    }

    /** Reports malformed chunks by local header index, and continues scanning other entries. */
    public void scan(Path file, BooleanSupplier cancelled, Consumer<Chunk> onChunk,
                     BiConsumer<Integer, String> onError) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(onChunk, "onChunk");
        Objects.requireNonNull(onError, "onError");
        int[] region = regionCoordinates(file);
        if (cancelled.getAsBoolean()) {
            return;
        }
        try (RandomAccessFile source = new RandomAccessFile(file.toFile(), "r")) {
            if (source.length() < HEADER_BYTES) {
                throw new IOException("Truncated region header: " + file.getFileName());
            }
            Header[] initialHeaders = headers(source);
            for (int index = 0; index < 1024; index++) {
                if (cancelled.getAsBoolean()) {
                    return;
                }
                if (initialHeaders[index].location == 0) {
                    continue;
                }
                int chunkX = coordinate(region[0], index & 31);
                int chunkZ = coordinate(region[1], index >>> 5);
                Chunk chunk = readStable(source, file, index, initialHeaders[index], chunkX, chunkZ, cancelled, onError);
                if (chunk != null && !cancelled.getAsBoolean()) {
                    onChunk.accept(chunk);
                }
            }
        } catch (ScanCancelled ignored) {
            // Cancellation is an expected successful stop, not a corrupt chunk report.
        }
    }

    private Chunk readStable(RandomAccessFile source, Path file, int index, Header initial, int chunkX, int chunkZ,
                             BooleanSupplier cancelled, BiConsumer<Integer, String> onError) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (cancelled.getAsBoolean()) {
                throw new ScanCancelled();
            }
            Header before = attempt == 0 ? initial : header(source, index);
            if (before.location == 0) {
                return null;
            }
            Map<String, Object> data = null;
            IOException failure = null;
            try {
                data = readChunk(source, file, before.location, chunkX, chunkZ, cancelled);
            } catch (ScanCancelled stopped) {
                throw stopped;
            } catch (IOException invalid) {
                failure = invalid;
            }
            Header after = header(source, index);
            boolean changed = !before.equals(after) || failure instanceof ConcurrentWrite;
            if (changed && attempt == 0) {
                continue;
            }
            if (changed) {
                onError.accept(index, "Chunk changed during both read attempts; skipped");
                return null;
            }
            if (failure != null) {
                onError.accept(index, failure.getMessage());
                return null;
            }
            return new Chunk(chunkX, chunkZ, data);
        }
        throw new AssertionError("Unreachable retry state");
    }

    private Map<String, Object> readChunk(RandomAccessFile source, Path regionFile, int location,
                                          int chunkX, int chunkZ, BooleanSupplier cancelled) throws IOException {
        int firstSector = location >>> 8;
        int sectors = location & 255;
        long start = (long) firstSector * SECTOR_BYTES;
        long extent = (long) sectors * SECTOR_BYTES;
        long fileLength = source.length();
        // A live Paper region can end immediately after its final chunk payload;
        // the last reserved sector does not need to have been zero-padded yet.
        if (firstSector < 2 || sectors == 0 || start + 5 > fileLength) {
            throw new IOException("Invalid chunk sector extent");
        }
        source.seek(start);
        byte[] recordHeader = new byte[5];
        source.readFully(recordHeader);
        int length = bigInt(recordHeader, 0);
        if (length < 1 || length > extent - 4 || start + 4L + length > fileLength
                || length - 1L > maxEncodedBytes) {
            throw new IOException("Invalid or oversized encoded chunk length");
        }
        int compression = recordHeader[4] & 255;
        boolean external = (compression & 128) != 0;
        compression &= 127;
        if (compression < 1 || compression > 4) {
            throw new IOException("Unsupported region compression id: " + compression);
        }
        byte[] encoded;
        Path externalFile = null;
        FileStamp beforeExternal = null;
        if (external) {
            if (length != 1) {
                throw new IOException("External chunk stub must have length 1");
            }
            externalFile = regionFile.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
            beforeExternal = stamp(externalFile);
            if (!beforeExternal.regular || beforeExternal.size > maxEncodedBytes) {
                throw new IOException("Invalid or oversized external chunk file");
            }
            encoded = new byte[(int) beforeExternal.size];
            try (InputStream input = new CancelInputStream(Files.newInputStream(externalFile), cancelled)) {
                readFully(input, encoded);
                if (input.read() != -1) {
                    throw new ConcurrentWrite("External chunk grew during read");
                }
            }
        } else {
            encoded = new byte[length - 1];
            for (int offset = 0; offset < encoded.length; ) {
                if (cancelled.getAsBoolean()) {
                    throw new ScanCancelled();
                }
                int count = Math.min(65_536, encoded.length - offset);
                source.readFully(encoded, offset, count);
                offset += count;
            }
        }
        InputStream raw = new CancelInputStream(new ByteArrayInputStream(encoded), cancelled);
        Map<String, Object> result;
        InputStream decodedStream;
        switch (compression) {
            case 1: decodedStream = new GZIPInputStream(raw); break;
            case 2: decodedStream = new InflaterInputStream(raw); break;
            case 3: decodedStream = raw; break;
            case 4: decodedStream = new BoundedLz4InputStream(raw, maxChunkBytes); break;
            default: throw new AssertionError();
        }
        try (InputStream decoded = decodedStream) {
            result = NbtReader.read(new CancelInputStream(decoded, cancelled), maxChunkBytes, maxNbtDepth);
        }
        if (externalFile != null && !beforeExternal.equals(stamp(externalFile))) {
            throw new ConcurrentWrite("External chunk changed during read");
        }
        return result;
    }

    private Header header(RandomAccessFile source, int index) throws IOException {
        byte[] bytes = new byte[8];
        source.seek(index * 4L);
        source.readFully(bytes, 0, 4);
        source.seek(SECTOR_BYTES + index * 4L);
        source.readFully(bytes, 4, 4);
        return new Header(bigInt(bytes, 0), bigInt(bytes, 4));
    }

    private Header[] headers(RandomAccessFile source) throws IOException {
        byte[] bytes = new byte[HEADER_BYTES];
        source.seek(0);
        source.readFully(bytes);
        Header[] headers = new Header[1024];
        for (int index = 0; index < headers.length; index++) {
            headers[index] = new Header(bigInt(bytes, index * 4), bigInt(bytes, SECTOR_BYTES + index * 4));
        }
        return headers;
    }

    private static int bigInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 255) << 24) | ((bytes[offset + 1] & 255) << 16)
                | ((bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
    }

    private static int[] regionCoordinates(Path file) throws IOException {
        Matcher matcher = FILE_NAME.matcher(file.getFileName().toString());
        if (!matcher.matches()) {
            throw new IOException("Invalid region filename: " + file.getFileName());
        }
        try {
            int x = Integer.parseInt(matcher.group(1));
            int z = Integer.parseInt(matcher.group(2));
            coordinate(x, 31);
            coordinate(z, 31);
            return new int[]{x, z};
        } catch (NumberFormatException invalid) {
            throw new IOException("Region coordinates are outside supported range", invalid);
        }
    }

    private static int coordinate(int region, int local) throws IOException {
        long coordinate = region * 32L + local;
        if (coordinate < Integer.MIN_VALUE || coordinate > Integer.MAX_VALUE) {
            throw new IOException("Region coordinates are outside supported range");
        }
        return (int) coordinate;
    }

    private static FileStamp stamp(Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        return new FileStamp(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey(), attrs.isRegularFile());
    }

    private static void readFully(InputStream input, byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            int count = input.read(bytes, offset, Math.min(65_536, bytes.length - offset));
            if (count == -1) {
                throw new ConcurrentWrite("External chunk shrank during read");
            }
            offset += count;
        }
    }

    private static final class Header {
        private final int location;
        private final int timestamp;
        public Header(int location, int timestamp) {
            this.location = location;
            this.timestamp = timestamp;
        }
        public int location() { return location; }
        public int timestamp() { return timestamp; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Header)) return false;
            Header other = (Header) value;
            return location == other.location && timestamp == other.timestamp;
        }
        @Override public int hashCode() { return java.util.Objects.hash(location, timestamp); }
        @Override public String toString() { return "Header[" + "location=" + location + ", " + "timestamp=" + timestamp + "]"; }
    }

    private static final class FileStamp {
        private final long size;
        private final java.nio.file.attribute.FileTime modified;
        private final Object key;
        private final boolean regular;
        public FileStamp(long size, java.nio.file.attribute.FileTime modified, Object key, boolean regular) {
            this.size = size;
            this.modified = modified;
            this.key = key;
            this.regular = regular;
        }
        public long size() { return size; }
        public java.nio.file.attribute.FileTime modified() { return modified; }
        public Object key() { return key; }
        public boolean regular() { return regular; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof FileStamp)) return false;
            FileStamp other = (FileStamp) value;
            return size == other.size && java.util.Objects.equals(modified, other.modified) && java.util.Objects.equals(key, other.key) && regular == other.regular;
        }
        @Override public int hashCode() { return java.util.Objects.hash(size, modified, key, regular); }
        @Override public String toString() { return "FileStamp[" + "size=" + size + ", " + "modified=" + modified + ", " + "key=" + key + ", " + "regular=" + regular + "]"; }
    }

    private static final class ConcurrentWrite extends IOException {
        private ConcurrentWrite(String message) {
            super(message);
        }
    }

    private static final class ScanCancelled extends IOException {
    }

    private static final class CancelInputStream extends FilterInputStream {
        private final BooleanSupplier cancelled;

        private CancelInputStream(InputStream input, BooleanSupplier cancelled) {
            super(input);
            this.cancelled = cancelled;
        }

        private void check() throws ScanCancelled {
            if (cancelled.getAsBoolean()) {
                throw new ScanCancelled();
            }
        }

        @Override
        public int read() throws IOException {
            check();
            return in.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            check();
            return in.read(bytes, offset, length);
        }
    }
}
