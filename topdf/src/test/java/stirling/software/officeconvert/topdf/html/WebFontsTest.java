package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.HtmlAssets;
import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.font.FontLibrary;

class WebFontsTest {

    @TempDir
    Path dir;

    private static byte[] ttf() throws IOException {
        try (InputStream in = FontLibrary.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertNotNull(in);
            return in.readAllBytes();
        }
    }

    private static byte[] woff(byte[] sfnt) {
        ByteBuffer b = ByteBuffer.wrap(sfnt);
        int n = b.getShort(4) & 0xFFFF;
        List<byte[]> tags = new ArrayList<>();
        List<byte[]> bodies = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int at = 12 + 16 * i;
            byte[] tag = new byte[4];
            System.arraycopy(sfnt, at, tag, 0, 4);
            int offset = b.getInt(at + 8);
            int length = b.getInt(at + 12);
            byte[] table = new byte[length];
            System.arraycopy(sfnt, offset, table, 0, length);
            Deflater d = new Deflater();
            d.setInput(table);
            d.finish();
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            while (!d.finished()) {
                z.write(buf, 0, d.deflate(buf));
            }
            d.end();
            byte[] packed = z.size() < length ? z.toByteArray() : table;
            tags.add(tag);
            bodies.add(packed);
            lengths.add(length);
        }
        int size = 44 + 20 * n;
        for (byte[] body : bodies) {
            size += (body.length + 3) & ~3;
        }
        ByteBuffer out = ByteBuffer.allocate(size);
        out.putInt(0x774F4646).putInt(0x00010000).putInt(size).putShort((short) n).putShort((short) 0)
                .putInt(sfnt.length).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0);
        int offset = 44 + 20 * n;
        for (int i = 0; i < n; i++) {
            out.put(tags.get(i)).putInt(offset).putInt(bodies.get(i).length).putInt(lengths.get(i)).putInt(0);
            System.arraycopy(bodies.get(i), 0, out.array(), offset, bodies.get(i).length);
            offset += (bodies.get(i).length + 3) & ~3;
        }
        return out.array();
    }

    private static String page(String src) {
        return "<!DOCTYPE html><html><head><style>@font-face{font-family:'Brandish Sans';src:" + src
                + "}body{font-family:'Brandish Sans',monospace}</style></head><body><p>Webfont words here</p>"
                + "</body></html>";
    }

    private record Out(List<String> fonts, String text, OfficeToPdf.Result result) {}

    private Out convert(String name, String html, OfficeToPdf.Options options) throws IOException {
        Path in = dir.resolve(name + ".html");
        Files.writeString(in, html, StandardCharsets.UTF_8);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(in, out, options);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            List<String> fonts = new ArrayList<>();
            PDResources res = doc.getPage(0).getResources();
            for (COSName f : res.getFontNames()) {
                PDFont font = res.getFont(f);
                fonts.add(font.getName());
            }
            return new Out(fonts, new PDFTextStripper().getText(doc), r);
        }
    }

    private static boolean uses(Out out) {
        return out.fonts().stream().anyMatch(f -> f.contains("BrandishSans"));
    }

    @Test
    void aFaceHasOneFamilyWhileQuotedCommasBelongToItsName() throws IOException {
        assertNull(WebFonts.family("Alpha, Beta"));
        assertNull(WebFonts.family("'Alpha', 'Beta'"));
        assertNull(WebFonts.family("'Unclosed, Name"));
        assertEquals("Alpha, Beta", WebFonts.family("'Alpha, Beta'"));
        assertEquals("Alpha, Beta", WebFonts.family("\"Alpha, Beta\""));
        assertEquals("Alpha Beta", WebFonts.family("Alpha Beta"));
        byte[] renamed = WebFonts.renamed(ttf(), WebFonts.family("'Alpha, Beta'"), false, false);
        assertNotNull(FontLibrary.of(List.of()).withFonts(List.of(renamed)).exact("Alpha, Beta", false, false));
    }

    @Test
    void aQuotedCommaFamilyCanBeSelectedByThePage() throws IOException {
        String uri = "data:font/ttf;base64," + Base64.getEncoder().encodeToString(ttf());
        String html = page("url(" + uri + ") format('truetype')").replace("Brandish Sans", "Brandish, Sans");
        Out out = convert("quoted-comma", html, OfficeToPdf.Options.defaults());
        assertTrue(uses(out), out.fonts().toString());
        assertTrue(out.text().contains("Webfont words here"), out.text());
    }

    @Test
    void aTrueTypeFontInADataUriDrawsTheText() throws IOException {
        String uri = "data:font/ttf;base64," + Base64.getEncoder().encodeToString(ttf());
        Out out = convert("ttf", page("url(" + uri + ") format('truetype')"), OfficeToPdf.Options.defaults());
        assertTrue(uses(out), out.fonts().toString());
        assertTrue(out.text().contains("Webfont words here"), out.text());
    }

    @Test
    void aWoffFontIsUnpacked() throws IOException {
        byte[] sfnt = ttf();
        assertNotNull(WebFonts.woff(woff(sfnt)));
        String uri = "data:application/font-woff;base64," + Base64.getEncoder().encodeToString(woff(sfnt));
        Out out = convert("woff", page("url(data:font/woff2;base64,AAAA) format('woff2'), url(" + uri
                + ") format('woff')"), OfficeToPdf.Options.defaults());
        assertTrue(uses(out), out.fonts().toString());
    }

    @Test
    void aFontBesideThePageIsReadOnlyThroughTheAssets() throws IOException {
        Files.write(dir.resolve("brand.ttf"), ttf());
        String html = page("url(brand.ttf)");
        Out closed = convert("closed", html, OfficeToPdf.Options.defaults());
        assertFalse(uses(closed), closed.fonts().toString());
        assertTrue(closed.result().warnings().stream().anyMatch(w -> w.contains("@font-face")),
                closed.result().warnings().toString());
        Out open = convert("open", html, OfficeToPdf.Options.defaults().htmlAssets(HtmlAssets.folder(dir,
                "open.html")));
        assertTrue(uses(open), open.fonts().toString());
    }

    @Test
    void aRenamedFontAnswersToItsCssFamilyAndDescriptors() throws IOException {
        byte[] bold = WebFonts.renamed(ttf(), "House Face", true, false);
        FontLibrary fonts = FontLibrary.of(List.of()).withFonts(List.of(bold));
        assertNotNull(fonts.exact("House Face", true, false));
        assertNull(WebFonts.renamed(new byte[64], "House Face", false, false));
        assertNull(WebFonts.sfnt("wOF2 not supported".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void anUnreadableTrueTypeSourceDoesNotHideTheNextValidSource() throws IOException {
        byte[] malformed = ByteBuffer.allocate(12).putInt(0x00010000).array();
        String bad = "data:font/ttf;base64," + Base64.getEncoder().encodeToString(malformed);
        String good = "data:font/ttf;base64," + Base64.getEncoder().encodeToString(ttf());
        Out out = convert("next-source", page("url(" + bad + ") format('truetype'), url(" + good
                + ") format('truetype')"), OfficeToPdf.Options.defaults());
        assertTrue(uses(out), out.fonts().toString());
        assertTrue(out.text().contains("Webfont words here"), out.text());
    }

    @Test
    void unsupportedWoff2AndPostscriptOutlinesWarnAndKeepReadableText() throws IOException {
        for (String format : new String[] {"woff2", "opentype"}) {
            int tag = format.equals("woff2") ? 0x774F4632 : 0x4F54544F;
            byte[] program = ByteBuffer.allocate(64).putInt(tag).array();
            String uri = "data:font/" + format + ";base64," + Base64.getEncoder().encodeToString(program);
            Out out = convert("unsupported-" + format, page("url(" + uri + ") format('" + format + "')"),
                    OfficeToPdf.Options.defaults());
            assertFalse(uses(out), out.fonts().toString());
            assertTrue(out.text().contains("Webfont words here"), out.text());
            assertTrue(out.result().warnings().stream().anyMatch(w -> w.contains("@font-face")));
        }
    }


    private static byte[] overlappingTables() {
        int count = 256;
        int offset = 12 + 16 * count;
        int length = FontLibrary.MAX_DOCUMENT_FONT_BYTES / count + 1;
        ByteBuffer out = ByteBuffer.allocate(offset + length);
        out.putInt(0x00010000).putShort((short) count);
        for (int i = 0; i < count; i++) {
            String tag = i == 0 ? "head" : i == 1 ? "glyf" : String.format(java.util.Locale.ROOT, "t%03d", i);
            out.position(12 + 16 * i);
            out.put(tag.getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(offset).putInt(length);
        }
        return out.array();
    }

    @Test
    void overlappingSfntTablesCannotExpandBeyondTheFontByteLimit() {
        byte[] source = overlappingTables();
        assertTrue(source.length < 140000);
        assertTrue(WebFonts.renamed(source, "Bounded Face", false, false) == null, "expanded sfnt exceeded its byte budget");
    }

    @Test
    void overlappingWoffTablesCannotExpandBeyondTheirDeclaredFontSize() {
        byte[] source = woff(overlappingTables());
        assertTrue(source.length < 140000);
        assertTrue(WebFonts.woff(source) == null, "expanded WOFF exceeded its declared byte budget");
    }


    private static byte[] unusableFont() {
        ByteBuffer bad = ByteBuffer.allocate(104);
        bad.putInt(0x00010000).putShort((short) 2);
        bad.position(12);
        bad.put("head".getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(44).putInt(54);
        bad.put("glyf".getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(100).putInt(4);
        bad.putInt(56, 0x5F0F3CF5).putShort(62, (short) 1000);
        return bad.array();
    }

    @Test
    void aRenameableButUnusableFontDoesNotHideTheNextValidSource() throws IOException {
        byte[] bad = unusableFont();
        assertNotNull(WebFonts.renamed(bad, "Brandish Sans", false, false));
        String broken = "data:font/ttf;base64," + Base64.getEncoder().encodeToString(bad);
        String good = "data:font/ttf;base64," + Base64.getEncoder().encodeToString(ttf());
        Out out = convert("unusable-source", page("url(" + broken + "), url(" + good + ")"),
                OfficeToPdf.Options.defaults());
        assertTrue(uses(out), out.fonts().toString());
        assertTrue(out.text().contains("Webfont words here"), out.text());
    }


    @Test
    void aBundledFamilyCannotMakeAnUnusableSourceLookUsable() throws IOException {
        byte[] good = ttf();
        String family = "Liberation Sans";
        String broken = "data:font/ttf;base64," + Base64.getEncoder().encodeToString(unusableFont());
        String source = "url(" + broken + "), url(data:font/ttf;base64," + Base64.getEncoder().encodeToString(good) + ")";
        var face = new WebFonts.Face(Map.of("font-family", family, "src", source), "");
        var loaded = WebFonts.load(List.of(face), new Linked(HtmlAssets.NONE), new ArrayList<>());
        assertTrue(loaded.fonts().size() == 1);
        assertTrue(java.util.Arrays.equals(WebFonts.renamed(good, family, false, false), loaded.fonts().getFirst()));
    }


    @Test
    void cancelledFontLoadingStopsBeforeTryingSources() {
        var face = new WebFonts.Face(Map.of("font-family", "Brandish Sans", "src", "url(unread.ttf)"), "");
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> WebFonts.load(List.of(face), new Linked(HtmlAssets.NONE), new ArrayList<>()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

}
