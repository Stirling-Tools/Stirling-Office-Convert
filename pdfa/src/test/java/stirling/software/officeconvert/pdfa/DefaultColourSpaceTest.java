package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class DefaultColourSpaceTest {

    private static final COSName DEFAULT_CMYK = COSName.getPDFName("DefaultCMYK");

    @TempDir
    Path dir;

    private Path page(String content) throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage p = Samples.page(d);
            PDStream s = new PDStream(d);
            try (OutputStream o = s.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(content.getBytes(StandardCharsets.US_ASCII));
            }
            p.setContents(s);
            d.save(in.toFile());
        }
        return in;
    }

    private static COSDictionary colourSpaces(PDDocument d) {
        COSDictionary res = d.getPage(0).getCOSObject().getCOSDictionary(COSName.RESOURCES);
        return res == null ? null : res.getCOSDictionary(COSName.COLORSPACE);
    }

    @ParameterizedTest
    @CsvSource({"0 0 1 rg 10 10 50 50 re f,false", "0 0 0 1 k 10 10 50 50 re f,true",
            "/DeviceCMYK cs 0 1 0 0 sc 10 10 50 50 re f,true",
            "BI /W 1 /H 1 /CS /CMYK /BPC 8 ID \u0000\u0000\u0000\u00ff EI,true"})
    void cmykGetsAProfileOnlyWhenThePageUsesIt(String content, boolean cmyk) throws Exception {
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(page(content), out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary cs = colourSpaces(d);
            assertEquals(cmyk, cs != null && cs.containsKey(DEFAULT_CMYK));
        }
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
    }

    @Test
    void metadataHasNoPaddingAndTheProfileIsSmall() throws Exception {
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(page("0 0 1 rg 10 10 50 50 re f"), out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        try (PDDocument d = Loader.loadPDF(out.toFile());
                InputStream x = d.getDocumentCatalog().getMetadata().createInputStream()) {
            String xmp = new String(x.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(xmp.contains("          "));
            assertTrue(xmp.contains("<pdfaid:part>1</pdfaid:part>"));
            assertNull(colourSpaces(d));
        }
        assertTrue(IccProfiles.srgb().length < 1200);
        assertNotNull(IccProfiles.header(IccProfiles.srgb()));
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
    }

    private static COSArray numbers(double... v) {
        COSArray a = new COSArray();
        for (double x : v) {
            a.add(new COSFloat((float) x));
        }
        return a;
    }

    private static COSArray names(String... v) {
        COSArray a = new COSArray();
        for (String x : v) {
            a.add(COSName.getPDFName(x));
        }
        return a;
    }

    private Path deviceNShading() throws Exception {
        Path in = dir.resolve("shading.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage p = Samples.page(d);
            COSStream tint = d.getDocument().createCOSStream();
            try (OutputStream o = tint.createOutputStream()) {
                o.write("{ 0 4 1 roll }".getBytes(StandardCharsets.US_ASCII));
            }
            tint.setInt(COSName.FUNCTION_TYPE, 4);
            tint.setItem(COSName.DOMAIN, numbers(0, 1, 0, 1, 0, 1));
            tint.setItem(COSName.RANGE, numbers(0, 1, 0, 1, 0, 1, 0, 1));
            COSDictionary process = new COSDictionary();
            process.setItem(COSName.COLORSPACE, COSName.DEVICECMYK);
            process.setItem(COSName.getPDFName("Components"), names("Cyan", "Magenta", "Yellow", "Black"));
            COSDictionary attributes = new COSDictionary();
            attributes.setItem(COSName.SUBTYPE, COSName.getPDFName("NChannel"));
            attributes.setItem(COSName.getPDFName("Process"), process);
            COSArray space = new COSArray();
            space.add(COSName.DEVICEN);
            space.add(names("Magenta", "Yellow", "Black"));
            space.add(COSName.DEVICECMYK);
            space.add(tint);
            space.add(attributes);
            COSDictionary ramp = new COSDictionary();
            ramp.setInt(COSName.FUNCTION_TYPE, 2);
            ramp.setItem(COSName.DOMAIN, numbers(0, 1));
            ramp.setItem(COSName.C0, numbers(0, 0, 0));
            ramp.setItem(COSName.C1, numbers(1, 0.5, 0.2));
            ramp.setItem(COSName.N, COSInteger.ONE);
            COSDictionary shading = new COSDictionary();
            shading.setInt(COSName.SHADING_TYPE, 2);
            shading.setItem(COSName.COLORSPACE, space);
            shading.setItem(COSName.COORDS, numbers(0, 0, 500, 0));
            shading.setItem(COSName.FUNCTION, ramp);
            COSDictionary shadings = new COSDictionary();
            shadings.setItem(COSName.getPDFName("Sh0"), shading);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.SHADING, shadings);
            p.getCOSObject().setItem(COSName.RESOURCES, res);
            PDStream s = new PDStream(d);
            try (OutputStream o = s.createOutputStream(COSName.FLATE_DECODE)) {
                o.write("0 0 0 1 k 10 10 50 50 re f q 0 0 500 500 re W n /Sh0 sh Q".getBytes(StandardCharsets.US_ASCII));
            }
            p.setContents(s);
            d.save(in.toFile());
        }
        return in;
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B", "A4"})
    void deviceAlternatesOfShadingsGetTheProfileOfTheDefaultSpace(PdfALevel level) throws Exception {
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(deviceNShading(), out, PdfToPdfA.Options.defaults().level(level));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary res = d.getPage(0).getCOSObject().getCOSDictionary(COSName.RESOURCES);
            COSDictionary shading = res.getCOSDictionary(COSName.SHADING).getCOSDictionary(COSName.getPDFName("Sh0"));
            COSArray space = (COSArray) shading.getDictionaryObject(COSName.COLORSPACE);
            COSBase alternate = space.getObject(2);
            COSBase defaultCmyk = colourSpaces(d).getDictionaryObject(DEFAULT_CMYK);
            assertTrue(alternate instanceof COSArray a && COSName.ICCBASED.equals(a.getObject(0)));
            assertEquals(((COSArray) defaultCmyk).getObject(1), ((COSArray) alternate).getObject(1));
            COSDictionary process = ((COSDictionary) space.getObject(4)).getCOSDictionary(COSName.getPDFName("Process"));
            assertEquals(((COSArray) alternate).getObject(1),
                    ((COSArray) process.getDictionaryObject(COSName.COLORSPACE)).getObject(1));
        }
        VeraPdf.assertCompliant(out, level);
    }
}
