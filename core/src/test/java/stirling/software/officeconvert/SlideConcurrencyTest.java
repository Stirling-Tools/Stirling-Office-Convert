package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlideConcurrencyTest {

    @TempDir Path dir;

    private interface Converter {
        void convert(PDDocument doc, ByteArrayOutputStream out) throws IOException;
    }

    @Test
    void parallelPptxMatchesSequential() throws Exception {
        check((doc, out) -> PdfToPptx.convert(doc, out, PdfToPptx.Options.defaults()));
    }

    @Test
    void parallelOdpMatchesSequential() throws Exception {
        check((doc, out) -> PdfToOdp.convert(doc, out, PdfToPptx.Options.defaults()));
    }

    private void check(Converter converter) throws Exception {
        List<Path> pdfs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            pdfs.add(SlideFixtures.deck(dir, i));
        }
        List<Map<String, byte[]>> expected = new ArrayList<>();
        for (Path pdf : pdfs) {
            expected.add(convert(pdf, converter));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Map<String, byte[]>>> runs = new ArrayList<>();
            for (int round = 0; round < 4; round++) {
                for (Path pdf : pdfs) {
                    runs.add(pool.submit(() -> convert(pdf, converter)));
                }
            }
            for (int i = 0; i < runs.size(); i++) {
                Map<String, byte[]> want = expected.get(i % pdfs.size());
                Map<String, byte[]> got = runs.get(i).get();
                assertEquals(want.keySet(), got.keySet());
                for (String part : want.keySet()) {
                    assertArrayEquals(want.get(part), got.get(part), part + " of document " + i % pdfs.size());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, byte[]> convert(Path pdf, Converter converter) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            converter.convert(doc, out);
        }
        Map<String, byte[]> parts = SlideFixtures.parts(out.toByteArray());
        parts.remove("docProps/core.xml");
        parts.remove("meta.xml");
        return parts;
    }
}
