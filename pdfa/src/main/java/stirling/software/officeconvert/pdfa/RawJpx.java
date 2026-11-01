package stirling.software.officeconvert.pdfa;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import stirling.software.officeconvert.jpx.JpxImage;
import stirling.software.officeconvert.jpx.JpxRaster;

final class RawJpx {

    private final JpxRaster raster;

    private final COSBase colourSpace;

    private final int[] colours;

    private final int alpha;

    private final boolean indices;

    private RawJpx(JpxRaster raster, COSBase colourSpace, int[] colours, int alpha, boolean indices) {
        this.raster = raster;
        this.colourSpace = colourSpace;
        this.colours = colours;
        this.alpha = alpha;
        this.indices = indices;
    }

    static RawJpx of(JpxImage jpx, COSBase colourSpace, int smaskInData, BufferedImage composed) {
        int n = JpxImages.components(colourSpace);
        JpxRaster r = jpx.raster();
        if (n < 1 || r.components() < n) {
            return null;
        }
        boolean indexed = colourSpace instanceof COSArray a && a.size() > 0
                && COSName.INDEXED.equals(a.getObject(0)) || COSName.INDEXED.equals(colourSpace);
        if (jpx.palette() && !indexed) {
            return null;
        }
        int alpha = alphaComponent(jpx, n, smaskInData);
        if (!indexed && !special(colourSpace) && composed != null
                && composed.getColorModel().getNumColorComponents() == n
                && composed.getColorModel().hasAlpha() == (alpha >= 0)) {
            return null;
        }
        int[] colours = new int[n];
        for (int c = 0, i = 0; i < n; c++) {
            if (c != alpha) {
                colours[i++] = c;
            }
        }
        return new RawJpx(r, colourSpace, colours, alpha, indexed);
    }

    private static boolean special(COSBase colourSpace) {
        if (colourSpace instanceof COSArray a && a.size() > 0 && a.getObject(0) instanceof COSName family) {
            return COSName.DEVICEN.equals(family) || COSName.SEPARATION.equals(family);
        }
        return false;
    }

    private static int alphaComponent(JpxImage jpx, int n, int smaskInData) {
        int[][] defs = jpx.channelDefinitions();
        if (defs != null && !jpx.palette()) {
            for (int[] d : defs) {
                if ((d[1] == 1 || d[1] == 2) && d[0] < jpx.raster().components()) {
                    return jpx.raster().components() > n ? d[0] : -1;
                }
            }
        }
        return smaskInData != 0 && jpx.raster().components() > n ? n : -1;
    }

    COSStream colours(PDDocument doc) throws IOException {
        int w = raster.width();
        int h = raster.height();
        COSStream s = doc.getDocument().createCOSStream();
        byte[] row = new byte[w * colours.length];
        try (OutputStream out = s.createOutputStream(COSName.FLATE_DECODE)) {
            for (int y = 0; y < h; y++) {
                for (int i = 0; i < colours.length; i++) {
                    int c = colours[i];
                    int depth = raster.depth(c);
                    for (int x = 0; x < w; x++) {
                        int v = raster.imageSample(c, x, y);
                        row[x * colours.length + i] = (byte) (indices ? Math.min(v, 255) : scale(v, depth));
                    }
                }
                out.write(row);
            }
        }
        s.setItem(COSName.TYPE, COSName.XOBJECT);
        s.setItem(COSName.SUBTYPE, COSName.IMAGE);
        s.setInt(COSName.WIDTH, w);
        s.setInt(COSName.HEIGHT, h);
        s.setInt(COSName.BITS_PER_COMPONENT, 8);
        s.setItem(COSName.COLORSPACE, colourSpace);
        return s;
    }

    PDImageXObject alpha(PDDocument doc) throws IOException {
        if (alpha < 0) {
            return null;
        }
        int w = raster.width();
        int h = raster.height();
        int depth = raster.depth(alpha);
        COSStream s = doc.getDocument().createCOSStream();
        byte[] row = new byte[w];
        try (OutputStream out = s.createOutputStream(COSName.FLATE_DECODE)) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    row[x] = (byte) scale(raster.imageSample(alpha, x, y), depth);
                }
                out.write(row);
            }
        }
        s.setItem(COSName.TYPE, COSName.XOBJECT);
        s.setItem(COSName.SUBTYPE, COSName.IMAGE);
        s.setInt(COSName.WIDTH, w);
        s.setInt(COSName.HEIGHT, h);
        s.setInt(COSName.BITS_PER_COMPONENT, 8);
        s.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
        return new PDImageXObject(new PDStream(s), null);
    }

    private static int scale(int v, int depth) {
        if (depth == 8) {
            return v;
        }
        long max = (1L << depth) - 1;
        return (int) Math.min(255, (v * 510L + max) / (2 * max));
    }
}
