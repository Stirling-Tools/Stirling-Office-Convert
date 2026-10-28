package stirling.software.officeconvert.topdf.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.font.PdfFonts;
import stirling.software.officeconvert.topdf.testing.TestFonts;

class FontAliasTest {

    private static byte[] aliases(boolean whole) throws IOException {
        byte[] source = TestFonts.renamed("Aliased Face");
        try (TrueTypeFont font = new TTFParser().parse(new RandomAccessReadBuffer(source))) {
            TreeMap<Integer, Integer> codes = new TreeMap<>();
            for (int cp = 32; cp < 127; cp++) {
                codes.put(cp, font.getUnicodeCmapLookup().getGlyphId(cp));
            }
            int glyph = codes.get((int) 'X');
            codes.put((int) 'Q', glyph);
            codes.put(0x10437, glyph);
            codes.put(0x10438, glyph);
            codes.put(0x20000, glyph);
            int length = 16 + 12 * codes.size();
            ByteBuffer cmap = ByteBuffer.allocate(12 + length);
            cmap.putShort((short) 0).putShort((short) 1).putShort((short) 3).putShort((short) 10).putInt(12);
            cmap.putShort((short) 12).putShort((short) 0).putInt(length).putInt(0).putInt(codes.size());
            for (var entry : codes.entrySet()) {
                cmap.putInt(entry.getKey()).putInt(entry.getKey()).putInt(entry.getValue());
            }
            int offset = (source.length + 3) & ~3;
            byte[] result = Arrays.copyOf(source, offset + cmap.capacity());
            System.arraycopy(cmap.array(), 0, result, offset, cmap.capacity());
            ByteBuffer data = ByteBuffer.wrap(result);
            int count = data.getShort(4) & 0xFFFF;
            for (int i = 0; i < count; i++) {
                int at = 12 + 16 * i;
                String tag = new String(result, at, 4, StandardCharsets.ISO_8859_1);
                if (tag.equals("cmap")) {
                    data.putInt(at + 8, offset).putInt(at + 12, cmap.capacity());
                }
                if (whole && tag.equals("OS/2")) {
                    data.putShort(data.getInt(at + 8) + 8, (short) 0x100);
                }
            }
            return result;
        }
    }

    private static FontLibrary library(boolean whole) throws IOException {
        return FontLibrary.of(List.of()).withFonts(List.of(aliases(whole)));
    }

    private static byte[] draw(FontLibrary fonts, String text) throws IOException {
        FontFace face = fonts.exact("Aliased Face", false, false);
        assertNotNull(face);
        return draw(fonts, face, text);
    }

    private static byte[] draw(FontLibrary fonts, FontFace face, String text) throws IOException {
        try (PdfOutput output = new PdfOutput(fonts)) {
            try (PdfCanvas canvas = output.newPage(PageSize.A4)) {
                canvas.text(text, 72, 100, TextStyle.of(face, 16));
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            output.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static final class Positions extends PDFTextStripper {
        final List<TextPosition> positions = new ArrayList<>();

        Positions() throws IOException {}

        @Override
        protected void processTextPosition(TextPosition position) {
            positions.add(position);
            super.processTextPosition(position);
        }
    }

    @Test
    void ordinaryAliasesHaveTheirOwnUnicodeWhileSharingTheSameGlyph() throws Exception {
        verify(false);
    }

    @Test
    void aliasesAlsoPreserveTextWhenTheFontMustBeEmbeddedWhole() throws Exception {
        verify(true);
    }

    private static void verify(boolean whole) throws Exception {
        FontLibrary fonts = library(whole);
        String text = "XQXQ\uD801\uDC37abc";
        byte[] aliased = draw(fonts, text);
        byte[] canonical = draw(fonts, "XXXXXabc");
        try (PDDocument actual = Loader.loadPDF(aliased); PDDocument reference = Loader.loadPDF(canonical)) {
            Positions left = new Positions();
            Positions right = new Positions();
            assertTrue(left.getText(actual).contains(text));
            right.getText(reference);
            assertEquals(right.positions.size(), left.positions.size());
            for (int i = 0; i < left.positions.size(); i++) {
                TextPosition a = left.positions.get(i);
                TextPosition b = right.positions.get(i);
                assertEquals(b.getXDirAdj(), a.getXDirAdj(), 0.0001);
                assertEquals(b.getYDirAdj(), a.getYDirAdj(), 0.0001);
                assertEquals(b.getWidthDirAdj(), a.getWidthDirAdj(), 0.0001);
            }
            int x = left.positions.get(0).getCharacterCodes()[0];
            int q = left.positions.get(1).getCharacterCodes()[0];
            int supplementary = left.positions.get(4).getCharacterCodes()[0];
            assertFalse(x == q);
            assertFalse(q == supplementary);
            assertEquals(q, left.positions.get(3).getCharacterCodes()[0]);
            var resources = actual.getPage(0).getResources();
            PDType0Font embedded = (PDType0Font) resources.getFont(resources.getFontNames().iterator().next());
            assertEquals(embedded.codeToGID(x), embedded.codeToGID(q));
            assertEquals(embedded.codeToGID(x), embedded.codeToGID(supplementary));
            assertEquals(embedded.getWidth(x), embedded.getWidth(q));
            assertNotNull(embedded.getDescendantFont().getCOSObject().getDictionaryObject(COSName.CID_TO_GID_MAP));
            BufferedImage a = new PDFRenderer(actual).renderImageWithDPI(0, 96);
            BufferedImage b = new PDFRenderer(reference).renderImageWithDPI(0, 96);
            assertEquals(b.getWidth(), a.getWidth());
            assertEquals(b.getHeight(), a.getHeight());
            assertArrayEquals(b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth()),
                    a.getRGB(0, 0, a.getWidth(), a.getHeight(), null, 0, a.getWidth()));
        }
    }

    @Test
    void borrowedGlyphsKeepDistinctCharactersSharingOneFallbackGlyph() throws Exception {
        FontLibrary fonts = library(false);
        FontFace original = fonts.exact("Liberation Sans", false, false);
        assertNotNull(original);
        assertFalse(original.covers(0x10437));
        assertFalse(original.covers(0x10438));
        String text = "\uD801\uDC37\uD801\uDC38\uD801\uDC37";
        try (PDDocument document = Loader.loadPDF(draw(fonts, original, text))) {
            Positions extracted = new Positions();
            assertTrue(extracted.getText(document).contains(text));
            assertEquals(3, extracted.positions.size());
            int first = extracted.positions.get(0).getCharacterCodes()[0];
            int second = extracted.positions.get(1).getCharacterCodes()[0];
            assertFalse(first == second);
            assertEquals(first, extracted.positions.get(2).getCharacterCodes()[0]);
        }
    }

    @Test
    void anExhaustedCidSpaceCannotSilentlyUseAnotherCharactersText() throws Exception {
        FontLibrary fonts = library(false);
        FontFace face = fonts.exact("Aliased Face", false, false);
        assertNotNull(face);
        try (PDDocument document = new PDDocument(); PdfFonts pdf = new PdfFonts(document, fonts)) {
            PdfFonts.Embedded embedded = pdf.font(face);
            int glyph = face.glyph('X');
            pdf.used(embedded, new int[] {glyph}, new int[] {'X'}, 1);
            int last = 0;
            for (int i = 0; i <= 0xFFFF - face.glyphCount(); i++) {
                last = pdf.encoded(embedded, glyph, 0x10000 + i);
            }
            assertEquals(0xFFFF, last);
            IOException failure = assertThrows(IOException.class,
                    () -> pdf.encoded(embedded, glyph, 0x20000));
            assertTrue(failure.getMessage().contains("remaining CID"));
            assertEquals(face.glyphCount(), pdf.encoded(embedded, glyph, 0x10000));
            var shaped = face.shape(new String(Character.toChars(0x20000)), false);
            assertEquals(glyph, shaped.glyph(0));
            IllegalStateException shapedFailure = assertThrows(IllegalStateException.class,
                    () -> pdf.used(embedded, shaped));
            assertTrue(shapedFailure.getMessage().contains("remaining CID"));
            int known = face.glyphCount() + 0x10437 - 0x10000;
            assertEquals(known, pdf.used(embedded, face.shape("\uD801\uDC37", false))[0]);
            pdf.finish();
            PDType0Font written = new PDType0Font(embedded.font());
            assertEquals("\uD801\uDC37", written.toUnicode(known));
            assertEquals(new String(Character.toChars(0x10000 + 0xFFFF - face.glyphCount())),
                    written.toUnicode(last));
            assertEquals(written.codeToGID(glyph), written.codeToGID(known));
        }
    }
}
