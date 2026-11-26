package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import stirling.software.officeconvert.topdf.OfficeToPdf;

/** Every sample at every level: veraPDF passes the level the output claims, or the conversion refuses or reduces. */
class ConformanceSweepTest {

    private static final String TEXT = "Archive éß€ 日本 مرحبا";

    @TempDir
    Path dir;

    static Stream<Arguments> cases() {
        Stream<String> names = Stream.concat(Stream.concat(Samples.ALL.keySet().stream()
                .filter(n -> !n.equals("s06_encrypted_user")).map(n -> "s:" + n),
                RuleSamples.ALL.keySet().stream().map(n -> "r:" + n)), SweepSamples.ALL.keySet().stream().map(n -> "w:" + n));
        return names.flatMap(n -> Stream.of(PdfALevel.values()).flatMap(l -> l.tagged()
                ? Stream.of(Arguments.of(n, l, false), Arguments.of(n, l, true)) : Stream.of(Arguments.of(n, l, false))));
    }

    @ParameterizedTest(name = "{0} to {1}, fallback {2}")
    @MethodSource("cases")
    void theOutputPassesTheLevelItClaims(String sample, PdfALevel level, boolean fallback) throws Exception {
        String name = sample.substring(2);
        Path in = switch (sample.charAt(0)) {
            case 's' -> Samples.write(dir, name);
            case 'r' -> RuleSamples.write(dir, name);
            default -> Files.write(dir.resolve(name + ".pdf"), SweepSamples.ALL.get(name).make());
        };
        String o = Conformance.outcome(in, dir.resolve("out.pdf"),
                PdfToPdfA.Options.defaults().level(level).levelFallback(fallback));
        // Sweep inputs must convert; only a missing structure may refuse level a.
        boolean ok = o.equals("pass") || fallback && o.startsWith("reduced")
                || sample.charAt(0) != 'w' && o.startsWith("refused")
                || level.tagged() && !fallback && o.startsWith("refused: " + LevelANotMet.class.getName());
        assertTrue(ok, o);
    }

    static Stream<Arguments> office() {
        return Stream.of("docx", "xlsx", "pptx").flatMap(f -> Stream.of(PdfALevel.A1B, PdfALevel.A2B, PdfALevel.A2U,
                PdfALevel.A3B, PdfALevel.A2A, PdfALevel.A4).map(l -> Arguments.of(f, l)));
    }

    @ParameterizedTest(name = "{0} to {1}")
    @MethodSource("office")
    void officeOutputPassesTheLevelItClaims(String format, PdfALevel level) throws Exception {
        Path doc = dir.resolve("in." + format);
        Files.write(doc, switch (format) {
            case "docx" -> docx();
            case "xlsx" -> xlsx();
            default -> pptx();
        });
        Path pdf = dir.resolve("in.pdf");
        OfficeToPdf.convert(doc, pdf);
        String o = Conformance.outcome(pdf, dir.resolve("out.pdf"),
                PdfToPdfA.Options.defaults().level(level).levelFallback(true));
        assertTrue(o.equals("pass") || o.startsWith("reduced"), o);
    }

    private static byte[] docx() throws Exception {
        try (XWPFDocument d = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFRun r = d.createParagraph().createRun();
            r.setBold(true);
            r.setColor("C00000");
            r.setText(TEXT);
            d.createParagraph().createRun().addPicture(new ByteArrayInputStream(png()), PictureType.PNG,
                    "a.png", 914400, 914400);
            d.createTable(2, 2).getRow(0).getCell(0).setText(TEXT);
            d.createParagraph().createHyperlinkRun("https://example.com").setText("link");
            d.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] xlsx() throws Exception {
        try (XSSFWorkbook w = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet s = w.createSheet("Data");
            XSSFCellStyle fill = w.createCellStyle();
            fill.setFillForegroundColor(IndexedColors.LIGHT_ORANGE.getIndex());
            fill.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for (int i = 0; i < 40; i++) {
                s.createRow(i).createCell(0).setCellValue(TEXT + i);
                s.getRow(i).createCell(1).setCellValue(i * 1.5);
                s.getRow(i).getCell(1).setCellStyle(fill);
            }
            w.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] pptx() throws Exception {
        try (XMLSlideShow p = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFSlide slide = p.createSlide();
            XSLFAutoShape box = slide.createAutoShape();
            box.setAnchor(new Rectangle(50, 50, 400, 120));
            box.setFillColor(new Color(40, 90, 200, 128));
            box.setText(TEXT);
            slide.createPicture(p.addPicture(png(), PictureData.PictureType.PNG)).setAnchor(new Rectangle(100, 250, 200, 200));
            p.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] png() throws Exception {
        BufferedImage im = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                im.setRGB(x, y, (x * 8) << 24 | 0xff0000 | y * 8);
            }
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(im, "png", b);
        return b.toByteArray();
    }
}
