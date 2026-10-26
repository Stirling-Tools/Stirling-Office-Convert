package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
}
