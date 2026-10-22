package stirling.software.officeconvert.topdf.crypt;

import java.io.InterruptedIOException;
import java.util.Arrays;

public final class Argon2id {

    private static final int VERSION = 0x13;

    private static final int TYPE = 2;

    private static final int WORDS = 128;

    private static final int SLICES = 4;

    private Argon2id() {}

    public static byte[] hash(byte[] password, byte[] salt, int iterations, int memoryKiB, int lanes, int length)
            throws InterruptedIOException {
        return hash(password, salt, new byte[0], new byte[0], iterations, memoryKiB, lanes, length);
    }

    static byte[] hash(byte[] password, byte[] salt, byte[] secret, byte[] data, int iterations, int memoryKiB,
            int lanes, int length) throws InterruptedIOException {
        if (iterations < 1 || lanes < 1 || lanes > 0xFFFFFF || length < 4 || memoryKiB < 8 * lanes) {
            throw new IllegalArgumentException("Argon2 parameters out of range");
        }
        Blake2b h0 = new Blake2b(64).updateInt(lanes).updateInt(length).updateInt(memoryKiB).updateInt(iterations)
                .updateInt(VERSION).updateInt(TYPE);
        h0.updateInt(password.length).update(password).updateInt(salt.length).update(salt);
        h0.updateInt(secret.length).update(secret).updateInt(data.length).update(data);
        byte[] seed = Arrays.copyOf(h0.digest(), 72);

        int segment = memoryKiB / (SLICES * lanes);
        int laneLength = segment * SLICES;
        int blocks = laneLength * lanes;
        long[] memory = new long[blocks * WORDS];
        for (int lane = 0; lane < lanes; lane++) {
            for (int j = 0; j < 2; j++) {
                le32(seed, 64, j);
                le32(seed, 68, lane);
                byte[] block = variable(1024, seed);
                int base = (lane * laneLength + j) * WORDS;
                for (int w = 0; w < WORDS; w++) {
                    memory[base + w] = Blake2b.le64(block, w * 8);
                }
            }
        }
        Filler f = new Filler(memory, lanes, laneLength, segment, iterations, blocks);
        for (int pass = 0; pass < iterations; pass++) {
            for (int slice = 0; slice < SLICES; slice++) {
                for (int lane = 0; lane < lanes; lane++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("Conversion interrupted");
                    }
                    f.segment(pass, slice, lane);
                }
            }
        }
        long[] last = new long[WORDS];
        for (int lane = 0; lane < lanes; lane++) {
            int base = (lane * laneLength + laneLength - 1) * WORDS;
            for (int w = 0; w < WORDS; w++) {
                last[w] ^= memory[base + w];
            }
        }
        byte[] c = new byte[1024];
        for (int w = 0; w < WORDS; w++) {
            for (int i = 0; i < 8; i++) {
                c[w * 8 + i] = (byte) (last[w] >>> (8 * i));
            }
        }
        return variable(length, c);
    }

    private static byte[] variable(int length, byte[] input) {
        byte[] prefix = new byte[4];
        le32(prefix, 0, length);
        if (length <= 64) {
            return new Blake2b(length).update(prefix).update(input).digest();
        }
        byte[] out = new byte[length];
        byte[] v = new Blake2b(64).update(prefix).update(input).digest();
        int pos = 0;
        while (length - pos > 64) {
            System.arraycopy(v, 0, out, pos, 32);
            pos += 32;
            v = new Blake2b(Math.min(64, length - pos)).update(v).digest();
        }
        System.arraycopy(v, 0, out, pos, length - pos);
        return out;
    }

    private static void le32(byte[] b, int off, int value) {
        b[off] = (byte) value;
        b[off + 1] = (byte) (value >>> 8);
        b[off + 2] = (byte) (value >>> 16);
        b[off + 3] = (byte) (value >>> 24);
    }

    private static final class Filler {

        private final long[] memory;

        private final int lanes;

        private final int laneLength;

        private final int segment;

        private final int passes;

        private final int blocks;

        private final long[] r = new long[WORDS];

        private final long[] tmp = new long[WORDS];

        private final long[] zero = new long[WORDS];

        private final long[] input = new long[WORDS];

        private final long[] address = new long[WORDS];

        Filler(long[] memory, int lanes, int laneLength, int segment, int passes, int blocks) {
            this.memory = memory;
            this.lanes = lanes;
            this.laneLength = laneLength;
            this.segment = segment;
            this.passes = passes;
            this.blocks = blocks;
        }

        void segment(int pass, int slice, int lane) {
            boolean independent = pass == 0 && slice < SLICES / 2;
            if (independent) {
                Arrays.fill(input, 0);
                input[0] = pass;
                input[1] = lane;
                input[2] = slice;
                input[3] = blocks;
                input[4] = passes;
                input[5] = TYPE;
            }
            int start = 0;
            if (pass == 0 && slice == 0) {
                start = 2;
                if (independent) {
                    nextAddresses();
                }
            }
            int current = lane * laneLength + slice * segment + start;
            int previous = current % laneLength == 0 ? current + laneLength - 1 : current - 1;
            for (int i = start; i < segment; i++, current++, previous++) {
                if (current % laneLength == 1) {
                    previous = current - 1;
                }
                long random;
                if (independent) {
                    if (i % WORDS == 0) {
                        nextAddresses();
                    }
                    random = address[i % WORDS];
                } else {
                    random = memory[previous * WORDS];
                }
                int refLane = (int) Long.remainderUnsigned(random >>> 32, lanes);
                if (pass == 0 && slice == 0) {
                    refLane = lane;
                }
                int refIndex = index(pass, slice, i, random & 0xFFFFFFFFL, refLane == lane);
                fill(memory, previous * WORDS, memory, (refLane * laneLength + refIndex) * WORDS, memory,
                        current * WORDS, pass > 0);
            }
        }

        private int index(int pass, int slice, int i, long random, boolean sameLane) {
            long area;
            if (pass == 0) {
                if (slice == 0) {
                    area = i - 1;
                } else if (sameLane) {
                    area = (long) slice * segment + i - 1;
                } else {
                    area = (long) slice * segment + (i == 0 ? -1 : 0);
                }
            } else if (sameLane) {
                area = laneLength - segment + i - 1;
            } else {
                area = laneLength - segment + (i == 0 ? -1 : 0);
            }
            long rel = (random * random) >>> 32;
            rel = area - 1 - ((area * rel) >>> 32);
            long start = pass != 0 && slice != SLICES - 1 ? (long) (slice + 1) * segment : 0;
            return (int) ((start + rel) % laneLength);
        }

        private void nextAddresses() {
            input[6]++;
            fill(zero, 0, input, 0, address, 0, false);
            fill(zero, 0, address, 0, address, 0, false);
        }

        private void fill(long[] prev, int p, long[] ref, int q, long[] next, int n, boolean xor) {
            for (int w = 0; w < WORDS; w++) {
                r[w] = prev[p + w] ^ ref[q + w];
                tmp[w] = xor ? r[w] ^ next[n + w] : r[w];
            }
            for (int i = 0; i < 8; i++) {
                int b = 16 * i;
                round(b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7, b + 8, b + 9, b + 10, b + 11, b + 12, b + 13,
                        b + 14, b + 15);
            }
            for (int i = 0; i < 8; i++) {
                int b = 2 * i;
                round(b, b + 1, b + 16, b + 17, b + 32, b + 33, b + 48, b + 49, b + 64, b + 65, b + 80, b + 81, b + 96,
                        b + 97, b + 112, b + 113);
            }
            for (int w = 0; w < WORDS; w++) {
                next[n + w] = tmp[w] ^ r[w];
            }
        }

        private void round(int v0, int v1, int v2, int v3, int v4, int v5, int v6, int v7, int v8, int v9, int v10,
                int v11, int v12, int v13, int v14, int v15) {
            g(v0, v4, v8, v12);
            g(v1, v5, v9, v13);
            g(v2, v6, v10, v14);
            g(v3, v7, v11, v15);
            g(v0, v5, v10, v15);
            g(v1, v6, v11, v12);
            g(v2, v7, v8, v13);
            g(v3, v4, v9, v14);
        }

        private void g(int a, int b, int c, int d) {
            r[a] = blamka(r[a], r[b]);
            r[d] = Long.rotateRight(r[d] ^ r[a], 32);
            r[c] = blamka(r[c], r[d]);
            r[b] = Long.rotateRight(r[b] ^ r[c], 24);
            r[a] = blamka(r[a], r[b]);
            r[d] = Long.rotateRight(r[d] ^ r[a], 16);
            r[c] = blamka(r[c], r[d]);
            r[b] = Long.rotateRight(r[b] ^ r[c], 63);
        }

        private static long blamka(long x, long y) {
            return x + y + 2 * (x & 0xFFFFFFFFL) * (y & 0xFFFFFFFFL);
        }
    }
}
