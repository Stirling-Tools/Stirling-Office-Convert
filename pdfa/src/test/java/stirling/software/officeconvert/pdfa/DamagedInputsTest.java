package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DamagedInputsTest {

    @TempDir
    Path dir;

    @Test
    void aDefaultAppearanceNamingAMissingFontKeepsItsSizeAndColour() {
        assertEquals("/Helv 12 Tf 0 g", FormAppearances.withStandardFont("/F1 12 Tf 0 g"));
        assertEquals("/Helv 12.00000 Tf 0.1 0.2 0.3 rg", FormAppearances.withStandardFont("/ 12.00000 Tf 0.1 0.2 0.3 rg"));
        assertEquals("/Helv 0 Tf 0 g", FormAppearances.withStandardFont(null));
        assertEquals("/Helv 0 Tf 1 0 0 rg", FormAppearances.withStandardFont("1 0 0 rg"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/F1 12 Tf 0 g", "/ 12 Tf 0 g", ""})
    void aTextFieldWhoseAppearanceCannotBeBuiltAsWrittenStillGetsOne(String da) throws Exception {
        Path in = dir.resolve("form.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage p = Samples.page(d);
            PDAcroForm form = new PDAcroForm(d);
            d.getDocumentCatalog().setAcroForm(form);
            form.setDefaultResources(new PDResources());
            form.setNeedAppearances(true);
            PDTextField t = new PDTextField(form);
            t.setPartialName("group");
            form.getFields().add(t);
            PDAnnotationWidget w = t.getWidgets().get(0);
            w.setRectangle(new PDRectangle(50, 700, 250, 24));
            w.setPage(p);
            p.getAnnotations().add(w);
            t.getCOSObject().setString(COSName.DA, da);
            t.getCOSObject().setString(COSName.V, "Hello");
            d.save(in.toFile());
        }
        Path out = dir.resolve("form-2b.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDAnnotationWidget w = (PDAnnotationWidget) d.getPage(0).getAnnotations().get(0);
            assertNotNull(w.getNormalAppearanceStream());
        }
    }

    @Test
    void textInACompositeFontWithoutADescendantIsDroppedAndTheRestKept() throws Exception {
        Path in = dir.resolve("type0.pdf");
        try (PDDocument d = new PDDocument()) {
            PDFont good = FontSamples.trueType(d);
            PDType0Font broken = PDType0Font.load(d, Samples.liberation(), false);
            broken.getCOSObject().setItem(COSName.DESCENDANT_FONTS, new COSArray());
            FontSamples.page(d, "BT /A 12 Tf 72 700 Td (Kept) Tj /B 12 Tf 0 -20 Td <0041> Tj ET", good,
                    broken);
            d.save(in.toFile());
        }
        Path out = dir.resolve("type0-2b.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
        assertEquals("Kept", Converted.text(out).strip());
    }

    @Test
    void aFontProgramThatCannotBeDecodedIsReplacedByAStandIn() throws Exception {
        Path in = dir.resolve("a85.pdf");
        try (PDDocument d = new PDDocument()) {
            PDFont font = FontSamples.trueType(d);
            COSDictionary fd = (COSDictionary) font.getCOSObject().getDictionaryObject(COSName.FONT_DESC);
            COSStream bad = d.getDocument().createCOSStream();
            try (OutputStream o = bad.createRawOutputStream()) {
                o.write("abc\u0001\u0002{}xyz~>".getBytes(StandardCharsets.ISO_8859_1));
            }
            bad.setItem(COSName.FILTER, COSName.ASCII85_DECODE);
            fd.setItem(COSName.FONT_FILE2, bad);
            FontSamples.page(d, "BT /A 12 Tf 72 700 Td (Readable) Tj ET", font);
            d.save(in.toFile());
        }
        Path out = dir.resolve("a85-2b.pdf");
        PdfToPdfA.Result r = PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
        assertEquals("Readable", Converted.text(out).strip());
        assertTrue(!r.substitutedFonts().isEmpty(), r.toString());
    }
}
