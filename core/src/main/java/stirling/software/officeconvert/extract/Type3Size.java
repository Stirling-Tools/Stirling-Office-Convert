package stirling.software.officeconvert.extract;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;

final class Type3Size {

    private static final String CAPITALS = "HEFIKLTNMZXUVWAY";

    private static final String SMALL = "xzvwumnrc";

    private static final float CAP_EM = 0.7f;

    private static final float X_EM = 0.5f;

    private static final int SAMPLE = 6;

    private static final float LOW = 1f / 3;

    private static final float HIGH = 3f;

    private static final int AGREEING = 3;

    private static final float SPREAD = 1.3f;

    private Type3Size() {}

    static float scale(PDType3Font font, UnmappedGlyphs owner) {
        try {
            float caps = top(font, owner, CAPITALS);
            float ratio = caps > 0 ? caps / CAP_EM : top(font, owner, SMALL) / X_EM;
            return ratio > 0 && Float.isFinite(ratio) && (ratio < LOW || ratio > HIGH) ? ratio : 1f;
        } catch (RuntimeException e) {
            return 1f;
        }
    }

    private static float top(PDType3Font font, UnmappedGlyphs owner, String letters) {
        Encoding encoding = font.getEncoding();
        if (encoding == null || font.getCharProcs() == null) {
            return 0;
        }
        AffineTransform matrix = font.getFontMatrix().createAffineTransform();
        if (matrix.getShearX() != 0 || matrix.getShearY() != 0) {
            return 0;
        }
        List<Float> tops = new ArrayList<>();
        for (Map.Entry<Integer, String> e : encoding.getCodeToNameMap().entrySet()) {
            int code = e.getKey();
            String text = font.toUnicode(code, TextPositions.GLYPHS);
            if (text == null || text.length() != 1 || letters.indexOf(text.charAt(0)) < 0
                    || !font.getCharProcs().containsKey(COSName.getPDFName(e.getValue()))) {
                continue;
            }
            PDType3CharProc proc = font.getCharProc(code);
            GeneralPath ink = proc == null ? null : Type3Ink.of(proc, owner);
            if (ink == null) {
                continue;
            }
            Rectangle2D box = ink.getBounds2D();
            if (box.isEmpty()) {
                continue;
            }
            float height = (float) Math.max(box.getMaxY(), -box.getMinY());
            if (height > 0) {
                tops.add(height);
            }
            if (tops.size() >= SAMPLE) {
                break;
            }
        }
        if (tops.size() < AGREEING) {
            return 0;
        }
        float[] sorted = new float[tops.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = tops.get(i);
        }
        Arrays.sort(sorted);
        float middle = sorted[sorted.length / 2];
        int agreeing = 0;
        for (float top : sorted) {
            agreeing += top <= middle * SPREAD && top * SPREAD >= middle ? 1 : 0;
        }
        return agreeing >= AGREEING ? middle : 0;
    }
}
