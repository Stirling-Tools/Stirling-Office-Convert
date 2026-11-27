package stirling.software.officeconvert.topdf.ofd;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.io.Dom;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

final class OfdText {

    static final int MAX_CODES = 100_000;

    private record Faces(FontFace embedded, OfdOutlines outlines, FontFace system) {}

    record Program(FontFace face, OfdOutlines outlines) {}

    private final OfdPackage pkg;

    private final OfdResources res;

    private final FontLibrary library;

    private final Set<String> notes;

    private final Map<String, Faces> faces = new HashMap<>();

    private final Map<String, Program> embedded;

    OfdText(OfdPackage pkg, OfdResources res, FontLibrary library, Set<String> notes,
            Map<String, Program> embedded) {
        this.pkg = pkg;
        this.res = res;
        this.library = library;
        this.notes = notes;
        this.embedded = embedded;
    }

    void draw(PdfCanvas canvas, Element e, Element param, AffineTransform ctm, double alpha, OfdPainter painter)
            throws IOException {
        double size = OfdNumbers.number(e.getAttribute("Size"), 0);
        if (!(size > 0 && size < 5000)) {
            return;
        }
        boolean fill = OfdPainter.flag(e, "Fill", true);
        boolean stroke = OfdPainter.flag(e, "Stroke", false);
        if (!fill && !stroke) {
            return;
        }
        String fontId = e.getAttribute("Font").trim();
        OfdResources.Font font = res.font(fontId);
        int weight = OfdNumbers.integer(e.getAttribute("Weight"), 400);
        boolean bold = weight >= 600 || font != null && font.bold();
        boolean italic = OfdPainter.flag(e, "Italic", false) || font != null && font.italic();
        Faces f = faces(fontId, font, bold, italic);
        Color color = fill ? painter.fillColor(e, param, alpha) : painter.strokeColor(e, param, alpha);
        double hscale = OfdNumbers.number(e.getAttribute("HScale"), 1);
        if (!(hscale > 0.01 && hscale < 100)) {
            hscale = 1;
        }
        int readDir = direction(e.getAttribute("ReadDirection"));
        int charDir = direction(e.getAttribute("CharDirection"));
        Map<Integer, Integer> glyphs = glyphs(e);
        if (ctm != null) {
            canvas.transform(ctm);
        }
        TextStyle base = TextStyle.of(f.system(), (float) size).color(color);
        double penX = 0;
        double penY = 0;
        int code = 0;
        for (Element tc : Dom.kids(e, "TextCode")) {
            String s = Dom.text(tc);
            if (s.isEmpty()) {
                continue;
            }
            int[] cps = s.codePoints().limit(MAX_CODES).toArray();
            double x = OfdNumbers.number(tc.getAttribute("X"), penX);
            double y = OfdNumbers.number(tc.getAttribute("Y"), penY);
            double[] dx = OfdNumbers.deltas(tc.getAttribute("DeltaX"), cps.length - 1);
            double[] dy = OfdNumbers.deltas(tc.getAttribute("DeltaY"), cps.length - 1);
            for (int i = 0; i < cps.length; i++, code++) {
                if ((code & 1023) == 1023) {
                    OfficeZip.checkNotInterrupted();
                }
                int cp = cps[i];
                if (!Character.isWhitespace(cp) && !Character.isISOControl(cp)) {
                    glyph(canvas, cp, glyphs.getOrDefault(code, 0), x, y, f, base, hscale, charDir);
                }
                if (i < dx.length || i < dy.length) {
                    x += i < dx.length ? dx[i] : 0;
                    y += i < dy.length ? dy[i] : 0;
                    continue;
                }
                FontFace face = f.embedded() != null && f.embedded().covers(cp) ? f.embedded() : f.system();
                double advance = face.advance(cp, (float) size) * hscale;
                switch (readDir) {
                    case 90 -> y += advance;
                    case 180 -> x -= advance;
                    case 270 -> y -= advance;
                    default -> x += advance;
                }
            }
            penX = x;
            penY = y;
        }
    }

    private void glyph(PdfCanvas canvas, int cp, int glyphId, double x, double y, Faces f, TextStyle base,
            double hscale, int charDir) throws IOException {
        String ch = new String(Character.toChars(cp));
        FontFace embedded = f.embedded();
        boolean covered = embedded != null && embedded.covers(cp);
        Shape shape = !covered && glyphId > 0 && f.outlines() != null ? f.outlines().outline(glyphId) : null;
        boolean privateUse = Character.getType(cp) == Character.PRIVATE_USE;
        if (shape == null && !covered && privateUse && !f.system().covers(cp)) {
            return;
        }
        TextStyle style = covered ? base.face(embedded) : base;
        if (shape == null && charDir == 0 && hscale == 1) {
            canvas.text(ch, (float) x, (float) y, style);
            return;
        }
        canvas.save();
        try {
            canvas.translate((float) x, (float) y);
            if (charDir != 0) {
                canvas.rotate(charDir, 0, 0);
            }
            if (shape != null) {
                AffineTransform t = AffineTransform.getScaleInstance(base.size() * hscale, base.size());
                canvas.draw(t.createTransformedShape(shape), Fill.solid(base.color()), null);
                if (!privateUse) {
                    boolean was = canvas.hideText(true);
                    canvas.text(ch, 0, 0, base);
                    canvas.hideText(was);
                }
                return;
            }
            canvas.text(ch, 0, 0, hscale == 1 ? style : style.horizontalScale((float) (hscale * 100)));
        } finally {
            canvas.restore();
        }
    }

    private static Map<Integer, Integer> glyphs(Element e) {
        Map<Integer, Integer> out = new HashMap<>();
        for (Element cg : Dom.kids(e, "CGTransform")) {
            if (out.size() >= MAX_CODES) {
                break;
            }
            double[] list = OfdNumbers.list(Dom.text(Dom.kid(cg, "Glyphs")));
            int position = OfdNumbers.integer(cg.getAttribute("CodePosition"), 0);
            int count = Math.max(1, OfdNumbers.integer(cg.getAttribute("CodeCount"), 1));
            if (count != list.length || position < 0 || count > MAX_CODES) {
                continue;
            }
            for (int i = 0; i < count && out.size() < MAX_CODES; i++) {
                out.putIfAbsent(position + i, (int) list[i]);
            }
        }
        return out;
    }

    private Faces faces(String id, OfdResources.Font font, boolean bold, boolean italic) throws IOException {
        String key = id + '\u0000' + bold + italic;
        Faces known = faces.get(key);
        if (known != null) {
            return known;
        }
        Program program = null;
        if (font != null && font.file() != null && pkg.exists(font.file())) {
            program = embedded.get(font.file());
            if (program == null) {
                FontFace read = null;
                byte[] data = null;
                try {
                    data = pkg.read(font.file(), FontLibrary.MAX_DOCUMENT_FONT_BYTES);
                    read = FontLibrary.documentFont(data);
                } catch (InterruptedIOException e) {
                    throw e;
                } catch (IOException | RuntimeException e) {
                    read = null;
                }
                OfdOutlines outlines = data == null ? null : OfdOutlines.of(data);
                if (read == null && outlines == null) {
                    notes.add("An embedded font in the OFD document could not be read; a substitute was used");
                }
                program = new Program(read, outlines);
                embedded.put(font.file(), program);
            }
        }
        String family = family(font);
        FontFace system = library.find(family, bold, italic);
        Faces made = program == null ? new Faces(null, null, system)
                : new Faces(program.face(), program.outlines(), system);
        faces.put(key, made);
        return made;
    }

    static String family(OfdResources.Font font) {
        if (font == null) {
            return "SimSun";
        }
        String name = !font.name().isEmpty() ? font.name() : font.family();
        if (name.isEmpty()) {
            return font.serif() ? "SimSun" : "SimHei";
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith("_gb2312")) {
            return name.substring(0, name.length() - "_gb2312".length());
        }
        return name;
    }

    private static int direction(String s) {
        int d = OfdNumbers.integer(s, 0);
        return switch (d) {
            case 90, 180, 270 -> d;
            default -> 0;
        };
    }
}
