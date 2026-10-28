package stirling.software.officeconvert.lineart;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import stirling.software.officeconvert.extract.PdfFiles;

abstract class Source {

    interface Band {
        void accept(int y, BufferedImage rows) throws IOException;
    }

    private static final COSName[] CARRIED = {COSName.BITS_PER_COMPONENT, COSName.COLORSPACE, COSName.DECODE,
        COSName.INTENT};

    final int width;
    final int height;

    Source(int width, int height) {
        this.width = width;
        this.height = height;
    }

    abstract void bands(int rows, boolean keyed, Band band) throws IOException;

    static Source of(BufferedImage image) {
        return new Source(image.getWidth(), image.getHeight()) {
            @Override
            void bands(int rows, boolean keyed, Band band) throws IOException {
                band.accept(0, image);
            }
        };
    }

    static Source of(PDImageXObject image, PDResources resources, boolean premultiplied) {
        COSStream stream = image.getCOSObject();
        List<COSName> filters = filters(stream);
        if (!premultiplied && filters != null && Decoded.STREAMING.containsAll(filters)) {
            return new Streamed(image, resources);
        }
        return new Regions(image, premultiplied);
    }

    int rows(int rows, int y) {
        int n = Math.min(rows, height - y);
        return height - y - n == 1 ? n + 1 : n;
    }

    static final int BYTES_PER_PIXEL = 28;

    static final int CONTEXT_BYTES_PER_PIXEL = 8;

    static int bandRows(int width, int height, int context, long budgetBytes) {
        long perRow = (long) width * BYTES_PER_PIXEL;
        long fits = (budgetBytes - contextBytes(width, height, context)) / perRow;
        if (fits >= height) {
            return height;
        }
        return Math.clamp(fits - 1, Math.min(2, height), height);
    }

    static long bandBytes(int width, int height, int context, int rows) {
        long band = Math.min(height, rows + 1L);
        return band * width * BYTES_PER_PIXEL + contextBytes(width, height, context);
    }

    private static long contextBytes(int width, int height, int context) {
        return (long) Math.min(height, context) * width * CONTEXT_BYTES_PER_PIXEL;
    }

    static List<COSName> filters(COSStream stream) {
        COSBase f = stream.getDictionaryObject(COSName.FILTER);
        if (f == null) {
            return List.of();
        }
        if (f instanceof COSName name) {
            return List.of(name);
        }
        if (f instanceof COSArray array) {
            List<COSName> names = new ArrayList<>();
            for (COSBase b : array) {
                if (!(b.getCOSObject() instanceof COSName name)) {
                    return null;
                }
                names.add(name);
            }
            return names;
        }
        return null;
    }

    private static final class Regions extends Source {
        private final PDImageXObject image;
        private final boolean premultiplied;
        private boolean checked;

        Regions(PDImageXObject image, boolean premultiplied) {
            super(image.getWidth(), image.getHeight());
            this.image = image;
            this.premultiplied = premultiplied;
        }

        @Override
        void bands(int rows, boolean masked, Band band) throws IOException {
            boolean keyed = masked || premultiplied;
            if (!checked) {
                Decoded.check(image);
                PDImageXObject soft = premultiplied ? image.getSoftMask() : null;
                if (soft != null) {
                    Decoded.check(soft);
                }
                checked = true;
            }
            if (rows >= height) {
                band.accept(0, keyed ? image.getImage() : image.getOpaqueImage(null, 1));
                return;
            }
            for (int y = 0, n; y < height; y += n) {
                PdfFiles.stopIfInterrupted();
                n = rows(rows, y);
                Rectangle region = new Rectangle(0, y, width, n);
                band.accept(y, keyed ? image.getImage(region, 1) : image.getOpaqueImage(region, 1));
            }
        }
    }

    private static final class Streamed extends Source {
        private final PDImageXObject image;
        private final PDResources resources;
        private final int rowBytes;

        Streamed(PDImageXObject image, PDResources resources) {
            super(image.getWidth(), image.getHeight());
            this.image = image;
            this.resources = resources;
            this.rowBytes = Math.toIntExact(Decoded.rowBytes(image));
        }

        @Override
        void bands(int rows, boolean keyed, Band band) throws IOException {
            COSStream original = image.getCOSObject();
            int step = Math.min(rows, height);
            byte[] buffer = new byte[Math.multiplyExact(rowBytes, Math.min(step + 1, height))];
            try (InputStream in = Decoded.open(original, filters(original), (long) rowBytes * height)) {
                for (int y = 0, n; y < height; y += n) {
                    PdfFiles.stopIfInterrupted();
                    n = rows(step, y);
                    int read = in.readNBytes(buffer, 0, rowBytes * n);
                    if (read < rowBytes * n) {
                        Arrays.fill(buffer, read, rowBytes * n, (byte) 0);
                    }
                    if (y + n >= height) {
                        Decoded.drain(in);
                    }
                    band.accept(y, decode(original, buffer, n, keyed));
                }
            }
        }

        private BufferedImage decode(COSStream original, byte[] buffer, int rows, boolean keyed) throws IOException {
            try (COSStream part = new COSStream()) {
                part.setItem(COSName.TYPE, COSName.XOBJECT);
                part.setItem(COSName.SUBTYPE, COSName.IMAGE);
                part.setInt(COSName.WIDTH, width);
                part.setInt(COSName.HEIGHT, rows);
                for (COSName key : CARRIED) {
                    COSBase v = original.getItem(key);
                    if (v != null) {
                        part.setItem(key, v);
                    }
                }
                if (!part.containsKey(COSName.BITS_PER_COMPONENT)) {
                    part.setInt(COSName.BITS_PER_COMPONENT, image.getBitsPerComponent());
                }
                if (!part.containsKey(COSName.COLORSPACE)) {
                    part.setItem(COSName.COLORSPACE, image.getColorSpace());
                }
                if (keyed && original.getDictionaryObject(COSName.MASK) instanceof COSArray key) {
                    part.setItem(COSName.MASK, key);
                }
                try (OutputStream os = part.createOutputStream()) {
                    os.write(buffer, 0, rowBytes * rows);
                }
                PDImageXObject piece = new PDImageXObject(new PDStream(part), resources);
                return keyed ? piece.getImage() : piece.getOpaqueImage(null, 1);
            }
        }
    }
}
