package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.TreeMap;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrokenTextMapTest {

    private static final String FONT = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";
    private static final String CMAP = """
            /CIDInit /ProcSet findresource begin
            12 dict begin
            begincmap
            /CMapName /Broken-UCS def
            /CMapType 2 def
            1 begincodespacerange
            <00> <FF>
            endcodespacerange
            2 beginbfchar
            <20> <0063>
            <2E> <0062>
            endbfchar
            endcmap
            CMapName currentdict /CMap defineresource pop
            end
            end
            """;

    @TempDir Path dir;

    @Test
    void drawnCharactersWinOverAWrongTextMap() throws Exception {
        Path txt = dir.resolve("broken.txt");
        PdfToText.convert(makePdf(), txt, PdfToDocx.Options.defaults());
        String text = Files.readString(txt, StandardCharsets.UTF_8);
        assertTrue(text.contains("Hello world. See you soon."), text);
    }

    @Test
    void compositeFontDrawnLettersWinOverAWrongTextMap() throws Exception {
        String line = "Toruń leży nad Wisłą.";
        Path pdf = dir.resolve("composite.pdf");
        try (PDDocument doc = new PDDocument(); InputStream ttf = PDTrueTypeFont.class.getResourceAsStream(FONT)) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, ttf, false);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 14);
                cs.newLineAtOffset(72, 720);
                cs.showText(line);
                cs.endText();
            }
            StringBuilder chars = new StringBuilder();
            int mapped = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                String code = HexFormat.of().withUpperCase().formatHex(font.encode(String.valueOf(c)));
                if (chars.indexOf("<" + code + ">") < 0) {
                    chars.append('<').append(code).append("> <").append(String.format("%04X", c == 'ń' ? (int) 'o' : (int) c))
                            .append(">\n");
                    mapped++;
                }
            }
            String cmap = "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/CMapName /Broken-UCS def\n"
                    + "/CMapType 2 def\n1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n" + mapped
                    + " beginbfchar\n" + chars + "endbfchar\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n";
            COSStream map = doc.getDocument().createCOSStream();
            try (OutputStream out = map.createOutputStream()) {
                out.write(cmap.getBytes(StandardCharsets.US_ASCII));
            }
            font.getCOSObject().setItem(COSName.TO_UNICODE, map);
            doc.save(pdf.toFile());
        }
        Path txt = dir.resolve("composite.txt");
        PdfToText.convert(pdf, txt, PdfToDocx.Options.defaults());
        String text = Files.readString(txt, StandardCharsets.UTF_8);
        assertTrue(text.contains(line), text);
    }

    @Test
    void compositeFontWithoutATextMapIsReadFromItsProgram() throws Exception {
        String line = "Côtes du Rhône, Südliche Rhône.";
        Path saved = dir.resolve("mapped.pdf");
        try (PDDocument doc = new PDDocument(); InputStream ttf = PDTrueTypeFont.class.getResourceAsStream(FONT)) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, ttf, false);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 14);
                cs.newLineAtOffset(72, 720);
                cs.showText(line);
                cs.endText();
            }
            doc.save(saved.toFile());
        }
        Path pdf = dir.resolve("unmapped.pdf");
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(saved.toFile())) {
            for (COSName name : doc.getPage(0).getResources().getFontNames()) {
                doc.getPage(0).getResources().getFont(name).getCOSObject().removeItem(COSName.TO_UNICODE);
            }
            doc.save(pdf.toFile());
        }
        Path txt = dir.resolve("unmapped.txt");
        PdfToText.convert(pdf, txt, PdfToDocx.Options.defaults());
        String text = Files.readString(txt, StandardCharsets.UTF_8);
        assertTrue(text.contains(line), text);
    }

    @Test
    void textMapThatNamesTheDrawnGlyphsWinsOverAShuffledFontCmap() throws Exception {
        String drawn = "Some text";
        String codes = "!\"#$ %$&%";
        byte[] program;
        try (InputStream in = PDTrueTypeFont.class.getResourceAsStream(FONT)) {
            program = in.readAllBytes();
        }
        TrueTypeFont original = new TTFParser().parse(new RandomAccessReadBuffer(program));
        StringBuilder map = new StringBuilder();
        int[] gids = new int[codes.length()];
        for (int i = 0; i < codes.length(); i++) {
            gids[i] = original.getUnicodeCmapLookup().getGlyphId(drawn.charAt(i));
            map.append(String.format("<%02X> <%04X>%n", (int) codes.charAt(i), (int) drawn.charAt(i)));
        }
        Path pdf = dir.resolve("shuffled.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDTrueTypeFont font = PDTrueTypeFont.load(doc, new ByteArrayInputStream(program), WinAnsiEncoding.INSTANCE);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 14);
                cs.newLineAtOffset(72, 720);
                cs.showText(codes);
                cs.endText();
            }
            String cmap = "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/CMapName /Named-UCS def\n"
                    + "/CMapType 2 def\n1 begincodespacerange\n<00> <FF>\nendcodespacerange\n" + codes.length()
                    + " beginbfchar\n" + map + "endbfchar\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n";
            COSStream toUnicode = doc.getDocument().createCOSStream();
            try (OutputStream out = toUnicode.createOutputStream()) {
                out.write(cmap.getBytes(StandardCharsets.US_ASCII));
            }
            font.getCOSObject().setItem(COSName.TO_UNICODE, toUnicode);
            try (OutputStream out = font.getFontDescriptor().getFontFile2().getCOSObject()
                    .createOutputStream(COSName.FLATE_DECODE)) {
                out.write(withCmap(program, codes, gids));
            }
            doc.save(pdf.toFile());
        }
        Path txt = dir.resolve("shuffled.txt");
        PdfToText.convert(pdf, txt, PdfToDocx.Options.defaults());
        String text = Files.readString(txt, StandardCharsets.UTF_8);
        assertTrue(text.contains(drawn), text);
    }

    private static byte[] withCmap(byte[] program, String codes, int[] gids) {
        TreeMap<Integer, Integer> byCode = new TreeMap<>();
        for (int i = 0; i < codes.length(); i++) {
            byCode.put((int) codes.charAt(i), gids[i]);
        }
        int segments = byCode.size() + 1;
        ByteBuffer table = ByteBuffer.allocate(12 + 16 + 8 * segments);
        table.putShort((short) 0).putShort((short) 1).putShort((short) 3).putShort((short) 1).putInt(12);
        table.putShort((short) 4).putShort((short) (16 + 8 * segments)).putShort((short) 0);
        table.putShort((short) (2 * segments)).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        byCode.keySet().forEach(c -> table.putShort((short) c.intValue()));
        table.putShort((short) 0xFFFF).putShort((short) 0);
        byCode.keySet().forEach(c -> table.putShort((short) c.intValue()));
        table.putShort((short) 0xFFFF);
        byCode.forEach((c, gid) -> table.putShort((short) (gid - c)));
        table.putShort((short) 1);
        for (int i = 0; i < segments; i++) {
            table.putShort((short) 0);
        }
        int at = (program.length + 3) & ~3;
        byte[] out = Arrays.copyOf(program, at + table.capacity());
        System.arraycopy(table.array(), 0, out, at, table.capacity());
        ByteBuffer font = ByteBuffer.wrap(out);
        int tables = font.getShort(4) & 0xFFFF;
        for (int i = 0; i < tables; i++) {
            int entry = 12 + 16 * i;
            if (font.getInt(entry) == ('c' << 24 | 'm' << 16 | 'a' << 8 | 'p')) {
                font.putInt(entry + 8, at).putInt(entry + 12, table.capacity());
            }
        }
        return out;
    }

    private Path makePdf() throws IOException {
        Path pdf = dir.resolve("broken.pdf");
        try (PDDocument doc = new PDDocument(); InputStream ttf = PDTrueTypeFont.class.getResourceAsStream(FONT)) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDTrueTypeFont font = PDTrueTypeFont.load(doc, ttf, WinAnsiEncoding.INSTANCE);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 14);
                cs.newLineAtOffset(72, 720);
                cs.showText("Hello world. See you soon.");
                cs.endText();
            }
            COSStream map = doc.getDocument().createCOSStream();
            try (OutputStream out = map.createOutputStream()) {
                out.write(CMAP.getBytes(StandardCharsets.US_ASCII));
            }
            font.getCOSObject().setItem(COSName.TO_UNICODE, map);
            doc.save(pdf.toFile());
        }
        return pdf;
    }
}
