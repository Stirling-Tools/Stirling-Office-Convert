package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfA4ReviewAdversarialTest {

    private static final COSName HT = COSName.getPDFName("HT");

    private static final COSName HALFTONE_TYPE = COSName.getPDFName("HalftoneType");

    private static final COSName TRANSFER_FUNCTION = COSName.getPDFName("TransferFunction");

    private static final COSName DEST = COSName.getPDFName("DestOutputProfile");

    private static final COSName DEST_REF = COSName.getPDFName("DestOutputProfileRef");

    @TempDir
    Path dir;

    private Path convert(PDDocument d, String name, PdfALevel level) throws Exception {
        Path in = dir.resolve(name + ".pdf");
        d.save(in.toFile());
        Path out = dir.resolve(name + "-" + level + ".pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        return out;
    }

    private static COSDictionary screen() {
        COSDictionary s = new COSDictionary();
        s.setInt(HALFTONE_TYPE, 1);
        s.setInt(COSName.getPDFName("Frequency"), 60);
        s.setInt(COSName.getPDFName("Angle"), 45);
        s.setItem(COSName.getPDFName("SpotFunction"), COSName.getPDFName("Round"));
        return s;
    }

    private static PDPage halftonePage(PDDocument d, COSDictionary halftone) throws Exception {
        PDPage page = FontSamples.page(d, "/GS0 gs BT /A 14 Tf 50 780 Td (Screens) Tj ET", FontSamples.trueType(d));
        COSDictionary gs = new COSDictionary();
        gs.setItem(COSName.TYPE, COSName.EXT_G_STATE);
        gs.setItem(HT, halftone);
        COSDictionary states = new COSDictionary();
        states.setItem("GS0", gs);
        page.getResources().getCOSObject().setItem(COSName.EXT_G_STATE, states);
        return page;
    }

    private static COSDictionary keptHalftone(Path out) throws Exception {
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary states = (COSDictionary) d.getPage(0).getResources().getCOSObject()
                    .getDictionaryObject(COSName.EXT_G_STATE);
            COSDictionary ht = (COSDictionary) ((COSDictionary) states.getDictionaryObject("GS0"))
                    .getDictionaryObject(HT);
            return ht == null ? null : new COSDictionary(ht);
        }
    }

    @Test
    void typeFiveHalftoneWithAnRgbComponentAndNoTransferFunctionIsCompliant() throws Exception {
        Path out;
        try (PDDocument d = new PDDocument()) {
            COSDictionary five = new COSDictionary();
            five.setInt(HALFTONE_TYPE, 5);
            five.setItem(COSName.getPDFName("Red"), screen());
            five.setItem(COSName.getPDFName("Default"), screen());
            halftonePage(d, five);
            out = convert(d, "ht5-red", PdfALevel.A4);
        }
        VeraPdf.assertCompliant(out, PdfALevel.A4);
    }

    @Test
    void typeFiveHalftoneWithProcessSpotAndDefaultComponentsIsKept() throws Exception {
        Path out;
        try (PDDocument d = new PDDocument()) {
            COSDictionary five = new COSDictionary();
            five.setInt(HALFTONE_TYPE, 5);
            five.setString(COSName.getPDFName("HalftoneName"), "Plate");
            five.setItem(COSName.getPDFName("Cyan"), screen());
            COSDictionary spot = screen();
            spot.setItem(TRANSFER_FUNCTION, COSName.getPDFName("Identity"));
            five.setItem(COSName.getPDFName("PANTONE 185 C"), spot);
            five.setItem(COSName.getPDFName("Default"), screen());
            halftonePage(d, five);
            out = convert(d, "ht5-mixed", PdfALevel.A4);
        }
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        COSDictionary ht = keptHalftone(out);
        assertNotNull(ht);
        assertNull(ht.getDictionaryObject(COSName.getPDFName("HalftoneName")));
        assertEquals(5, ht.getInt(HALFTONE_TYPE));
    }

    @Test
    void typeFiveHalftoneNestingAnotherTypeFiveIsRemoved() throws Exception {
        Path out;
        try (PDDocument d = new PDDocument()) {
            COSDictionary inner = new COSDictionary();
            inner.setInt(HALFTONE_TYPE, 5);
            inner.setItem(COSName.getPDFName("Default"), screen());
            COSDictionary five = new COSDictionary();
            five.setInt(HALFTONE_TYPE, 5);
            five.setItem(COSName.getPDFName("Cyan"), inner);
            five.setItem(COSName.getPDFName("Default"), screen());
            halftonePage(d, five);
            out = convert(d, "ht5-nested", PdfALevel.A4);
        }
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        assertNull(keptHalftone(out));
    }

    @Test
    void outputIntentProfileReferenceIsUntouchedAtPartTwo() throws Exception {
        Path out;
        try (PDDocument d = new PDDocument()) {
            FontSamples.page(d, "0 0 1 rg 50 50 100 100 re f", FontSamples.trueType(d));
            COSDictionary oi = intent(d, IccProfiles.srgb(), 3, "GTS_PDFA1");
            COSDictionary ref = new COSDictionary();
            ref.setString(COSName.getPDFName("ICCVersion"), "4.3");
            oi.setItem(DEST_REF, ref);
            COSArray intents = new COSArray();
            intents.add(oi);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.OUTPUT_INTENTS, intents);
            out = convert(d, "dest-ref", PdfALevel.A2B);
        }
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSArray intents = (COSArray) d.getDocumentCatalog().getCOSObject()
                    .getDictionaryObject(COSName.OUTPUT_INTENTS);
            assertNotNull(((COSDictionary) intents.getObject(0)).getDictionaryObject(DEST_REF),
                    "part 2 output changed: DestOutputProfileRef was stripped");
        }
    }

    @Test
    void outputIntentProfileReferenceIsDroppedAtPartFour() throws Exception {
        Path out;
        try (PDDocument d = new PDDocument()) {
            PDPage page = FontSamples.page(d, "0 0 1 rg 50 50 100 100 re f", FontSamples.trueType(d));
            COSDictionary oi = intent(d, IccProfiles.srgb(), 3, "GTS_PDFX");
            oi.setItem(DEST_REF, new COSDictionary());
            COSArray intents = new COSArray();
            intents.add(oi);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.OUTPUT_INTENTS, intents);
            page.getCOSObject().setItem(COSName.OUTPUT_INTENTS, intents);
            out = convert(d, "dest-ref-a4", PdfALevel.A4);
        }
        VeraPdf.assertCompliant(out, PdfALevel.A4);
    }

    @Test
    void grayIntentWithRgbAndCmykInPagesImagesShadingsGroupsAndAppearances() throws Exception {
        Path out;
        try (PDDocument d = new PDDocument()) {
            PDPage page = FontSamples.page(d, "1 0 0 rg 50 50 100 100 re f 0 0 0 1 k 200 50 100 100 re f"
                    + " q 60 0 0 40 50 300 cm /Im0 Do Q /Sh0 sh BT /A 14 Tf 50 780 Td (Gray) Tj ET",
                    FontSamples.trueType(d));
            COSDictionary xobjects = new COSDictionary();
            xobjects.setItem("Im0", Samples.cmyk(d).getCOSObject());
            page.getResources().getCOSObject().setItem(COSName.XOBJECT, xobjects);
            COSDictionary shading = new COSDictionary();
            shading.setInt(COSName.SHADING_TYPE, 2);
            shading.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
            COSArray coords = new COSArray();
            for (int v : new int[] {0, 0, 500, 0}) {
                coords.add(COSInteger.get(v));
            }
            shading.setItem(COSName.COORDS, coords);
            COSDictionary fn = new COSDictionary();
            fn.setInt(COSName.FUNCTION_TYPE, 2);
            COSArray domain = new COSArray();
            domain.add(COSInteger.ZERO);
            domain.add(COSInteger.ONE);
            fn.setItem(COSName.DOMAIN, domain);
            COSArray c1 = new COSArray();
            c1.add(COSInteger.ONE);
            c1.add(COSInteger.ZERO);
            c1.add(COSInteger.ZERO);
            fn.setItem(COSName.C1, c1);
            fn.setInt(COSName.N, 1);
            shading.setItem(COSName.FUNCTION, fn);
            COSDictionary shadings = new COSDictionary();
            shadings.setItem("Sh0", shading);
            page.getResources().getCOSObject().setItem(COSName.SHADING, shadings);
            COSDictionary group = new COSDictionary();
            group.setItem(COSName.TYPE, COSName.GROUP);
            group.setItem(COSName.S, COSName.TRANSPARENCY);
            group.setItem(COSName.CS, COSName.DEVICERGB);
            page.getCOSObject().setItem(COSName.GROUP, group);
            COSStream ap = d.getDocument().createCOSStream();
            try (OutputStream o = ap.createOutputStream()) {
                o.write("0 1 0 rg 0 0 50 20 re f".getBytes(StandardCharsets.ISO_8859_1));
            }
            ap.setItem(COSName.TYPE, COSName.XOBJECT);
            ap.setItem(COSName.SUBTYPE, COSName.FORM);
            COSArray bbox = new COSArray();
            for (int v : new int[] {0, 0, 50, 20}) {
                bbox.add(COSInteger.get(v));
            }
            ap.setItem(COSName.BBOX, bbox);
            COSDictionary apd = new COSDictionary();
            apd.setItem(COSName.N, ap);
            COSDictionary annot = new COSDictionary();
            annot.setItem(COSName.TYPE, COSName.ANNOT);
            annot.setItem(COSName.SUBTYPE, COSName.getPDFName("Square"));
            annot.setItem(COSName.RECT, bbox);
            annot.setInt(COSName.F, 4);
            annot.setItem(COSName.AP, apd);
            COSArray annots = new COSArray();
            annots.add(annot);
            page.getCOSObject().setItem(COSName.ANNOTS, annots);
            COSArray intents = new COSArray();
            intents.add(intent(d, IccProfiles.gray(), 1, "GTS_PDFA1"));
            d.getDocumentCatalog().getCOSObject().setItem(COSName.OUTPUT_INTENTS, intents);
            out = convert(d, "gray-mixed", PdfALevel.A4);
        }
        VeraPdf.assertCompliant(out, PdfALevel.A4);
    }

    @Test
    void grayPageIntentUnderAGrayDocumentIntentWithRgbContent() throws Exception {
        Path out;
        try (PDDocument d = new PDDocument()) {
            PDPage page = FontSamples.page(d, "1 0 0 rg 50 50 100 100 re f 0 0 0 1 k 200 50 100 100 re f",
                    FontSamples.trueType(d));
            COSArray intents = new COSArray();
            intents.add(intent(d, IccProfiles.gray(), 1, "GTS_PDFA1"));
            d.getDocumentCatalog().getCOSObject().setItem(COSName.OUTPUT_INTENTS, intents);
            COSArray pageIntents = new COSArray();
            pageIntents.add(intent(d, IccProfiles.gray(), 3, "GTS_PDFA1"));
            page.getCOSObject().setItem(COSName.OUTPUT_INTENTS, pageIntents);
            out = convert(d, "gray-page", PdfALevel.A4);
        }
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertNotNull(d.getPage(0).getCOSObject().getDictionaryObject(COSName.OUTPUT_INTENTS));
        }
    }

    @Test
    void pageIntentWithTwoDifferentProfilesIsDropped() throws Exception {
        Path out;
        try (PDDocument d = new PDDocument()) {
            PDPage page = FontSamples.page(d, "1 0 0 rg 50 50 100 100 re f", FontSamples.trueType(d));
            COSArray pageIntents = new COSArray();
            pageIntents.add(intent(d, IccProfiles.srgb(), 3, "GTS_PDFA1"));
            pageIntents.add(intent(d, IccProfiles.srgb(), 3, "GTS_PDFX"));
            page.getCOSObject().setItem(COSName.OUTPUT_INTENTS, pageIntents);
            out = convert(d, "page-two", PdfALevel.A4);
        }
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertNull(d.getPage(0).getCOSObject().getDictionaryObject(COSName.OUTPUT_INTENTS));
        }
    }

    @Test
    void outOfRangeNumbersAtPartFourStayWritable() throws Exception {
        String huge = "1" + "0".repeat(45) + ".5";
        String bigInt = "9".repeat(25);
        Path out;
        try (PDDocument d = new PDDocument()) {
            PDPage page = FontSamples.page(d, "q 1 0 0 1 0 0 cm " + huge + " w " + bigInt + " M 0.0000000000000000000000000000000000000000001 0 0 rg"
                    + " 50 50 100 100 re f Q BT /A 14 Tf 50 780 Td (Numbers) Tj ET", FontSamples.trueType(d));
            COSDictionary gs = new COSDictionary();
            gs.setItem(COSName.TYPE, COSName.EXT_G_STATE);
            gs.setItem(COSName.LW, new COSFloat(3.0e38f));
            page.getResources().getCOSObject().setItem(COSName.EXT_G_STATE, new COSDictionary());
            ((COSDictionary) page.getResources().getCOSObject().getDictionaryObject(COSName.EXT_G_STATE))
                    .setItem("GS0", gs);
            out = convert(d, "numbers", PdfALevel.A4);
        }
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            String content = new String(d.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(content.contains("Infinity") || content.contains("NaN")
                    || java.util.regex.Pattern.compile("[0-9][eE][-+]?[0-9]").matcher(content).find(), content);
        }
    }

    @Test
    void scriptsKeptAtPartFourButForbiddenActionsInTheirChainGo() throws Exception {
        Path out;
        try (PDDocument d = new PDDocument()) {
            PDPage page = FontSamples.page(d, "BT /A 14 Tf 50 780 Td (Scripts) Tj ET", FontSamples.trueType(d));
            COSDictionary launch = new COSDictionary();
            launch.setItem(COSName.S, COSName.getPDFName("Launch"));
            launch.setString(COSName.F, "calc.exe");
            COSDictionary uri = new COSDictionary();
            uri.setItem(COSName.S, COSName.URI);
            uri.setString(COSName.URI, "https://example.com");
            COSArray next = new COSArray();
            next.add(launch);
            next.add(uri);
            COSDictionary js = new COSDictionary();
            js.setItem(COSName.S, COSName.JAVA_SCRIPT);
            js.setItem(COSName.JS, new COSString("app.alert('hi')"));
            js.setItem(COSName.NEXT, next);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.OPEN_ACTION, js);
            COSDictionary close = new COSDictionary();
            close.setItem(COSName.S, COSName.JAVA_SCRIPT);
            close.setItem(COSName.JS, new COSString("app.alert('bye')"));
            COSDictionary catalogAa = new COSDictionary();
            catalogAa.setItem(COSName.getPDFName("WC"), close);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.AA, catalogAa);
            COSDictionary pageAa = new COSDictionary();
            pageAa.setItem(COSName.O, close);
            page.getCOSObject().setItem(COSName.AA, pageAa);
            COSDictionary rich = new COSDictionary();
            rich.setItem(COSName.S, COSName.getPDFName("RichMediaExecute"));
            rich.setItem(COSName.NEXT, launch);
            COSDictionary link = new COSDictionary();
            link.setItem(COSName.TYPE, COSName.ANNOT);
            link.setItem(COSName.SUBTYPE, COSName.LINK);
            COSArray rect = new COSArray();
            for (int v : new int[] {50, 50, 150, 80}) {
                rect.add(COSInteger.get(v));
            }
            link.setItem(COSName.RECT, rect);
            link.setInt(COSName.F, 4);
            link.setItem(COSName.A, rich);
            COSArray annots = new COSArray();
            annots.add(link);
            page.getCOSObject().setItem(COSName.ANNOTS, annots);
            out = convert(d, "scripts", PdfALevel.A4);
        }
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary open = (COSDictionary) d.getDocumentCatalog().getCOSObject()
                    .getDictionaryObject(COSName.OPEN_ACTION);
            assertEquals("JavaScript", open.getNameAsString(COSName.S));
            String all = new String(java.nio.file.Files.readAllBytes(out), StandardCharsets.ISO_8859_1);
            assertFalse(all.contains("calc.exe"));
        }
    }

    @Test
    void customXmpWithClashingPrefixesAndAttributeFormsIsValid() throws Exception {
        String xmp = "<?xpacket begin='﻿' id='W5M0MpCehiHzreSzNTczkc9d'?>"
                + "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                + "<rdf:Description rdf:about='uuid:1' xmlns:pdf='http://example.com/notpdf/'"
                + " xmlns:xmp='http://ns.adobe.com/xap/1.0/' xmp:Rating='3' xmp:CreateDate='2001-01-01'>"
                + "<pdf:Producer>Not the PDF producer</pdf:Producer><pdf:Keywords>other</pdf:Keywords>"
                + "</rdf:Description>"
                + "<rdf:Description rdf:about='uuid:1' xmlns:ex='http://example.com/a/'>"
                + "<ex:one>A</ex:one></rdf:Description>"
                + "<rdf:Description rdf:about='uuid:1' xmlns:ex='http://example.com/b/'>"
                + "<ex:two>B</ex:two><ex:nest><rdf:Description><ex:inner>deep</ex:inner></rdf:Description></ex:nest>"
                + "<ex:lang><rdf:Alt><rdf:li xml:lang='x-default'>hello</rdf:li><rdf:li xml:lang='fr'>salut</rdf:li>"
                + "</rdf:Alt></ex:lang><ex:x>x prefix</ex:x></rdf:Description>"
                + "<rdf:Description rdf:about='' xmlns:x2='adobe:ns:meta/'><x2:thing>meta ns</x2:thing></rdf:Description>"
                + "<rdf:Description rdf:about='' xmlns:dc='http://purl.org/dc/elements/1.1/'>"
                + "<dc:subject><rdf:Bag><rdf:li>tag</rdf:li></rdf:Bag></dc:subject></rdf:Description>"
                + "</rdf:RDF></x:xmpmeta><?xpacket end='w'?>";
        Path out;
        try (PDDocument d = new PDDocument()) {
            FontSamples.page(d, "BT /A 14 Tf 50 780 Td (Metadata) Tj ET", FontSamples.trueType(d));
            d.getDocumentInformation().setKeywords("info keywords");
            PDMetadata metadata = new PDMetadata(d);
            metadata.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
            d.getDocumentCatalog().setMetadata(metadata);
            out = convert(d, "xmp-clash", PdfALevel.A4);
        }
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        String result;
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            result = new String(d.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
        }
        assertTrue(result.contains("Not the PDF producer"), result);
        assertTrue(result.contains(">A<") && result.contains(">B<") && result.contains("deep"), result);
        assertTrue(result.contains("<xmp:Rating>3</xmp:Rating>"), result);
        assertTrue(result.contains("salut") && result.contains("tag"), result);
        org.w3c.dom.Document parsed = XmpCarryOver.parse(result.getBytes(StandardCharsets.UTF_8));
        assertNotNull(parsed);
        assertEquals(1, parsed.getElementsByTagNameNS("http://example.com/a/", "one").getLength());
        assertEquals(1, parsed.getElementsByTagNameNS("http://example.com/b/", "two").getLength());
        assertEquals(1, parsed.getElementsByTagNameNS("http://example.com/notpdf/", "Keywords").getLength());
        assertEquals(1, parsed.getElementsByTagNameNS("http://ns.adobe.com/pdf/1.3/", "Keywords").getLength());
    }

    private static COSDictionary intent(PDDocument d, byte[] profile, int n, String subtype) throws Exception {
        COSStream s = d.getDocument().createCOSStream();
        try (OutputStream o = s.createOutputStream(COSName.FLATE_DECODE)) {
            o.write(profile);
        }
        s.setInt(COSName.N, n);
        COSDictionary oi = new COSDictionary();
        oi.setItem(COSName.TYPE, COSName.getPDFName("OutputIntent"));
        oi.setItem(COSName.S, COSName.getPDFName(subtype));
        oi.setString(COSName.getPDFName("OutputConditionIdentifier"), "Test condition");
        oi.setItem(DEST, s);
        return oi;
    }
}
