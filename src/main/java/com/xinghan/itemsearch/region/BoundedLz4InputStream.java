package com.xinghan.itemsearch.region;

import net.jpountz.lz4.LZ4Exception;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4SafeDecompressor;
import net.jpountz.xxhash.XXHash32;
import net.jpountz.xxhash.XXHashFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

/** Minecraft's LZ4Block framing, validating lengths before decoder allocations. */
final class BoundedLz4InputStream extends InputStream {
    private static final byte[] MAGIC = {'L', 'Z', '4', 'B', 'l', 'o', 'c', 'k'};
    private static final int HASH_SEED = 0x9747b28c;
    private final DataInputStream input;
    private final int maxBytes;
    private final LZ4SafeDecompressor decompressor = LZ4Factory.safeInstance().safeDecompressor();
    private final XXHash32 hash = XXHashFactory.safeInstance().hash32();
    private byte[] block = new byte[0];
    private int position;
    private long decoded;
    private boolean finished;

    BoundedLz4InputStream(InputStream input, int maxBytes) {
        this.input = new DataInputStream(input);
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        if (!ensureBlock()) {
            return -1;
        }
        return block[position++] & 255;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        if (length == 0) {
            return 0;
        }
        if (!ensureBlock()) {
            return -1;
        }
        int count = Math.min(length, block.length - position);
        System.arraycopy(block, position, bytes, offset, count);
        position += count;
        return count;
    }

    private boolean ensureBlock() throws IOException {
        if (position < block.length) {
            return true;
        }
        if (finished) {
            return false;
        }
        byte[] header = new byte[21];
        input.readFully(header);
        if (!Arrays.equals(MAGIC, Arrays.copyOf(header, MAGIC.length))) {
            throw new IOException("Invalid LZ4Block magic");
        }
        int token = header[8] & 255;
        int method = token & 0xf0;
        int compressed = littleInt(header, 9);
        int original = littleInt(header, 13);
        int checksum = littleInt(header, 17);
        long blockLimit = 1L << (10 + (token & 15));
        if ((method != 0x10 && method != 0x20) || original < 0 || compressed < 0
                || original > blockLimit || original > maxBytes - decoded
                || compressed > maxBytes || (original == 0) != (compressed == 0)
                || (method == 0x10 && compressed != original)) {
            throw new IOException("Invalid or oversized LZ4 block lengths");
        }
        if (original == 0) {
            if (checksum != 0) {
                throw new IOException("Invalid LZ4 stream terminator checksum");
            }
            if (input.read() != -1) {
                throw new IOException("Trailing data after LZ4 stream");
            }
            finished = true;
            block = new byte[0];
            return false;
        }
        byte[] encoded = new byte[compressed];
        input.readFully(encoded);
        if (method == 0x10) {
            block = encoded;
        } else {
            block = new byte[original];
            try {
                int actual = decompressor.decompress(encoded, 0, encoded.length, block, 0, original);
                if (actual != original) {
                    throw new IOException("LZ4 decompressed length mismatch");
                }
            } catch (LZ4Exception invalid) {
                throw new IOException("Invalid compressed LZ4 block", invalid);
            }
        }
        // lz4-java's StreamingXXHash32.asChecksum() masks to 28 bits. Minecraft
        // uses that adapter when writing LZ4Block streams, so preserve its format.
        if ((hash.hash(block, 0, block.length, HASH_SEED) & 0x0fff_ffff) != checksum) {
            throw new IOException("LZ4 block checksum mismatch");
        }
        decoded += original;
        position = 0;
        return true;
    }

    private static int littleInt(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8)
                | ((bytes[offset + 2] & 255) << 16) | ((bytes[offset + 3] & 255) << 24);
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
