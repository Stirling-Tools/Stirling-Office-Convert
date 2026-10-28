package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepairTest {

    @TempDir
    Path dir;

    private static final String[] COLOURS = {"1 0 0", "0 1 0", "0 0 1", "1 1 0"};

    private static RawPdf pages(int count) {
        RawPdf r = new RawPdf();
        r.add("<</Type/Catalog/Pages 2 0 R>>");
        StringBuilder kids = new StringBuilder();
        for (int p = 0; p < count; p++) {
            kids.append(3 + 2 * p).append(" 0 R ");
        }
        r.add("<</Type/Pages/Kids[" + kids + "]/Count " + count + "/MediaBox[0 0 200 300]>>");
        for (int p = 0; p < count; p++) {
            r.add("<</Type/Page/Parent 2 0 R/Resources<<>>/Contents " + (4 + 2 * p) + " 0 R>>");
            r.add(RawPdf.stream("", COLOURS[p % COLOURS.length] + " rg 10 " + (10 + 20 * p) + " 50 50 re f"));
        }
        return r;
    }

    private Path generated(String name) throws IOException {
        Path in = dir.resolve(name + ".pdf");
        try (PDDocument d = new PDDocument()) {
            PDType1Font helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            BufferedImage picture = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 48; y++) {
                for (int x = 0; x < 64; x++) {
                    picture.setRGB(x, y, new Color(x * 4, y * 5, (x * y) % 256).getRGB());
                }
            }
            for (int p = 0; p < 3; p++) {
                PDPage page = new PDPage(PDRectangle.A6);
                d.addPage(page);
                PDImageXObject image = LosslessFactory.createFromImage(d, picture);
                try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                    for (int i = 0; i < 30; i++) {
                        cs.setNonStrokingColor(i / 30f, 0.5f, 0.75f);
                        cs.addRect(10.5f + i, 20.25f + i * 3, 100 - i, 40);
                        cs.fill();
                    }
                    cs.drawImage(image, 20, 150, 64, 48);
                    cs.beginText();
                    cs.setFont(helvetica, 11);
                    cs.newLineAtOffset(12, 250);
                    cs.showText("Page " + (p + 1));
                    cs.endText();
                }
            }
            d.save(in.toFile(), CompressParameters.NO_COMPRESSION);
        }
        return in;
    }

    private static String text(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
    }

    private Path write(String name, String content) throws IOException {
        Path p = dir.resolve(name + ".pdf");
        Files.write(p, content.getBytes(StandardCharsets.ISO_8859_1));
        return p;
    }

    private static void assertSoundTree(Path pdf, int expected) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            COSDictionary root = d.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.PAGES);
            assertNotNull(root);
            assertEquals(expected, root.getInt(COSName.COUNT));
            assertEquals(expected, d.getNumberOfPages());
            int seen = 0;
            for (PDPage page : d.getPages()) {
                PDRectangle box = page.getMediaBox();
                assertTrue(box.getWidth() > 0 && box.getHeight() > 0);
                seen++;
            }
            assertEquals(expected, seen);
            assertNoNullKids(root, 0);
        }
    }

    private static void assertNoNullKids(COSDictionary node, int depth) {
        COSArray kids = node.getCOSArray(COSName.KIDS);
        if (kids == null || depth > 32) {
            return;
        }
        for (int i = 0; i < kids.size(); i++) {
            COSBase kid = kids.get(i);
            assertTrue(kid instanceof COSObject, "kid " + i + " is not a reference");
            COSBase value = ((COSObject) kid).getObject();
            assertTrue(value instanceof COSDictionary, "kid " + i + " is " + value);
            assertNoNullKids((COSDictionary) value, depth + 1);
        }
    }

    @Test
    void aLowPageCountKeepsEveryPage() throws Exception {
        Path good = pages(4).write(dir, "good");
        Path in = write("low", text(good).replace("/Count 4", "/Count 1"));
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.Result r = PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults());
        assertTrue(r.rewritten());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("page count")), r.warnings().toString());
        assertSoundTree(out, 4);
        Pages.assertSameRendering(good, out);
    }

    @Test
    void aHugePageCountIsCorrected() throws Exception {
        Path good = pages(2).write(dir, "good");
        Path in = write("huge", text(good).replace("/Count 2", "/Count 1410065407"));
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults());
        assertSoundTree(out, 2);
        Pages.assertSameRendering(good, out);
    }

    @Test
    void aKidPointingAtAMissingObjectGetsItsOrphanedPageBack() throws Exception {
        Path good = pages(3).write(dir, "good");
        Path in = write("dangling", text(good).replace("7 0 R ]", "97 0 R ]"));
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.Result r = PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("reattached")), r.warnings().toString());
        assertSoundTree(out, 3);
        Pages.assertSameRendering(good, out);
    }

    @Test
    void aKidPointingAtTheWrongObjectGetsItsOrphanedPageBack() throws Exception {
        RawPdf raw = pages(3);
        raw.add("<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>");
        Path good = raw.write(dir, "good");
        Path in = write("wrong", text(good).replace("5 0 R ", "9 0 R "));
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults());
        assertSoundTree(out, 3);
        Pages.assertSameRendering(good, out);
    }

    @Test
    void aDeletedPageBecomesASizedBlankPageNotANullKid() throws Exception {
        RawPdf raw = pages(3);
        raw.set(5, "null");
        Path in = raw.write(dir, "deleted");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.Result r = PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("blank")), r.warnings().toString());
        assertSoundTree(out, 3);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertEquals(200, d.getPage(1).getMediaBox().getWidth(), 0.001);
            assertEquals(300, d.getPage(1).getMediaBox().getHeight(), 0.001);
        }
    }

    @Test
    void aPageTreeRootThatIsNotADictionaryIsRebuiltFromThePages() throws Exception {
        RawPdf raw = pages(3);
        raw.set(1, "<</Type/Catalog/Pages 9 0 R>>");
        raw.add("42");
        Path good = pages(3).write(dir, "good");
        Path in = raw.write(dir, "rootless");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults());
        assertSoundTree(out, 3);
        Pages.assertSameRendering(good, out);
    }

    @Test
    void aDeepPageTreeKeepsItsPage() throws Exception {
        RawPdf raw = new RawPdf();
        raw.add("<</Type/Catalog/Pages 2 0 R>>");
        int depth = 200;
        for (int level = 0; level < depth; level++) {
            int number = 2 + level;
            String parent = level == 0 ? "" : "/Parent " + (number - 1) + " 0 R";
            raw.add("<</Type/Pages/Kids[" + (number + 1) + " 0 R]/Count 1" + parent + ">>");
        }
        int page = 2 + depth;
        raw.add("<</Type/Page/Parent " + (page - 1) + " 0 R/MediaBox[0 0 200 300]/Resources<<>>/Contents " + (page + 1)
                + " 0 R>>");
        raw.add(RawPdf.stream("", "1 0 0 rg 10 10 50 50 re f"));
        Path in = raw.write(dir, "deep");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults().alwaysRewrite(true));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertEquals(1, d.getNumberOfPages());
        }
        Pages.assertSameRendering(in, out);
    }

    @Test
    void aPageTreeLoopIsCutAndTheRealPagesStay() throws Exception {
        RawPdf raw = pages(1);
        raw.set(2, "<</Type/Pages/Kids[3 0 R 5 0 R]/Count 2/MediaBox[0 0 200 300]>>");
        raw.add("<</Type/Pages/Kids[6 0 R]/Count 1>>");
        raw.add("<</Type/Pages/Kids[5 0 R]/Count 1>>");
        Path in = raw.write(dir, "loop");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults());
        assertSoundTree(out, 1);
        Pages.assertSameRendering(pages(1).write(dir, "good"), out);
    }

    @Test
    void pagesWithoutAMediaBoxAnywhereGetTheDefaultOne() throws Exception {
        RawPdf raw = RawPdf.page("", "0 0 1 rg 10 10 50 50 re f");
        raw.set(3, "<</Type/Page/Parent 2 0 R/Resources<<>>/Contents 4 0 R>>");
        Path in = raw.write(dir, "boxless");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults());
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSBase box = inherited(d.getPage(0).getCOSObject(), COSName.MEDIA_BOX);
            assertTrue(box instanceof COSArray a && a.size() == 4, String.valueOf(box));
        }
    }

    @Test
    void aTopLevelObjectTheScanFoundButTheTableLacksIsRecovered() throws Exception {
        Path good = pages(2).write(dir, "good");
        String s = text(good);
        int at = s.indexOf("xref\n");
        Path in = write("shifted", s.substring(0, at) + "xref\n0 3\n0000000000 65535 f\r\n0000000999 00000 n\r\n"
                + "0000000888 00000 n\r\n" + s.substring(s.indexOf("trailer")));
        try (PDDocument document = Loader.loadPDF(in.toFile())) {
            COSObjectKey page = new COSObjectKey(5, 0);
            document.getDocument().getXrefTable().remove(page);
            Source source = new Source(document.getDocument());
            assertTrue(source.rebuilt());
            assertEquals(List.of(page), source.recover(List.of(page), (key, value) -> {
                assertTrue(value instanceof COSDictionary d && COSName.PAGE.equals(d.getCOSName(COSName.TYPE)));
            }));
        }
    }

    @Test
    void crlfConvertedFilesGetTheirStreamsBack() throws Exception {
        Path good = generated("good");
        String mangled = text(good).replaceAll("(?<!\r)\n", "\r\n");
        Path in = write("crlf", mangled);
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.Result r = PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("line endings")), r.warnings().toString());
        assertSoundTree(out, 3);
        Pages.assertSameRendering(good, out);
    }

    @Test
    void crlfConversionOfStreamsThatHadNoLineFeedsIsLeftAlone() throws Exception {
        Path good = pages(2).write(dir, "good");
        String s = text(good);
        Path in = write("crlf", s.replaceAll("(?<!\r)\n", "\r\n"));
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults());
        assertSoundTree(out, 2);
        Pages.assertSameRendering(good, out);
    }

    @Test
    void aRepairIsWrittenEvenWhenItIsLarger() throws Exception {
        RawPdf raw = pages(1);
        String s = text(raw.write(dir, "plain"));
        Path in = write("noxref", s.substring(0, s.indexOf("xref\n")) + "%%EOF\n");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.Result r = PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults());
        assertTrue(r.rewritten(), r.warnings().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("repaired")), r.warnings().toString());
        assertFalse(Arrays.equals(Files.readAllBytes(in), Files.readAllBytes(out)));
        assertTrue(text(out).contains("startxref"));
        assertSoundTree(out, 1);
    }

    @Test
    void anUndamagedFileIsNotReportedAsRepaired() throws Exception {
        Path in = pages(3).write(dir, "fine");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.Result r = PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults());
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("repaired") || w.contains("damaged")),
                r.warnings().toString());
        Pages.assertSameRendering(in, out);
    }

    @Test
    void readableMembersOfADamagedObjectStreamAreRecovered() throws Exception {
        String[] members = {
                "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 300]/Resources<<>>/Contents 6 0 R>>",
                "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 300]/Resources<<>>/Contents 7 0 R>>",
                "<</Info(" + filler(6000) + ")>>",
                "<</Type/Pages/Kids[3 0 R 4 0 R]/Count 2>>",
                "<</Type/Catalog/Pages 2 0 R>>"};
        int[] numbers = {3, 4, 5, 2, 1};
        StringBuilder header = new StringBuilder();
        StringBuilder bodies = new StringBuilder();
        for (int i = 0; i < members.length; i++) {
            header.append(numbers[i]).append(' ').append(bodies.length()).append(' ');
            bodies.append(members[i]).append('\n');
        }
        byte[] packed = deflate((header + bodies.toString()).getBytes(StandardCharsets.ISO_8859_1));
        Random noise = new Random(7);
        byte[] damaged = packed.clone();
        int from = damaged.length / 2;
        for (int i = from; i < Math.min(damaged.length, from + 64); i++) {
            damaged[i] = (byte) noise.nextInt(256);
        }
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        String start = "%PDF-1.5\n6 0 obj " + RawPdf.stream("", "1 0 0 rg 10 10 50 50 re f") + " endobj\n"
                + "7 0 obj " + RawPdf.stream("", "0 0 1 rg 10 10 50 50 re f") + " endobj\n8 0 obj ";
        pdf.writeBytes(start.getBytes(StandardCharsets.ISO_8859_1));
        pdf.writeBytes(RawPdf.stream("/Type/ObjStm/N 5/First " + header.length() + "/Filter/FlateDecode", damaged));
        pdf.writeBytes(" endobj\ntrailer <</Size 9/Root 1 0 R>>\nstartxref\n0\n%%EOF\n"
                .getBytes(StandardCharsets.ISO_8859_1));
        Path in = dir.resolve("objstm.pdf");
        Files.write(in, pdf.toByteArray());
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.Result r = PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults());
        assertTrue(r.rewritten());
        assertSoundTree(out, 2);
        RawPdf expected = new RawPdf();
        expected.add("<</Type/Catalog/Pages 2 0 R>>");
        expected.add("<</Type/Pages/Kids[3 0 R 4 0 R]/Count 2>>");
        expected.add(members[0].replace("6 0 R", "5 0 R"));
        expected.add(members[1].replace("7 0 R", "6 0 R"));
        expected.add(RawPdf.stream("", "1 0 0 rg 10 10 50 50 re f"));
        expected.add(RawPdf.stream("", "0 0 1 rg 10 10 50 50 re f"));
        Pages.assertSameRendering(expected.write(dir, "expected"), out);
    }

    @Test
    void anOutputWithANullKidFailsTheCheck() throws Exception {
        RawPdf raw = pages(2);
        raw.set(2, "<</Type/Pages/Kids[3 0 R null]/Count 2/MediaBox[0 0 200 300]>>");
        assertThrows(IOException.class, () -> PdfOptimizer.check(raw.write(dir, "nullkid"), null, 2));
        assertEquals(2, PdfOptimizer.check(pages(2).write(dir, "fine"), null, 2));
    }

    private static COSBase inherited(COSDictionary node, COSName key) {
        for (int depth = 0; node != null && depth < 32; depth++) {
            COSBase value = node.getDictionaryObject(key);
            if (value != null) {
                return value;
            }
            node = node.getCOSDictionary(COSName.PARENT);
        }
        return null;
    }

    private static String filler(int length) {
        Random r = new Random(11);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < length; i++) {
            b.append((char) ('a' + r.nextInt(26)));
        }
        return b.toString();
    }

    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(b, new Deflater(9))) {
            z.write(data);
        }
        return b.toByteArray();
    }
}
