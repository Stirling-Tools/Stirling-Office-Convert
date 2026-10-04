package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.TreeSet;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuleRegressionTest {

    @TempDir
    Path dir;

    @Test
    void untypedGraphicsStatesHaveTheirIntentAndBlendModeRepaired() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            COSDictionary state = new COSDictionary();
            state.setName(COSName.RI, "Bogus");
            state.setName(COSName.BM, "Bogus");
            page(doc, state);
            StreamFixer.run(doc, PdfALevel.A2B, new Report(), b -> {});
            assertEquals("RelativeColorimetric", state.getNameAsString(COSName.RI));
            assertEquals("Normal", state.getNameAsString(COSName.BM));
        }
    }

    @Test
    void strokeOnlyOpacityIsFlattenedAndRemovedForPartOne() throws Exception {
        Path input = dir.resolve("stroke.pdf");
        try (PDDocument doc = new PDDocument()) {
            COSDictionary state = new COSDictionary();
            state.setFloat(COSName.CA, 0.5f);
            PDPage page = page(doc, state);
            Samples.raw(page, doc, "/G1 gs 20 w 100 100 m 200 200 l S");
            doc.save(input.toFile());
        }
        Path output = dir.resolve("stroke-out.pdf");
        PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        VeraPdf.assertCompliant(output, PdfALevel.A1B);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 20_000, 50_000})
    void invalidInheritedCropBoxesGetALocalValidReplacement(int size) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSDictionary parent = ContentGraph.dict(page.getCOSObject().getDictionaryObject(COSName.PARENT));
            parent.setItem(COSName.CROP_BOX, ColourSamples.floats(0, 0, size, size));
            page.getCOSObject().setItem(COSName.BLEED_BOX, ColourSamples.floats(0, 0, 16_000, 16_000));
            PageSize.run(doc, PdfALevel.A2B, new Report());
            assertTrue(page.getCOSObject().containsKey(COSName.CROP_BOX));
            assertEquals(page.getMediaBox().getWidth(), page.getCropBox().getWidth());
            assertFalse(page.getCOSObject().containsKey(COSName.BLEED_BOX));
        }
    }

    @Test
    void anUnreadableUsedFontCannotProduceAClaimedPdfA() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            COSDictionary font = new COSDictionary();
            font.setItem(COSName.TYPE, COSName.FONT);
            font.setItem(COSName.SUBTYPE, COSName.TYPE0);
            FontUsage usage = new FontUsage();
            usage.codes().put(font, new TreeSet<>(java.util.Set.of(65)));
            IOException error = assertThrows(IOException.class,
                    () -> FontFixer.run(doc, usage, PdfALevel.A2B, () -> null, new Report()));
            assertTrue(error.getMessage().contains("could not be read for PDF/A"));
        }
    }

    private static PDPage page(PDDocument doc, COSDictionary state) {
        PDPage page = new PDPage();
        doc.addPage(page);
        COSDictionary states = new COSDictionary();
        states.setItem("G1", state);
        PDResources resources = new PDResources();
        resources.getCOSObject().setItem(COSName.EXT_G_STATE, states);
        page.setResources(resources);
        return page;
    }
}
