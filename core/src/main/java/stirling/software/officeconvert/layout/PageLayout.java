package stirling.software.officeconvert.layout;

import java.util.List;
import java.util.Set;

import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.extract.PageGraphics;
import stirling.software.officeconvert.extract.PageGraphics.ImageDraw;
import stirling.software.officeconvert.table.TableDetection;

public record PageLayout(
        PageData page,
        List<Band> bands,
        boolean ocrText,
        List<Decoration> decorations,
        List<Note> notes,
        List<ParaDraft> noteContinuation,
        List<TextBoxItem> furniture,
        List<Veil> veils,
        Set<Object> textPaint) {

    public PageLayout(PageData page, List<Band> bands, boolean ocrText, List<Decoration> decorations, List<Note> notes,
            List<ParaDraft> noteContinuation, List<TextBoxItem> furniture, List<Veil> veils) {
        this(page, bands, ocrText, decorations, notes, noteContinuation, furniture, veils, Set.of());
    }

    public record Veil(Box box, List<PageGraphics.VectorMark> marks, int order) {}

    public record Note(int id, String marker, List<ParaDraft> paras) {}

    public record Decoration(Box box, int rgb, int lineRgb, float lineWidth, boolean rounded, boolean ellipse, int order) {

        public Decoration(Box box, int rgb) {
            this(box, rgb, -1, 0, false, false, Integer.MAX_VALUE);
        }

        public Decoration(Box box, int rgb, int lineRgb, float lineWidth, boolean rounded) {
            this(box, rgb, lineRgb, lineWidth, rounded, false, Integer.MAX_VALUE);
        }
    }

    public sealed interface Item
            permits ParaItem, TableItem, ImageItem, FigureItem, FloatItem, PictureRow, TextBoxItem {
        float top();

        float bottom();

        float x();

        float right();

        default boolean behindText() {
            return false;
        }
    }

    public record ParaItem(ParaDraft para) implements Item {
        public float top() {
            return para.top();
        }

        public float bottom() {
            return para.bottom();
        }

        public float x() {
            return para.x();
        }

        public float right() {
            return para.rightEdge();
        }
    }

    public record TableItem(
            TableDetection.Found table, List<List<ParaDraft>> cellParas, float[] rowEdges, float pad)
            implements Item {
        public float top() {
            return table.top();
        }

        public float bottom() {
            return table.bottom();
        }

        public float x() {
            return table.left();
        }

        public float right() {
            return table.right();
        }
    }

    public record ImageItem(ImageDraw draw, boolean background) implements Item {
        @Override
        public boolean behindText() {
            return background;
        }

        public float top() {
            return draw.clipTop();
        }

        public float bottom() {
            return draw.clipBottom();
        }

        public float x() {
            return draw.clipX();
        }

        public float right() {
            return draw.clipRight();
        }
    }

    public record FigureItem(Box box, boolean backdrop, boolean paper, int order) implements Item {

        public FigureItem(Box box, boolean backdrop) {
            this(box, backdrop, false, -1);
        }

        @Override
        public boolean behindText() {
            return backdrop;
        }

        public float top() {
            return box.top();
        }

        public float bottom() {
            return box.bottom();
        }

        public float x() {
            return box.x();
        }

        public float right() {
            return box.right();
        }
    }

    public record FloatItem(Item picture, float gap, boolean overlay) implements Item {
        public float top() {
            return picture.top();
        }

        public float bottom() {
            return picture.bottom();
        }

        public float x() {
            return picture.x();
        }

        public float right() {
            return picture.right();
        }
    }

    public record PictureRow(List<Item> pictures) implements Item {
        public float top() {
            float t = Float.MAX_VALUE;
            for (Item i : pictures) {
                t = Math.min(t, i.top());
            }
            return t;
        }

        public float bottom() {
            float b = -Float.MAX_VALUE;
            for (Item i : pictures) {
                b = Math.max(b, i.bottom());
            }
            return b;
        }

        public float x() {
            return pictures.getFirst().x();
        }

        public float right() {
            return pictures.getLast().right();
        }
    }

    public record TextBoxItem(
            Box box, int fillRgb, float textLeft, float textRight, List<ParaDraft> paras, float gap,
            int lineRgb, float lineWidth, boolean rounded, boolean overlay, Turn turn, int groundRgb)
            implements Item {
        public TextBoxItem(Box box, int fillRgb, float textLeft, float textRight, List<ParaDraft> paras, float gap,
                int lineRgb, float lineWidth, boolean rounded, boolean overlay) {
            this(box, fillRgb, textLeft, textRight, paras, gap, lineRgb, lineWidth, rounded, overlay, null, -1);
        }

        public TextBoxItem(Box box, int fillRgb, float textLeft, float textRight, List<ParaDraft> paras, float gap,
                int lineRgb, float lineWidth, boolean rounded, boolean overlay, Turn turn) {
            this(box, fillRgb, textLeft, textRight, paras, gap, lineRgb, lineWidth, rounded, overlay, turn, -1);
        }

        public TextBoxItem withGround(int rgb) {
            return new TextBoxItem(box, fillRgb, textLeft, textRight, paras, gap, lineRgb, lineWidth, rounded, overlay, turn,
                    rgb);
        }

        public TextBoxItem turned(int direction, Box onPage, boolean upright) {
            return new TextBoxItem(box, fillRgb, textLeft, textRight, paras, gap, lineRgb, lineWidth, rounded, overlay,
                    new Turn(direction, onPage, upright), groundRgb);
        }

        public float top() {
            return box.top();
        }

        public float bottom() {
            return box.bottom();
        }

        public float x() {
            return box.x();
        }

        public float right() {
            return box.right();
        }
    }

    public record Turn(int direction, Box onPage, boolean upright) {}

    public record Column(float left, float right, List<Item> items) {}

    public record Band(float top, float bottom, List<Column> columns) {}
}
