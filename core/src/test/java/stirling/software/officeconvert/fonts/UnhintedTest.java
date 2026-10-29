package stirling.software.officeconvert.fonts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TTFSubsetter;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

class UnhintedTest {

    private static byte[] liberation() throws IOException {
        try (InputStream in = PDDocument.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            return in.readAllBytes();
        }
    }

    private static TrueTypeFont parse(byte[] font) throws IOException {
        return new TTFParser().parseEmbedded(new ByteArrayInputStream(font));
    }

    private static byte[] subset(byte[] font, String text) throws IOException {
        TrueTypeFont ttf = parse(font);
        TTFSubsetter subsetter = new TTFSubsetter(ttf,
                List.of("head", "hhea", "loca", "maxp", "cvt ", "prep", "glyf", "hmtx", "fpgm", "gasp"));
        text.codePoints().forEach(subsetter::add);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        subsetter.writeToStream(out);
        return out.toByteArray();
    }

    @Test
    void dropsTheHintingButKeepsEveryOutlineAndWidth() throws IOException {
        for (byte[] font : List.of(liberation(), subset(liberation(), "Àpple Pie, 2024 – ½ ǺÅ fi"))) {
            byte[] stripped = Unhinted.strip(font);
            assertTrue(stripped.length < font.length * 0.8, stripped.length + " of " + font.length);
            TrueTypeFont before = parse(font);
            TrueTypeFont after = parse(stripped);
            for (String tag : Set.of("fpgm", "prep", "cvt ")) {
                assertFalse(after.getTableMap().containsKey(tag), tag);
            }
            assertEquals(0, after.getMaximumProfile().getMaxSizeOfInstructions());
            assertEquals(before.getNumberOfGlyphs(), after.getNumberOfGlyphs());
            for (int gid = 0; gid < before.getNumberOfGlyphs(); gid++) {
                GlyphData a = before.getGlyph().getGlyph(gid);
                GlyphData b = after.getGlyph().getGlyph(gid);
                assertEquals(a == null, b == null, "glyph " + gid);
                if (a != null) {
                    assertEquals(points(a.getPath()), points(b.getPath()), "glyph " + gid);
                }
                assertEquals(before.getAdvanceWidth(gid), after.getAdvanceWidth(gid), "glyph " + gid);
            }
            if (before.getTableMap().containsKey("cmap")) {
                assertEquals(before.getUnicodeCmapLookup().getGlyphId('P'), after.getUnicodeCmapLookup().getGlyphId('P'));
            }
            assertEquals(0xB1B0AFBA, sum(stripped), "whole-font checksum");
        }
    }

    @Test
    void anythingElseComesBackAsItWas() {
        byte[] cff = ByteBuffer.allocate(64).putInt(0x4F54544F).putShort((short) 1).array();
        assertSame(cff, Unhinted.strip(cff));
        byte[] junk = {0, 1, 0, 0, 0, 9, 1, 2, 3};
        assertSame(junk, Unhinted.strip(junk));
        assertNull(Unhinted.strip(null));
    }

    @Test
    void aDamagedGlyphTableLeavesTheFontAlone() throws IOException {
        byte[] font = subset(liberation(), "Hello");
        TrueTypeFont ttf = parse(font);
        long glyf = ttf.getTableMap().get("glyf").getOffset();
        byte[] damaged = font.clone();
        ByteBuffer.wrap(damaged).putShort((int) glyf, (short) 0x7FFF);
        assertArrayEquals(damaged, Unhinted.strip(damaged));
    }

    private static List<Double> points(GeneralPath path) {
        List<Double> out = new ArrayList<>();
        double[] c = new double[6];
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            out.add((double) type);
            for (int i = 0; i < 6; i++) {
                out.add(c[i]);
            }
        }
        return out;
    }

    private static int sum(byte[] data) {
        int sum = 0;
        ByteBuffer b = ByteBuffer.wrap(data);
        for (int i = 0; i + 4 <= data.length; i += 4) {
            sum += b.getInt(i);
        }
        return sum;
    }
}
