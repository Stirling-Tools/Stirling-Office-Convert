package stirling.software.officeconvert.topdf.font;

import java.util.Arrays;
import java.util.Objects;

public final class GlyphRun {

    private final FontFace face;

    private final String text;

    private final boolean rightToLeft;

    private final boolean shaped;

    private final int[] glyphs;

    private final float[] x;

    private final float[] y;

    private final int[] clusters;

    private final float advance;

    private final float stretch;

    private volatile String[] texts;

    GlyphRun(FontFace face, String text, boolean rightToLeft, boolean shaped, int[] glyphs, float[] x, float[] y,
            int[] clusters, float advance) {
        this(face, text, rightToLeft, shaped, glyphs, x, y, clusters, advance, 1);
    }

    private GlyphRun(FontFace face, String text, boolean rightToLeft, boolean shaped, int[] glyphs, float[] x,
            float[] y, int[] clusters, float advance, float stretch) {
        this.stretch = stretch;
        this.face = Objects.requireNonNull(face, "face");
        this.text = Objects.requireNonNull(text, "text");
        this.rightToLeft = rightToLeft;
        this.shaped = shaped;
        this.glyphs = glyphs;
        this.x = x;
        this.y = y;
        this.clusters = clusters;
        this.advance = advance;
    }

    public FontFace face() {
        return face;
    }

    public String text() {
        return text;
    }

    public boolean rightToLeft() {
        return rightToLeft;
    }

    public boolean shaped() {
        return shaped;
    }

    public int size() {
        return glyphs.length;
    }

    public int glyph(int i) {
        return glyphs[i];
    }

    public float x(int i) {
        return x[i];
    }

    public float y(int i) {
        return y[i];
    }

    public int cluster(int i) {
        return clusters[i];
    }

    public float advance() {
        return advance;
    }

    // The same glyphs drawn k times as wide
    GlyphRun stretched(float k) {
        float[] sx = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            sx[i] = x[i] * k;
        }
        return new GlyphRun(face, text, rightToLeft, shaped, glyphs, sx, y, clusters, advance * k, stretch * k);
    }

    // Glyphs whose positions already include a scale k, which the glyphs themselves are drawn at
    GlyphRun drawnAt(float k) {
        return new GlyphRun(face, text, rightToLeft, shaped, glyphs, x, y, clusters, advance, k);
    }

    /** The horizontal scale the glyphs are drawn at, 1 unless a stand-in keeps another font's widths. */
    public float stretch() {
        return stretch;
    }

    public float width(float size) {
        return advance * size / face.unitsPerEm();
    }

    public int[] glyphs() {
        return glyphs.clone();
    }

    public String clusterText(int i) {
        String[] t = texts;
        if (t == null) {
            t = new String[clusters.length];
            int[] starts = Arrays.stream(clusters).distinct().sorted().toArray();
            boolean[] seen = new boolean[starts.length];
            for (int k = 0; k < clusters.length; k++) {
                int g = rightToLeft ? clusters.length - 1 - k : k;
                int at = Arrays.binarySearch(starts, clusters[g]);
                if (!seen[at]) {
                    seen[at] = true;
                    int end = at + 1 < starts.length ? starts[at + 1] : text.length();
                    t[g] = text.substring(starts[at], end);
                }
            }
            texts = t;
        }
        return t[i];
    }

    // Glyphs that draw one piece of text together (a body and its dots, a letter and its points, a vowel sign
    // drawn before its consonant): the end index of each group at its first glyph, 0 elsewhere
    public int[] groups() {
        int n = clusters.length;
        int[] ends = new int[n];
        if (n == 0) {
            return ends;
        }
        int[] keys = new int[n];
        for (int i = 0; i < n; i++) {
            keys[i] = clusters[i];
            while (keys[i] > 0 && keys[i] < text.length() && mark(text.charAt(keys[i]))) {
                keys[i]--;
            }
        }
        int[] suffix = new int[n];
        suffix[n - 1] = keys[n - 1];
        for (int i = n - 2; i >= 0; i--) {
            suffix[i] = rightToLeft ? Math.max(suffix[i + 1], keys[i]) : Math.min(suffix[i + 1], keys[i]);
        }
        int start = 0;
        int prefix = keys[0];
        for (int i = 1; i <= n; i++) {
            if (i == n || (rightToLeft ? prefix > suffix[i] : prefix < suffix[i])) {
                if (i - start > 1) {
                    ends[start] = i;
                }
                start = i;
            }
            if (i < n) {
                prefix = rightToLeft ? Math.min(prefix, keys[i]) : Math.max(prefix, keys[i]);
            }
        }
        return ends;
    }

    // A mark drawn over its letter (a point, a vowel sign above or below) belongs to the letter's group
    private static boolean mark(char c) {
        int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }

    /** The glyph of a group that stands for the group's text: its widest, which the others sit on. */
    public int carrier(int start, int end) {
        int best = start;
        for (int i = start + 1; i < end; i++) {
            boolean wider = glyphs[best] <= 0 || face.glyphAdvance(glyphs[i]) > face.glyphAdvance(glyphs[best]);
            if (glyphs[i] > 0 && wider) {
                best = i;
            }
        }
        return best;
    }

    /** The text of the glyphs from {@code start} to {@code end}, in logical order. */
    public String groupText(int start, int end) {
        int lo = Integer.MAX_VALUE;
        int hi = -1;
        for (int i = start; i < end; i++) {
            lo = Math.min(lo, clusters[i]);
            hi = Math.max(hi, clusters[i]);
        }
        int limit = text.length();
        for (int c : clusters) {
            if (c > hi && c < limit) {
                limit = c;
            }
        }
        return hi < 0 ? "" : text.substring(lo, limit);
    }

    @Override
    public String toString() {
        return "GlyphRun[" + face.family() + (rightToLeft ? ", rtl" : "") + (shaped ? ", shaped" : "") + ", "
                + Arrays.toString(glyphs) + "]";
    }
}
