package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTPageMargins;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTPageSetup;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorksheet;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STOrientation;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.testing.TestFonts;

class PrintGeometryTest {

    @TempDir
    Path dir;

    @Test
    void hintedAdvancesAreWholePixelsFromInstalledFontsOnly() {
        FontLibrary fonts = FontLibrary.of(List.of());
        FontFace face = fonts.lastResort(false, false);
        for (int ppem : new int[] {9, 15, 92}) {
            int hinted = face.hintedAdvance('0', ppem);
            double linear = face.advance('0') * (double) ppem / face.unitsPerEm();
            assertTrue(hinted > 0 && Math.abs(hinted - linear) <= 1, ppem + ": " + hinted + " vs " + linear);
        }
        assertEquals(-1, face.hintedAdvance('0', 0));
        FontFace embedded = fonts.withFonts(List.of(TestFonts.renamed("Embedded Face"))).exact("Embedded Face",
                false, false);
        assertEquals(-1, embedded.hintedAdvance('0', 15));
    }

    @Test
    void screenWidthsScaleHintedAdvancesLikeExcelsAutofit() {
        FontLibrary fonts = FontLibrary.of(List.of());
        Typesetter t = new Typesetter(fonts);
        FontFace face = fonts.lastResort(false, false);
        FontSpec regular = new FontSpec(face.family(), 11, false, false, null, false, Color.BLACK, null);
        String text = "Wrap 1234";
        int sum = 0;
        for (char c : text.toCharArray()) {
            sum += face.hintedAdvance(c, 15);
        }
        assertEquals(sum * Typesetter.SCREEN_REGULAR, t.screenWidth(text, regular, 15), 1e-9);
        assertTrue(Typesetter.screenFactor(true, 9) > Typesetter.screenFactor(true, 10));
        assertTrue(Typesetter.screenFactor(false, 13) > Typesetter.screenFactor(true, 13));
    }

    @Test
    void printerColumnWidthsRoundToWholeDevicePixels() {
        FontMeasure calibri = FontMeasure.of(FontLibrary.of(List.of()), "Calibri", false, false);
        PrintMetrics m = new PrintMetrics(calibri, 11);
        assertEquals(47, m.printerDigit());
        assertEquals(477 * PrintMetrics.PX, m.columnPoints(10.140625), 1e-9);
        assertEquals(430 * PrintMetrics.PX, m.columnPoints(9.140625), 1e-9);
        assertEquals(44, m.printerDigit(0.95));
    }

    @Test
    void scaledRowsAndColumnsSnapToDevicePixels() {
        assertEquals(12.34, Paginator.quantize(12.34, 1), 1e-12);
        assertEquals(121 * PrintMetrics.PX / 0.95, Paginator.quantize(127 * PrintMetrics.PX, 0.95), 1e-12);
        assertEquals(374 * PrintMetrics.PX / 0.87, Paginator.quantize(430 * PrintMetrics.PX, 0.87), 1e-12);
        assertEquals(8 * PrintMetrics.PX / 0.95, BorderPainter.deviceWidth(0.96, 0.95), 1e-12);
        assertEquals(4 * PrintMetrics.PX / 0.46, BorderPainter.deviceWidth(0.96, 0.46), 1e-12);
    }

    @Test
    void centredLetterPagesAreCentredOnTheA4SheetTheyPrintOn() {
        PageSetup wide = PageSetup.of(letter(true, false));
        assertTrue(wide.resized());
        double w = 500;
        AffineTransform t = wide.resize();
        double left = t.getScaleX() * wide.centeredLeft(w) + t.getTranslateX();
        double centre = left + w * PageSetup.LETTER_ON_A4 / 2;
        double expected = (36 + wide.bandOffsetX() + wide.output().width() - 36 - PrintMetrics.ORIGIN) / 2;
        assertEquals(expected, centre, 0.01);
        assertTrue(wide.bandOffsetX() > 30 && wide.bandOffsetY() == 0);
        PageSetup tall = PageSetup.of(letter(false, false));
        t = tall.resize();
        left = t.getScaleX() * tall.centeredLeft(w) + t.getTranslateX();
        assertEquals((tall.output().width() - PrintMetrics.ORIGIN) / 2, left + w * PageSetup.LETTER_ON_A4 / 2, 0.01);
        assertTrue(tall.bandOffsetY() > 30 && tall.bandOffsetX() == 0);
        PageSetup fitted = PageSetup.of(letter(false, true));
        assertFalse(fitted.resized());
        assertEquals(PageSetup.DEFAULT_PAPER.width(), fitted.paper().width(), 0.01);
    }

    @Test
    void lettersFootersSitOnTheA4PageAndGridlinesGetAThinFrame() throws Exception {
        byte[] xlsx = XlsxTesting.workbook(wb -> {
            XSSFSheet s = wb.createSheet("Frame");
            for (int i = 0; i < 3; i++) {
                s.createRow(i).createCell(0).setCellValue("cell " + i);
            }
            s.setPrintGridlines(true);
            s.getFooter().setCenter("Footer text");
            s.getCTWorksheet().addNewPageSetup().setId("rId9");
        });
        XlsxTesting.convert(dir, "frame.xlsx", xlsx);
        try (PDDocument doc = Loader.loadPDF(dir.resolve("frame.xlsx.pdf").toFile())) {
            assertEquals(841.89f, doc.getPage(0).getMediaBox().getHeight(), 1f);
            List<TextPosition> footer = new ArrayList<>();
            PDFTextStripper strip = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    if (text.contains("Footer")) {
                        footer.addAll(positions);
                    }
                }
            };
            strip.getText(doc);
            assertFalse(footer.isEmpty());
            float baseline = footer.get(0).getYDirAdj();
            assertTrue(baseline > 841.89f - 0.3f * 72 - 8 && baseline < 841.89f - 0.3f * 72, "footer at " + baseline);
            float centre = (footer.get(0).getXDirAdj() + footer.get(footer.size() - 1).getXDirAdj()
                    + footer.get(footer.size() - 1).getWidthDirAdj()) / 2;
            assertEquals(595.28f / 2, centre, 3f);
            double thinWidth = BorderPainter.deviceWidth(Headings.THIN, PageSetup.LETTER_ON_A4);
            int thin = 0;
            List<float[]> path = new ArrayList<>();
            List<Object> tokens = new PDFStreamParser(doc.getPage(0)).parse();
            for (int i = 0; i < tokens.size(); i++) {
                if (!(tokens.get(i) instanceof Operator op)) {
                    continue;
                }
                String name = op.getName();
                if ((name.equals("m") || name.equals("l")) && tokens.get(i - 2) instanceof COSNumber x
                        && tokens.get(i - 1) instanceof COSNumber y) {
                    path.add(new float[] {x.floatValue(), y.floatValue()});
                } else if (name.equals("f") || name.equals("f*")) {
                    if (!path.isEmpty() && Math.abs(minSide(path) - thinWidth) < 0.01) {
                        thin++;
                    }
                    path.clear();
                } else if (!name.equals("h")) {
                    path.clear();
                }
            }
            assertTrue(thin >= 4, "thin frame lines: " + thin);
        }
    }

    private static double minSide(List<float[]> points) {
        float x0 = Float.MAX_VALUE;
        float y0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE;
        float y1 = -Float.MAX_VALUE;
        for (float[] p : points) {
            x0 = Math.min(x0, p[0]);
            y0 = Math.min(y0, p[1]);
            x1 = Math.max(x1, p[0]);
            y1 = Math.max(y1, p[1]);
        }
        return Math.min(x1 - x0, y1 - y0);
    }

    private static CTWorksheet letter(boolean landscape, boolean fit) {
        CTWorksheet ws = CTWorksheet.Factory.newInstance();
        CTPageSetup ps = ws.addNewPageSetup();
        ps.setId("rId1");
        if (landscape) {
            ps.setOrientation(STOrientation.LANDSCAPE);
        }
        if (fit) {
            ws.addNewSheetPr().addNewPageSetUpPr().setFitToPage(true);
        }
        ws.addNewPrintOptions().setHorizontalCentered(true);
        CTPageMargins m = ws.addNewPageMargins();
        m.setLeft(0.5);
        m.setRight(0.5);
        m.setTop(1);
        m.setBottom(1);
        m.setHeader(0.3);
        m.setFooter(0.3);
        return ws;
    }
}
