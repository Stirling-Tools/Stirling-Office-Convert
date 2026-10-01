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
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
}
