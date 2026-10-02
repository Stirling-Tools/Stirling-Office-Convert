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

    @BeforeAll
    static void makePdf() throws IOException {
        byte[] jpx;
        try (InputStream in = JpxPicturesTest.class.getResourceAsStream("/jpx/photo.jp2")) {
            jpx = in.readAllBytes();
        }
        pdf = dir.resolve("jpx.pdf");
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
            img.setInt(COSName.WIDTH, 480);
            img.setInt(COSName.HEIGHT, 360);
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
    }

    @ParameterizedTest
    @CsvSource({"docx,word/media/", "pptx,ppt/media/", "odt,Pictures/"})
    void jpegTwoThousandPicturesReachTheDocument(String format, String folder) throws IOException {
        Path out = dir.resolve("jpx." + format);
        OfficeConvert.convert(pdf, out, OfficeConvert.Settings.defaults());
        List<BufferedImage> pictures = new ArrayList<>();
        try (ZipFile zip = new ZipFile(out.toFile())) {
            for (ZipEntry e : Collections.list(zip.entries())) {
                if (e.getName().startsWith(folder)) {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(zip.getInputStream(e).readAllBytes()));
                    if (img != null && img.getWidth() >= 100) {
                        pictures.add(img);
                    }
                }
            }
        }
        assertEquals(1, pictures.size(), "pictures in " + format);
        BufferedImage p = pictures.get(0);
        int x = p.getWidth() * 360 / 480;
        int y = p.getHeight() * 250 / 360;
        int rgb = p.getRGB(x, y);
        assertTrue((rgb & 0xFF) > 150 && (rgb >> 16 & 0xFF) < 80, "blue box colour " + Integer.toHexString(rgb));
    }
}
