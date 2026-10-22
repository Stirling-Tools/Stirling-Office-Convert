package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemColorAndPictureFillTest {

    private static final String FONTS = "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>";

    private static final String TAIL = "<borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf/>"
            + "</cellStyleXfs><cellXfs count=\"2\"><xf/><xf fillId=\"2\" applyFill=\"1\"/></cellXfs>";

    @TempDir
    Path dir;

    @Test
    void aSolidFillInTheSystemBackgroundColourIsWhite() throws IOException {
        String styles = FONTS + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill"
                + " patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor indexed=\"65\"/>"
                + "<bgColor indexed=\"8\"/></patternFill></fill></fills>" + TAIL;
        StringBuilder rows = new StringBuilder("<row r=\"1\">" + RawXlsx.inline("A1", "Marker") + "</row>");
        for (int r = 2; r <= 30; r++) {
            rows.append("<row r=\"").append(r).append("\">");
            for (char c = 'A'; c <= 'F'; c++) {
                rows.append("<c r=\"").append(c).append(r).append("\" s=\"1\"/>");
            }
            rows.append("</row>");
        }
        byte[] xlsx = new RawXlsx().styles(styles).sheet("S", "<sheetData>" + rows + "</sheetData>").bytes();
        XlsxTesting.convert(dir, "system.xlsx", xlsx);
        double dark = darkShare(dir.resolve("system.xlsx.pdf"));
        assertTrue(dark < 0.02, "dark share " + dark);
    }

    @Test
    void aRectangleFilledWithAPictureShowsThePictureNotItsStyleFill() throws IOException {
        String drawing = "<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\""
                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><xdr:oneCellAnchor>"
                + "<xdr:from><xdr:col>1</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>0</xdr:rowOff>"
                + "</xdr:from><xdr:ext cx=\"2540000\" cy=\"2540000\"/><xdr:sp macro=\"\" textlink=\"\"><xdr:nvSpPr>"
                + "<xdr:cNvPr id=\"2\" name=\"Panel\"/><xdr:cNvSpPr txBox=\"1\"/></xdr:nvSpPr><xdr:spPr><a:prstGeom"
                + " prst=\"rect\"><a:avLst/></a:prstGeom><a:blipFill><a:blip r:embed=\"rIdImg\"/><a:stretch><a:fillRect/>"
                + "</a:stretch></a:blipFill><a:ln><a:noFill/></a:ln></xdr:spPr><xdr:style><a:lnRef idx=\"0\"><a:schemeClr"
                + " val=\"lt1\"/></a:lnRef><a:fillRef idx=\"1\"><a:srgbClr val=\"000000\"/></a:fillRef><a:effectRef"
                + " idx=\"0\"><a:schemeClr val=\"dk1\"/></a:effectRef><a:fontRef idx=\"minor\"><a:schemeClr"
                + " val=\"lt1\"/></a:fontRef></xdr:style></xdr:sp><xdr:clientData/></xdr:oneCellAnchor></xdr:wsDr>";
        String sheet = "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "Marker") + "</row></sheetData>"
                + "<drawing r:id=\"rIdD\"/>";
        String styles = FONTS + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill"
                + " patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs"
                + " count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"1\"><xf/></cellXfs>";
        byte[] xlsx = new RawXlsx().styles(styles).sheet("S", sheet)
                .part("xl/drawings/drawing1.xml", "application/vnd.openxmlformats-officedocument.drawing+xml", drawing)
                .rel("xl/worksheets/sheet1.xml", "rIdD", "drawing", "../drawings/drawing1.xml")
                .binary("xl/media/image1.png", "png", "image/png", whitePng())
                .rel("xl/drawings/drawing1.xml", "rIdImg", "image", "../media/image1.png").bytes();
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "picture.xlsx", xlsx);
        assertEquals(1, c.images());
        double dark = darkShare(dir.resolve("picture.xlsx.pdf"));
        assertTrue(dark < 0.02, "dark share " + dark);
    }

    private static byte[] whitePng() throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                img.setRGB(x, y, Color.WHITE.getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static double darkShare(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, 36, ImageType.RGB);
            long dark = 0;
            for (int y = 0; y < page.getHeight(); y++) {
                for (int x = 0; x < page.getWidth(); x++) {
                    int p = page.getRGB(x, y);
                    if (((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255) < 200) {
                        dark++;
                    }
                }
            }
            return dark / (double) (page.getWidth() * page.getHeight());
        }
    }
}
