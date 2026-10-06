package com.xinghan.itemsearch.nbt;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded counterpart of NbtReader for named, big-endian disk compounds. */
public final class NbtWriter {
    private NbtWriter() { }

    public static byte[] write(Map<String, Object> root, int maxBytes, int maxDepth) throws IOException {
        Objects.requireNonNull(root, "root");
        if (maxBytes < 4 || maxDepth < 1 || maxDepth > 512) {
            throw new IllegalArgumentException("NBT limits require maxBytes >= 4 and depth in 1..512");
        }
        LimitedOutput output = new LimitedOutput(maxBytes);
        Encoder encoder = new Encoder(output, maxDepth);
        encoder.output.writeByte(10);
        encoder.writeString("");
        encoder.compound(root, 0);
        return output.bytes.toByteArray();
    }

    private static final class Encoder {
        final LimitedOutput bounded;
        final DataOutputStream output;
        final int maxDepth;

        Encoder(LimitedOutput bounded, int maxDepth) {
            this.bounded = bounded;
            this.output = new DataOutputStream(bounded);
            this.maxDepth = maxDepth;
        }

        void checkDepth(int depth) throws IOException {
            if (depth > maxDepth) throw new IOException("NBT nesting limit exceeded");
        }

        void compound(Map<?, ?> values, int depth) throws IOException {
            checkDepth(depth);
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new IOException("NBT compound keys must be strings");
                String name = (String) entry.getKey();
                int type = type(entry.getValue());
                output.writeByte(type);
                writeString(name);
                payload(entry.getValue(), type, depth + 1);
            }
            output.writeByte(0);
        }

        void payload(Object value, int type, int depth) throws IOException {
            checkDepth(depth);
            switch (type) {
                case 1: output.writeByte((Byte) value); break;
                case 2: output.writeShort((Short) value); break;
                case 3: output.writeInt((Integer) value); break;
                case 4: output.writeLong((Long) value); break;
                case 5: output.writeFloat((Float) value); break;
                case 6: output.writeDouble((Double) value); break;
                case 7: {
                    byte[] bytes = (byte[]) value;
                    bounded.require(4L + bytes.length);
                    output.writeInt(bytes.length);
                    output.write(bytes);
                    break;
                }
                case 8: writeString((String) value); break;
                case 9: list((List<?>) value, depth); break;
                case 10: compound((Map<?, ?>) value, depth); break;
                case 11: {
                    int[] values = (int[]) value;
                    bounded.require(4L + values.length * 4L);
                    output.writeInt(values.length);
                    for (int item : values) output.writeInt(item);
                    break;
                }
                case 12: {
                    long[] values = (long[]) value;
                    bounded.require(4L + values.length * 8L);
                    output.writeInt(values.length);
                    for (long item : values) output.writeLong(item);
                    break;
                }
                default: throw new IOException("Unsupported NBT tag type: " + type);
            }
        }

        void list(List<?> values, int depth) throws IOException {
            int size = values.size();
            if (size < 0) throw new IOException("Negative NBT list size");
            int type = size == 0 ? 0 : type(values.get(0));
            int minWidth;
            switch (type) {
                case 0: minWidth = 0; break;
                case 2: case 8: minWidth = 2; break;
                case 3: case 5: case 7: case 11: case 12: minWidth = 4; break;
                case 4: case 6: minWidth = 8; break;
                case 9: minWidth = 5; break;
                default: minWidth = 1;
            }
            bounded.require(5L + (long) size * minWidth);
            output.writeByte(type);
            output.writeInt(size);
            for (Object item : values) {
                if (type(item) != type) throw new IOException("NBT lists must contain one tag type");
                payload(item, type, depth + 1);
            }
        }

        void writeString(String value) throws IOException {
            long utfBytes = 0;
            for (int i = 0; i < value.length(); i++) {
                char character = value.charAt(i);
                utfBytes += character >= 1 && character <= 127 ? 1 : character <= 2047 ? 2 : 3;
                if (utfBytes > 65_535) throw new IOException("NBT modified-UTF string exceeds 65535 bytes");
            }
            bounded.require(2 + utfBytes);
            output.writeUTF(value);
        }

        int type(Object value) throws IOException {
            if (value instanceof Byte) return 1;
            if (value instanceof Short) return 2;
            if (value instanceof Integer) return 3;
            if (value instanceof Long) return 4;
            if (value instanceof Float) return 5;
            if (value instanceof Double) return 6;
            if (value instanceof byte[]) return 7;
            if (value instanceof String) return 8;
            if (value instanceof List<?>) return 9;
            if (value instanceof Map<?, ?>) return 10;
            if (value instanceof int[]) return 11;
            if (value instanceof long[]) return 12;
            throw new IOException("Unsupported NBT value: " + (value == null ? "null" : value.getClass().getName()));
        }
    }

    private static final class LimitedOutput extends OutputStream {
        final ByteArrayOutputStream bytes;
        final int maximum;

        LimitedOutput(int maximum) {
            this.maximum = maximum;
            bytes = new ByteArrayOutputStream(Math.min(8192, maximum));
        }

        void require(long count) throws IOException {
            if (count < 0 || count > maximum - (long) bytes.size()) throw new IOException("NBT byte limit exceeded");
        }

        @Override public void write(int value) throws IOException {
            require(1);
            bytes.write(value);
        }

        @Override public void write(byte[] values, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > values.length - length) throw new IndexOutOfBoundsException();
            require(length);
            bytes.write(values, offset, length);
        }
    }
}
