package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.GeneralPath;
import java.io.InputStream;
import java.util.TreeMap;

import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

class TrueTypeWriterTest {

    static TrueTypeFont liberation() throws Exception {
        try (InputStream in = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            return new TTFParser().parse(new RandomAccessReadBuffer(in.readAllBytes()));
        }
    }

    @Test
    void copiedGlyphsKeepTheirOutlinesAndTakeTheGivenWidths() throws Exception {
        try (TrueTypeFont src = liberation()) {
            int a = src.getUnicodeCmapLookup().getGlyphId('A');
            int aacute = src.getUnicodeCmapLookup().getGlyphId(0xC1);
            TrueTypeWriter w = new TrueTypeWriter(src);
            int s1 = w.addCopy(a, 1000);
            int s2 = w.addCopy(a, 1500);
            int s3 = w.addCopy(aacute, 1400);
            int s4 = w.addEmpty(200);
            TreeMap<Integer, Integer> cmap = new TreeMap<>();
            cmap.put(0xF041, s1);
            cmap.put(0xF042, s2);
            cmap.put(0xF043, s3);
            cmap.put(0xF020, s4);
            byte[] bytes = w.build("Test-Font", cmap, 0);
            try (TrueTypeFont out = new TTFParser(true).parse(new RandomAccessReadBuffer(bytes))) {
                assertEquals(1000, out.getAdvanceWidth(s1));
                assertEquals(1500, out.getAdvanceWidth(s2));
                assertEquals(1400, out.getAdvanceWidth(s3));
                assertEquals(200, out.getAdvanceWidth(s4));
                CmapSubtable sym = out.getCmap().getSubtable(3, 0);
                assertNotNull(sym);
                assertEquals(s3, sym.getGlyphId(0xF043));
                GeneralPath before = src.getGlyph().getGlyph(aacute).getPath();
                GeneralPath after = out.getGlyph().getGlyph(s3).getPath();
                assertEquals(before.getBounds2D(), after.getBounds2D());
                assertTrue(out.getNumberOfGlyphs() > 5, "the accent's components are carried along");
            }
        }
    }

    @Test
    void cubicOutlinesBecomeCloseQuadraticOnes() throws Exception {
        GeneralPath p = new GeneralPath();
        p.moveTo(100, 0);
        p.curveTo(100, 300, 500, 300, 500, 0);
        p.lineTo(300, -200);
        p.closePath();
        TrueTypeWriter w = new TrueTypeWriter(1000);
        int g = w.addOutline(p, 600);
        byte[] bytes = w.build("Outline", new TreeMap<>(java.util.Map.of(0xF041, g)), 0);
        try (TrueTypeFont out = new TTFParser(true).parse(new RandomAccessReadBuffer(bytes))) {
            GeneralPath q = out.getGlyph().getGlyph(g).getPath();
            assertEquals(p.getBounds2D().getMinX(), q.getBounds2D().getMinX(), 1);
            assertEquals(p.getBounds2D().getMaxX(), q.getBounds2D().getMaxX(), 1);
            assertEquals(225, q.getBounds2D().getMaxY(), 2);
            assertEquals(600, out.getAdvanceWidth(g));
        }
    }
}
