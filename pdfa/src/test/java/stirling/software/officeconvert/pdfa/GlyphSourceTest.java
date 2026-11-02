package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.font.FontSet;

class GlyphSourceTest {

    private static final int HIRAGANA_A = 0x3042;

    private static final int ONE = 0x4E00;

    private static final int TWO = 0x4E8C;

    @TempDir
    Path dir;

    @Test
    void fallbackGlyphsFromOneFontOpenThatFontOnce() throws Exception {
        FontLibrary library = PdfToPdfA.Options.defaults().fontLibrary();
        assumeTrue(library.fallback(0x4E00, false, false) != null, "no CJK fallback font installed");
        try (GlyphSource source = GlyphSource.open(library, BaseFontName.parse("MSMincho", 4, 400))) {
            int drawn = 0;
            for (int cp = 0x4E00; cp < 0x4E00 + 300; cp++) {
                if (source.fallback(new String(Character.toChars(cp)), 1000) != null) {
                    drawn++;
                }
            }
            assertTrue(drawn > 0);
            assertTrue(source.fontsOpen() <= 4, "fonts open: " + source.fontsOpen());
        }
    }

    @Test
    void anEastAsianFaceThatCoversEveryCharacterStandsInAheadOfFallbacksThatCoverAsMuch() throws Exception {
        FontLibrary library = library(List.of(HIRAGANA_A, ONE, TWO), List.of(HIRAGANA_A, ONE, TWO));
        try (GlyphSource source = GlyphSource.open(library, BaseFontName.parse("KozMinPr6N-Regular", 6, 400),
                List.of(HIRAGANA_A, ONE, TWO), "MSMincho")) {
            assertTrue(source.description().contains("MSMincho"), source.description());
        }
    }

    @Test
    void aFallbackThatCoversMoreReplacesAnEastAsianFaceThatMissesCharacters() throws Exception {
        FontLibrary library = library(List.of(HIRAGANA_A), List.of(HIRAGANA_A, ONE, TWO));
        try (GlyphSource source = GlyphSource.open(library, BaseFontName.parse("KozMinPr6N-Regular", 6, 400),
                List.of(HIRAGANA_A, ONE, TWO), "MSMincho")) {
            assertTrue(source.description().contains("CoverAll"), source.description());
        }
    }

    private FontLibrary library(List<Integer> eastAsian, List<Integer> fallback) throws IOException {
        Path fonts = Files.createDirectories(dir.resolve("fonts"));
        Files.write(fonts.resolve("MSMincho.ttf"), font("MSMincho", eastAsian));
        Files.write(fonts.resolve("CoverAll.ttf"), font("CoverAll", fallback));
        return PdfToPdfA.Options.defaults()
                .fonts(FontSet.builder().systemFonts(false).directory(fonts).build()).fontLibrary();
    }

    private static byte[] font(String family, List<Integer> codePoints) throws IOException {
        TrueTypeWriter w = new TrueTypeWriter(1000);
        TreeMap<Integer, Integer> cmap = new TreeMap<>();
        cmap.put(0x41, w.addOutline(square(100), 600));
        for (int cp : codePoints) {
            cmap.put(cp, w.addOutline(square(200), 1000));
        }
        return w.build(family, Cmaps.format4(cmap, 1), Map.of("OS/2", os2()));
    }

    private static byte[] os2() {
        ByteBuffer b = ByteBuffer.allocate(96);
        b.putShort((short) 4);
        b.putShort((short) 500);
        b.putShort((short) 400);
        b.putShort((short) 5);
        b.putShort(62, (short) 0x40);
        return b.array();
    }

    private static GeneralPath square(int size) {
        GeneralPath p = new GeneralPath();
        p.moveTo(100, 0);
        p.lineTo(100 + size, 0);
        p.lineTo(100 + size, size);
        p.lineTo(100, size);
        p.closePath();
        return p;
    }
}
