package stirling.software.officeconvert.table;

import java.io.IOException;
import java.io.Writer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import stirling.software.officeconvert.table.PageContent.PdfWord;

final class PageWords extends PDFTextStripper {

    private static final float MIN_SPACE_EM = 0.15f;

    private static final float MAX_SPACE_EM = 0.35f;

    private static final float DEFAULT_SPACE_EM = 0.28f;

    private static final float ASCENT_EM = 0.8f;

    private static final float DESCENT_EM = 0.2f;

    private final List<PdfWord> words = new ArrayList<>();
    private final List<Integer> directions = new ArrayList<>();
    private final Map<TextPosition, GlyphState> glyphStates = new IdentityHashMap<>();

    private record GlyphState(int rgb, RenderingMode mode) {}

    private PageWords() {}

    static Result read(PDDocument document, int pageNumber) throws IOException {
        PageWords collector = new PageWords();
        collector.setStartPage(pageNumber);
        collector.setEndPage(pageNumber);
        collector.setSortByPosition(true);
        collector.writeText(document, Writer.nullWriter());
        int direction = collector.dominantDirection();
        List<PdfWord> words = new ArrayList<>();
        for (int i = 0; i < collector.words.size(); i++) {
            if (collector.directions.get(i) == direction) {
                words.add(collector.words.get(i));
            }
        }
        return new Result(words, direction);
    }

    record Result(List<PdfWord> words, int direction) {}

    private int dominantDirection() {
        Map<Integer, Integer> chars = new HashMap<>();
        for (int i = 0; i < words.size(); i++) {
            chars.merge(directions.get(i), words.get(i).text().length(), Integer::sum);
        }
        int best = 0;
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> e : chars.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    @Override
    protected void processTextPosition(TextPosition text) {
        PDGraphicsState gs = getGraphicsState();
        RenderingMode mode = gs.getTextState().getRenderingMode();
        PDColor colour =
                mode.isStroke() && !mode.isFill()
                        ? gs.getStrokingColor()
                        : gs.getNonStrokingColor();
        glyphStates.put(text, new GlyphState(Colours.toRgb(colour, 0), mode));
        super.processTextPosition(text);
    }

    @Override
    protected void writeString(String text, List<TextPosition> positions) {
        List<TextPosition> run = new ArrayList<>();
        for (TextPosition tp : positions) {
            String u = tp.getUnicode();
            if (u == null || u.isBlank()) {
                flush(run);
                continue;
            }
            if (!run.isEmpty() && jumps(run.getLast(), tp)) {
                flush(run);
            }
            run.add(tp);
        }
        flush(run);
    }

    private static boolean jumps(TextPosition prev, TextPosition next) {
        float size = Math.max(fontSize(prev), fontSize(next));
        return Math.abs(next.getYDirAdj() - prev.getYDirAdj()) > 0.5f * size
                || next.getXDirAdj() < prev.getXDirAdj() - 0.5f * fontSize(prev);
    }

    private void flush(List<TextPosition> run) {
        if (run.isEmpty()) {
            return;
        }
        int n = run.size();
        float[] left = new float[n];
        float[] right = new float[n];
        String[] texts = new String[n];
        StringBuilder sb = new StringBuilder();
        float size = 0;
        float space = 0;
        int boldCount = 0;
        int italicCount = 0;
        for (int i = 0; i < n; i++) {
            TextPosition tp = run.get(i);
            left[i] = tp.getXDirAdj();
            right[i] = tp.getXDirAdj() + Math.max(tp.getWidthDirAdj(), 0.1f);
            texts[i] = normalise(tp.getUnicode());
            sb.append(texts[i]);
            size = Math.max(size, fontSize(tp));
            space = Math.max(space, tp.getWidthOfSpace());
            GlyphState state = glyphStates.get(tp);
            if (isBold(tp.getFont(), state == null ? RenderingMode.FILL : state.mode())) {
                boldCount++;
            }
            if (isItalic(tp.getFont())) {
                italicCount++;
            }
        }
        float x = left[0];
        float r = right[0];
        for (int i = 1; i < n; i++) {
            x = Math.min(x, left[i]);
            r = Math.max(r, right[i]);
        }
        space =
                space <= 0
                        ? size * DEFAULT_SPACE_EM
                        : Math.clamp(space, size * MIN_SPACE_EM, size * MAX_SPACE_EM);
        TextPosition first = run.getFirst();
        GlyphState state = glyphStates.get(first);
        float baseline = first.getYDirAdj();
        directions.add(Math.floorMod(Math.round(first.getDir()), 360));
        words.add(
                new PdfWord(
                        sb.toString(),
                        x,
                        r,
                        baseline - size * ASCENT_EM,
                        baseline + size * DESCENT_EM,
                        baseline,
                        size,
                        boldCount * 2 > n,
                        italicCount * 2 > n,
                        state == null ? 0 : state.rgb(),
                        space,
                        left,
                        right,
                        texts));
        run.clear();
    }

    private static float fontSize(TextPosition tp) {
        float size = tp.getYScale();
        if (size <= 0.5f || size > 200f) {
            size = tp.getFontSizeInPt();
        }
        if (size <= 0.5f || size > 200f) {
            size = Math.max(tp.getHeightDir() * 1.4f, 4f);
        }
        return size;
    }

    private static boolean isBold(PDFont font, RenderingMode mode) {
        if (mode == RenderingMode.FILL_STROKE) {
            return true;
        }
        if (font == null) {
            return false;
        }
        String name = font.getName() == null ? "" : font.getName().toLowerCase(Locale.ROOT);
        if (name.contains("bold")
                || name.contains("black")
                || name.contains("heavy")
                || name.contains("demi")
                || name.contains("semibold")
                || name.endsWith(",bd")
                || name.endsWith("-bd")) {
            return true;
        }
        PDFontDescriptor fd = font.getFontDescriptor();
        return fd != null && (fd.isForceBold() || fd.getFontWeight() >= 600);
    }

    private static boolean isItalic(PDFont font) {
        if (font == null) {
            return false;
        }
        String name = font.getName() == null ? "" : font.getName().toLowerCase(Locale.ROOT);
        if (name.contains("italic") || name.contains("oblique")) {
            return true;
        }
        PDFontDescriptor fd = font.getFontDescriptor();
        return fd != null && (fd.isItalic() || Math.abs(fd.getItalicAngle()) > 5);
    }

    private static String normalise(String unicode) {
        for (int i = 0; i < unicode.length(); i++) {
            char c = unicode.charAt(i);
            if ((c >= 0xFB00 && c <= 0xFB06) || (c >= 0xFF01 && c <= 0xFF5E)) {
                return Normalizer.normalize(unicode, Normalizer.Form.NFKC);
            }
        }
        return unicode;
    }
}
