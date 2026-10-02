package stirling.software.officeconvert.extract;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDShadingPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;

final class PatternTone {

    private static final int MAX_TILE_PIXELS = 64 * 64;

    private PatternTone() {}

    static int of(PDColor color, PDResources resources) {
        try {
            COSName name = color.getPatternName();
            if (name == null || resources == null) {
                return -1;
            }
            PDAbstractPattern pattern = resources.getPattern(name);
            if (pattern instanceof PDShadingPattern gradient) {
                return white(flat(gradient.getShading()));
            }
            if (!(pattern instanceof PDTilingPattern tiling) || tiling.getPaintType() != PDTilingPattern.PAINT_COLORED
                    || tiling.getResources() == null) {
                return -1;
            }
            PDImageXObject tile = null;
            int images = 0;
            for (COSName x : tiling.getResources().getXObjectNames()) {
                PDXObject object = tiling.getResources().getXObject(x);
                if (!(object instanceof PDImageXObject image)) {
                    return -1;
                }
                tile = image;
                images++;
            }
            if (tile == null) {
                return -1;
            }
            if (images == 1 && !ImageBudget.affordable(tile)) {
                return -1;
            }
            if (images == 1 && (long) tile.getWidth() * tile.getHeight() <= MAX_TILE_PIXELS) {
                return average(tile.getImage());
            }
            return white(plainTile(tiling));
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static final Set<String> PLACING = Set.of("q", "Q", "cm", "Do", "gs");
    private static final double PLAIN_BYTES = 0.02;
    private static final long MAX_PLAIN_PIXELS = 4_000_000;

    private static int plainTile(PDTilingPattern tiling) throws IOException {
        for (Object token : new PDFStreamParser(tiling).parse()) {
            if (token instanceof Operator op && !PLACING.contains(op.getName())) {
                return -1;
            }
        }
        int tone = -1;
        for (COSName x : tiling.getResources().getXObjectNames()) {
            int c = tiling.getResources().getXObject(x) instanceof PDImageXObject image ? plain(image) : -1;
            if (c < 0 || tone >= 0 && !alike(tone, c)) {
                return -1;
            }
            tone = tone < 0 ? c : tone;
        }
        return tone;
    }

    private static int plain(PDImageXObject image) throws IOException {
        if (!ImageBudget.affordable(image)) {
            return -1;
        }
        long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels <= 0 || pixels > MAX_PLAIN_PIXELS || image.getCOSObject().getLength() > PLAIN_BYTES * pixels + 1024) {
            return -1;
        }
        BufferedImage img = image.getImage();
        int first = img.getRGB(0, 0) & 0xFFFFFF;
        int[] row = new int[img.getWidth()];
        for (int y = 0; y < img.getHeight(); y++) {
            img.getRGB(0, y, row.length, 1, row, 0, row.length);
            for (int c : row) {
                if (!alike(first, c & 0xFFFFFF)) {
                    return -1;
                }
            }
        }
        return first;
    }

    private static final int WHITE = 250;

    private static int white(int tone) {
        return tone >= 0 && (tone >> 16 & 0xFF) >= WHITE && (tone >> 8 & 0xFF) >= WHITE && (tone & 0xFF) >= WHITE ? tone : -1;
    }

    private static final int ALIKE = 2;

    private static int flat(PDShading shading) throws IOException {
        if (!(shading instanceof PDShadingType2 axial) || !(axial.getExtend() instanceof COSArray ends) || ends.size() < 2
                || !ends.getObject(0).equals(COSBoolean.TRUE) || !ends.getObject(1).equals(COSBoolean.TRUE)) {
            return -1;
        }
        int tone = -1;
        for (int i = 0; i <= 4; i++) {
            float[] rgb = shading.getColorSpace().toRGB(shading.evalFunction(i / 4f));
            int c = Math.round(rgb[0] * 255) << 16 | Math.round(rgb[1] * 255) << 8 | Math.round(rgb[2] * 255);
            if (tone >= 0 && !alike(tone, c)) {
                return -1;
            }
            tone = tone < 0 ? c : tone;
        }
        return tone;
    }

    private static boolean alike(int a, int b) {
        return Math.abs((a >> 16 & 0xFF) - (b >> 16 & 0xFF)) <= ALIKE && Math.abs((a >> 8 & 0xFF) - (b >> 8 & 0xFF)) <= ALIKE
                && Math.abs((a & 0xFF) - (b & 0xFF)) <= ALIKE;
    }

    private static int average(BufferedImage image) {
        if (image == null) {
            return -1;
        }
        long r = 0;
        long g = 0;
        long b = 0;
        int n = image.getWidth() * image.getHeight();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                r += (rgb >> 16) & 0xFF;
                g += (rgb >> 8) & 0xFF;
                b += rgb & 0xFF;
            }
        }
        return n == 0 ? -1 : (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }
}
