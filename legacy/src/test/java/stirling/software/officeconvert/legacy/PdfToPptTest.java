package stirling.software.officeconvert.legacy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.apache.poi.ddf.AbstractEscherOptRecord;
import org.apache.poi.ddf.EscherArrayProperty;
import org.apache.poi.ddf.EscherPropertyTypes;
import org.apache.poi.hslf.usermodel.HSLFFreeformShape;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTable;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.util.LittleEndian;
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

    @Test
    void aTranslucentPolygonIsAFreeform() throws IOException {
        Path pdf = dir.resolve("facet.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(720, 540));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                gs.setNonStrokingAlphaConstant(0.5f);
                cs.setGraphicsStateParameters(gs);
                cs.setNonStrokingColor(0.25f, 0.5f, 0.75f);
                cs.moveTo(500, 540);
                cs.lineTo(720, 540);
                cs.lineTo(720, 0);
                cs.lineTo(600, 0);
                cs.closePath();
                cs.fill();
            }
            doc.save(pdf.toFile());
        }
        Path ppt = dir.resolve("facet.ppt");
        PdfToPpt.convert(pdf, ppt, PdfToPptx.Options.defaults());
        try (InputStream in = Files.newInputStream(ppt); HSLFSlideShow facet = new HSLFSlideShow(in)) {
            List<HSLFShape> shapes = facet.getSlides().get(0).getShapes();
            assertEquals(1, shapes.size());
            HSLFFreeformShape f = assertInstanceOf(HSLFFreeformShape.class, shapes.get(0));
            assertEquals(0x4080BF, f.getFillColor().getRGB() & 0xFFFFFF);
            assertEquals(0.5, f.getEscherProperty(EscherPropertyTypes.FILL__FILLOPACITY) / 65536.0, 0.01);
            assertEquals(500, f.getAnchor().getX(), 0.5);
            assertEquals(220, f.getAnchor().getWidth(), 0.5);
        }
    }

    @Test
    void eachPartOfAFreeformClosesOnItself() throws IOException {
        Path pdf = dir.resolve("pair.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(720, 540));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                gs.setNonStrokingAlphaConstant(0.5f);
                cs.setGraphicsStateParameters(gs);
                cs.setNonStrokingColor(0.25f, 0.5f, 0.75f);
                cs.moveTo(100, 400);
                cs.lineTo(200, 400);
                cs.lineTo(150, 300);
                cs.closePath();
                cs.moveTo(500, 200);
                cs.lineTo(600, 200);
                cs.lineTo(550, 100);
                cs.closePath();
                cs.fill();
            }
            doc.save(pdf.toFile());
        }
        Path ppt = dir.resolve("pair.ppt");
        PdfToPpt.convert(pdf, ppt, PdfToPptx.Options.defaults());
        try (InputStream in = Files.newInputStream(ppt); HSLFSlideShow pair = new HSLFSlideShow(in)) {
            List<HSLFShape> shapes = pair.getSlides().get(0).getShapes();
            assertEquals(1, shapes.size());
            HSLFFreeformShape f = assertInstanceOf(HSLFFreeformShape.class, shapes.get(0));
            AbstractEscherOptRecord opt = f.getEscherOptRecord();
            EscherArrayProperty vertices = opt.lookup(EscherPropertyTypes.GEOMETRY__VERTICES);
            List<Integer> segments = new ArrayList<>();
            for (byte[] e : (EscherArrayProperty) opt.lookup(EscherPropertyTypes.GEOMETRY__SEGMENTINFO)) {
                segments.add(LittleEndian.getUShort(e, 0));
            }
            int n = 0;
            int start = 0;
            int last = -1;
            int closes = 0;
            for (int i = 0; i < segments.size(); i++) {
                int t = segments.get(i);
                if (t == 0x4000) {
                    start = n++;
                } else if (t == 0xAC00 && (i + 1 == segments.size() || segments.get(i + 1) != 0x6001)) {
                    last = n++;
                } else if (t == 0xAD00) {
                    n += 3;
                } else if (t == 0x6001) {
                    assertArrayEquals(vertices.getElement(start), vertices.getElement(last), "part " + closes + " closes on its own start");
                    closes++;
                }
            }
            assertEquals(2, closes);
        }
    }
}
