package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class DamagedPartsTest {

    private static final String BROKEN = "<?xml version=\"1.0\"?><broken><unclosed>";

    @TempDir
    Path dir;

    @Test
    void aDamagedHeaderDoesNotStopTheBody() throws Exception {
        byte[] doc = Fixtures.edit(docxWithHeader("Body survives", "Header text")).put("word/header1.xml", BROKEN).bytes();
        Converted c = convert("header.docx", doc);
        assertTrue(c.text.contains("Body survives"), c.text);
        assertFalse(c.text.contains("Header text"), c.text);
        assertFalse(c.result.warnings().isEmpty());
    }

    @Test
    void damagedNotesThemeNumberingAndSettingsOfAWordDocumentAreLeftOut() throws Exception {
        for (String type : List.of("footnotes", "endnotes", "theme", "numbering", "settings")) {
            String part = type.equals("theme") ? "word/theme/theme1.xml" : "word/" + type + ".xml";
            Fixtures.Zip z = Fixtures.edit(Fixtures.docx("Body survives"));
            if (!z.has(part)) {
                z.relationship("/word/document.xml", "rIdBroken", Fixtures.REL + type, part.substring(5), false);
            }
            Converted c = convert(type + ".docx", z.put(part, BROKEN).bytes());
            assertTrue(c.text.contains("Body survives"), type + ": " + c.text);
            assertTrue(c.warned("Left out a damaged part: /" + part), type + ": " + c.result.warnings());
        }
    }

    @Test
    void lostOrDamagedContentTypesAndPackageRelationshipsAreMadeAgain() throws Exception {
        for (String part : List.of("[Content_Types].xml", "_rels/.rels")) {
            for (boolean lost : new boolean[] {true, false}) {
                String how = (lost ? "lost-" : "broken-") + part.replaceAll("\\W", "");
                Fixtures.Zip d = Fixtures.edit(Fixtures.docx("Word body"));
                Converted w = convert(how + ".docx", (lost ? d.remove(part) : d.put(part, BROKEN)).bytes());
                assertTrue(w.text.contains("Word body"), how + ": " + w.text);
                assertTrue(w.warned("was repaired from"), how + ": " + w.result.warnings());
                Fixtures.Zip p = Fixtures.edit(Fixtures.pptx("First slide", "Second slide"));
                Converted s = convert(how + ".pptx", (lost ? p.remove(part) : p.put(part, BROKEN)).bytes());
                assertEquals(2, s.result.pages(), how);
                assertTrue(s.text.contains("Second slide"), how + ": " + s.text);
                Fixtures.Zip x = Fixtures.edit(workbook());
                Converted b = convert(how + ".xlsx", (lost ? x.remove(part) : x.put(part, BROKEN)).bytes());
                assertTrue(b.text.contains("third sheet"), how + ": " + b.text);
            }
        }
        byte[] both = Fixtures.edit(Fixtures.docx("Both gone")).remove("[Content_Types].xml").put("_rels/.rels", BROKEN)
                .bytes();
        Converted c = convert("both.docx", both);
        assertTrue(c.text.contains("Both gone"), c.text);
        assertTrue(c.warned("its main part was found by its usual name"), c.result.warnings().toString());
    }

    @Test
    void mainRelationshipsThatBreakOffKeepTheRelationshipsBeforeTheBreak() throws Exception {
        String rels = "ppt/_rels/presentation.xml.rels";
        Fixtures.Zip z = Fixtures.edit(Fixtures.pptx("First slide", "Second slide", "Third slide"));
        String text = z.text(rels);
        int cut = text.indexOf("/>", text.indexOf("slides/slide1.xml")) + 2;
        Converted c = convert("rels.pptx", z.put(rels, text.substring(0, cut) + "<Relationship Id=\"rId9\" Tar").bytes());
        assertEquals(1, c.result.pages());
        assertTrue(c.text.contains("First slide"), c.text);
        assertTrue(c.warned("/" + rels + " is damaged; only its start"), c.result.warnings().toString());
    }

    @Test
    void leavingOutContentMakesThePdfPartialButLeavingOutOnlyLooksDoesNot() throws Exception {
        byte[] deck = Fixtures.edit(Fixtures.pptx("First slide", "Second slide", "Third slide"))
                .put("ppt/slides/slide2.xml", BROKEN).bytes();
        Converted slide = convert("partial.pptx", deck);
        assertEquals(2, slide.result.pages());
        assertTrue(slide.result.truncated(), slide.result.warnings().toString());
        assertFalse(slide.result.pageLimitReached());
        for (String type : List.of("footnotes", "endnotes", "numbering", "settings", "theme")) {
            String part = type.equals("theme") ? "word/theme/theme1.xml" : "word/" + type + ".xml";
            Fixtures.Zip z = Fixtures.edit(Fixtures.docx("Body survives"));
            if (!z.has(part)) {
                z.relationship("/word/document.xml", "rIdBroken", Fixtures.REL + type, part.substring(5), false);
            }
            Converted c = convert("look-" + type + ".docx", z.put(part, BROKEN).bytes());
            boolean content = List.of("footnotes", "endnotes", "numbering").contains(type);
            assertEquals(content, c.result.truncated(), type + ": " + c.result.warnings());
        }
        byte[] styles = Fixtures.edit(Fixtures.docx("Styled body")).put("word/styles.xml", BROKEN).bytes();
        assertFalse(convert("look-styles.docx", styles).result.truncated());
        assertFalse(convert("whole.pptx", Fixtures.pptx("One", "Two")).result.truncated());
        assertTrue(OfficeToPdf.losesContent("/xl/worksheets/sheet1.xml"));
        assertTrue(OfficeToPdf.losesContent("/xl/styles.xml"));
        assertTrue(OfficeToPdf.losesContent("/ppt/slides/_rels/slide2.xml.rels"));
        assertFalse(OfficeToPdf.losesContent("/docProps/app.xml"));
        assertFalse(OfficeToPdf.losesContent("/word/comments.xml"));
        assertFalse(OfficeToPdf.losesContent("/xl/comments1.xml"));
        assertFalse(OfficeToPdf.losesContent("/ppt/theme/_rels/theme1.xml.rels"));
    }

    @Test
    void aDoctypeInARelationshipsPartLeavesOutWhatItLinksAndSaysSo() throws Exception {
        String rels = "word/_rels/document.xml.rels";
        Converted plain = convert("header.docx", docxWithHeader("Body survives", "Header text"));
        assertTrue(plain.text.contains("Header text"), plain.text);
        assertFalse(plain.result.truncated(), plain.result.warnings().toString());
        Fixtures.Zip z = Fixtures.edit(docxWithHeader("Body survives", "Header text"));
        z.put(rels, z.text(rels).replaceFirst("<Relationships", "<!DOCTYPE Relationships><Relationships"));
        Converted c = convert("doctype-rels.docx", z.bytes());
        assertTrue(c.text.contains("Body survives"), c.text);
        assertFalse(c.text.contains("Header text"), c.text);
        assertTrue(c.result.truncated(), c.result.warnings().toString());
        assertFalse(c.result.pageLimitReached());
        assertTrue(c.warned("Left out the relationships of /word/document.xml"), c.result.warnings().toString());
        String slideRels = "ppt/slides/_rels/slide2.xml.rels";
        Fixtures.Zip p = Fixtures.edit(Fixtures.pptx("First slide", "Second slide"));
        p.put(slideRels, p.text(slideRels).replaceFirst("<Relationships", "<!DOCTYPE Relationships><Relationships"));
        IOException e = assertThrows(IOException.class, () -> convert("doctype-rels.pptx", p.bytes()));
        assertEquals(OfficeToPdf.DOCTYPE, e.getMessage(), "POI reads every relationships part, so the deck is refused");
    }

    @Test
    void aDoctypeInThePackagePartsIsStillRefusedRatherThanRepaired() throws Exception {
        String doctype = "<?xml version=\"1.0\"?><!DOCTYPE t [<!ENTITY e \"x\">]><Types>&e;</Types>";
        for (String part : List.of("[Content_Types].xml", "_rels/.rels")) {
            byte[] doc = Fixtures.edit(Fixtures.docx("x")).put(part, doctype).bytes();
            IOException e = assertThrows(IOException.class, () -> convert("doctype.docx", doc));
            assertEquals(OfficeToPdf.DOCTYPE, e.getMessage(), part);
        }
    }

    @Test
    void aZipWithoutAnyOfficePartsIsStillNotAnOfficeDocument() throws Exception {
        byte[] zip = Fixtures.edit(Fixtures.docx("x")).remove("[Content_Types].xml").remove("_rels/.rels")
                .remove("word/document.xml").put("readme.txt", "just a zip").bytes();
        IOException e = assertThrows(IOException.class, () -> convert("plain.docx", zip));
        assertTrue(e.getMessage().contains("not an Office document"), e.getMessage());
    }

    @Test
    void damagedStylesDoNotStopTheDocument() throws Exception {
        byte[] doc = Fixtures.edit(Fixtures.docx("Styled body")).put("word/styles.xml", BROKEN).bytes();
        assertTrue(convert("styles.docx", doc).text.contains("Styled body"));
    }

    @Test
    void aDamagedMainPartStillFailsTheDocument() throws Exception {
        byte[] doc = Fixtures.edit(Fixtures.docx("x")).put("word/document.xml", BROKEN).bytes();
        assertThrows(IOException.class, () -> convert("main.docx", doc));
        byte[] deck = Fixtures.edit(Fixtures.pptx("x")).put("ppt/presentation.xml", BROKEN).bytes();
        IOException e = assertThrows(IOException.class, () -> convert("main.pptx", deck));
        assertTrue(e.getMessage().contains("/ppt/presentation.xml"), e.getMessage());
    }

    @Test
    void aDamagedSlideIsLeftOutAndTheOthersConvert() throws Exception {
        byte[] deck = Fixtures.edit(Fixtures.pptx("First slide", "Second slide", "Third slide"))
                .put("ppt/slides/slide2.xml", BROKEN).bytes();
        Converted c = convert("deck.pptx", deck);
        assertEquals(2, c.result.pages());
        assertTrue(c.text.contains("First slide") && c.text.contains("Third slide"), c.text);
        assertTrue(c.warned("Left out a damaged part: /ppt/slides/slide2.xml"), c.result.warnings().toString());
    }

    @Test
    void damagedLayoutsThemesAndStylesOfAPresentationAreLeftOut() throws Exception {
        for (String part : List.of("ppt/slideLayouts/slideLayout1.xml", "ppt/theme/theme1.xml", "ppt/tableStyles.xml",
                "ppt/slideLayouts/_rels/slideLayout1.xml.rels")) {
            byte[] deck = Fixtures.edit(Fixtures.pptx("First slide", "Second slide")).put(part, BROKEN).bytes();
            Converted c = convert(part.replace('/', '_') + ".pptx", deck);
            assertEquals(2, c.result.pages(), part);
            assertTrue(c.text.contains("First slide") && c.text.contains("Second slide"), part + ": " + c.text);
            assertTrue(c.warned("/" + part), part + ": " + c.result.warnings());
        }
    }

    @Test
    void aDamagedOrLostSlideMasterIsReplacedByAnEmptyOne() throws Exception {
        String master = "ppt/slideMasters/slideMaster1.xml";
        for (boolean lost : new boolean[] {false, true}) {
            Fixtures.Zip z = Fixtures.edit(Fixtures.pptx("Kept text", "Second slide"));
            Converted c = convert("master.pptx", (lost ? z.remove(master) : z.put(master, BROKEN)).bytes());
            assertEquals(2, c.result.pages());
            assertTrue(c.text.contains("Kept text") && c.text.contains("Second slide"), c.text);
            assertTrue(c.warned("The slide master /" + master + " is missing or damaged"), c.result.warnings().toString());
        }
    }

    @Test
    void slidesAndLayoutsThatLostTheirLinksAreLinkedAgain() throws Exception {
        String layout = "ppt/slideLayouts/slideLayout7.xml";
        for (String broken : List.of("remove " + layout, "remove ppt/slides/_rels/slide1.xml.rels",
                "remove ppt/slideLayouts/_rels/slideLayout7.xml.rels", "damage " + layout)) {
            String part = broken.substring(broken.indexOf(' ') + 1);
            Fixtures.Zip z = Fixtures.edit(Fixtures.pptx("Layout gone", "Still here"));
            assertTrue(z.has(part), part);
            Converted c = convert("links.pptx", (broken.startsWith("remove") ? z.remove(part) : z.put(part, BROKEN))
                    .bytes());
            assertEquals(2, c.result.pages(), broken);
            assertTrue(c.text.contains("Layout gone") && c.text.contains("Still here"), broken + ": " + c.text);
            assertTrue(c.warned("slide layout") || c.warned("slide master"), broken + ": " + c.result.warnings());
        }
    }

    @Test
    void aPartWithoutAContentTypeIsSkippedInAPresentation() throws Exception {
        byte[] deck = Fixtures.edit(Fixtures.pptx("Only slide")).put("ppt/slides/.slide1.xml.swp", new byte[] {1, 2, 3})
                .bytes();
        Converted c = convert("swap.pptx", deck);
        assertTrue(c.text.contains("Only slide"), c.text);
        assertEquals(1, c.result.pages());
        assertTrue(c.warned("/ppt/slides/.slide1.xml.swp"), c.result.warnings().toString());
    }

    @Test
    void aDamagedSheetDoesNotStopTheOtherSheets() throws Exception {
        byte[] book = Fixtures.edit(workbook()).put("xl/worksheets/sheet2.xml", BROKEN).bytes();
        Converted c = convert("book.xlsx", book);
        assertTrue(c.text.contains("first sheet") && c.text.contains("third sheet"), c.text);
        assertTrue(c.warned("second_sheet"), c.result.warnings().toString());
    }

    @Test
    void unreadableZipDataInAPartIsReportedAsThatPart() throws Exception {
        byte[] doc = corrupt(Fixtures.edit(docxWithHeader("Body survives", "Header text")).bytes(), "word/header1.xml");
        Converted c = convert("crc.docx", doc);
        assertTrue(c.text.contains("Body survives"), c.text);
        assertTrue(c.warned("/word/header1.xml"), c.result.warnings().toString());
    }

    @Test
    void aHugeThumbnailThatInflatesLikeABombIsNeverReadSoTheDocumentConverts() throws Exception {
        byte[] padding = new byte[3 << 20];
        new java.util.Random(4).nextBytes(padding);
        byte[] doc = Fixtures.zipBomb(Fixtures.edit(Fixtures.docx("Thumbnail aside")).put("word/media/x.bin", padding)
                .bytes(), "docProps/thumbnail.emf", 150L << 20);
        assertTrue(convert("thumb.docx", doc).text.contains("Thumbnail aside"));
    }

    @Test
    void leavesOutAtMostEightDamagedPartsAndThenReportsTheFirst() throws Exception {
        Fixtures.Zip z = Fixtures.edit(Fixtures.docx("x"));
        for (int i = 0; i < 12; i++) {
            z.put("word/broken" + i + ".xml", BROKEN);
        }
        Path in = Fixtures.write(dir, "many.docx", z.bytes());
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        OfficeToPdf.Renderer readsAll = (source, job) -> {
            calls.incrementAndGet();
            for (int i = 0; i < 12; i++) {
                if (job.zip().exists("/word/broken" + i + ".xml")) {
                    job.zip().xml("/word/broken" + i + ".xml");
                }
            }
        };
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.render(in, OfficeToPdf.Format.DOCX,
                java.io.OutputStream.nullOutputStream(), OfficeToPdf.Options.defaults(), readsAll));
        assertTrue(e.getMessage().contains("/word/broken0.xml"), e.getMessage());
        assertEquals(OfficeToPdf.MAX_DAMAGED_PARTS + 1, calls.get());

        calls.set(0);
        OfficeToPdf.Renderer readsTwo = (source, job) -> {
            calls.incrementAndGet();
            for (int i = 0; i < 2; i++) {
                if (job.zip().exists("/word/broken" + i + ".xml")) {
                    job.zip().xml("/word/broken" + i + ".xml");
                }
            }
            job.newPage(300, 300).close();
        };
        OfficeToPdf.Result r = OfficeToPdf.render(in, OfficeToPdf.Format.DOCX, java.io.OutputStream.nullOutputStream(),
                OfficeToPdf.Options.defaults(), readsTwo);
        assertEquals(3, calls.get());
        assertEquals(1, r.pages());
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("Left out a damaged part: /word/broken1.xml")),
                r.warnings().toString());

        calls.set(0);
        byte[] badMain = Fixtures.edit(Fixtures.docx("x")).put("word/document.xml", BROKEN).bytes();
        Path main = Fixtures.write(dir, "main2.docx", badMain);
        OfficeToPdf.Renderer readsMain = (source, job) -> {
            calls.incrementAndGet();
            job.zip().xml("/word/document.xml");
        };
        assertThrows(OfficeZip.DamagedPart.class, () -> OfficeToPdf.render(main, OfficeToPdf.Format.DOCX,
                java.io.OutputStream.nullOutputStream(), OfficeToPdf.Options.defaults(), readsMain));
        assertEquals(1, calls.get());
    }

    @Test
    void aSlideWhoseRelationshipsAreDamagedIsLeftOutWithThem() throws Exception {
        byte[] deck = Fixtures.edit(Fixtures.pptx("First slide", "Second slide", "Third slide"))
                .put("ppt/slides/_rels/slide2.xml.rels", BROKEN).bytes();
        Converted c = convert("slide-rels.pptx", deck);
        assertEquals(2, c.result.pages());
        assertTrue(c.text.contains("First slide") && c.text.contains("Third slide"), c.text);
        assertTrue(c.warned("/ppt/slides/_rels/slide2.xml.rels"), c.result.warnings().toString());
        assertEquals("/ppt/slides/slide2.xml", OfficeToPdf.sourceOf("/ppt/slides/_rels/slide2.xml.rels"));
        assertEquals("/word/document.xml", OfficeToPdf.sourceOf("word/_rels/document.xml.rels"));
        assertEquals(null, OfficeToPdf.sourceOf("/_rels/.rels"));
        assertEquals(null, OfficeToPdf.sourceOf("/ppt/slides/slide2.xml"));
    }

    @Test
    void aDocumentCutShortOfItsZipDirectoryIsRepairedAndConverted() throws Exception {
        byte[] docx = Fixtures.docx("Truncated but readable");
        Converted d = convert("cut.docx", java.util.Arrays.copyOf(docx, directoryOffset(docx)));
        assertTrue(d.text.contains("Truncated but readable"), d.text);
        assertTrue(d.warned("was repaired from"), d.result.warnings().toString());
        byte[] pptx = Fixtures.pptx("Slide one", "Slide two");
        Converted p = convert("cut.pptx", java.util.Arrays.copyOf(pptx, directoryOffset(pptx)));
        assertEquals(2, p.result.pages());
        assertTrue(p.text.contains("Slide two"), p.text);
        byte[] xlsx = workbook();
        Converted x = convert("cut.xlsx", java.util.Arrays.copyOf(xlsx, directoryOffset(xlsx)));
        assertTrue(x.text.contains("second sheet"), x.text);
        assertTrue(x.warned("was repaired from"), x.result.warnings().toString());
    }

    @Test
    void aRepairedPackageThatLostItsContentTypesGetsThemFromThePartNames() throws Exception {
        byte[] docx = Fixtures.edit(Fixtures.docx("No types left")).remove("[Content_Types].xml").bytes();
        Converted d = convert("notypes.docx", java.util.Arrays.copyOf(docx, directoryOffset(docx)));
        assertTrue(d.text.contains("No types left"), d.text);
        assertTrue(d.warned("guessed from the part names"), d.result.warnings().toString());
        byte[] pptx = Fixtures.edit(Fixtures.pptx("Typed again", "And again")).remove("[Content_Types].xml").bytes();
        Converted p = convert("notypes.pptx", java.util.Arrays.copyOf(pptx, directoryOffset(pptx)));
        assertEquals(2, p.result.pages());
        assertTrue(p.text.contains("And again"), p.text);
        byte[] xlsx = Fixtures.edit(workbook()).remove("[Content_Types].xml").bytes();
        Converted x = convert("notypes.xlsx", java.util.Arrays.copyOf(xlsx, directoryOffset(xlsx)));
        assertTrue(x.text.contains("third sheet"), x.text);
        Converted intact = convert("intact-notypes.docx", docx);
        assertTrue(intact.text.contains("No types left"), intact.text);
        assertTrue(intact.warned("guessed from the part names"), intact.result.warnings().toString());
        byte[] bare = Fixtures.edit(Fixtures.pptx("Found anyway")).remove("[Content_Types].xml").remove("_rels/.rels")
                .bytes();
        Converted b = convert("bare.pptx", java.util.Arrays.copyOf(bare, directoryOffset(bare)));
        assertTrue(b.text.contains("Found anyway"), b.text);
        assertTrue(b.warned("its main part was found by its usual name"), b.result.warnings().toString());
    }

    @Test
    void aDocumentWhoseMainPartIsCutShortConvertsWhatIsLeft() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            body.append("<w:p><w:r><w:t>Line ").append(i).append(" of the kept start</w:t></w:r></w:p>");
        }
        Fixtures.Zip z = Fixtures.edit(Fixtures.docx("x"));
        String main = z.text("word/document.xml").replace("<w:body>", "<w:body>" + body);
        byte[] docx = z.remove("word/document.xml").put("word/document.xml", main).bytes();
        Converted c = convert("main-cut.docx", java.util.Arrays.copyOf(docx, directoryOffset(docx) - 2500));
        assertTrue(c.text.contains("Line 0 of the kept start"), c.text.substring(0, Math.min(200, c.text.length())));
        assertFalse(c.text.contains("Line 1999 of"));
        assertTrue(c.warned("cut short were kept"), c.result.warnings().toString());
    }

    @Test
    void aMainPartThatBreaksOffIsConvertedUpToTheBreak() throws Exception {
        Fixtures.Zip z = Fixtures.edit(Fixtures.docx("Before the break", "After the break"));
        String main = z.text("word/document.xml");
        int second = main.indexOf("<w:p", main.indexOf("Before the break"));
        byte[] docx = z.put("word/document.xml", main.substring(0, second) + "<w:p><w:r><w:t>bad</w:t>"
                + main.substring(second)).bytes();
        Converted c = convert("broken-main.docx", docx);
        assertTrue(c.text.contains("Before the break"), c.text);
        assertFalse(c.text.contains("After the break"), c.text);
        assertTrue(c.warned("only its start, up to the damage, was converted"), c.result.warnings().toString());
    }

    private static int directoryOffset(byte[] zip) {
        for (int i = zip.length - 22; i >= 0; i--) {
            if (zip[i] == 'P' && zip[i + 1] == 'K' && zip[i + 2] == 5 && zip[i + 3] == 6) {
                return (zip[i + 16] & 0xFF) | (zip[i + 17] & 0xFF) << 8 | (zip[i + 18] & 0xFF) << 16
                        | (zip[i + 19] & 0xFF) << 24;
            }
        }
        throw new IllegalArgumentException("no end record");
    }

    private Converted convert(String name, byte[] data) throws IOException {
        Path in = Fixtures.write(dir, name, data);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.Result result = OfficeToPdf.convert(in, out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            return new Converted(result, new PDFTextStripper().getText(pdf));
        }
    }

    private record Converted(OfficeToPdf.Result result, String text) {
        boolean warned(String part) {
            return result.warnings().stream().anyMatch(w -> w.contains(part));
        }
    }

    private static byte[] docxWithHeader(String body, String header) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText(body);
            XWPFHeader h = doc.createHeader(HeaderFooterType.DEFAULT);
            h.createParagraph().createRun().setText(header);
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] workbook() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String name : List.of("first sheet", "second sheet", "third sheet")) {
                wb.createSheet(name.replace(' ', '_')).createRow(0).createCell(0).setCellValue(name);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // The entry's deflated bytes are scrambled, so inflating it fails or yields garbage
    private static byte[] corrupt(byte[] zip, String entry) {
        byte[] data = Fixtures.edit(zip).put(entry, "<w:hdr>" + "x".repeat(4000)).bytes();
        byte[] name = entry.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + 30 < data.length; i++) {
            if (data[i] == 'P' && data[i + 1] == 'K' && data[i + 2] == 3 && data[i + 3] == 4
                    && (data[i + 26] & 0xFF) == name.length
                    && new String(data, i + 30, name.length, StandardCharsets.UTF_8).equals(entry)) {
                int start = i + 30 + name.length + (data[i + 28] & 0xFF);
                for (int k = start + 2; k < start + 12; k++) {
                    data[k] = (byte) 0xFF;
                }
                return data;
            }
        }
        throw new IllegalArgumentException("no entry " + entry);
    }
}
