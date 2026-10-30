package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.TreeMap;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfA4FeaturesTest {

    @TempDir
    Path dir;

    private Path convert(PDDocument d, String name) throws Exception {
        Path in = dir.resolve(name + ".pdf");
        d.save(in.toFile());
        Path out = dir.resolve(name + "-a4.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A4));
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        return out;
    }

    @Test
    void symbolicTrueTypeWithOnlyAWindowsUnicodeCmapIsRebuilt() throws Exception {
        Path out;
        try (PDDocument d = new PDDocument()) {
            org.apache.fontbox.ttf.TrueTypeFont lib = Samples.liberation();
            TreeMap<Integer, Integer> map = new TreeMap<>();
            for (char c = 'A'; c <= 'Z'; c++) {
                map.put((int) c, lib.nameToGID(String.valueOf(c)));
            }
            byte[] program = FontSamples.withCmap(lib, Cmaps.table(java.util.List.of(new Cmaps.Subtable(3, 1, map))));
            COSDictionary font = Samples.trueTypeUnembedded("UnicodeOnly", Samples.arialWidths());
            COSStream file = d.getDocument().createCOSStream();
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(program);
            }
            file.setInt(COSName.LENGTH1, program.length);
            COSDictionary fd = (COSDictionary) font.getDictionaryObject(COSName.FONT_DESC);
            fd.setItem(COSName.FONT_FILE2, file);
            fd.setInt(COSName.FLAGS, 4);
            font.removeItem(COSName.ENCODING);
            FontSamples.page(d, "BT /A 14 Tf 50 780 Td (ABC) Tj ET", PDFontFactory.createFont(font));
            out = convert(d, "symbolic");
        }
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertTrue(new PDFTextStripper().getText(d).contains("ABC"));
        }
    }

    @Test
    void halftoneOriginIsRemovedFromGraphicsStates() throws Exception {
        try (PDDocument d = new PDDocument()) {
            PDPage page = FontSamples.page(d, "/GS0 gs BT /A 14 Tf 50 780 Td (Halftone) Tj ET",
                    FontSamples.trueType(d));
            COSDictionary gs = new COSDictionary();
            gs.setItem(COSName.TYPE, COSName.EXT_G_STATE);
            COSArray origin = new COSArray();
            origin.add(org.apache.pdfbox.cos.COSInteger.ZERO);
            origin.add(org.apache.pdfbox.cos.COSInteger.ZERO);
            gs.setItem(COSName.getPDFName("HTO"), origin);
            COSDictionary states = new COSDictionary();
            states.setItem("GS0", gs);
            page.getResources().getCOSObject().setItem(COSName.EXT_G_STATE, states);
            convert(d, "hto");
        }
    }

    @Test
    void partTwoLimitsDoNotApplyToPartFour() throws Exception {
        String longText = "x".repeat(40_000);
        Path out;
        try (PDDocument d = new PDDocument()) {
            StringBuilder content = new StringBuilder();
            content.append("q ".repeat(32)).append("0 0 1 rg 10 10 100 100 re f ").append("Q ".repeat(32));
            PDPage page = FontSamples.page(d, content.toString(), FontSamples.trueType(d));
            page.setMediaBox(new PDRectangle(20_000, 300));
            COSDictionary annot = new COSDictionary();
            annot.setItem(COSName.TYPE, COSName.ANNOT);
            annot.setItem(COSName.SUBTYPE, COSName.getPDFName("Text"));
            annot.setItem(COSName.RECT, new PDRectangle(50, 50, 20, 20).getCOSArray());
            annot.setString(COSName.CONTENTS, longText);
            COSArray annots = new COSArray();
            annots.add(annot);
            page.getCOSObject().setItem(COSName.ANNOTS, annots);
            out = convert(d, "limits");
        }
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDPage page = d.getPage(0);
            assertEquals(20_000, page.getMediaBox().getWidth(), 0.01);
            assertEquals(longText, page.getAnnotations().get(0).getContents());
            String content = new String(page.getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertEquals(32, java.util.Arrays.stream(content.split("\\s+")).filter("q"::equals).count());
            assertFalse(page.getResources().getXObjectNames().iterator().hasNext());
        }
    }

    @Test
    void annotationTriggersAllowedByPartFourAreKeptAndOthersRemoved() throws Exception {
        Path out;
        try (PDDocument d = new PDDocument()) {
            PDPage page = FontSamples.page(d, "BT /A 14 Tf 50 780 Td (Triggers) Tj ET", FontSamples.trueType(d));
            COSDictionary annot = new COSDictionary();
            annot.setItem(COSName.TYPE, COSName.ANNOT);
            annot.setItem(COSName.SUBTYPE, COSName.getPDFName("Square"));
            annot.setItem(COSName.RECT, new PDRectangle(50, 600, 100, 100).getCOSArray());
            annot.setItem(COSName.BM, COSName.getPDFName("Sparkle"));
            COSDictionary aa = new COSDictionary();
            aa.setItem("E", uri("https://example.com/enter"));
            aa.setItem("PO", uri("https://example.com/open"));
            aa.setItem("X", script());
            annot.setItem(COSName.AA, aa);
            COSArray annots = new COSArray();
            annots.add(annot);
            page.getCOSObject().setItem(COSName.ANNOTS, annots);
            out = convert(d, "triggers");
        }
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary annot = d.getPage(0).getAnnotations().get(0).getCOSObject();
            COSDictionary aa = (COSDictionary) annot.getDictionaryObject(COSName.AA);
            assertNotNull(aa.getDictionaryObject("E"));
            assertNull(aa.getDictionaryObject("PO"));
            assertNull(aa.getDictionaryObject("X"));
            assertEquals(COSName.getPDFName("Normal"), annot.getDictionaryObject(COSName.BM));
        }
    }

    @Test
    void pageOutputIntentsGiveWayToTheDocumentIntent() throws Exception {
        Path out;
        try (PDDocument d = new PDDocument()) {
            PDPage page = FontSamples.page(d, "0 0 0 1 k 50 50 100 100 re f", FontSamples.trueType(d));
            COSStream profile = d.getDocument().createCOSStream();
            try (OutputStream o = profile.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(IccProfiles.cmyk());
            }
            profile.setInt(COSName.N, 4);
            COSDictionary intent = new COSDictionary();
            intent.setItem(COSName.TYPE, COSName.getPDFName("OutputIntent"));
            intent.setItem(COSName.S, COSName.getPDFName("GTS_PDFA1"));
            intent.setString(COSName.getPDFName("OutputConditionIdentifier"), "Scanner");
            intent.setItem(COSName.getPDFName("DestOutputProfile"), profile);
            COSArray intents = new COSArray();
            intents.add(intent);
            page.getCOSObject().setItem(COSName.OUTPUT_INTENTS, intents);
            out = convert(d, "page-intent");
        }
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertNull(d.getPage(0).getCOSObject().getDictionaryObject(COSName.OUTPUT_INTENTS));
        }
    }

    @Test
    void optionalContentAutoStateAndObjectMetadataSurvivePartFour() throws Exception {
        Path out;
        try (PDDocument d = new PDDocument()) {
            PDPage page = FontSamples.page(d, "BT /A 14 Tf 50 780 Td (Layers) Tj ET", FontSamples.trueType(d));
            COSDictionary group = new COSDictionary();
            group.setItem(COSName.TYPE, COSName.OCG);
            group.setString(COSName.NAME, "Print only");
            COSArray groups = new COSArray();
            groups.add(group);
            COSDictionary usage = new COSDictionary();
            usage.setItem(COSName.getPDFName("Event"), COSName.getPDFName("Print"));
            usage.setItem(COSName.getPDFName("Category"), new COSArray(java.util.List.of(COSName.getPDFName("Print"))));
            COSArray as = new COSArray();
            as.add(usage);
            COSDictionary config = new COSDictionary();
            config.setString(COSName.NAME, "Default");
            config.setItem(COSName.getPDFName("AS"), as);
            config.setItem(COSName.getPDFName("Order"), groups);
            COSDictionary props = new COSDictionary();
            props.setItem(COSName.OCGS, groups);
            props.setItem(COSName.D, config);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.OCPROPERTIES, props);
            COSStream xmp = d.getDocument().createCOSStream();
            try (OutputStream o = xmp.createOutputStream()) {
                o.write(("<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                        + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description "
                        + "rdf:about=\"\" xmlns:acme=\"http://example.com/acme/\" acme:Sheet=\"7\"/></rdf:RDF>"
                        + "</x:xmpmeta><?xpacket end=\"w\"?>").getBytes(StandardCharsets.UTF_8));
            }
            xmp.setItem(COSName.TYPE, COSName.METADATA);
            xmp.setItem(COSName.SUBTYPE, COSName.getPDFName("XML"));
            page.getCOSObject().setItem(COSName.METADATA, xmp);
            out = convert(d, "layers");
        }
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary config = (COSDictionary) ((COSDictionary) d.getDocumentCatalog().getCOSObject()
                    .getDictionaryObject(COSName.OCPROPERTIES)).getDictionaryObject(COSName.D);
            assertNotNull(config.getDictionaryObject(COSName.getPDFName("AS")));
            assertNotNull(d.getPage(0).getCOSObject().getDictionaryObject(COSName.METADATA));
        }
    }

    @Test
    void scriptsAreRemovedWithAnHonestReason() throws Exception {
        Path in = dir.resolve("script.pdf");
        try (PDDocument d = new PDDocument()) {
            FontSamples.page(d, "BT /A 14 Tf 50 780 Td (Script) Tj ET", FontSamples.trueType(d));
            d.getDocumentCatalog().getCOSObject().setItem(COSName.OPEN_ACTION, script());
            d.save(in.toFile());
        }
        Path out = dir.resolve("script-a4.pdf");
        PdfToPdfA.Result r = PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A4));
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("keeps no scripts")), r.warnings().toString());
        assertFalse(r.warnings().stream().anyMatch(w -> w.contains("JavaScript action, which PDF/A does not allow")));
    }

    private static COSDictionary uri(String target) {
        COSDictionary a = new COSDictionary();
        a.setItem(COSName.TYPE, COSName.getPDFName("Action"));
        a.setItem(COSName.S, COSName.URI);
        a.setItem(COSName.URI, new COSString(target));
        return a;
    }

    private static COSDictionary script() {
        COSDictionary a = new COSDictionary();
        a.setItem(COSName.TYPE, COSName.getPDFName("Action"));
        a.setItem(COSName.S, COSName.JAVA_SCRIPT);
        a.setItem(COSName.JS, new COSString("app.alert('hi')"));
        return a;
    }
}
