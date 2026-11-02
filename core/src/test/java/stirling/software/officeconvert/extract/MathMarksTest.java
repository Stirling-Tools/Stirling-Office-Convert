package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class MathMarksTest {

    @Test
    void aStretchedClippedBarOverALetterBecomesAMacron() throws IOException {
        String content = "BT /F1 10 Tf 100 700 Td (x) Tj ET\n"
                + "q 99 700 7 14 re W n BT /F1 1 Tf 500 0 0 10 -40 703 Tm <96> Tj ET Q\n";
        List<Glyph> glyphs = read(content);
        assertEquals("x\u0304", text(glyphs));
    }

    @Test
    void aStretchedBarIsCutToItsClip() throws IOException {
        String content = "BT /F1 10 Tf 100 700 Td (Rule) Tj ET\n"
                + "q 300 640 20 14 re W n BT /F1 1 Tf 500 0 0 10 100 640 Tm <96> Tj ET Q\n";
        List<Glyph> glyphs = read(content);
        Glyph bar = glyphs.stream().filter(g -> g.text.equals("\u00AF")).findFirst().orElseThrow(() -> new AssertionError(glyphs.stream().map(g -> g.text + "@" + g.x + "+" + g.width).toList().toString()));
        assertTrue(bar.x >= 299 && bar.right() <= 321, bar.x + " " + bar.right());
    }

    @Test
    void aBarDrawnExactlyOverALetterBecomesAMacron() throws IOException {
        String content = "BT /F1 10 Tf 100 700 Td (y) Tj ET\nBT /F1 10 Tf 100 703 Td <96> Tj ET\n";
        assertEquals("\u0233", text(read(content)));
    }

    @Test
    void aDashBetweenWordsStaysADash() throws IOException {
        String content = "BT /F1 10 Tf 100 700 Td (a ) Tj <96> Tj ( b) Tj ET\n";
        assertEquals("a \u2013 b", text(read(content)));
    }

    @Test
    void aRaisedMacronJoinsTheCapitalBelow() throws IOException {
        String content = "BT /F1 10 Tf 100 700 Td (A) Tj ET\nBT /F1 10 Tf 101.2 702.4 Td <AF> Tj ET\n";
        assertEquals("\u0100", text(read(content)));
    }

    @Test
    void aCaretDrawnOverALetterBecomesAHat() throws IOException {
        String content = "BT /F1 10 Tf 100 700 Td (^) Tj ET\nBT /F1 10 Tf 100.5 700 Td (u) Tj ET\n";
        assertEquals("\u00FB", text(read(content)));
    }

    @Test
    void aCaretBesideALetterStaysACaret() throws IOException {
        String content = "BT /F1 10 Tf 100 700 Td (x^2) Tj ET\n";
        assertEquals("x^2", text(read(content)));
    }

    @Test
    void anAssembledRadicalReadsAsOneRootSign() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            resources.put(COSName.getPDFName("R1"), new PDType3Font(radicalFont(document), null));
            page.setResources(resources);
            document.addPage(page);
            String content = "BT /R1 10 Tf 100 712 Td <02> Tj ET\nBT /R1 10 Tf 100 700 Td <01> Tj ET\n"
                    + "BT /F1 10 Tf 110 700 Td (n) Tj ET\n";
            PDStream stream = new PDStream(document);
            try (OutputStream out = stream.createOutputStream()) {
                out.write(content.getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            assertEquals("\u221An", text(read(document)));
        }
    }

    private static COSDictionary radicalFont(PDDocument document) throws IOException {
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.TYPE3);
        font.setItem(COSName.FONT_BBOX, numbers(0, -200, 800, 1000));
        font.setItem(COSName.FONT_MATRIX, numbers(0.001f, 0, 0, 0.001f, 0, 0));
        COSDictionary procs = new COSDictionary();
        procs.setItem(COSName.getPDFName("bottom"),
                proc(document, "800 0 0 -200 800 1000 d1 100 0 m 400 -200 l 700 1000 l 650 1000 l 400 -100 l f"));
        procs.setItem(COSName.getPDFName("top"), proc(document, "800 0 0 0 800 1000 d1 650 0 m 700 0 l 700 1000 l 650 1000 l f"));
        font.setItem(COSName.CHAR_PROCS, procs);
        COSDictionary encoding = new COSDictionary();
        COSArray differences = new COSArray();
        differences.add(COSInteger.get(1));
        differences.add(COSName.getPDFName("bottom"));
        differences.add(COSName.getPDFName("top"));
        encoding.setItem(COSName.DIFFERENCES, differences);
        font.setItem(COSName.ENCODING, encoding);
        font.setInt(COSName.FIRST_CHAR, 1);
        font.setInt(COSName.LAST_CHAR, 2);
        font.setItem(COSName.WIDTHS, numbers(800, 800));
        String cmap = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /R1 def "
                + "1 begincodespacerange <01> <02> endcodespacerange 2 beginbfchar <01> <23B7> <02> <E001> endbfchar "
                + "endcmap CMapName currentdict /CMap defineresource pop end end";
        COSStream toUnicode = document.getDocument().createCOSStream();
        try (OutputStream out = toUnicode.createOutputStream()) {
            out.write(cmap.getBytes(StandardCharsets.US_ASCII));
        }
        font.setItem(COSName.TO_UNICODE, toUnicode);
        return font;
    }

    private static COSStream proc(PDDocument document, String ops) throws IOException {
        COSStream stream = document.getDocument().createCOSStream();
        try (OutputStream out = stream.createOutputStream()) {
            out.write(ops.getBytes(StandardCharsets.US_ASCII));
        }
        return stream;
    }

    private static COSArray numbers(float... values) {
        COSArray array = new COSArray();
        for (float v : values) {
            array.add(new COSFloat(v));
        }
        return array;
    }

    private static List<Glyph> read(String content) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            page.setResources(resources);
            document.addPage(page);
            PDStream stream = new PDStream(document);
            try (OutputStream out = stream.createOutputStream()) {
                out.write(content.getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            return read(document);
        }
    }

    private static List<Glyph> read(PDDocument document) throws IOException {
        List<PageData> pages = new ArrayList<>();
        new PageReader(document).read(0, 0, false, pages::add);
        return pages.getFirst().glyphs();
    }

    private static String text(List<Glyph> glyphs) {
        return glyphs.stream().sorted(Comparator.comparingDouble((Glyph g) -> g.x)).map(g -> g.text)
                .collect(Collectors.joining());
    }

    private static long cpuMillis(java.util.concurrent.Callable<?> work) throws Exception {
        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
        long start = bean.getCurrentThreadCpuTime();
        work.call();
        return (bean.getCurrentThreadCpuTime() - start) / 1_000_000;
    }

    @Test
    void manyStretchedBarsStayCheap() throws Exception {
        StringBuilder content = new StringBuilder("BT /F1 1 Tf ");
        for (int i = 0; i < 20_000; i++) {
            content.append("10 0 0 10 ").append(20 + i % 500).append(' ').append(50 + i / 500 * 15).append(" Tm (x) Tj ");
        }
        content.append("ET 0 0 612 792 re W n BT /F1 1 Tf ");
        for (int i = 0; i < 20_000; i++) {
            content.append("500 0 0 10 -200 ").append(53 + i / 500 * 15).append(" Tm <96> Tj ");
        }
        content.append("ET");
        long ms = cpuMillis(() -> read(content.toString()));
        assertTrue(ms < 20_000, "took " + ms + " ms of CPU");
    }

    @Test
    void manyRadicalPiecesStayCheap() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("R1"), new PDType3Font(radicalFont(document), null));
            page.setResources(resources);
            document.addPage(page);
            StringBuilder content = new StringBuilder("BT /R1 10 Tf ");
            for (int i = 0; i < 15_000; i++) {
                float x = 20 + i % 70 * 8;
                float y = 20 + i / 70 * 3.5f;
                content.append("1 0 0 1 ").append(x).append(' ').append(y).append(" Tm <01> Tj 1 0 0 1 ").append(x + 0.5f)
                        .append(' ').append(y + 12).append(" Tm <02> Tj ");
            }
            content.append("ET");
            PDStream stream = new PDStream(document);
            try (OutputStream out = stream.createOutputStream()) {
                out.write(content.toString().getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            long ms = cpuMillis(() -> read(document));
            assertTrue(ms < 20_000, "took " + ms + " ms of CPU");
        }
    }
}
