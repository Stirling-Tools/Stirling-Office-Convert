package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.fontbox.cff.CFFCIDFont;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cff.CFFParser;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.PostScriptFonts;

class LazyCidCffTest {

    private static final int[] CHARS = {'A', 'B', 'C', 'D', 'E', 'F', 0x4E00, 0x3042, 0xAC00};

    @TempDir
    Path dir;

    @Test
    void cidKeyedOutlinesMatchAFullParseGlyphForGlyph() throws Exception {
        FontEntry entry = entry(PostScriptFonts.font("Lazy Cid", 400, true, CHARS), "Lazy Cid");
        CFFCIDFont full = (CFFCIDFont) new CFFParser()
                .parse(new RandomAccessReadBuffer(FontScanner.table(entry, "CFF ", Long.MAX_VALUE))).get(0);
        LazyCidCff lazy = LazyCidCff.open(entry);
        assertEquals(full.getNumCharStrings(), lazy.glyphCount());
        assertEquals(full.getFontMatrix(), lazy.fontMatrix());
        int drawn = 0;
        for (int gid = 0; gid < lazy.glyphCount(); gid++) {
            GeneralPath expected = full.getType2CharString(full.getCharset().getCIDForGID(gid)).getPath();
            GeneralPath actual = lazy.path(gid);
            assertEquals(segments(expected), segments(actual), "glyph " + gid);
            drawn += segments(actual).isEmpty() ? 0 : 1;
        }
        assertTrue(drawn > CHARS.length);
    }

    @Test
    void embeddableFontsDrawTheSameOutlinesFromACollection() throws Exception {
        byte[] ttc = PostScriptFonts.collection(PostScriptFonts.font("Pair One", 400, true, CHARS),
                PostScriptFonts.font("Pair Two", 400, false, CHARS));
        Path fonts = Files.createDirectories(dir.resolve("ttc"));
        Files.write(fonts.resolve("Pair.ttc"), ttc);
        FontLibrary lib = FontLibrary.of(List.of(fonts));
        for (String family : List.of("Pair One", "Pair Two")) {
            FontFace face = lib.find(family, false, false);
            FontEntry entry = face.program().entry();
            CFFParser parser = new CFFParser();
            CFFFont full = parser.parse(new RandomAccessReadBuffer(FontScanner.table(entry, "CFF ", Long.MAX_VALUE)))
                    .get(0);
            try (EmbeddableFont font = EmbeddableFont.open(face)) {
                for (int gid = 1; gid < font.font().getNumberOfGlyphs(); gid++) {
                    int selector = full instanceof CFFCIDFont cid ? cid.getCharset().getCIDForGID(gid) : gid;
                    assertEquals(segments(full.getType2CharString(selector).getPath()), segments(font.outline(gid)),
                            family + " glyph " + gid);
                }
            }
        }
    }

    @Test
    void nameKeyedFontsAreLeftToTheFullParser() throws Exception {
        FontEntry entry = entry(PostScriptFonts.font("Lazy Names", 400, false, CHARS), "Lazy Names");
        assertThrows(IOException.class, () -> LazyCidCff.open(entry));
    }

    private FontEntry entry(byte[] font, String family) throws Exception {
        Path fonts = Files.createDirectories(dir.resolve(family.replace(' ', '-')));
        Files.write(fonts.resolve("f.otf"), font);
        return FontLibrary.of(List.of(fonts)).find(family, false, false).program().entry();
    }

    private static List<String> segments(GeneralPath path) {
        List<String> out = new ArrayList<>();
        if (path == null) {
            return out;
        }
        double[] c = new double[6];
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            out.add(type + ":" + Arrays.toString(c));
        }
        return out;
    }
}
