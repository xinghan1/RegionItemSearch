package com.xinghan.itemsearch.nbt;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/** Bounded reader for the named, big-endian NBT compounds stored on disk. */
public final class NbtReader {
    private NbtReader() {
    }

    /** Reads one complete named root compound, without closing the caller's stream. */
    public static Map<String, Object> read(InputStream input, int maxBytes, int maxDepth) throws IOException {
        Objects.requireNonNull(input, "input");
        if (maxBytes < 4 || maxDepth < 1 || maxDepth > 512) {
            throw new IllegalArgumentException("NBT limits require maxBytes >= 4 and depth in 1..512");
        }
        BudgetInputStream bounded = new BudgetInputStream(input, maxBytes);
        Decoder decoder = new Decoder(bounded, maxBytes, maxDepth);
        if (decoder.input.readUnsignedByte() != 10) {
            throw new IOException("NBT root must be a named compound");
        }
        decoder.readString(); // The root name is metadata, not part of the compound.
        Map<String, Object> root = decoder.readCompound(0);
        if (bounded.read() != -1) {
            throw new IOException("Trailing data after NBT root");
        }
        return root;
    }

    /** Accepts raw disk NBT and Bukkit's gzip-compressed serializeAsBytes representation. */
    public static Map<String, Object> readAuto(byte[] bytes, int maxBytes, int maxDepth) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        ByteArrayInputStream raw = new ByteArrayInputStream(bytes);
        if (bytes.length >= 2 && (bytes[0] & 255) == 0x1f && (bytes[1] & 255) == 0x8b) {
            try (GZIPInputStream gzip = new GZIPInputStream(raw)) {
                return read(gzip, maxBytes, maxDepth);
            }
        }
        return read(raw, maxBytes, maxDepth);
    }

    private static final class Decoder {
        private final BudgetInputStream bounded;
        private final DataInputStream input;
        private final int maxDepth;
        private long allocationRemaining;

        private Decoder(BudgetInputStream bounded, int maxBytes, int maxDepth) {
            this.bounded = bounded;
            this.input = new DataInputStream(bounded);
            this.maxDepth = maxDepth;
            // Covers collection nodes, boxed values and strings as well as raw arrays.
            this.allocationRemaining = Math.min(256L * 1024 * 1024, Math.max(4096L, (long) maxBytes * 16));
        }

        private void allocate(long bytes) throws IOException {
            if (bytes < 0 || bytes > allocationRemaining) {
                throw new IOException("NBT allocation budget exceeded");
            }
            allocationRemaining -= bytes;
        }

        private void depth(int depth) throws IOException {
            if (depth > maxDepth) {
                throw new IOException("NBT nesting limit exceeded");
            }
        }

        private String readString() throws IOException {
            int length = input.readUnsignedShort();
            requireBytes(length);
            allocate(40L + length * 4L);
            // Java Edition's disk format uses DataInput's modified UTF encoding.
            byte[] encoded = new byte[length + 2];
            encoded[0] = (byte) (length >>> 8);
            encoded[1] = (byte) length;
            input.readFully(encoded, 2, length);
            return new DataInputStream(new ByteArrayInputStream(encoded)).readUTF();
        }

        private Map<String, Object> readCompound(int depth) throws IOException {
            depth(depth);
            allocate(64);
            Map<String, Object> result = new LinkedHashMap<>();
            for (;;) {
                int type = input.readUnsignedByte();
                if (type == 0) {
                    return result;
                }
                validType(type);
                allocate(64);
                String name = readString();
                if (result.containsKey(name)) {
                    throw new IOException("Duplicate NBT compound key: " + name);
                }
                result.put(name, readPayload(type, depth + 1));
            }
        }

        private Object readPayload(int type, int depth) throws IOException {
            depth(depth);
            allocate(24);
            switch (type) {
                case 1: return input.readByte();
                case 2: return input.readShort();
                case 3: return input.readInt();
                case 4: return input.readLong();
                case 5: return input.readFloat();
                case 6: return input.readDouble();
                case 7: return readByteArray();
                case 8: return readString();
                case 9: return readList(depth);
                case 10: return readCompound(depth);
                case 11: return readIntArray();
                case 12: return readLongArray();
                default: throw new IOException("Invalid NBT tag type: " + type);
            }
        }

        private byte[] readByteArray() throws IOException {
            int length = arrayLength(1);
            byte[] values = new byte[length];
            input.readFully(values);
            return values;
        }

        private int[] readIntArray() throws IOException {
            int length = arrayLength(4);
            int[] values = new int[length];
            for (int i = 0; i < length; i++) {
                values[i] = input.readInt();
            }
            return values;
        }

        private long[] readLongArray() throws IOException {
            int length = arrayLength(8);
            long[] values = new long[length];
            for (int i = 0; i < length; i++) {
                values[i] = input.readLong();
            }
            return values;
        }

        private int arrayLength(int width) throws IOException {
            int length = input.readInt();
            if (length < 0) {
                throw new IOException("Negative NBT array length");
            }
            long bytes = (long) length * width;
            requireBytes(bytes);
            allocate(16 + bytes);
            return length;
        }

        private List<Object> readList(int depth) throws IOException {
            int type = input.readUnsignedByte();
            int length = input.readInt();
            if (length < 0) {
                throw new IOException("Negative NBT list length");
            }
            if (type == 0 && length != 0) {
                throw new IOException("Non-empty NBT list cannot contain END tags");
            }
            if (type != 0) {
                validType(type);
            }
            int minWidth;
            switch (type) {
                case 0: minWidth = 0; break;
                case 2: case 8: minWidth = 2; break;
                case 3: case 5: case 7: case 11: case 12: minWidth = 4; break;
                case 4: case 6: minWidth = 8; break;
                case 9: minWidth = 5; break;
                default: minWidth = 1;
            }
            requireBytes((long) length * minWidth);
            allocate(24L + length * 8L);
            // Do not preallocate according to an untrusted list length.
            List<Object> values = new ArrayList<>(Math.min(length, 1024));
            for (int i = 0; i < length; i++) {
                values.add(readPayload(type, depth + 1));
            }
            return values;
        }

        private void requireBytes(long bytes) throws IOException {
            if (bytes > bounded.remaining()) {
                throw new IOException("NBT byte limit exceeded by declared length");
            }
        }

        private void validType(int type) throws IOException {
            if (type < 1 || type > 12) {
                throw new IOException("Invalid NBT tag type: " + type);
            }
        }
    }

    private static final class BudgetInputStream extends FilterInputStream {
        private final byte[] buffer;
        private int position;
        private int limit;
        private long remaining;

        private BudgetInputStream(InputStream source, long limit) {
            super(source);
            this.buffer = new byte[(int) Math.min(8192, limit)];
            this.remaining = limit;
        }

        private long remaining() {
            return remaining;
        }

        @Override
        public int read() throws IOException {
            if (position == limit && !fill()) {
                return -1;
            }
            remaining--;
            return buffer[position++] & 255;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                return read() == -1 ? -1 : 0; // read() throws on an excess byte.
            }
            // Large arrays can read directly; scalar NBT fields share the buffer.
            if (position == limit && length >= buffer.length) {
                int count = in.read(bytes, offset, (int) Math.min(length, remaining));
                if (count > 0) {
                    remaining -= count;
                }
                return count;
            }
            if (position == limit && !fill()) {
                return -1;
            }
            int count = Math.min(length, limit - position);
            System.arraycopy(buffer, position, bytes, offset, count);
            position += count;
            if (count > 0) {
                remaining -= count;
            }
            return count;
        }

        private boolean fill() throws IOException {
            if (remaining == 0) {
                if (in.read() != -1) {
                    throw new IOException("Decompressed NBT byte limit exceeded");
                }
                return false;
            }
            // Limit the fill before decompression. Prefetched bytes stay within
            // the budget, while remaining tracks logical consumption for lengths.
            int count = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count == 0) {
                int value = in.read();
                if (value == -1) {
                    return false;
                }
                buffer[0] = (byte) value;
                count = 1;
            }
            if (count == -1) {
                return false;
            }
            position = 0;
            limit = count;
            return true;
        }
    }
}
