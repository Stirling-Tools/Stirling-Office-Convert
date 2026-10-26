package stirling.software.officeconvert.photosplit;

import java.util.Arrays;

final class IntStack {

    private int[] items = new int[1024];
    private int size;

    void push(int v) {
        if (size == items.length) {
            items = Arrays.copyOf(items, items.length * 2);
        }
        items[size++] = v;
    }

    int pop() {
        return items[--size];
    }

    boolean isEmpty() {
        return size == 0;
    }

    void clear() {
        size = 0;
    }
}
