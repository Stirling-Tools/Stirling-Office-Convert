package stirling.software.officeconvert.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import stirling.software.officeconvert.PdfToDocx;
import stirling.software.officeconvert.PdfToHtml;

class PdfWebFontsTest {

    private static final String TEXT = "Quarterly revenue grew in every region";

    private static byte[] liberation() throws IOException {
        try (InputStream in = PDDocument.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            return in.readAllBytes();
        }
    }

    private enum Font { NONE, FULL, SUBSET_WITHOUT_CMAP }

    private static PDFont arial(PDDocument doc, boolean subset) throws IOException {
        PDType0Font font = PDType0Font.load(doc, new ByteArrayInputStream(liberation()), subset);
        font.getFontDescriptor().setFontFamily("Arial");
        return font;
    }

    private static String html(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfToHtml.convert(doc, out, new PdfToDocx.Options(1, 0, true, 150, null));
        return out.toString(StandardCharsets.UTF_8);
    }

    private static PDDocument document(Font kind) throws IOException {
        return document(kind, false);
    }

    private static PDDocument document(Font kind, boolean stroked) throws IOException {
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDFont font = kind == Font.NONE ? new PDType1Font(Standard14Fonts.FontName.HELVETICA)
                    : arial(doc, kind == Font.SUBSET_WITHOUT_CMAP);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                if (stroked) {
                    cs.setLineWidth(0.5f);
                    cs.setRenderingMode(RenderingMode.FILL_STROKE);
                }
                cs.beginText();
                cs.setFont(font, 11);
                cs.newLineAtOffset(72, 700);
                cs.showText(TEXT);
                cs.endText();
            }
            doc.save(pdf);
        }
        return Loader.loadPDF(pdf.toByteArray());
    }

    @Test
    void embeddedFontsBecomeSmallWebFontsUnderTheirOwnFamily() throws Exception {
        String html;
        try (PDDocument doc = document(Font.FULL)) {
            html = html(doc);
        }
        Matcher face = Pattern.compile("@font-face\\{font-family:'Arial';font-weight:normal;font-style:normal;src:"
                + "local\\('[^']*'\\),url\\(data:font/woff;base64,([A-Za-z0-9+/=]+)\\) format\\('woff'\\)\\}").matcher(html);
        assertTrue(face.find(), html.substring(0, Math.min(html.length(), 2000)));
        assertTrue(html.contains("'Arial', 'Liberation Sans', 'Arimo', sans-serif"), html);
        byte[] woff = Base64.getDecoder().decode(face.group(1));
        assertTrue(woff.length < liberation().length / 4, "only the used glyphs are kept: " + woff.length);
        byte[] sfnt = sfnt(woff);
        TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(sfnt));
        int os2 = ttf.getTableMap().get("OS/2").getLength() >= 96 ? 5 : 1;
        assertTrue(ttf.getOS2Windows().getVersion() <= os2, "OS/2 version fits its length");
        assertTrue(ttf.getNumberOfGlyphs() < 40, "glyphs " + ttf.getNumberOfGlyphs());
        assertTrue(ttf.getUnicodeCmapLookup().getGlyphId('Q') > 0);
        assertEquals(0, ttf.getUnicodeCmapLookup().getGlyphId('Z'));
    }

    @Test
    void textMadeBoldByAStrokeStillUsesTheEmbeddedRegularFace() throws IOException {
        String html;
        try (PDDocument doc = document(Font.FULL, true)) {
            html = html(doc);
        }
        assertTrue(html.contains("<b>"), html);
        assertTrue(html.contains("@font-face{font-family:'Arial';font-weight:normal;font-style:normal;"),
                html.substring(0, Math.min(html.length(), 2000)));
    }

    @Test
    void fontsThatAreNotEmbeddedOrHaveNoCharacterMapAddNoFaces() throws IOException {
        for (Font kind : new Font[] {Font.NONE, Font.SUBSET_WITHOUT_CMAP}) {
            try (PDDocument doc = document(kind)) {
                String html = html(doc);
                assertFalse(html.contains("@font-face"), html);
                assertTrue(html.contains(TEXT), html);
            }
        }
    }

    @Test
    void unusedOrUnsupportedFontsGiveNothing() throws IOException {
        try (PDDocument doc = document(Font.FULL)) {
            PDFont font = doc.getPage(0).getResources().getFont(doc.getPage(0).getResources().getFontNames().iterator().next());
            assertNull(PdfWebFonts.webFont(font, Set.of(0x4E2D)));
            assertNull(PdfWebFonts.webFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), Set.of((int) 'A')));
            assertNotNull(new PdfWebFonts().webFont(font));
        }
        assertNull(PdfWebFonts.woff(new byte[] {1, 2, 3}));
    }

    private static byte[] sfnt(byte[] woff) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(woff);
        assertEquals(0x774F4646, in.getInt(0));
        assertEquals(woff.length, in.getInt(8));
        int count = in.getShort(12) & 0xFFFF;
        ByteBuffer out = ByteBuffer.allocate(in.getInt(16));
        out.putInt(in.getInt(4)).putShort((short) count).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        int at = 12 + 16 * count;
        for (int i = 0; i < count; i++) {
            int e = 44 + 20 * i;
            int offset = in.getInt(e + 4);
            int comp = in.getInt(e + 8);
            int orig = in.getInt(e + 12);
            byte[] table = new byte[orig];
            if (comp < orig) {
                Inflater inflater = new Inflater();
                inflater.setInput(woff, offset, comp);
                assertEquals(orig, inflater.inflate(table));
                inflater.end();
            } else {
                System.arraycopy(woff, offset, table, 0, orig);
            }
            out.putInt(12 + 16 * i, in.getInt(e)).putInt(16 + 16 * i, in.getInt(e + 16)).putInt(20 + 16 * i, at)
                    .putInt(24 + 16 * i, orig);
            out.put(at, table);
            at += (orig + 3) & ~3;
        }
        return out.array();
    }

    private static byte[] withFsType(int fsType) throws IOException {
        byte[] sfnt = liberation();
        ByteBuffer b = ByteBuffer.wrap(sfnt);
        for (int i = 0; i < (b.getShort(4) & 0xFFFF); i++) {
            if (b.getInt(12 + 16 * i) == 0x4F532F32) {
                b.putShort(b.getInt(20 + 16 * i) + 8, (short) fsType);
            }
        }
        return sfnt;
    }

    @Test
    void fontsThatForbidEmbeddingSubsettingOrOutlinesAreNotEmbedded() throws IOException {
        assertFalse(PdfWebFonts.restricted(withFsType(0)));
        assertFalse(PdfWebFonts.restricted(withFsType(0x0008)));
        assertTrue(PdfWebFonts.restricted(withFsType(0x0002)));
        assertTrue(PdfWebFonts.restricted(withFsType(0x0100)));
        assertTrue(PdfWebFonts.restricted(withFsType(0x0200)));
    }

    @Test
    void anEmbeddedFontIsRenamedToTheDocumentsFamily() throws IOException {
        byte[] named = PdfWebFonts.named(liberation(), "Georgia", true, false);
        try (TrueTypeFont font = new TTFParser(true).parse(new RandomAccessReadBuffer(named))) {
            assertEquals("Georgia", font.getNaming().getFontFamily());
            assertEquals("Bold", font.getNaming().getFontSubFamily());
            assertEquals("Georgia-Bold", font.getNaming().getPostScriptName());
            assertNotNull(font.getUnicodeCmapLookup());
        }
    }
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void aFlatTreeOfManyPagesIsScannedInOnePass() throws IOException {
        try (PDDocument doc = flat(40_000)) {
            assertNotNull(PdfWebFonts.of(doc, 1, 0));
        }
    }

    @Test
    void anInterruptedScanStops() throws IOException {
        try (PDDocument doc = flat(10)) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> PdfWebFonts.of(doc, 1, 0));
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void aPageTreeNestedTooDeeplyIsAnIOException() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            COSDictionary node = new COSDictionary();
            doc.getDocumentCatalog().getCOSObject().setItem(COSName.PAGES, node);
            for (int i = 0; i < 200_000; i++) {
                node.setItem(COSName.TYPE, COSName.PAGES);
                node.setInt(COSName.COUNT, 1);
                COSDictionary child = new COSDictionary();
                COSArray kids = new COSArray();
                kids.add(child);
                node.setItem(COSName.KIDS, kids);
                node = child;
            }
            node.setItem(COSName.TYPE, COSName.PAGE);
            node.setItem(COSName.MEDIA_BOX, PDRectangle.LETTER.getCOSArray());
            Throwable[] thrown = new Throwable[1];
            Thread t = Thread.ofPlatform().stackSize(256 << 10).start(() -> {
                try {
                    PdfWebFonts.of(doc, 1, 0);
                } catch (Throwable e) {
                    thrown[0] = e;
                }
            });
            t.join();
            assertTrue(thrown[0] instanceof IOException, String.valueOf(thrown[0]));
        }
    }

    private static PDDocument flat(int pages) {
        PDDocument doc = new PDDocument();
        for (int i = 0; i < pages; i++) {
            doc.addPage(new PDPage());
        }
        return doc;
    }
}

