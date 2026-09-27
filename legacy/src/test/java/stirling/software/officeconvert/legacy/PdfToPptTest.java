package stirling.software.officeconvert.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.util.Matrix;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTable;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.PdfToPptx;

class PdfToPptTest {

    private static final PDType1Font SANS = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    @TempDir static Path dir;
    static HSLFSlideShow show;

    @BeforeAll
    static void convert() throws IOException {
        Path pdf = dir.resolve("deck.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage first = new PDPage(new PDRectangle(720, 540));
            doc.addPage(first);
            try (PDPageContentStream cs = new PDPageContentStream(doc, first)) {
                text(cs, BOLD, 40, 60, 460, "Plan for the year");
                float y = 380;
                for (String item : new String[] {"Hire two engineers", "Open the Lisbon office", "Ship version four"}) {
                    text(cs, SANS, 24, 80, y, "•");
                    text(cs, SANS, 24, 110, y, item);
                    y -= 40;
                }
            }
            PDPage second = new PDPage(new PDRectangle(720, 540));
            doc.addPage(second);
            try (PDPageContentStream cs = new PDPageContentStream(doc, second)) {
                text(cs, SANS, 20, 60, 460, "A plain line and a ");
                text(cs, BOLD, 20, 238, 460, "bold ending");
            }
            PDPage third = new PDPage(new PDRectangle(720, 540));
            doc.addPage(third);
            try (PDPageContentStream cs = new PDPageContentStream(doc, third)) {
                float x = 60;
                for (String label : new String[] {"North", "South", "East", "West"}) {
                    text(cs, SANS, 20, x, 300, label);
                    x += 150;
                }
                cs.beginText();
                cs.setFont(SANS, 20);
                cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(90), 680, 100));
                cs.showText("Sideways note");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
        Path ppt = dir.resolve("deck.ppt");
        PdfToPpt.convert(pdf, ppt, PdfToPptx.Options.defaults());
        try (InputStream in = Files.newInputStream(ppt)) {
            show = new HSLFSlideShow(in);
        }
    }

    private static void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    private static List<HSLFTextParagraph> paragraphs(HSLFSlide slide) {
        List<HSLFTextParagraph> out = new ArrayList<>();
        for (HSLFShape s : slide.getShapes()) {
            if (s instanceof HSLFTextShape t && !(s instanceof HSLFTable)) {
                out.addAll(t.getTextParagraphs());
            }
        }
        return out;
    }

    @Test
    void oneSlidePerPageAtThePageSize() {
        assertEquals(3, show.getSlides().size());
        assertEquals(720, show.getPageSize().width);
        assertEquals(540, show.getPageSize().height);
    }

    @Test
    void bulletsAreRealBullets() {
        int bullets = 0;
        for (HSLFTextParagraph p : paragraphs(show.getSlides().getFirst())) {
            String text = HSLFTextParagraph.getRawText(List.of(p));
            if (p.isBullet()) {
                bullets++;
                assertFalse(text.contains("•"), "the bullet is not text");
            }
        }
        assertEquals(3, bullets);
    }

    @Test
    void boldStaysOnItsOwnWords() {
        boolean plainSeen = false;
        boolean boldSeen = false;
        for (HSLFTextParagraph p : paragraphs(show.getSlides().get(1))) {
            for (HSLFTextRun r : p.getTextRuns()) {
                if (r.getRawText().contains("plain")) {
                    assertFalse(r.isBold());
                    plainSeen = true;
                }
                if (r.getRawText().contains("bold")) {
                    assertTrue(r.isBold());
                    boldSeen = true;
                }
            }
        }
        assertTrue(plainSeen && boldSeen);
    }

    @Test
    void tabStopsLeaveTheFirstLineWhereItWas() {
        boolean seen = false;
        for (HSLFTextParagraph p : paragraphs(show.getSlides().get(2))) {
            if (HSLFTextParagraph.getRawText(List.of(p)).contains("North")) {
                seen = true;
                assertFalse(p.getTabStops().isEmpty(), "the labels are set at tab stops");
                assertEquals(0.0, p.getIndent(), 0.01, "no hanging indent from the ruler POI adds for tab stops");
            }
        }
        assertTrue(seen);
    }

    @Test
    void aSidewaysBoxStoresItsTurnedAnchor() {
        for (HSLFShape s : show.getSlides().get(2).getShapes()) {
            if (s instanceof HSLFTextShape t && t.getText().contains("Sideways")) {
                assertEquals(270, t.getRotation(), 0.01);
                assertTrue(t.getAnchor().getHeight() > t.getAnchor().getWidth(), "the format keeps a sideways box turned");
                return;
            }
        }
        fail("no sideways text box");
    }
}
