package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFreeText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.junit.jupiter.api.Test;

class TwoPassWidgetsTest {

    @Test
    void completingAGlyphOnlyPageKeepsTheNotesOfAOnePassRead() throws IOException {
        byte[] pdf = noteOverItsOwnText();
        List<PageData.Widget> onePass = new ArrayList<>();
        List<PageData.Widget> twoPass = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(pdf)) {
            new PageReader(d).read(0, 0, true, p -> onePass.addAll(p.widgets()));
        }
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PageReader reader = new PageReader(d);
            List<PageData> glyphsOnly = new ArrayList<>();
            reader.read(0, 0, false, glyphsOnly::add);
            twoPass.addAll(reader.complete(glyphsOnly.getFirst()).widgets());
        }
        assertEquals(1, onePass.size());
        assertEquals(onePass, twoPass);
    }

    private static byte[] noteOverItsOwnText() throws IOException {
        try (PDDocument d = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            page.setResources(resources);
            page.setContents(stream(d, "BT /F1 12 Tf 72 72 Td (Body) Tj ET"));
            d.addPage(page);
            PDAnnotationFreeText note = new PDAnnotationFreeText();
            note.setRectangle(new PDRectangle(72, 700, 300, 30));
            PDAppearanceStream appearance = new PDAppearanceStream(
                    stream(d, "0 0 1 RG 0.5 0.5 299 29 re S BT /F1 12 Tf 0 g 4 10 Td (Note text) Tj ET").getCOSObject());
            appearance.setBBox(new PDRectangle(300, 30));
            appearance.setResources(resources);
            PDAppearanceDictionary ap = new PDAppearanceDictionary();
            ap.setNormalAppearance(appearance);
            note.setAppearance(ap);
            page.getCOSObject().setItem(COSName.ANNOTS, new COSArray(List.of(note)));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            d.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static PDStream stream(PDDocument d, String content) throws IOException {
        PDStream s = new PDStream(d);
        try (OutputStream out = s.createOutputStream()) {
            out.write(content.getBytes(StandardCharsets.ISO_8859_1));
        }
        return s;
    }
}
