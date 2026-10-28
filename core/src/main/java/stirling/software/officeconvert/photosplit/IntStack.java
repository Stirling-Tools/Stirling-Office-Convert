package stirling.software.officeconvert.photosplit;

import java.util.Arrays;

final class IntStack {

    private int[] items = new int[1024];
    private int size;
    private int pops;

    void push(int v) {
        if (size == items.length) {
            items = Arrays.copyOf(items, items.length * 2);
        }
        items[size++] = v;
    }

    int pop() {
        if ((++pops & 0xFFFF) == 0) {
            PhotoSplitter.checkpoint();
        }
        return items[--size];
    }

    boolean isEmpty() {
        return size == 0;
    }

    void clear() {
        size = 0;
    }
}
