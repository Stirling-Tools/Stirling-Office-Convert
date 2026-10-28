package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.WorldPdfs.Text;

class EbookDirectionTest {

    private static final List<String> HEBREW = List.of(
            "הגרסה החדשה של המדריך",
            "סעיף ראשון מסביר את השלבים",
            "השתמשו בקובץ כדי לפתוח אותו");

    private static byte[] convert(String language, OfficeConvert.Format format) throws IOException {
        byte[] pdf = WorldPdfs.mapped(List.of(Text.line(HEBREW.get(0), true, 100), Text.line(HEBREW.get(1), true, 140),
                Text.line(HEBREW.get(2), true, 180)));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            doc.getDocumentCatalog().setLanguage(language);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            OfficeConvert.convert(doc, out, format, OfficeConvert.Settings.defaults());
            return out.toByteArray();
        }
    }

    private static Map<String, String> parts(byte[] epub) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(epub))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                out.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    @Test
    void rightToLeftBooksKeepTheirLanguageDirectionAndLogicalText() throws IOException {
        Map<String, String> parts = parts(convert("he-IL", OfficeConvert.Format.EPUB));
        String opf = parts.get("OEBPS/content.opf");
        assertTrue(opf.contains("<dc:language>he-IL</dc:language>"), opf);
        assertTrue(opf.contains("<spine toc=\"ncx\" page-progression-direction=\"rtl\">"), opf);
        String chapter = parts.get("OEBPS/text/ch1.xhtml");
        assertTrue(chapter.contains("lang=\"he-IL\" xml:lang=\"he-IL\""), chapter);
        assertTrue(chapter.contains("dir=\"rtl\""), chapter);
        String text = chapter.replaceAll("<[^>]*>", "");
        for (String line : HEBREW) {
            for (String word : line.split(" ")) {
                assertTrue(text.contains(word), word);
            }
        }
    }

    @Test
    void aLeftToRightDocumentLanguageGivesWayToTheScriptOfRightToLeftText() throws IOException {
        String opf = parts(convert("en-US", OfficeConvert.Format.EPUB)).get("OEBPS/content.opf");
        assertTrue(opf.contains("<dc:language>he</dc:language>"), opf);
    }

    @Test
    void anUnknownLanguageIsUndetermined() throws IOException {
        byte[] pdf = WorldPdfs.mapped(List.of(Text.line("Plain English text for the book.", false, 100)));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            doc.getDocumentCatalog().setLanguage("not a language!");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            OfficeConvert.convert(doc, out, OfficeConvert.Format.EPUB, OfficeConvert.Settings.defaults());
            Map<String, String> parts = parts(out.toByteArray());
            String opf = parts.get("OEBPS/content.opf");
            assertTrue(opf.contains("<dc:language>und</dc:language>"), opf);
            assertTrue(opf.contains("<spine toc=\"ncx\">"), opf);
            assertFalse(parts.get("OEBPS/text/ch1.xhtml").contains("xml:lang"));
        }
    }

    @Test
    void kindleBooksNameTheLanguageAndPageDirection() throws IOException {
        byte[] book = convert("he-IL", OfficeConvert.Format.AZW3);
        Map<Integer, String> exth = exth(book);
        assertEquals("he-IL", exth.get(524));
        assertEquals("rtl", exth.get(527));
    }

    private static long u32(byte[] b, int i) {
        return (long) (b[i] & 0xFF) << 24 | (b[i + 1] & 0xFF) << 16 | (b[i + 2] & 0xFF) << 8 | (b[i + 3] & 0xFF);
    }

    private static Map<Integer, String> exth(byte[] book) {
        int r0 = (int) u32(book, 78);
        int at = r0 + 16 + (int) u32(book, r0 + 20);
        assertEquals("EXTH", new String(book, at, 4, StandardCharsets.US_ASCII));
        Map<Integer, String> out = new HashMap<>();
        long count = u32(book, at + 8);
        int p = at + 12;
        for (int i = 0; i < count; i++) {
            int type = (int) u32(book, p);
            int size = (int) u32(book, p + 4);
            out.put(type, new String(book, p + 8, size - 8, StandardCharsets.UTF_8));
            p += size;
        }
        return out;
    }
}
