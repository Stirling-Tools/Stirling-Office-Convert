package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfWriterTest {

    private static final COSName ITEMS = COSName.getPDFName("Items");

    @TempDir
    Path dir;

    private Path sample(int items) throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument d = new PDDocument()) {
            for (int p = 0; p < 3; p++) {
                PDPage page = Samples.page(d);
                try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                    Samples.text(cs, Samples.std(Standard14Fonts.FontName.HELVETICA), 12, 72, 700, "Page " + p);
                }
            }
            COSArray list = new COSArray();
            for (int i = 0; i < items; i++) {
                COSDictionary item = new COSDictionary();
                item.setInt(COSName.K, i);
                item.setString(COSName.T, "item (" + i + ")");
                list.add(item);
            }
            d.getDocumentCatalog().getCOSObject().setItem(ITEMS, list);
            d.save(in.toFile());
        }
        return in;
    }

    private static String latin(Path pdf) throws Exception {
        return new String(Files.readAllBytes(pdf), StandardCharsets.ISO_8859_1);
    }

    @Test
    void partsTwoAndThreePackObjectsIntoObjectStreams() throws Exception {
        Path in = sample(1000);
        Path out = dir.resolve("2b.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        String s = latin(out);
        assertTrue(s.contains("/Type/ObjStm"));
        assertTrue(s.contains("/Type/XRef"));
        assertFalse(s.contains("\ntrailer"));
        assertTrue(Pattern.compile("\n\\d+ 0 obj\n").matcher(s).results().count() < 50);
        checkItems(out, 1000);
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
    }

    @Test
    void partOneWritesAClassicTableWithoutGaps() throws Exception {
        Path in = sample(300);
        Path out = dir.resolve("1b.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        String s = latin(out);
        assertFalse(s.contains("/ObjStm"));
        assertFalse(s.contains("/XRef"));
        Matcher m = Pattern.compile("\nxref\n0 (\\d+)\n").matcher(s);
        assertTrue(m.find());
        int size = Integer.parseInt(m.group(1));
        String table = s.substring(m.end(), m.end() + size * 20);
        assertEquals(1, Pattern.compile(" f\r\n").matcher(table).results().count());
        assertEquals(size - 1, Pattern.compile("\n\\d+ 0 obj\n").matcher(s).results().count());
        checkItems(out, 300);
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
    }

    @Test
    void streamsAreCompressedAndLongOnesGetALengthObject() throws Exception {
        Path in = dir.resolve("streams.pdf");
        byte[] big = new byte[PdfWriter.BUFFERED_STREAM_BYTES + 1000];
        new Random(7).nextBytes(big);
        try (PDDocument d = new PDDocument()) {
            Samples.page(d);
            COSStream plain = d.getDocument().createCOSStream();
            try (OutputStream o = plain.createOutputStream()) {
                o.write("abc ".repeat(5000).getBytes(StandardCharsets.US_ASCII));
            }
            COSStream large = d.getDocument().createCOSStream();
            try (OutputStream o = large.createOutputStream()) {
                o.write(big);
            }
            COSDictionary cat = d.getDocumentCatalog().getCOSObject();
            cat.setItem(COSName.getPDFName("Plain"), plain);
            cat.setItem(COSName.getPDFName("Large"), large);
            cat.setItem(COSName.getPDFName("Dangling"), new COSObject(null, new COSObjectKey(999_999, 0)));
            d.save(in.toFile());
        }
        for (PdfALevel level : new PdfALevel[] {PdfALevel.A1B, PdfALevel.A2B}) {
            Path out = dir.resolve(level + ".pdf");
            PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                COSDictionary cat = d.getDocumentCatalog().getCOSObject();
                COSStream plain = (COSStream) cat.getDictionaryObject(COSName.getPDFName("Plain"));
                assertEquals(COSName.FLATE_DECODE, plain.getFilters());
                try (InputStream i = plain.createInputStream()) {
                    assertEquals("abc ".repeat(5000), new String(i.readAllBytes(), StandardCharsets.US_ASCII));
                }
                COSStream large = (COSStream) cat.getDictionaryObject(COSName.getPDFName("Large"));
                assertTrue(large.getItem(COSName.LENGTH) instanceof COSObject);
                try (InputStream i = large.createInputStream()) {
                    assertArrayEquals(big, i.readAllBytes());
                }
                assertNull(cat.getDictionaryObject(COSName.getPDFName("Dangling")));
            }
            VeraPdf.assertCompliant(out, level);
        }
    }

    @Test
    void objectsUsedOnceAreWrittenInPlaceAndSharedOnesStayShared() throws Exception {
        Path in = dir.resolve("refs.pdf");
        try (PDDocument d = new PDDocument()) {
            Samples.page(d);
            COSArray list = new COSArray();
            COSDictionary shared = new COSDictionary();
            shared.setInt(COSName.N, 42);
            for (int i = 0; i < 400; i++) {
                COSDictionary item = new COSDictionary();
                COSDictionary own = new COSDictionary();
                own.setInt(COSName.K, i);
                item.setItem(COSName.A, own);
                item.setItem(COSName.getPDFName("Shared"), shared);
                list.add(item);
            }
            d.getDocumentCatalog().getCOSObject().setItem(ITEMS, list);
            d.save(in.toFile());
        }
        Path out = dir.resolve("refs-1b.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        assertTrue(Pattern.compile("\n\\d+ 0 obj\n").matcher(latin(out)).results().count() < 450);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSArray list = (COSArray) d.getDocumentCatalog().getCOSObject().getDictionaryObject(ITEMS);
            COSObject first = (COSObject) ((COSDictionary) list.getObject(0)).getItem(COSName.getPDFName("Shared"));
            COSObject last = (COSObject) ((COSDictionary) list.getObject(399)).getItem(COSName.getPDFName("Shared"));
            assertEquals(first.getKey(), last.getKey());
            assertEquals(42, ((COSDictionary) first.getObject()).getInt(COSName.N));
            assertEquals(399, ((COSDictionary) ((COSDictionary) list.getObject(399)).getDictionaryObject(COSName.A))
                    .getInt(COSName.K));
        }
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
    }

    private static void checkItems(Path out, int n) throws Exception {
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSArray list = (COSArray) d.getDocumentCatalog().getCOSObject().getDictionaryObject(ITEMS);
            assertEquals(n, list.size());
            for (int i = 0; i < n; i++) {
                COSDictionary item = (COSDictionary) list.getObject(i);
                assertEquals(i, item.getInt(COSName.K));
                assertEquals("item (" + i + ")", item.getString(COSName.T));
            }
            assertEquals(3, d.getNumberOfPages());
            assertTrue(Converted.text(out).contains("Page 2"));
        }
    }
}
