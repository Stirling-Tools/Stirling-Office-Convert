package stirling.software.officeconvert.jpx;

import java.util.Arrays;

final class Segment {

    final int id;

    final int firstPass;

    int passes;

    private int[] chunks = new int[4];

    private int count;

    private int length;

    Segment(int id, int firstPass) {
        this.id = id;
        this.firstPass = firstPass;
    }

    void add(int offset, int len) {
        if (count + 2 > chunks.length) {
            chunks = Arrays.copyOf(chunks, chunks.length * 2);
        }
        chunks[count++] = offset;
        chunks[count++] = len;
        length += len;
    }

    int length() {
        return length;
    }

    byte[] bytes(byte[] source, byte[] scratch) {
        byte[] out = scratch.length >= length ? scratch : new byte[length];
        int at = 0;
        for (int i = 0; i < count; i += 2) {
            System.arraycopy(source, chunks[i], out, at, chunks[i + 1]);
            at += chunks[i + 1];
        }
        return out;
    }
}
