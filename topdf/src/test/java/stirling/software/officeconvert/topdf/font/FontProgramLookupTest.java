package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.fontbox.ttf.CmapLookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FontProgramLookupTest {

    @TempDir
    Path dir;

    @Test
    void everyBasicPlaneCodePointMapsToTheGlyphItsCmapNames() throws Exception {
        FontProgram program = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("empty"))))
                .find("Calibri", false, false).program();
        int[] expected = new int[0x10000];
        try (FontProgram.Opened opened = FontProgram.open(program.entry())) {
            CmapLookup cmap = opened.font().getUnicodeCmapLookup();
            for (int cp = 0; cp < expected.length; cp++) {
                int g = cmap.getGlyphId(cp);
                expected[cp] = g > 0 && g < program.glyphCount() ? g : 0;
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<int[]>> runs = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                int start = t * 0x4000;
                runs.add(pool.submit(() -> {
                    int[] seen = new int[0x10000];
                    for (int i = 0; i < seen.length; i++) {
                        int cp = (start + i) & 0xFFFF;
                        seen[cp] = program.glyph(cp);
                    }
                    return seen;
                }));
            }
            for (Future<int[]> run : runs) {
                assertArrayEquals(expected, run.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
