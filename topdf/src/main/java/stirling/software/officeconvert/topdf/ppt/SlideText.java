package stirling.software.officeconvert.topdf.ppt;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Paint;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.text.AttributedCharacterIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.util.Matrix;
import org.apache.poi.sl.draw.DrawTextParagraph;

import de.rototor.pdfbox.graphics2d.IPdfBoxGraphics2DFontTextDrawer;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.font.BidiRuns;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontRun;
import stirling.software.officeconvert.topdf.font.GlyphRun;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

// POI lays text out with AWT fonts; each run is written with our fonts, fitted to the width AWT gave it
final class SlideText implements IPdfBoxGraphics2DFontTextDrawer {

    private record Piece(String text, FontFace face, float size, float rise, float advance, Color color,
            boolean underline, boolean strike, SlideLinks.Target link) {}

    private final RenderJob job;

    private final Map<String, String> families;

    private final Map<String, FontFace> faces = new HashMap<>();

    private Map<String, SlideLinks.Target> targets = Map.of();

    private final List<SlideLinks.Area> areas = new ArrayList<>();

    private float pageHeight;

    SlideText(RenderJob job, Map<String, String> families) {
        this.job = job;
        this.families = families;
    }

    void startSlide(Map<String, SlideLinks.Target> links, float height) {
        targets = links;
        pageHeight = height;
        areas.clear();
    }

    List<SlideLinks.Area> links() {
        return List.copyOf(areas);
    }

    // Gradient and pattern text stays with POI's outlines
    @Override
    public boolean canDrawText(AttributedCharacterIterator it, IFontTextDrawerEnv env) {
        for (int i = it.getBeginIndex(); i < it.getEndIndex(); ) {
            it.setIndex(i);
            Object fg = it.getAttribute(TextAttribute.FOREGROUND);
            Paint paint = fg instanceof Paint p ? p : env.getPaint();
            if (it.getAttribute(TextAttribute.BACKGROUND) != null || !(paint instanceof Color)) {
                return false;
            }
            i = it.getRunLimit();
        }
        return true;
    }

    @Override
    public void drawText(AttributedCharacterIterator it, IFontTextDrawerEnv env) throws IOException {
        FontRenderContext frc = env.getFontRenderContext();
        List<Piece> pieces = new ArrayList<>();
        float total = 0;
        float largest = 1;
        for (int i = it.getBeginIndex(); i < it.getEndIndex(); ) {
            it.setIndex(i);
            int limit = it.getRunLimit();
            Font font = it.getAttribute(TextAttribute.FONT) instanceof Font f ? f : env.getFont();
            Object fg = it.getAttribute(TextAttribute.FOREGROUND);
            Color color = fg instanceof Color c ? c : env.getPaint() instanceof Color c ? c : Color.BLACK;
            boolean underline = TextAttribute.UNDERLINE_ON.equals(it.getAttribute(TextAttribute.UNDERLINE));
            boolean strike = TextAttribute.STRIKETHROUGH_ON.equals(it.getAttribute(TextAttribute.STRIKETHROUGH));
            SlideLinks.Target link = it.getAttribute(DrawTextParagraph.HYPERLINK_HREF) instanceof String href
                    ? targets.get(href) : null;
            StringBuilder b = new StringBuilder(limit - i);
            for (char c = it.current(); it.getIndex() < limit; c = it.next()) {
                b.append(c);
            }
            i = limit;
            String text = b.toString();
            if (text.isEmpty() || font == null) {
                continue;
            }
            AffineTransform t = font.getTransform();
            float scale = (float) Math.sqrt(Math.abs(t.getDeterminant()));
            float size = font.getSize2D() * (scale > 0 ? scale : 1);
            float advance = (float) font.getStringBounds(text, frc).getWidth();
            if (!(size > 0 && size <= 10_000) || !Float.isFinite(advance)) {
                continue;
            }
            pieces.add(new Piece(text, face(font), size, (float) t.getTranslateY(), advance, color, underline, strike,
                    link));
            total += Math.max(0, advance);
            largest = Math.max(largest, size);
        }
        if (pieces.stream().allMatch(p -> p.text().isBlank() && !p.underline() && !p.strike())) {
            return;
        }
        float pad = 2 * largest;
        float height = 2 * pad;
        PdfCanvas canvas = job.output().newForm(Math.max(1, total), height, pad);
        try (canvas) {
            float x = 0;
            for (Piece p : pieces) {
                TextStyle style = TextStyle.of(p.face(), p.size()).color(p.color());
                boolean complex = FontFace.needsShaping(p.text()) || BidiRuns.needed(p.text());
                List<Object> parts = complex ? shaped(p.text(), p.face()) : List.of(p.text());
                float natural = 0;
                for (Object part : parts) {
                    natural += part instanceof GlyphRun g ? g.width(p.size()) : style.width((String) part);
                }
                if (natural > 0 && p.advance() > 0 && Math.abs(p.advance() / natural - 1) > 0.001f) {
                    style = style.horizontalScale(Math.max(1, Math.min(10_000, 100 * p.advance() / natural)));
                }
                float baseline = pad + p.rise();
                float at = x;
                for (Object part : p.text().isBlank() ? List.of() : parts) {
                    at += part instanceof GlyphRun g ? canvas.drawGlyphs(g, at, baseline, style.face(g.face()))
                            : canvas.text((String) part, at, baseline, style);
                }
                if (p.underline()) {
                    canvas.underline(x, baseline, p.advance(), style);
                }
                if (p.strike()) {
                    canvas.strikeout(x, baseline, p.advance(), style);
                }
                if (p.link() != null) {
                    area(env.getCurrentEffectiveTransform(), x, p, p.link());
                }
                x += p.advance();
            }
        }
        PDPageContentStream cs = env.getContentStream();
        cs.saveGraphicsState();
        cs.transform(new Matrix(1, 0, 0, -1, 0, height - pad));
        cs.drawForm(canvas.form());
        cs.restoreGraphicsState();
    }

    private void area(AffineTransform toPage, float x, Piece p, SlideLinks.Target target) {
        if (areas.size() >= 2000 || !(p.advance() > 0)) {
            return;
        }
        Rectangle2D user = new Rectangle2D.Float(x, p.rise() - 0.9f * p.size(), p.advance(), 1.15f * p.size());
        Rectangle2D pdf = toPage.createTransformedShape(user).getBounds2D();
        double top = pageHeight - pdf.getMaxY();
        if (pdf.getWidth() > 0 && pdf.getHeight() > 0 && Double.isFinite(top)) {
            areas.add(new SlideLinks.Area(new Rectangle2D.Double(pdf.getX(), top, pdf.getWidth(), pdf.getHeight()),
                    target));
        }
    }

    // Right-to-left runs in visual order, each shaped with the face that covers it
    private List<Object> shaped(String text, FontFace primary) {
        List<Object> out = new ArrayList<>();
        for (BidiRuns.Run run : BidiRuns.visual(BidiRuns.logical(text, null))) {
            List<FontRun> runs = new ArrayList<>(job.fonts().runs(run.of(text), primary));
            if (run.rightToLeft()) {
                Collections.reverse(runs);
            }
            for (FontRun r : runs) {
                boolean shape = run.rightToLeft() || FontFace.needsShaping(r.text());
                if (shape && r.face().shapeable()) {
                    out.add(r.face().shape(r.text(), run.rightToLeft()));
                } else {
                    out.add(run.rightToLeft() ? new StringBuilder(r.text()).reverse().toString() : r.text());
                }
            }
        }
        return out;
    }

    @Override
    public FontMetrics getFontMetrics(Font f, IFontTextDrawerEnv env) {
        return env.getCalculationGraphics().getFontMetrics(f);
    }

    private FontFace face(Font font) {
        String name = font.getName();
        String family = families.getOrDefault(name.toLowerCase(Locale.ROOT), name);
        String key = family + '\u0000' + font.isBold() + font.isItalic();
        return faces.computeIfAbsent(key, k -> job.fonts().find(family, font.isBold(), font.isItalic()));
    }
}
