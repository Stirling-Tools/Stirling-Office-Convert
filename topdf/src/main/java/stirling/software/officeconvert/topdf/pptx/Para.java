package stirling.software.officeconvert.topdf.pptx;

import java.util.List;
import java.util.function.Supplier;

import org.apache.poi.sl.usermodel.TabStop.TabStopType;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;

import stirling.software.officeconvert.topdf.io.DecodedPicture;

record Para(List<Piece> pieces, EmptyLine emptyLine, TextAlign align, float marL, float marR, float indent, Spacing line,
        Spacing before, Spacing after, float defTab, List<Tab> tabs, Bullet bullet, int level, boolean rtl) {

    record Tab(float position, TabStopType type) {}

    // The look of a line without text, measured only when a line needs it
    static final class EmptyLine {

        private final Supplier<Piece> probe;

        private boolean measured;

        private Piece piece;

        EmptyLine(Supplier<Piece> probe) {
            this.probe = probe;
        }

        Piece get() {
            if (!measured) {
                measured = true;
                piece = probe.get();
            }
            return piece;
        }
    }

    Piece empty() {
        return emptyLine == null ? null : emptyLine.get();
    }

    record Spacing(float percent, float points) {

        static final Spacing SINGLE = new Spacing(1, -1);

        static final Spacing NONE = new Spacing(0, 0);

        static Spacing of(Double poi, Spacing fallback) {
            if (poi == null || !Double.isFinite(poi)) {
                return fallback;
            }
            return poi >= 0 ? new Spacing((float) (poi / 100), -1) : new Spacing(0, (float) -poi);
        }

        boolean exact() {
            return points >= 0;
        }

        float amount(float size) {
            return exact() ? points : percent * 1.2f * size;
        }
    }

    record Bullet(Piece piece, DecodedPicture picture, float height) {

        Bullet(Piece piece) {
            this(piece, null, 0);
        }

        float width() {
            if (picture == null) {
                return piece.style().width(piece.text());
            }
            float w = picture.naturalWidth();
            float h = picture.naturalHeight();
            return w > 0 && h > 0 ? Math.min(height * 8, height * w / h) : height;
        }
    }

    boolean isEmpty() {
        for (Piece p : pieces) {
            if (!p.text().isEmpty() && !p.text().equals("\n")) {
                return false;
            }
        }
        return true;
    }
}
