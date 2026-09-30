package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultFontAndHeadingsTest {

    @TempDir
    Path dir;

    private static String styles(String font0, String alignment) {
        return "<fonts count=\"1\"><font>" + font0 + "</font></fonts><fills count=\"1\"><fill><patternFill "
                + "patternType=\"none\"/></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs "
                + "count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"2\"><xf/><xf>" + alignment + "</xf></cellXfs>";
    }

    @Test
    void schemeFontsWithoutAThemeTakeExcelsDefaultThemeFont() throws Exception {
        String sheet = "<sheetFormatPr defaultRowHeight=\"15\"/><sheetData><row r=\"1\">" + RawXlsx.inline("A1", "One")
                + "</row><row r=\"9\">" + RawXlsx.inline("A9", "Nine") + "</row></sheetData>";
        String plain = "<sz val=\"11\"/><name val=\"Calibri\"/>";
        XlsxTesting.convert(dir, "named.xlsx", new RawXlsx().styles(styles(plain, "")).sheet("S", sheet).bytes());
        XlsxTesting.convert(dir, "scheme.xlsx", new RawXlsx().styles(styles(plain + "<scheme val=\"minor\"/>", ""))
                .sheet("S", sheet).bytes());
        float named = gap(dir.resolve("named.xlsx.pdf"));
        float scheme = gap(dir.resolve("scheme.xlsx.pdf"));
        assertEquals(8 * 121 * 72 / 600f, named, 0.3);
        assertEquals(8 * 127 * 72 / 600f, scheme, 0.3);
    }

    private static float gap(Path pdf) throws Exception {
        return position(pdf, "Nine")[1] - position(pdf, "One")[1];
    }

    @Test
    void theLineGapOfAPrinterRowIsTruncated() {
        FontMeasure arial = new FontMeasure(2048, 1139, 1854, 434, 1854, -434, 67, false);
        assertEquals(103, arial.printerLinePx(10));
        assertEquals(17, arial.screenLinePx(10));
        FontMeasure calibri = new FontMeasure(2048, 1038, 1950, 550, 1536, -512, 452, false);
        assertEquals(121, calibri.printerLinePx(11));
    }

    @Test
    void rowHeadingsKeepRoomForTwoDigits() throws Exception {
        float few = headingOffset("few.xlsx", 3);
        float tens = headingOffset("tens.xlsx", 40);
        assertEquals(tens, few, 0.2);
    }

    private float headingOffset(String name, int rows) throws Exception {
        StringBuilder data = new StringBuilder("<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "Marker") + "</row>");
        data.append("<row r=\"").append(rows).append("\">").append(RawXlsx.inline("B" + rows, "End")).append("</row>");
        String sheet = data + "</sheetData><printOptions headings=\"1\"/>";
        XlsxTesting.convert(dir, name, new RawXlsx().sheet("S", sheet).bytes());
        return position(dir.resolve(name + ".pdf"), "Marker")[0];
    }

    @Test
    void wrappedVerticalTextStandsInLinesSideBySide() throws Exception {
        String alignment = "<alignment textRotation=\"90\" wrapText=\"1\" vertical=\"center\"/>";
        String sheet = "<cols><col min=\"1\" max=\"1\" width=\"30\" customWidth=\"1\"/></cols><sheetData>"
                + "<row r=\"1\" ht=\"60\" customHeight=\"1\"><c r=\"A1\" s=\"1\" t=\"inlineStr\"><is><t>Alpha beta "
                + "gamma delta epsilon Omega</t></is></c></row></sheetData>";
        XlsxTesting.convert(dir, "vertical.xlsx", new RawXlsx()
                .styles(styles("<sz val=\"11\"/><name val=\"Calibri\"/>", alignment)).sheet("S", sheet).bytes());
        int bands = 0;
        try (PDDocument doc = Loader.loadPDF(dir.resolve("vertical.xlsx.pdf").toFile())) {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, 72);
            boolean inked = false;
            for (int x = 0; x < img.getWidth(); x++) {
                boolean any = false;
                for (int y = 40; y < 130 && !any; y++) {
                    any = (img.getRGB(x, y) & 0xFF) < 100;
                }
                bands += any && !inked ? 1 : 0;
                inked = any;
            }
        }
        assertTrue(bands >= 4, "lines " + bands);
    }

    private static float[] position(Path pdf, String prefix) throws Exception {
        List<float[]> found = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    if (text.strip().startsWith(prefix) && found.isEmpty()) {
                        TextPosition p = positions.get(0);
                        found.add(new float[] {p.getXDirAdj(), p.getYDirAdj()});
                    }
                }
            }.getText(doc);
        }
        assertFalse(found.isEmpty(), prefix + " not found");
        return found.get(0);
    }
}
