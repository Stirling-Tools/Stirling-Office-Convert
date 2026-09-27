package stirling.software.officeconvert.sheet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import stirling.software.officeconvert.layout.Box;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.layout.ParaDraft;

final class PageBlocks {

    sealed interface Block permits Text, Grid, Figure {
        float top();

        float bottom();
    }

    record Text(ParaDraft para, String prefix) implements Block {
        public float top() {
            return para.top();
        }

        public float bottom() {
            return para.bottom();
        }
    }

    record Figure(Box box) implements Block {
        public float top() {
            return box.top();
        }

        public float bottom() {
            return box.bottom();
        }
    }

    record Grid(PageLayout.TableItem item) implements Block {
        public float top() {
            return item.top();
        }

        public float bottom() {
            return item.bottom();
        }
    }

    private PageBlocks() {}

    static List<Block> of(PageLayout layout, Set<Line> taken) {
        List<Block> out = new ArrayList<>();
        for (Block b : of(layout)) {
            if (!(b instanceof Text t) || t.para().lines.isEmpty() || !taken.containsAll(t.para().lines)) {
                out.add(b);
            }
        }
        return out;
    }

    static List<Block> of(PageLayout layout) {
        List<Block> flow = new ArrayList<>();
        for (PageLayout.Band band : layout.bands()) {
            for (PageLayout.Column column : band.columns()) {
                for (PageLayout.Item item : column.items()) {
                    add(flow, item);
                }
            }
        }
        List<Block> out = new ArrayList<>(flow);
        List<PageLayout.TextBoxItem> furniture = new ArrayList<>(layout.furniture());
        furniture.sort((a, b) -> Float.compare(b.top(), a.top()));
        for (PageLayout.TextBoxItem box : furniture) {
            int at = out.size();
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).bottom() > box.bottom()) {
                    at = i;
                    break;
                }
            }
            List<Block> paras = new ArrayList<>();
            for (ParaDraft p : box.paras()) {
                paras.add(new Text(p, ""));
            }
            out.addAll(at, paras);
        }
        for (ParaDraft p : layout.noteContinuation()) {
            out.add(new Text(p, ""));
        }
        for (PageLayout.Note note : layout.notes()) {
            boolean first = true;
            for (ParaDraft p : note.paras()) {
                out.add(new Text(p, first ? note.marker() : ""));
                first = false;
            }
        }
        return out;
    }

    private static void add(List<Block> out, PageLayout.Item item) {
        switch (item) {
            case PageLayout.ParaItem p -> out.add(new Text(p.para(), ""));
            case PageLayout.TableItem t -> out.add(new Grid(t));
            case PageLayout.TextBoxItem box -> {
                for (ParaDraft p : box.paras()) {
                    out.add(new Text(p, ""));
                }
            }
            case PageLayout.FloatItem f -> add(out, f.picture());
            case PageLayout.FigureItem f -> {
                if (!f.backdrop()) {
                    out.add(new Figure(f.box()));
                }
            }
            case PageLayout.PictureRow row -> row.pictures().forEach(p -> add(out, p));
            default -> { }
        }
    }
}
