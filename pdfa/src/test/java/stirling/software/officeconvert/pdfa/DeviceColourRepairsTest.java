package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DeviceColourRepairsTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @CsvSource({"A1B,false", "A2B,false", "A2U,false", "A3B,false", "A1B,true", "A2B,true",
            "A2U,true", "A3B,true"})
    void shadingPatternsGetAnExplicitIccSpace(PdfALevel level, boolean rgb) throws Exception {
        Path input = dir.resolve("pattern.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            if (rgb) {
                try (ByteArrayInputStream profile = new ByteArrayInputStream(IccProfiles.cmyk())) {
                    doc.getDocumentCatalog().addOutputIntent(new PDOutputIntent(doc, profile));
                }
            }
            COSDictionary shading = new COSDictionary();
            shading.setInt(COSName.SHADING_TYPE, 2);
            shading.setItem(COSName.COLORSPACE, rgb ? COSName.DEVICERGB : COSName.DEVICECMYK);
            shading.setItem(COSName.COORDS, ColourSamples.floats(0, 0, 200, 0));
            COSDictionary function = new COSDictionary();
            function.setInt(COSName.FUNCTION_TYPE, 2);
            function.setItem(COSName.DOMAIN, ColourSamples.floats(0, 1));
            function.setItem(COSName.C0, rgb ? ColourSamples.floats(1, 0, 0) : ColourSamples.floats(0, 1, 0, 0));
            function.setItem(COSName.C1, rgb ? ColourSamples.floats(0, 1, 0) : ColourSamples.floats(1, 0, 0, 0));
            function.setInt(COSName.N, 1);
            shading.setItem(COSName.FUNCTION, function);
            COSDictionary pattern = new COSDictionary();
            pattern.setInt(COSName.PATTERN_TYPE, 2);
            pattern.setItem(COSName.SHADING, shading);
            COSDictionary patterns = new COSDictionary();
            patterns.setItem("P1", pattern);
            PDResources resources = new PDResources();
            resources.getCOSObject().setItem(COSName.PATTERN, patterns);
            page.setResources(resources);
            Samples.raw(page, doc, "/Pattern cs /P1 scn 0 0 200 200 re f");
            doc.save(input.toFile());
        }
        Path output = dir.resolve("pattern-out.pdf");
        PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(output, level);
        double diff = ColourRenderingTest.differing(ColourRenderingTest.render(input),
                ColourRenderingTest.render(output));
        assertTrue(diff < 0.005, "pixels differing: " + diff);
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            COSDictionary patterns = ContentGraph.dict(doc.getPage(0).getResources().getCOSObject()
                    .getDictionaryObject(COSName.PATTERN));
            COSDictionary pattern = ContentGraph.dict(patterns.getDictionaryObject("P1"));
            COSDictionary shading = ContentGraph.dict(pattern.getDictionaryObject(COSName.SHADING));
            COSArray space = ContentGraph.array(shading.getDictionaryObject(COSName.COLORSPACE));
            assertNotNull(space);
            assertEquals(COSName.ICCBASED, space.getObject(0));
        }
    }

    @Test
    void annotationAppearanceWithoutResourcesGetsDefaultCmyk() throws Exception {
        Path input = dir.resolve("appearance.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSStream appearance = doc.getDocument().createCOSStream();
            appearance.setItem(COSName.TYPE, COSName.XOBJECT);
            appearance.setItem(COSName.SUBTYPE, COSName.FORM);
            appearance.setItem(COSName.BBOX, ColourSamples.floats(0, 0, 100, 100));
            try (OutputStream out = appearance.createOutputStream()) {
                out.write("0 1 0 0 k 0 0 100 100 re f".getBytes(StandardCharsets.US_ASCII));
            }
            COSDictionary ap = new COSDictionary();
            ap.setItem(COSName.N, appearance);
            COSDictionary annotation = new COSDictionary();
            annotation.setItem(COSName.TYPE, COSName.ANNOT);
            annotation.setName(COSName.SUBTYPE, "Square");
            annotation.setInt(COSName.F, 4);
            annotation.setItem(COSName.RECT, ColourSamples.floats(100, 100, 200, 200));
            annotation.setItem(COSName.AP, ap);
            COSArray annotations = new COSArray();
            annotations.add(annotation);
            page.getCOSObject().setItem(COSName.ANNOTS, annotations);
            doc.save(input.toFile());
        }
        Path output = dir.resolve("appearance-out.pdf");
        PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults());
        VeraPdf.assertCompliant(output, PdfALevel.A2B);
    }
}
