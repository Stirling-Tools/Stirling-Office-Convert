package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfA4KeepsAllowedTest {

    private static final COSName HT = COSName.getPDFName("HT");

    private static final COSName DEST = COSName.getPDFName("DestOutputProfile");

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
    void typeOneHalftonesStayAndOthersGo() throws Exception {
        Path out;
        try (PDDocument d = new PDDocument()) {
            PDPage page = FontSamples.page(d, "/GS0 gs /GS1 gs BT /A 14 Tf 50 780 Td (Screens) Tj ET",
                    FontSamples.trueType(d));
            COSDictionary screen = new COSDictionary();
            screen.setInt(COSName.getPDFName("HalftoneType"), 1);
            screen.setInt(COSName.getPDFName("Frequency"), 60);
            screen.setInt(COSName.getPDFName("Angle"), 45);
            screen.setItem(COSName.getPDFName("SpotFunction"), COSName.getPDFName("Round"));
            screen.setString(COSName.getPDFName("HalftoneName"), "Coarse");
            COSDictionary keep = new COSDictionary();
            keep.setItem(COSName.TYPE, COSName.EXT_G_STATE);
            keep.setItem(HT, screen);
            COSDictionary threshold = new COSDictionary();
            threshold.setInt(COSName.getPDFName("HalftoneType"), 6);
            COSDictionary drop = new COSDictionary();
            drop.setItem(COSName.TYPE, COSName.EXT_G_STATE);
            drop.setItem(HT, threshold);
            COSDictionary states = new COSDictionary();
            states.setItem("GS0", keep);
            states.setItem("GS1", drop);
            page.getResources().getCOSObject().setItem(COSName.EXT_G_STATE, states);
            out = convert(d, "halftones");
        }
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary states = (COSDictionary) d.getPage(0).getResources().getCOSObject()
                    .getDictionaryObject(COSName.EXT_G_STATE);
            COSDictionary screen = (COSDictionary) ((COSDictionary) states.getDictionaryObject("GS0"))
                    .getDictionaryObject(HT);
            assertNotNull(screen);
            assertNull(screen.getDictionaryObject(COSName.getPDFName("HalftoneName")));
            assertNull(((COSDictionary) states.getDictionaryObject("GS1")).getDictionaryObject(HT));
        }
    }

    @Test
    void grayOutputIntentIsKept() throws Exception {
        Path out;
        try (PDDocument d = new PDDocument()) {
            FontSamples.page(d, "0 0 1 rg 50 50 100 100 re f 0.5 g 200 50 100 100 re f", FontSamples.trueType(d));
            d.getDocumentCatalog().getCOSObject().setItem(COSName.OUTPUT_INTENTS,
                    intents(intent(d, IccProfiles.gray(), 1, "GTS_PDFA1")));
            out = convert(d, "gray-intent");
        }
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSArray intents = (COSArray) d.getDocumentCatalog().getCOSObject()
                    .getDictionaryObject(COSName.OUTPUT_INTENTS);
            COSStream profile = (COSStream) ((COSDictionary) intents.getObject(0)).getDictionaryObject(DEST);
            assertEquals(1, profile.getInt(COSName.N));
        }
    }

    @Test
    void pdfXIntentSharingTheProfileIsKeptBesideThePdfAOne() throws Exception {
        Path out;
        try (PDDocument d = new PDDocument()) {
            FontSamples.page(d, "0 0 0 1 k 50 50 100 100 re f", FontSamples.trueType(d));
            byte[] printer = IccProfiles.cmyk();
            System.arraycopy("prtr".getBytes(StandardCharsets.US_ASCII), 0, printer, 12, 4);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.OUTPUT_INTENTS,
                    intents(intent(d, printer, 4, "GTS_PDFX")));
            out = convert(d, "pdfx-intent");
        }
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSArray intents = (COSArray) d.getDocumentCatalog().getCOSObject()
                    .getDictionaryObject(COSName.OUTPUT_INTENTS);
            assertEquals(2, intents.size());
            COSDictionary a = (COSDictionary) intents.getObject(0);
            COSDictionary b = (COSDictionary) intents.getObject(1);
            assertEquals("GTS_PDFA1", a.getNameAsString(COSName.S));
            assertEquals("GTS_PDFX", b.getNameAsString(COSName.S));
            assertTrue(a.getDictionaryObject(DEST) == b.getDictionaryObject(DEST));
        }
    }

    @Test
    void validPageIntentMatchingTheDocumentIsKept() throws Exception {
        Path out;
        try (PDDocument d = new PDDocument()) {
            PDPage page = FontSamples.page(d, "0 0 1 rg 50 50 100 100 re f", FontSamples.trueType(d));
            page.getCOSObject().setItem(COSName.OUTPUT_INTENTS, intents(intent(d, IccProfiles.srgb(), 3, "GTS_PDFA1")));
            out = convert(d, "page-intent");
        }
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertNotNull(d.getPage(0).getCOSObject().getDictionaryObject(COSName.OUTPUT_INTENTS));
        }
    }

    @Test
    void longNamesAndDocumentLevelScriptsAndRenditionsAreKept() throws Exception {
        String longName = "Layer" + "x".repeat(200);
        Path out;
        try (PDDocument d = new PDDocument()) {
            PDPage page = FontSamples.page(d, "/" + longName + " gs BT /A 14 Tf 50 780 Td (Names) Tj ET",
                    FontSamples.trueType(d));
            COSDictionary gs = new COSDictionary();
            gs.setItem(COSName.TYPE, COSName.EXT_G_STATE);
            gs.setFloat(COSName.CA_NS, 1f);
            COSDictionary states = new COSDictionary();
            states.setItem(longName, gs);
            page.getResources().getCOSObject().setItem(COSName.EXT_G_STATE, states);
            COSDictionary script = new COSDictionary();
            script.setItem(COSName.S, COSName.JAVA_SCRIPT);
            script.setItem(COSName.JS, new COSString("console.println('open')"));
            COSArray scriptNames = new COSArray();
            scriptNames.add(new COSString("Init"));
            scriptNames.add(script);
            COSDictionary scripts = new COSDictionary();
            scripts.setItem(COSName.NAMES, scriptNames);
            COSDictionary renditions = new COSDictionary();
            renditions.setItem(COSName.NAMES, new COSArray());
            COSDictionary names = new COSDictionary();
            names.setItem(COSName.JAVA_SCRIPT, scripts);
            names.setItem(COSName.getPDFName("Renditions"), renditions);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.NAMES, names);
            out = convert(d, "names");
        }
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary states = (COSDictionary) d.getPage(0).getResources().getCOSObject()
                    .getDictionaryObject(COSName.EXT_G_STATE);
            assertNotNull(states.getDictionaryObject(longName));
            String content = new String(d.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(content.contains("/" + longName + " gs"));
            COSDictionary names = (COSDictionary) d.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.NAMES);
            assertNotNull(names.getDictionaryObject(COSName.JAVA_SCRIPT));
            assertNotNull(names.getDictionaryObject(COSName.getPDFName("Renditions")));
        }
    }

    private static COSArray intents(COSDictionary intent) {
        COSArray a = new COSArray();
        a.add(intent);
        return a;
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
