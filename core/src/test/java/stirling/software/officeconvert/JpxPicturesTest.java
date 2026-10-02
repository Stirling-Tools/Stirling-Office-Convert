package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JpxPicturesTest {

    @TempDir
    static Path dir;

    static Path pdf;

    static Path alphaPdf;

    @BeforeAll
    static void makePdfs() throws IOException {
        pdf = makePdf("photo.jp2", "jpx.pdf", 480, 360);
        alphaPdf = makePdf("rgba.jp2", "rgba.pdf", 61, 47);
    }

    private static Path makePdf(String resource, String name, int width, int height) throws IOException {
        byte[] jpx;
        try (InputStream in = JpxPicturesTest.class.getResourceAsStream("/jpx/" + resource)) {
            jpx = in.readAllBytes();
        }
        Path pdf = dir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            COSStream img = doc.getDocument().createCOSStream();
            try (OutputStream o = img.createRawOutputStream()) {
                o.write(jpx);
            }
            img.setItem(COSName.TYPE, COSName.XOBJECT);
            img.setItem(COSName.SUBTYPE, COSName.IMAGE);
            img.setItem(COSName.FILTER, COSName.JPX_DECODE);
            img.setInt(COSName.WIDTH, width);
            img.setInt(COSName.HEIGHT, height);
            COSDictionary xobjects = new COSDictionary();
            xobjects.setItem(COSName.getPDFName("Im0"), img);
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.XOBJECT, xobjects);
            page.getCOSObject().setItem(COSName.RESOURCES, resources);
            PDStream content = new PDStream(doc);
            try (OutputStream o = content.createOutputStream(COSName.FLATE_DECODE)) {
                o.write("q 360 0 0 270 72 400 cm /Im0 Do Q".getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(content);
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    @ParameterizedTest
    @CsvSource({"docx,word/media/", "pptx,ppt/media/", "odt,Pictures/"})
    void jpegTwoThousandPicturesReachTheDocument(String format, String folder) throws IOException {
        List<BufferedImage> pictures = pictures(pdf, "jpx." + format, folder, 100);
        assertEquals(1, pictures.size(), "pictures in " + format);
        BufferedImage p = pictures.get(0);
        int x = p.getWidth() * 360 / 480;
        int y = p.getHeight() * 250 / 360;
        int rgb = p.getRGB(x, y);
        assertTrue((rgb & 0xFF) > 150 && (rgb >> 16 & 0xFF) < 80, "blue box colour " + Integer.toHexString(rgb));
    }

    @ParameterizedTest
    @CsvSource({"docx,word/media/", "pptx,ppt/media/", "odt,Pictures/"})
    void alphaInTheCodestreamDoesNotScrambleThePictureWithoutSmaskInData(String format, String folder)
            throws IOException {
        List<BufferedImage> pictures = pictures(alphaPdf, "rgba." + format, folder, 40);
        assertEquals(1, pictures.size(), "pictures in " + format);
        BufferedImage p = pictures.get(0);
        long diff = 0;
        int count = 0;
        for (int y = 0; y < 47; y++) {
            for (int x = 0; x < 61; x++) {
                if (x >= 18 && x < 42 && y >= 8 && y < 32) {
                    continue;
                }
                int rgb = p.getRGB(x * p.getWidth() / 61, y * p.getHeight() / 47);
                diff += Math.abs((rgb >> 16 & 0xFF) - sample(x, y, 0)) + Math.abs((rgb >> 8 & 0xFF) - sample(x, y, 1))
                        + Math.abs((rgb & 0xFF) - sample(x, y, 2));
                count += 3;
            }
        }
        assertTrue(diff / (double) count < 8, "mean difference " + diff / (double) count + " in " + format);
    }

    private static int sample(int x, int y, int c) {
        return x * 5 + y * 3 + c * 40 + ((x * x + y * y * 3 + c * 7) >> 3) & 255;
    }

    private static List<BufferedImage> pictures(Path source, String name, String folder, int minWidth)
            throws IOException {
        Path out = dir.resolve(name);
        OfficeConvert.convert(source, out, OfficeConvert.Settings.defaults());
        List<BufferedImage> pictures = new ArrayList<>();
        try (ZipFile zip = new ZipFile(out.toFile())) {
            for (ZipEntry e : Collections.list(zip.entries())) {
                if (e.getName().startsWith(folder)) {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(zip.getInputStream(e).readAllBytes()));
                    if (img != null && img.getWidth() >= minWidth) {
                        pictures.add(img);
                    }
                }
            }
        }
        return pictures;
    }
}
