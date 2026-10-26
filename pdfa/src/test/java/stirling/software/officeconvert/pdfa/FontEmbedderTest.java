package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.FontSet;

class FontEmbedderTest {

    @TempDir
    Path dir;

    private Path embed(String sample, int expected) throws Exception {
        Path in = Samples.write(dir, sample);
        Path out = dir.resolve(sample + "-embedded.pdf");
        try (PDDocument d = Loader.loadPDF(in.toFile())) {
            d.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, new COSDictionary());
            FontEmbedder.Result r = FontEmbedder.embedMissing(d);
            assertEquals(expected, r.embedded().size(), r.embedded().toString());
            assertTrue(r.failed().isEmpty(), r.failed().toString());
            d.save(out.toFile());
        }
        assertEquals(Converted.text(in), Converted.text(out));
        List<float[]> before = Converted.glyphs(in);
        List<float[]> after = Converted.glyphs(out);
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i)[0], after.get(i)[0], 0.01, "glyph " + i + " moved");
            assertEquals(before.get(i)[2], after.get(i)[2], 0.01, "glyph " + i + " changed width");
        }
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertNotNull(d.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT));
            for (PDPage p : d.getPages()) {
                for (COSName n : p.getResources().getFontNames()) {
                    PDFont f = p.getResources().getFont(n);
                    if (!(f instanceof PDType3Font)) {
                        assertTrue(f.isEmbedded(), f.getName() + " is not embedded");
                    }
                }
            }
            new PDFRenderer(d).renderImage(0, 0.5f);
        }
        return out;
    }

    @Test
    void standardFontsAreEmbeddedWithoutMovingAGlyphOrTouchingTheRest() throws Exception {
        embed("s01_std14_unembedded", 15);
    }

    @Test
    void unembeddedTrueTypeAndCidFontsKeepTheirWidths() throws Exception {
        embed("s02_truetype_unembedded", 4);
        embed("s16_cid_unembedded", 1);
    }

    @Test
    void embeddedAndType3FontsAreLeftAlone() throws Exception {
        for (String sample : List.of("s03_type3", "s14_widths_mismatch")) {
            try (PDDocument d = Loader.loadPDF(Samples.write(dir, sample).toFile())) {
                PDPage page = d.getPage(0);
                Map<COSName, byte[]> before = programs(page);
                assertFalse(before.isEmpty(), sample);
                FontEmbedder.Result r = FontEmbedder.embedMissing(d, FontSet.builder().systemFonts(false).build());
                assertTrue(r.failed().isEmpty(), r.failed().toString());
                Map<COSName, byte[]> after = programs(page);
                assertEquals(before.keySet(), after.keySet());
                for (COSName n : before.keySet()) {
                    assertArrayEquals(before.get(n), after.get(n), sample + " " + n.getName());
                }
            }
        }
    }

    private static Map<COSName, byte[]> programs(PDPage page) throws Exception {
        Map<COSName, byte[]> out = new HashMap<>();
        for (COSName n : page.getResources().getFontNames()) {
            PDFont f = page.getResources().getFont(n);
            COSStream s = null;
            if (f instanceof PDType3Font t3) {
                s = t3.getCharProc(t3.getCOSObject().getInt(COSName.FIRST_CHAR)) == null ? null
                        : t3.getCharProc(t3.getCOSObject().getInt(COSName.FIRST_CHAR)).getCOSObject();
            } else if (f.isEmbedded()) {
                COSDictionary fd = (f instanceof PDType0Font t0 ? t0.getDescendantFont().getFontDescriptor()
                        : f.getFontDescriptor()).getCOSObject();
                for (COSName k : new COSName[] {COSName.FONT_FILE, COSName.FONT_FILE2, COSName.FONT_FILE3}) {
                    if (fd.getDictionaryObject(k) instanceof COSStream found) {
                        s = found;
                    }
                }
            }
            if (s != null) {
                try (InputStream in = s.createInputStream()) {
                    out.put(n, in.readAllBytes());
                }
            }
        }
        return out;
    }
}
