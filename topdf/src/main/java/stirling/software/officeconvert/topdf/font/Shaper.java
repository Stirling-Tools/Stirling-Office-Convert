package stirling.software.officeconvert.topdf.font;

import java.awt.AWTError;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.util.Arrays;

final class Shaper {

    private static final FontRenderContext FRC = new FontRenderContext(null, false, true);

    private static final int INVISIBLE = 0xFFFE;

    private Shaper() {}

    static GlyphRun shape(FontFace face, String text, boolean rightToLeft) {
        Font awt = text.isEmpty() ? null : face.program().awtFont();
        if (awt != null) {
            try {
                GlyphRun run = layout(face, awt, text, rightToLeft);
                if (run != null) {
                    float k = face.glyphStretch(text);
                    return k == 1 ? run : run.stretched(k);
                }
            } catch (RuntimeException | LinkageError | InternalError | AWTError e) {
                return simple(face, text, rightToLeft);
            }
        }
        return simple(face, text, rightToLeft);
    }

    private static GlyphRun layout(FontFace face, Font awt, String text, boolean rightToLeft) {
        char[] chars = text.toCharArray();
        int flags = rightToLeft ? Font.LAYOUT_RIGHT_TO_LEFT : Font.LAYOUT_LEFT_TO_RIGHT;
        GlyphVector gv = awt.layoutGlyphVector(FRC, chars, 0, chars.length, flags);
        int n = gv.getNumGlyphs();
        int glyphCount = face.program().glyphCount();
        float[] pos = gv.getGlyphPositions(0, n + 1, null);
        int[] codes = gv.getGlyphCodes(0, n, null);
        int[] chars2 = gv.getGlyphCharIndices(0, n, null);
        int[] glyphs = new int[n];
        float[] x = new float[n];
        float[] y = new float[n];
        int[] clusters = new int[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            int code = codes[i];
            if (code >= INVISIBLE) {
                continue;
            }
            if (code < 0 || code >= glyphCount) {
                return null;
            }
            glyphs[m] = code;
            x[m] = pos[2 * i];
            y[m] = -pos[2 * i + 1];
            clusters[m] = Math.max(0, Math.min(chars.length - 1, chars2[i]));
            m++;
        }
        return new GlyphRun(face, text, rightToLeft, true, Arrays.copyOf(glyphs, m), Arrays.copyOf(x, m),
                Arrays.copyOf(y, m), Arrays.copyOf(clusters, m), pos[2 * n]);
    }

    static GlyphRun simple(FontFace face, String text, boolean rightToLeft) {
        int count = text.codePointCount(0, text.length());
        int[] glyphs = new int[count];
        int[] clusters = new int[count];
        int[] advances = new int[count];
        int m = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!FontFace.ignorable(cp) && cp != FontFace.SOFT_HYPHEN) {
                glyphs[m] = face.glyph(cp);
                advances[m] = face.advance(cp);
                clusters[m] = i;
                m++;
            }
            i += Character.charCount(cp);
        }
        int[] g = new int[m];
        int[] c = new int[m];
        float[] x = new float[m];
        float pen = 0;
        for (int k = 0; k < m; k++) {
            int from = rightToLeft ? m - 1 - k : k;
            g[k] = glyphs[from];
            c[k] = clusters[from];
            x[k] = pen;
            pen += advances[from];
        }
        GlyphRun run = new GlyphRun(face, text, rightToLeft, false, g, x, new float[m], c, pen);
        float k = face.glyphStretch(text);
        return k == 1 ? run : run.drawnAt(k);
    }
}
