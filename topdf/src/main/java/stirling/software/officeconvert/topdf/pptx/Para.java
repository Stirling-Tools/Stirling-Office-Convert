package stirling.software.officeconvert.topdf.pptx;

import java.util.List;

import org.apache.poi.sl.usermodel.TabStop.TabStopType;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;

import stirling.software.officeconvert.topdf.io.DecodedPicture;

record Para(List<Piece> pieces, Piece empty, TextAlign align, float marL, float marR, float indent, Spacing line,
        Spacing before, Spacing after, float defTab, List<Tab> tabs, Bullet bullet, int level, boolean rtl) {

    record Tab(float position, TabStopType type) {}

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
