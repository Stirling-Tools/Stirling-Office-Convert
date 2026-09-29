package stirling.software.officeconvert.topdf.docx;

import java.util.ArrayList;
import java.util.List;

final class Line {

    static final class Slice {
        final Item item;
        final int from;
        final int to;
        float x;
        float w;
        int spaces;
        boolean justified;
        float wordExtra;
        float charExtra;
        TabStop tab;

        Slice(Item item, int from, int to, float x, float w) {
            this.item = item;
            this.from = from;
            this.to = to;
            this.x = x;
            this.w = w;
        }

        String text() {
            return item.text.substring(from, to);
        }
    }

    final List<Slice> slices = new ArrayList<>();

    final List<Item> zero = new ArrayList<>();

    float left;

    float right;

    float end;

    float ascent;

    float descent;

    float leading;

    float height;

    float slack;

    float baseline;

    float top;

    String breakType;

    boolean hyphen;

    boolean objectsOnBaseline;

    int startItem;

    int startOffset;

    boolean empty() {
        for (Slice s : slices) {
            if (s.item.kind != Item.Kind.TEXT || s.to > s.from) {
                return false;
            }
        }
        return true;
    }

    boolean last() {
        return "end".equals(breakType);
    }

    void append(Line other) {
        slices.addAll(other.slices);
        zero.addAll(other.zero);
        right = other.right;
        end = other.end;
        breakType = other.breakType;
        hyphen = other.hyphen;
    }
}
