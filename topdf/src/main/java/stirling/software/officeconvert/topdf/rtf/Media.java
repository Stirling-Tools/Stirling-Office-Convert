package stirling.software.officeconvert.topdf.rtf;

import java.util.ArrayList;
import java.util.List;

final class Media {

    static final long MAX_TOTAL = 384L << 20;

    static final int MAX_COUNT = 20_000;

    record Item(String name, byte[] data) {}

    final List<Item> items = new ArrayList<>();

    private long total;

    private int drawingIds;

    boolean lost;

    String add(byte[] data, String ext) {
        if (items.size() >= MAX_COUNT || total + data.length > MAX_TOTAL) {
            lost = true;
            return null;
        }
        String name = "media/image" + (items.size() + 1) + "." + ext;
        items.add(new Item(name, data));
        total += data.length;
        return name;
    }

    int nextId() {
        return ++drawingIds;
    }

    long total() {
        return total;
    }
}
