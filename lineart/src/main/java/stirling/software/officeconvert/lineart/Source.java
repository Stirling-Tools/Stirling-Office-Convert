package stirling.software.officeconvert.lineart;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import stirling.software.officeconvert.extract.PdfFiles;

abstract class Source {

    interface Band {
        void accept(int y, BufferedImage rows) throws IOException;
    }

    private static final COSName[] CARRIED = {COSName.BITS_PER_COMPONENT, COSName.COLORSPACE, COSName.DECODE,
        COSName.INTENT};

    private static final Set<COSName> DCT = Set.of(COSName.DCT_DECODE, COSName.DCT_DECODE_ABBREVIATION);

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
        Source regions = new Regions(image, premultiplied);
        if (!premultiplied && filters != null && filters.size() == 1 && DCT.contains(filters.get(0))) {
            int components = jpegComponents(image);
            if (components > 0) {
                return new Jpeg(image, components, regions);
            }
        }
        return regions;
    }

    private static int jpegComponents(PDImageXObject image) {
        COSStream stream = image.getCOSObject();
        if (stream.getDictionaryObject(COSName.DECODE) != null
                || stream.getDictionaryObject(COSName.DECODE_PARMS, COSName.DP) != null
                || image.getBitsPerComponent() != 8) {
            return 0;
        }
        PDColorSpace colourSpace;
        try {
            colourSpace = image.getColorSpace();
        } catch (IOException e) {
            return 0;
        }
        return colourSpace instanceof PDDeviceRGB ? 3 : colourSpace instanceof PDDeviceGray ? 1 : 0;
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

    private static final class Jpeg extends Source {
        private final PDImageXObject image;
        private final int components;
        private final Source fallback;

        Jpeg(PDImageXObject image, int components, Source fallback) {
            super(image.getWidth(), image.getHeight());
            this.image = image;
            this.components = components;
            this.fallback = fallback;
        }

        @Override
        void bands(int rows, boolean keyed, Band band) throws IOException {
            ImageReader reader = keyed ? null : reader();
            if (reader == null) {
                fallback.bands(rows, keyed, band);
                return;
            }
            try {
                byte[] data;
                try (InputStream in = image.getCOSObject().createRawInputStream()) {
                    data = in.readAllBytes();
                }
                int skip = data.length > 0 && data[0] == 0x0A ? 1 : 0;
                try (ImageInputStream in = new MemoryCacheImageInputStream(
                        new ByteArrayInputStream(data, skip, data.length - skip))) {
                    reader.setInput(in);
                    int step = Math.min(rows, height);
                    int n = rows(step, 0);
                    BufferedImage first = null;
                    try {
                        if (matches(reader)) {
                            first = read(reader, 0, n);
                        }
                    } catch (IOException | RuntimeException e) {
                        first = null;
                    }
                    if (first == null) {
                        fallback.bands(rows, false, band);
                        return;
                    }
                    PdfFiles.stopIfInterrupted();
                    band.accept(0, first);
                    first = null;
                    for (int y = n; y < height; y += n) {
                        PdfFiles.stopIfInterrupted();
                        n = rows(step, y);
                        band.accept(y, read(reader, y, n));
                    }
                }
            } finally {
                reader.dispose();
            }
        }

        private boolean matches(ImageReader reader) throws IOException {
            if (reader.getNumImages(true) < 1 || reader.getWidth(0) != width || reader.getHeight(0) != height) {
                return false;
            }
            ImageTypeSpecifier raw = reader.getRawImageType(0);
            return raw != null && raw.getNumBands() == components;
        }

        private BufferedImage read(ImageReader reader, int y, int n) throws IOException {
            ImageReadParam param = reader.getDefaultReadParam();
            if (n < height) {
                param.setSourceRegion(new Rectangle(0, y, width, n));
            }
            BufferedImage rows = reader.read(0, param);
            if (rows.getWidth() != width || rows.getHeight() != n || rows.getRaster().getNumBands() != components) {
                throw new IOException("The JPEG decoded to an unexpected " + rows.getWidth() + "x" + rows.getHeight()
                        + " picture");
            }
            return rows;
        }

        private static ImageReader reader() {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("JPEG");
            while (readers.hasNext()) {
                ImageReader reader = readers.next();
                if (reader != null && reader.canReadRaster()) {
                    return reader;
                }
                if (reader != null) {
                    reader.dispose();
                }
            }
            return null;
        }
    }
}
