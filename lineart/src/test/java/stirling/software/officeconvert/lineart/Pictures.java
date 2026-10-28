package stirling.software.officeconvert.lineart;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

final class Pictures {

    private Pictures() {}

    static BufferedImage drawing(int w, int h, Color ink, Color paper, long seed) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(paper);
        g.fillRect(0, 0, w, h);
        g.setColor(ink);
        Random random = new Random(seed);
        for (int i = 0; i < 40; i++) {
            g.setStroke(new BasicStroke(1 + random.nextInt(4)));
            int x = random.nextInt(w);
            int y = random.nextInt(h);
            switch (i % 4) {
                case 0 -> g.drawLine(x, y, random.nextInt(w), random.nextInt(h));
                case 1 -> g.drawOval(x, y, 5 + random.nextInt(1 + w / 3), 5 + random.nextInt(1 + h / 3));
                case 2 -> g.fillRect(x, y, 2 + random.nextInt(1 + w / 8), 2 + random.nextInt(1 + h / 8));
                default -> g.drawRect(x, y, 3 + random.nextInt(1 + w / 4), 3 + random.nextInt(1 + h / 4));
            }
        }
        g.dispose();
        return image;
    }

    static BufferedImage scan(int w, int h, long seed) {
        return scan(w, h, seed, 0.75);
    }

    static BufferedImage scan(int w, int h, long seed, double darkest) {
        BufferedImage drawing = drawing(w, h, new Color(30, 30, 35), Color.WHITE, seed);
        BufferedImage scan = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double shade = darkest + (1 - darkest) * x / w;
                int v = (int) ((drawing.getRGB(x, y) & 0xFF) * shade + random.nextGaussian() * 12);
                v = Math.clamp(v, 0, 255);
                scan.setRGB(x, y, v << 16 | v << 8 | v);
            }
        }
        return scan;
    }

    static PDDocument onePage(BufferedImage picture) throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDImageXObject image = LosslessFactory.createFromImage(doc, picture);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(image, 50, 400, picture.getWidth(), picture.getHeight());
        }
        return doc;
    }

    static PDDocument reload(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        doc.close();
        return Loader.loadPDF(out.toByteArray());
    }

    static PDImageXObject firstImage(PDDocument doc) throws IOException {
        PDPage page = doc.getPage(0);
        for (COSName name : page.getResources().getXObjectNames()) {
            if (page.getResources().getXObject(name) instanceof PDImageXObject image) {
                return image;
            }
        }
        throw new AssertionError("no image on the page");
    }

    static boolean dark(int rgb) {
        int r = (rgb >>> 16) & 0xFF;
        int g = (rgb >>> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r + g + b < 3 * 128;
    }
}
