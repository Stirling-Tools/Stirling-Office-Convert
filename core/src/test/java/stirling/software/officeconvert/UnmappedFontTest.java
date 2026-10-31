package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipFile;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnmappedFontTest {

    private static final String FONT = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

    private static final String LINE = "The quick brown fox jumps over the lazy dog.";

    private static final String SECOND = "Pack my box with five dozen liquor jugs, then sit.";

    private static final int STAR = 200;

    @TempDir Path dir;

    @Test
    void drawnType3LettersAreReadByTheirShape() throws Exception {
        String text = text(type3(false, false));
        assertTrue(text.contains(LINE), text);
        assertTrue(text.contains(SECOND), text);
    }

    @Test
    void bitmapType3LettersAreReadByTheirShape() throws Exception {
        String text = text(type3(true, false));
        assertTrue(text.contains(LINE), text);
        assertTrue(text.contains(SECOND), text);
    }

    @Test
    void anUnreadableGlyphKeepsItsLookInsteadOfAJunkLetter() throws Exception {
        Path pdf = type3(false, true);
        String text = text(pdf);
        assertTrue(text.contains(LINE), text);
        assertFalse(text.contains(String.valueOf((char) STAR)), text);
        Path docx = dir.resolve("star.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            assertTrue(zip.stream().anyMatch(e -> e.getName().startsWith("word/media/")));
        }
    }

    @Test
    void compositeFontWithoutAnyTextMapIsReadByItsShapes() throws Exception {
        Path saved = dir.resolve("cid.pdf");
        try (PDDocument doc = new PDDocument(); InputStream ttf = PDTrueTypeFont.class.getResourceAsStream(FONT)) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, ttf, false);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 14);
                cs.newLineAtOffset(72, 720);
                cs.showText(LINE);
                cs.newLineAtOffset(0, -24);
                cs.showText(SECOND);
                cs.endText();
            }
            doc.save(saved.toFile());
        }
        Path pdf = dir.resolve("cid-unmapped.pdf");
        try (PDDocument doc = Loader.loadPDF(saved.toFile())) {
            for (COSName name : doc.getPage(0).getResources().getFontNames()) {
                PDType0Font font = (PDType0Font) doc.getPage(0).getResources().getFont(name);
                font.getCOSObject().removeItem(COSName.TO_UNICODE);
                PDStream file = font.getDescendantFont().getFontDescriptor().getFontFile2();
                byte[] bytes;
                try (InputStream in = file.createInputStream()) {
                    bytes = in.readAllBytes();
                }
                hideTable(bytes, "cmap");
                hideTable(bytes, "post");
                try (OutputStream out = file.getCOSObject().createOutputStream(COSName.FLATE_DECODE)) {
                    out.write(bytes);
                }
            }
            doc.save(pdf.toFile());
        }
        String text = text(pdf);
        assertTrue(text.contains(LINE), text);
        assertTrue(text.contains(SECOND), text);
    }

    @Test
    void mappedType3FontsKeepTheirOwnText() throws Exception {
        Path pdf = dir.resolve("named.pdf");
        Map<Character, Integer> codes = new LinkedHashMap<>();
        for (char c : (LINE + SECOND).toCharArray()) {
            if (c != ' ') {
                codes.putIfAbsent(c, (int) c);
            }
        }
        writeType3(pdf, codes, false, false, true);
        String text = text(pdf);
        assertTrue(text.contains(LINE), text);
    }

    @Test
    void tangledType3GlyphsFinishQuickly() throws Exception {
        Path pdf = dir.resolve("tangled.pdf");
        Random random = new Random(7);
        try (PDDocument doc = new PDDocument()) {
            COSDictionary procs = new COSDictionary();
            COSArray differences = new COSArray();
            differences.add(COSInteger.ZERO);
            COSArray widths = new COSArray();
            for (int code = 0; code < 32; code++) {
                StringBuilder ops = new StringBuilder("1000 0 d0\n500 500 m\n");
                for (int i = 0; i < 3000; i++) {
                    ops.append(random.nextInt(1000)).append(' ').append(random.nextInt(1000)).append(" l\n");
                }
                ops.append(code % 2 == 0 ? "h f\n" : "h f* 0 0 m 10 0 l 10 10 l h f*\n");
                COSStream proc = doc.getDocument().createCOSStream();
                try (OutputStream out = proc.createOutputStream(COSName.FLATE_DECODE)) {
                    out.write(ops.toString().getBytes(StandardCharsets.US_ASCII));
                }
                procs.setItem(COSName.getPDFName("t" + code), proc);
                differences.add(COSName.getPDFName("t" + code));
                widths.add(COSInteger.get(1000));
            }
            COSDictionary encoding = new COSDictionary();
            encoding.setItem(COSName.TYPE, COSName.ENCODING);
            encoding.setItem(COSName.DIFFERENCES, differences);
            COSDictionary dict = new COSDictionary();
            dict.setItem(COSName.TYPE, COSName.FONT);
            dict.setItem(COSName.SUBTYPE, COSName.TYPE3);
            dict.setItem(COSName.FONT_MATRIX, numbers(0.001f, 0, 0, 0.001f, 0, 0));
            dict.setItem(COSName.FONT_BBOX, numbers(0, 0, 1000, 1000));
            dict.setItem(COSName.CHAR_PROCS, procs);
            dict.setItem(COSName.ENCODING, encoding);
            dict.setInt(COSName.FIRST_CHAR, 0);
            dict.setInt(COSName.LAST_CHAR, 31);
            dict.setItem(COSName.WIDTHS, widths);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType3Font(dict));
            page.setResources(resources);
            PDStream stream = new PDStream(doc);
            try (OutputStream out = stream.createOutputStream()) {
                out.write("BT /F1 14 Tf 72 720 Td <00010203> Tj ET".getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            doc.save(pdf.toFile());
        }
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> text(pdf));
    }

    private String text(Path pdf) throws IOException {
        Path txt = dir.resolve(pdf.getFileName() + ".txt");
        PdfToText.convert(pdf, txt, PdfToDocx.Options.defaults());
        return Files.readString(txt, StandardCharsets.UTF_8);
    }

    private Path type3(boolean bitmap, boolean star) throws IOException {
        Map<Character, Integer> codes = new LinkedHashMap<>();
        int next = 0;
        for (char c : (LINE + SECOND).toCharArray()) {
            if (c != ' ' && !codes.containsKey(c)) {
                codes.put(c, 40 + (next++ * 37) % 150);
            }
        }
        Path pdf = dir.resolve((bitmap ? "bitmap" : "drawn") + (star ? "-star" : "") + ".pdf");
        writeType3(pdf, codes, bitmap, star, false);
        return pdf;
    }

    private static void writeType3(Path pdf, Map<Character, Integer> codes, boolean bitmap, boolean star, boolean named)
            throws IOException {
        TrueTypeFont ttf;
        try (InputStream in = PDTrueTypeFont.class.getResourceAsStream(FONT)) {
            ttf = new TTFParser().parse(new RandomAccessReadBuffer(in));
        }
        CmapLookup cmap = ttf.getUnicodeCmapLookup();
        float unit = 1000f / ttf.getUnitsPerEm();
        try (PDDocument doc = new PDDocument()) {
            COSDictionary procs = new COSDictionary();
            COSArray differences = new COSArray();
            COSArray widths = new COSArray();
            for (int code = 0; code < 256; code++) {
                widths.add(COSInteger.ZERO);
            }
            for (Map.Entry<Character, Integer> e : codes.entrySet()) {
                int gid = cmap.getGlyphId(e.getKey());
                GeneralPath path = new GeneralPath(ttf.getGlyph().getGlyph(gid).getPath());
                path.transform(AffineTransform.getScaleInstance(unit, unit));
                int width = Math.round(ttf.getAdvanceWidth(gid) * unit);
                String name = named ? glyphName(e.getKey()) : "g" + e.getValue();
                procs.setItem(COSName.getPDFName(name), charProc(doc, path, width, bitmap));
                differences.add(COSInteger.get(e.getValue()));
                differences.add(COSName.getPDFName(name));
                widths.set(e.getValue(), COSInteger.get(width));
            }
            if (star) {
                procs.setItem(COSName.getPDFName("g" + STAR), charProc(doc, starPath(), 800, bitmap));
                differences.add(COSInteger.get(STAR));
                differences.add(COSName.getPDFName("g" + STAR));
                widths.set(STAR, COSInteger.get(800));
            }
            COSDictionary encoding = new COSDictionary();
            encoding.setItem(COSName.TYPE, COSName.ENCODING);
            encoding.setItem(COSName.DIFFERENCES, differences);
            COSDictionary dict = new COSDictionary();
            dict.setItem(COSName.TYPE, COSName.FONT);
            dict.setItem(COSName.SUBTYPE, COSName.TYPE3);
            dict.setItem(COSName.FONT_MATRIX, numbers(0.001f, 0, 0, 0.001f, 0, 0));
            dict.setItem(COSName.FONT_BBOX, numbers(-200, -300, 1200, 1000));
            dict.setItem(COSName.CHAR_PROCS, procs);
            dict.setItem(COSName.ENCODING, encoding);
            dict.setInt(COSName.FIRST_CHAR, 0);
            dict.setInt(COSName.LAST_CHAR, 255);
            dict.setItem(COSName.WIDTHS, widths);
            dict.setItem(COSName.RESOURCES, new COSDictionary());
            PDFont font = new PDType3Font(dict);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), font);
            page.setResources(resources);
            StringBuilder content = new StringBuilder("BT /F1 14 Tf 72 720 Td ");
            content.append(show(LINE, codes, star)).append(" 0 -24 Td ").append(show(SECOND, codes, false)).append(" ET");
            PDStream stream = new PDStream(doc);
            try (OutputStream out = stream.createOutputStream()) {
                out.write(content.toString().getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            doc.save(pdf.toFile());
        }
    }

    private static String show(String line, Map<Character, Integer> codes, boolean star) {
        StringBuilder sb = new StringBuilder("[");
        for (String word : line.split(" ")) {
            if (sb.length() > 1) {
                sb.append(" -280 ");
            }
            StringBuilder hex = new StringBuilder();
            for (char c : word.toCharArray()) {
                hex.append(String.format("%02X", codes.get(c)));
            }
            sb.append('<').append(hex).append('>');
        }
        if (star) {
            sb.append(" -280 <").append(String.format("%02X", STAR)).append('>');
        }
        return sb.append("] TJ").toString();
    }

    private static COSStream charProc(PDDocument doc, Shape path, int width, boolean bitmap) throws IOException {
        Rectangle2D b = path.getBounds2D();
        StringBuilder ops = new StringBuilder();
        ops.append(width).append(" 0 ").append(Math.round(b.getMinX())).append(' ').append(Math.round(b.getMinY()))
                .append(' ').append(Math.round(b.getMaxX())).append(' ').append(Math.round(b.getMaxY())).append(" d1\n");
        if (bitmap) {
            ops.append(mask(path));
        } else {
            ops.append(outline(path)).append("f\n");
        }
        COSStream stream = doc.getDocument().createCOSStream();
        try (OutputStream out = stream.createOutputStream()) {
            out.write(ops.toString().getBytes(StandardCharsets.US_ASCII));
        }
        return stream;
    }

    private static String outline(Shape path) {
        StringBuilder ops = new StringBuilder();
        double[] p = new double[6];
        double lastX = 0;
        double lastY = 0;
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            switch (it.currentSegment(p)) {
                case PathIterator.SEG_MOVETO -> {
                    ops.append(fmt(p[0], p[1])).append(" m\n");
                    lastX = p[0];
                    lastY = p[1];
                }
                case PathIterator.SEG_LINETO -> {
                    ops.append(fmt(p[0], p[1])).append(" l\n");
                    lastX = p[0];
                    lastY = p[1];
                }
                case PathIterator.SEG_QUADTO -> {
                    ops.append(fmt(lastX + 2 * (p[0] - lastX) / 3, lastY + 2 * (p[1] - lastY) / 3)).append(' ')
                            .append(fmt(p[2] + 2 * (p[0] - p[2]) / 3, p[3] + 2 * (p[1] - p[3]) / 3)).append(' ')
                            .append(fmt(p[2], p[3])).append(" c\n");
                    lastX = p[2];
                    lastY = p[3];
                }
                case PathIterator.SEG_CUBICTO -> {
                    ops.append(fmt(p[0], p[1])).append(' ').append(fmt(p[2], p[3])).append(' ').append(fmt(p[4], p[5]))
                            .append(" c\n");
                    lastX = p[4];
                    lastY = p[5];
                }
                default -> ops.append("h\n");
            }
        }
        return ops.toString();
    }

    private static String mask(Shape path) {
        Rectangle2D b = path.getBounds2D();
        double scale = 0.12;
        int w = Math.max(1, (int) Math.ceil(b.getWidth() * scale));
        int h = Math.max(1, (int) Math.ceil(b.getHeight() * scale));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.BLACK);
            AffineTransform t = AffineTransform.getScaleInstance(scale, -scale);
            t.translate(-b.getMinX(), -b.getMaxY());
            g.fill(t.createTransformedShape(path));
        } finally {
            g.dispose();
        }
        StringBuilder hex = new StringBuilder();
        for (int y = 0; y < h; y++) {
            int bits = 0;
            int count = 0;
            for (int x = 0; x < w; x++) {
                bits = bits << 1 | ((img.getRGB(x, y) & 0xFFFFFF) == 0 ? 0 : 1);
                if (++count == 8) {
                    hex.append(String.format("%02X", bits));
                    bits = 0;
                    count = 0;
                }
            }
            if (count > 0) {
                hex.append(String.format("%02X", bits << (8 - count)));
            }
        }
        return "q " + fmt(w / scale, 0) + ' ' + fmt(0, h / scale) + ' ' + fmt(b.getMinX(), b.getMinY())
                + " cm\nBI /IM true /W " + w + " /H " + h + " /BPC 1 /F /AHx ID\n" + hex + ">\nEI Q\n";
    }

    private static Shape starPath() {
        Path2D.Double star = new Path2D.Double();
        for (int i = 0; i < 10; i++) {
            double r = i % 2 == 0 ? 380 : 150;
            double a = Math.PI / 2 + i * Math.PI / 5;
            double x = 400 + r * Math.cos(a);
            double y = 330 + r * Math.sin(a);
            if (i == 0) {
                star.moveTo(x, y);
            } else {
                star.lineTo(x, y);
            }
        }
        star.closePath();
        return star;
    }

    private static String glyphName(char c) {
        return switch (c) {
            case '.' -> "period";
            case ',' -> "comma";
            default -> String.valueOf(c);
        };
    }

    private static void hideTable(byte[] font, String tag) {
        int tables = (font[4] & 0xFF) << 8 | font[5] & 0xFF;
        byte[] name = tag.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < tables; i++) {
            int at = 12 + 16 * i;
            if (font[at] == name[0] && font[at + 1] == name[1] && font[at + 2] == name[2] && font[at + 3] == name[3]) {
                font[at] = 'x';
            }
        }
    }

    private static COSArray numbers(float... values) {
        COSArray array = new COSArray();
        for (float v : values) {
            array.add(new COSFloat(v));
        }
        return array;
    }

    private static String fmt(double x, double y) {
        return String.format(Locale.ROOT, "%.2f %.2f", x, y);
    }
}
