package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.FontSet;
import stirling.software.officeconvert.topdf.testing.TestFonts;

class OfficeToPdfFontSetTest {

    @TempDir
    Path dir;

    private Path docx(String family) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFRun run = doc.createParagraph().createRun();
            run.setFontFamily(family);
            run.setText("The quick brown fox jumps over the lazy dog");
            doc.write(out);
            return Files.write(dir.resolve(family.replace(" ", "") + ".docx"), out.toByteArray());
        }
    }

    private static Set<String> fonts(Path pdf) throws IOException {
        Set<String> out = new TreeSet<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            for (PDPage page : doc.getPages()) {
                PDResources res = page.getResources();
                for (COSName n : res.getFontNames()) {
                    out.add(res.getFont(n).getName().replaceFirst("^[A-Z]{6}\\+", ""));
                }
            }
        }
        return out;
    }

    private static byte[] restricted(String family) {
        byte[] out = TestFonts.renamed(family);
        ByteBuffer b = ByteBuffer.wrap(out);
        int tables = b.getShort(4) & 0xFFFF;
        for (int i = 0; i < tables; i++) {
            int at = 12 + 16 * i;
            if (new String(out, at, 4, StandardCharsets.ISO_8859_1).equals("OS/2")) {
                b.putShort(b.getInt(at + 8) + 8, (short) 0x0002);
            }
        }
        return out;
    }

    @Test
    void aMappedFamilyIsDrawnWithTheHostsFont() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("fonts"));
        Files.write(fonts.resolve("brand.ttf"), TestFonts.renamed("Brand Sans"));
        FontSet set = FontSet.builder().directory(fonts).systemFonts(false).substitute("Aptos", "Brand Sans").build();
        Path pdf = dir.resolve("mapped.pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(docx("Aptos"), pdf, OfficeToPdf.Options.defaults().fonts(set));
        assertEquals(1, r.pages());
        assertEquals(Set.of("BrandSans"), fonts(pdf));
        assertFalse(r.warnings().stream().anyMatch(w -> w.contains("Aptos")), r.warnings().toString());
    }

    @Test
    void aFontWhoseLicenceForbidsEmbeddingIsNeverEmbedded() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("locked"));
        Files.write(fonts.resolve("locked.ttf"), restricted("Locked Sans"));
        Path pdf = dir.resolve("locked.pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(docx("Locked Sans"), pdf, OfficeToPdf.Options.defaults()
                .fonts(FontSet.builder().directory(fonts).systemFonts(false).build()));
        assertFalse(fonts(pdf).stream().anyMatch(f -> f.contains("Locked")), fonts(pdf).toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("Locked Sans") && w.contains("licence")),
                r.warnings().toString());
    }

    @Test
    void damagedHostFontsNeverFailAConversion() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("damaged"));
        byte[] good = TestFonts.renamed("Fuzz Sans");
        Random random = new Random(7);
        for (int i = 0; i < 12; i++) {
            byte[] bad = good.clone();
            for (int k = 0; k < 40 + random.nextInt(400); k++) {
                bad[random.nextInt(bad.length)] = (byte) random.nextInt(256);
            }
            Files.write(fonts.resolve("fuzz" + i + ".ttf"), bad);
        }
        Files.write(fonts.resolve("glyph.ttf"), TestFonts.damagedGlyph("Fuzz Sans", 'o'));
        FontSet set = FontSet.builder().directory(fonts).systemFonts(false).substitute("Aptos", "Fuzz Sans").build();
        Path pdf = dir.resolve("damaged.pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(docx("Aptos"), pdf, OfficeToPdf.Options.defaults().fonts(set));
        assertEquals(1, r.pages());
        assertTrue(Files.size(pdf) > 0);
    }

    @Test
    void fontDirsStillAddToTheSet() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("old"));
        Files.write(fonts.resolve("brand.ttf"), TestFonts.renamed("Old Sans"));
        OfficeToPdf.Options o = OfficeToPdf.Options.defaults().fontDirs(List.of(fonts))
                .fonts(FontSet.builder().systemFonts(false).build());
        assertEquals("Old Sans", o.fontLibrary().find("Old Sans", false, false).family());
        assertEquals(FontSet.system(), OfficeToPdf.Options.defaults().fonts());
    }
}
