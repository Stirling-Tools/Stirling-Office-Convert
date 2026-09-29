package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.pdf.PageSize;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;
import stirling.software.officeconvert.topdf.pdf.TextStyle;
import stirling.software.officeconvert.topdf.testing.TestFonts;

class ShapingTest {

    private static final String ARABIC = "سلام عليكم";

    private static final String DEVANAGARI = "किसी क्षेत्र";

    @TempDir
    Path dir;

    @Test
    void knowsWhichTextNeedsShaping() {
        assertFalse(FontFace.needsShaping("Hello, world 123"));
        assertFalse(FontFace.needsShaping("Été 中文"));
        assertTrue(FontFace.needsShaping(ARABIC));
        assertTrue(FontFace.needsShaping(DEVANAGARI));
        assertTrue(FontFace.needsShaping("é"));
        assertTrue(FontFace.needsShaping("שלום"));
    }

    @Test
    void shapesArabicIntoJoinedFormsInVisualOrder() {
        FontFace face = covering(0x0644);
        GlyphRun run = face.shape(ARABIC, true);
        assertTrue(run.shaped(), run.toString());
        assertTrue(run.rightToLeft());
        Set<Integer> nominal = nominal(face, ARABIC);
        assertTrue(anyContextual(run, nominal), "no joined forms in " + run);
        int words = 0;
        for (int i = 0; i < run.size(); i++) {
            words += run.clusterText(i) != null && run.clusterText(i).equals(" ") ? 1 : 0;
        }
        assertEquals(1, words);
        assertTrue(run.cluster(0) > run.cluster(run.size() - 1), "the first glyph drawn is the last character");
        GlyphRun lamAlef = face.shape("لا", true);
        assertTrue(lamAlef.size() < 2 || anyContextual(lamAlef, nominal(face, "لا")), lamAlef.toString());
        assertTrue(run.advance() > 0);
        for (int i = 1; i < run.size(); i++) {
            assertTrue(run.x(i) >= run.x(i - 1) - face.unitsPerEm(), "positions run left to right");
        }
    }

    @Test
    void shapesDevanagariClustersAndConjuncts() {
        FontFace face = covering(0x0915);
        GlyphRun run = face.shape(DEVANAGARI, false);
        assertTrue(run.shaped(), run.toString());
        assertEquals(0, run.cluster(0));
        assertEquals(0, run.cluster(1), "the i sign is drawn before its consonant, in the same cluster");
        assertNotEquals(face.glyph(0x0915), run.glyph(0), "the i sign comes first");
        assertEquals("कि", run.clusterText(0));
        GlyphRun conjunct = face.shape("क्ष", false);
        assertTrue(conjunct.size() < 3 || anyContextual(conjunct, nominal(face, "क्ष")),
                conjunct.toString());
    }

    @Test
    void fallsBackToCmapGlyphsForFontsItMustNotShape() throws Exception {
        FontLibrary docFonts = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("none"))))
                .withFonts(List.of(TestFonts.renamed("Doc Font")));
        FontFace face = docFonts.find("Doc Font", false, false);
        assertFalse(face.shapeable(), "fonts embedded in a document are never handed to the JDK's native shaper");
        GlyphRun run = face.shape("ab c", false);
        assertFalse(run.shaped());
        assertEquals(4, run.size());
        assertEquals(face.glyph('a'), run.glyph(0));
        assertEquals(face.width("ab c", face.unitsPerEm()), run.advance(), 0.01f);
        GlyphRun rtl = face.shape("abc", true);
        assertEquals(face.glyph('c'), rtl.glyph(0));
        assertEquals(2, rtl.cluster(0));
    }

    @Test
    void bidiSplitsAndReordersRuns() {
        String text = "abc אבג def";
        assertTrue(BidiRuns.needed(text));
        assertFalse(BidiRuns.needed("plain"));
        List<BidiRuns.Run> runs = BidiRuns.logical(text, null);
        assertEquals(3, runs.size());
        assertFalse(runs.get(0).rightToLeft());
        assertTrue(runs.get(1).rightToLeft());
        assertEquals("אבג", runs.get(1).of(text));
        String rtl = "אב abc גד";
        assertTrue(BidiRuns.baseRightToLeft(rtl));
        List<BidiRuns.Run> visual = BidiRuns.visual(BidiRuns.logical(rtl, null));
        assertEquals("גד", visual.get(0).of(rtl).strip());
        assertEquals("abc", visual.get(visual.size() / 2).of(rtl).strip());
        assertTrue(BidiRuns.logical("", null).isEmpty());
    }

    @Test
    void drawsShapedGlyphsThatStillExtract() throws Exception {
        FontFace arabic = covering(0x0644);
        FontFace hindi = covering(0x0915);
        Path pdf = dir.resolve("shaped.pdf");
        try (PdfOutput out = new PdfOutput(FontLibrary.system())) {
            try (PdfCanvas page = out.newPage(PageSize.A4)) {
                GlyphRun a = arabic.shape(ARABIC, true);
                float w = page.drawGlyphs(a, 72, 100, TextStyle.of(arabic, 18));
                assertEquals(a.width(18), w, 0.01f);
                page.drawGlyphs(hindi.shape(DEVANAGARI, false), 72, 160, TextStyle.of(hindi, 18));
                page.drawGlyphs(hindi.shape(DEVANAGARI, false), 72, 190, TextStyle.of(hindi, 18), true);
                page.text("Latin after", 72, 220, TextStyle.of(FontLibrary.system().find("Arial", false, false), 12));
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            out.save(bytes);
            Files.write(pdf, bytes.toByteArray());
        }
        Path reports = Path.of(System.getProperty("topdf.reportDir", "build/reports"));
        Files.createDirectories(reports);
        Files.copy(pdf, reports.resolve("shaping.pdf"), StandardCopyOption.REPLACE_EXISTING);
        byte[] raw = Files.readAllBytes(pdf);
        try (PDDocument doc = Loader.loadPDF(raw)) {
            String content = new String(doc.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(content.contains("/ActualText <FEFF0633"), "Word writes no ActualText; by default neither do we");
            assertTrue(content.contains("/ActualText <FEFF0915093F0938"), content);
            assertTrue(content.contains("/ActualText <FEFF>>> BDC"), "the i sign drawn after its consonant " + content);
            assertEquals(2, content.split("BDC", -1).length - 1, content);
            assertTrue(doc.getPage(0).getResources().getFont(org.apache.pdfbox.cos.COSName.getPDFName("F1")).isEmbedded());
            String text = Normalizer.normalize(new PDFTextStripper().getText(doc), Normalizer.Form.NFKC);
            for (int cp : new int[] {0x0633, 0x0645, 0x0639, 0x0643, 0x0915, 0x0938, 0x0924}) {
                assertTrue(text.indexOf(cp) >= 0, "U+" + Integer.toHexString(cp) + " missing from " + text);
            }
            assertTrue(text.contains("Latin after"), text);
        }
    }

    @Test
    void glyphsThatShareOrSwapTheirCharactersAreGrouped() throws Exception {
        FontFace face = bundled().find("Arial", false, false);
        int a = face.glyph('a');
        int b = face.glyph('b');
        int c = face.glyph('c');
        GlyphRun split = run(face, "ac", false, new int[] {a, b, c}, new int[] {0, 0, 1});
        assertArrayEqualsInts(new int[] {2, 0, 0}, split.groups());
        assertEquals("a", split.groupText(0, 2));
        GlyphRun swapped = run(face, "abc", false, new int[] {b, a, c}, new int[] {1, 0, 2});
        assertArrayEqualsInts(new int[] {2, 0, 0}, swapped.groups());
        assertEquals("ab", swapped.groupText(0, 2));
        GlyphRun rtl = run(face, "abc", true, new int[] {c, b, b, a}, new int[] {2, 1, 1, 0});
        assertArrayEqualsInts(new int[] {0, 3, 0, 0}, rtl.groups());
        assertEquals("b", rtl.groupText(1, 3));
        GlyphRun plain = run(face, "abc", false, new int[] {a, b, c}, new int[] {0, 1, 2});
        assertArrayEqualsInts(new int[3], plain.groups());
        GlyphRun narrowFirst = run(face, "im", false, new int[] {face.glyph('i'), face.glyph('m')}, new int[] {0, 0});
        assertEquals(1, narrowFirst.carrier(0, 2), "the widest glyph carries the group's text");
        GlyphRun pointed = run(face, "בְג", true, new int[] {c, face.glyph('i'), face.glyph('m')},
                new int[] {2, 1, 0});
        assertArrayEqualsInts(new int[] {0, 3, 0}, pointed.groups());
        assertEquals("בְ", pointed.groupText(1, 3), "a point belongs to its letter");
        assertEquals(2, pointed.carrier(1, 3));
    }

    @Test
    void aSplitLetterExtractsOnceAndAGlyphKeepsTheTextItWasShapedFor() throws Exception {
        FontLibrary lib = bundled();
        FontFace face = lib.find("Arial", false, false);
        int a = face.glyph('a');
        int b = face.glyph('b');
        int c = face.glyph('c');
        int d = face.glyph('d');
        byte[] pdf;
        try (PdfOutput out = new PdfOutput(lib)) {
            try (PdfCanvas page = out.newPage(PageSize.A4)) {
                TextStyle style = TextStyle.of(face, 12);
                page.drawGlyphs(run(face, "ac", false, new int[] {a, b, c}, new int[] {0, 0, 1}), 72, 100, style);
                page.drawGlyphs(run(face, "x", false, new int[] {d}, new int[] {0}), 72, 130, style);
                page.drawGlyphs(run(face, "y", false, new int[] {c}, new int[] {0}), 72, 160, style);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            out.save(bytes);
            pdf = bytes.toByteArray();
        }
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String content = new String(doc.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertEquals(1, content.split("BDC", -1).length - 1, content);
            assertTrue(content.contains("/ActualText <FEFF>>> BDC"), "the second glyph speaks no text " + content);
            PDType0Font font = (PDType0Font) doc.getPage(0).getResources()
                    .getFont(org.apache.pdfbox.cos.COSName.getPDFName("F1"));
            assertEquals("a", font.toUnicode(a));
            assertNull(font.toUnicode(b), "the second glyph of a split letter stands for nothing");
            assertEquals("d", font.toUnicode(d), "a glyph keeps its own character");
            assertEquals("c", font.toUnicode(c));
            int x = face.program().glyphCount();
            int y = x + 1;
            assertTrue(content.contains(String.format("<%04X>", x)) && content.contains(String.format("<%04X>", y)),
                    "glyphs drawn for other text get codes of their own " + content);
            assertEquals("x", font.toUnicode(x));
            assertEquals("y", font.toUnicode(y));
            PDCIDFontType2 cid = (PDCIDFontType2) font.getDescendantFont();
            assertEquals(cid.codeToGID(d), cid.codeToGID(x));
            assertEquals(cid.codeToGID(c), cid.codeToGID(y));
            assertEquals(cid.getWidth(c), cid.getWidth(y), 0.01f);
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("x") && text.contains("y") && !text.contains("b"), text);
        }
    }

    private FontLibrary bundled() throws IOException {
        return FontLibrary.of(List.of(Files.createDirectories(dir.resolve("bundled-only"))));
    }

    private static GlyphRun run(FontFace face, String text, boolean rtl, int[] glyphs, int[] clusters) {
        float[] x = new float[glyphs.length];
        float pen = 0;
        for (int i = 0; i < glyphs.length; i++) {
            x[i] = pen;
            pen += face.glyphAdvance(glyphs[i]);
        }
        return new GlyphRun(face, text, rtl, true, glyphs, x, new float[glyphs.length], clusters, pen);
    }

    private static void assertArrayEqualsInts(int[] expected, int[] actual) {
        assertEquals(java.util.Arrays.toString(expected), java.util.Arrays.toString(actual));
    }

    private static FontFace covering(int codePoint) {
        FontFace face = FontLibrary.system().fallback(codePoint, false, false);
        assumeTrue(face != null, "no font for U+" + Integer.toHexString(codePoint) + " on this machine");
        assumeTrue(face.shapeable(), face + " cannot be shaped here");
        assertNotNull(face);
        return face;
    }

    private static Set<Integer> nominal(FontFace face, String text) {
        Set<Integer> out = new HashSet<>();
        text.codePoints().forEach(cp -> out.add(face.glyph(cp)));
        return out;
    }

    private static boolean anyContextual(GlyphRun run, Set<Integer> nominal) {
        for (int i = 0; i < run.size(); i++) {
            if (!nominal.contains(run.glyph(i))) {
                return true;
            }
        }
        return false;
    }
}
