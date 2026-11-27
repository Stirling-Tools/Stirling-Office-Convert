package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class SvgToPdfTest {

    @TempDir
    Path dir;

    private record Drawn(BufferedImage image, String text, OfficeToPdf.Result result, float width, float height,
            int pictures) {}

    private Drawn draw(String svg) throws IOException {
        return draw(svg.getBytes(StandardCharsets.UTF_8), "in.svg");
    }

    private Drawn draw(byte[] svg, String name) throws IOException {
        Path in = dir.resolve(name);
        Path out = dir.resolve(name + ".pdf");
        Files.write(in, svg);
        OfficeToPdf.Result r = OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            PDPage page = doc.getPage(0);
            BufferedImage img = new PDFRenderer(doc).renderImage(0, 4f / 3f);
            return new Drawn(img, new PDFTextStripper().getText(doc), r, page.getMediaBox().getWidth(),
                    page.getMediaBox().getHeight(), pictures(page.getResources(), 0));
        }
    }

    private static int pictures(PDResources res, int depth) throws IOException {
        if (res == null || depth > 8) {
            return 0;
        }
        int n = 0;
        for (COSName name : res.getXObjectNames()) {
            PDXObject x = res.getXObject(name);
            if (x instanceof PDImageXObject) {
                n++;
            } else if (x instanceof PDFormXObject f) {
                n += pictures(f.getResources(), depth + 1);
            }
        }
        return n;
    }

    private static int rgb(Drawn d, int x, int y) {
        return d.image().getRGB(x, y) & 0xFFFFFF;
    }

    private static boolean near(int rgb, int want) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((rgb >> shift) & 0xFF) - ((want >> shift) & 0xFF)) > 24) {
                return false;
            }
        }
        return true;
    }

    @Test
    void shapesGradientsAndTextAreVectorsAndText() throws IOException {
        Drawn d = draw("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"300\" height=\"150\">"
                + "<linearGradient id=\"g\"><stop offset=\"0\" stop-color=\"#f00\"/><stop offset=\"1\" stop-color=\"#00f\"/>"
                + "</linearGradient><rect x=\"0\" y=\"0\" width=\"100\" height=\"50\" fill=\"#008000\"/>"
                + "<rect x=\"100\" y=\"0\" width=\"200\" height=\"50\" fill=\"url(#g)\"/>"
                + "<text x=\"10\" y=\"120\" font-size=\"30\" fill=\"#000\">Searchable</text></svg>");
        assertEquals(225, d.width(), 0.01, "300 px is 225 pt");
        assertEquals(112.5, d.height(), 0.01);
        assertTrue(near(rgb(d, 50, 25), 0x008000));
        assertTrue(near(rgb(d, 103, 25), 0xFF0000), Integer.toHexString(rgb(d, 103, 25)));
        assertTrue(near(rgb(d, 297, 25), 0x0000FF), Integer.toHexString(rgb(d, 297, 25)));
        assertTrue(d.text().contains("Searchable"));
        assertEquals(0, d.pictures(), "nothing is drawn as a picture");
    }

    @Test
    void clipsMasksPatternsAndGroupOpacity() throws IOException {
        Drawn d = draw("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"400\" height=\"100\">"
                + "<clipPath id=\"c\"><circle cx=\"50\" cy=\"50\" r=\"40\"/></clipPath>"
                + "<rect width=\"100\" height=\"100\" fill=\"#008000\" clip-path=\"url(#c)\"/>"
                + "<mask id=\"m\"><rect x=\"100\" width=\"50\" height=\"100\" fill=\"#fff\"/></mask>"
                + "<rect x=\"100\" width=\"100\" height=\"100\" fill=\"#0000ff\" mask=\"url(#m)\"/>"
                + "<pattern id=\"p\" width=\"20\" height=\"20\" patternUnits=\"userSpaceOnUse\">"
                + "<rect width=\"10\" height=\"20\" fill=\"#ff0000\"/></pattern>"
                + "<rect x=\"200\" width=\"100\" height=\"100\" fill=\"url(#p)\"/>"
                + "<g opacity=\"0.5\"><rect x=\"300\" width=\"60\" height=\"100\" fill=\"#000\"/>"
                + "<rect x=\"330\" width=\"60\" height=\"100\" fill=\"#000\"/></g></svg>");
        assertTrue(near(rgb(d, 50, 50), 0x008000), "inside the clip");
        assertTrue(near(rgb(d, 5, 5), 0xFFFFFF), "outside the clip");
        assertTrue(near(rgb(d, 125, 50), 0x0000FF), "white mask shows");
        assertTrue(near(rgb(d, 175, 50), 0xFFFFFF), "no mask hides");
        assertTrue(near(rgb(d, 205, 50), 0xFF0000), "pattern stripe");
        assertTrue(near(rgb(d, 215, 50), 0xFFFFFF), "pattern gap");
        assertTrue(near(rgb(d, 345, 50), rgb(d, 310, 50)), "group opacity applies to the group, not each shape");
        assertTrue(near(rgb(d, 345, 50), 0x808080), Integer.toHexString(rgb(d, 345, 50)));
    }

    @Test
    void markersUseAndSymbolsAreDrawn() throws IOException {
        Drawn d = draw("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
                + " viewBox=\"0 0 200 100\" width=\"200\" height=\"100\">"
                + "<marker id=\"a\" viewBox=\"0 0 10 10\" refX=\"5\" refY=\"5\" markerWidth=\"10\" markerHeight=\"10\">"
                + "<rect width=\"10\" height=\"10\" fill=\"#ff0000\"/></marker>"
                + "<symbol id=\"s\" viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\" fill=\"#0000ff\"/></symbol>"
                + "<line x1=\"10\" y1=\"50\" x2=\"90\" y2=\"50\" stroke=\"#000\" marker-end=\"url(#a)\"/>"
                + "<use xlink:href=\"#s\" x=\"120\" y=\"20\" width=\"60\" height=\"60\"/></svg>");
        assertTrue(near(rgb(d, 91, 53), 0xFF0000), "the marker sits on the end of the line");
        assertTrue(near(rgb(d, 150, 50), 0x0000FF), "the symbol is drawn through use");
    }

    @Test
    void onlyFilteredElementsBecomePictures() throws IOException {
        Drawn d = draw("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"100\">"
                + "<filter id=\"f\"><feGaussianBlur stdDeviation=\"4\"/><feOffset dx=\"5\" dy=\"5\"/></filter>"
                + "<rect x=\"10\" y=\"10\" width=\"60\" height=\"60\" fill=\"#000\" filter=\"url(#f)\"/>"
                + "<rect x=\"120\" y=\"10\" width=\"60\" height=\"60\" fill=\"#000\"/></svg>");
        assertEquals(1, d.pictures());
        int edge = rgb(d, 73, 45);
        assertTrue((edge & 0xFF) > 20 && (edge & 0xFF) < 235, "the blur softens the edge: " + Integer.toHexString(edge));
        assertTrue(near(rgb(d, 150, 40), 0x000000));
    }

    @Test
    void anSvgPictureInADataUriIsDrawnAsVectors() throws IOException {
        String inner = "%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='10'%3E%3Crect width='10'"
                + " height='10' fill='%23ff0000'/%3E%3C/svg%3E";
        Drawn d = draw("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\">"
                + "<image href=\"data:image/svg+xml," + inner + "\" width=\"100\" height=\"100\"/></svg>");
        assertTrue(near(rgb(d, 50, 50), 0xFF0000));
        assertEquals(0, d.pictures());
    }

    @Test
    void svgzIsUnpacked() throws IOException {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(gz)) {
            z.write("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" height=\"40\"><rect width=\"40\" height=\"40\" fill=\"#ff0000\"/></svg>"
                    .getBytes(StandardCharsets.UTF_8));
        }
        Drawn d = draw(gz.toByteArray(), "in.svgz");
        assertTrue(near(rgb(d, 20, 20), 0xFF0000));
    }

    @Test
    void nothingOutsideTheFileIsRead() throws IOException {
        Files.writeString(dir.resolve("secret.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><rect width=\"10\" height=\"10\"/></svg>");
        Drawn d = draw("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
                + " width=\"100\" height=\"100\"><image xlink:href=\"secret.svg\" width=\"100\" height=\"100\"/>"
                + "<image href=\"http://127.0.0.1:9/x.png\" width=\"100\" height=\"100\"/>"
                + "<use xlink:href=\"secret.svg#x\"/></svg>");
        assertTrue(near(rgb(d, 50, 50), 0xFFFFFF));
        assertTrue(d.result().warnings().stream().anyMatch(w -> w.contains("only data: URIs")), d.result().warnings()
                .toString());
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void hostileDrawingsStayBounded() throws IOException {
        StringBuilder lol = new StringBuilder("<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY a \"");
        lol.append("x".repeat(60_000)).append("\">]><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><text>");
        lol.append("&a;".repeat(2_000)).append("</text></svg>");
        draw(lol.toString());
        draw("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"10\" height=\"10\">"
                + "<g id=\"a\"><use xlink:href=\"#b\"/><use xlink:href=\"#b\"/></g>"
                + "<g id=\"b\"><use xlink:href=\"#a\"/><use xlink:href=\"#a\"/></g></svg>");
        Drawn d = draw("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"400\" height=\"400\">"
                + "<filter id=\"f\"><feMorphology operator=\"dilate\" radius=\"100000\"/></filter>"
                + "<rect width=\"10\" height=\"10\" filter=\"url(#f)\"/></svg>");
        assertFalse(d.text().contains("x"));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void reviewedAttacksStayBounded() throws IOException {
        StringBuilder text = new StringBuilder("a".repeat(30_000));
        draw("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><style>*:matches((a+)+$) {fill:red}"
                + " text:has(tspan) {fill:red}</style><text>" + text + "b</text></svg>");
        draw("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><rect width=\"10\" height=\"10\""
                + " fill=\"" + "url(#x) ".repeat(100_000) + "\"/></svg>");
        StringBuilder chain = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">");
        for (int i = 0; i < 3_000; i++) {
            chain.append("<pattern id=\"p").append(i).append("\" width=\"5\" height=\"5\" patternUnits=\"userSpaceOnUse\">")
                    .append("<rect width=\"5\" height=\"5\" fill=\"url(#p").append(i + 1).append(")\"/></pattern>");
            chain.append("<marker id=\"m").append(i).append("\"><path d=\"M0 0L1 1\" stroke=\"#000\" marker-end=\"url(#m")
                    .append(i + 1).append(")\"/></marker>");
        }
        chain.append("<rect width=\"10\" height=\"10\" fill=\"url(#p0)\"/><path d=\"M0 0L5 5\" marker-end=\"url(#m0)\"/>");
        draw(chain.append("</svg>").toString());
        String inner = "%3Csvg xmlns='http://www.w3.org/2000/svg' width='4' height='4'%3E"
                + "%3Crect width='4' height='4'/%3E%3C/svg%3E";
        StringBuilder uses = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
                + " width=\"10\" height=\"10\"><defs><image id=\"i\" width=\"4\" height=\"4\" href=\"data:image/svg+xml," + inner
                + "\"/></defs>");
        uses.append("<use xlink:href=\"#i\"/>".repeat(50_000));
        draw(uses.append("</svg>").toString());
    }
}
