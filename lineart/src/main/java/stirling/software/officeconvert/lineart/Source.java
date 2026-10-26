package stirling.software.officeconvert.lineart;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import stirling.software.officeconvert.extract.PdfFiles;

abstract class Source {

    interface Band {
        void accept(int y, BufferedImage rows) throws IOException;
    }

    private static final Set<COSName> STREAMING = Set.of(COSName.FLATE_DECODE, COSName.FLATE_DECODE_ABBREVIATION,
            COSName.LZW_DECODE, COSName.LZW_DECODE_ABBREVIATION, COSName.RUN_LENGTH_DECODE,
            COSName.RUN_LENGTH_DECODE_ABBREVIATION, COSName.ASCII_HEX_DECODE, COSName.ASCII_HEX_DECODE_ABBREVIATION,
            COSName.ASCII85_DECODE, COSName.ASCII85_DECODE_ABBREVIATION, COSName.CCITTFAX_DECODE,
            COSName.CCITTFAX_DECODE_ABBREVIATION, COSName.JBIG2_DECODE);

    private static final Set<COSName> PIPED = Set.of(COSName.FLATE_DECODE, COSName.FLATE_DECODE_ABBREVIATION,
            COSName.LZW_DECODE, COSName.LZW_DECODE_ABBREVIATION, COSName.RUN_LENGTH_DECODE,
            COSName.RUN_LENGTH_DECODE_ABBREVIATION, COSName.ASCII_HEX_DECODE, COSName.ASCII_HEX_DECODE_ABBREVIATION,
            COSName.ASCII85_DECODE, COSName.ASCII85_DECODE_ABBREVIATION);

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
        if (!premultiplied && filters != null && STREAMING.containsAll(filters)) {
            return new Streamed(image, resources);
        }
        return new Regions(image, premultiplied);
    }

    int rows(int rows, int y) {
        int n = Math.min(rows, height - y);
        return height - y - n == 1 ? n + 1 : n;
    }

    static int bandRows(int width, int height, long budgetPixels) {
        if ((long) width * height <= budgetPixels) {
            return height;
        }
        return Math.clamp(budgetPixels / width, Math.min(2, height), height);
    }

    private static List<COSName> filters(COSStream stream) {
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

        Regions(PDImageXObject image, boolean premultiplied) {
            super(image.getWidth(), image.getHeight());
            this.image = image;
            this.premultiplied = premultiplied;
        }

        @Override
        void bands(int rows, boolean masked, Band band) throws IOException {
            boolean keyed = masked || premultiplied;
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
            int components;
            try {
                components = image.getColorSpace().getNumberOfComponents();
            } catch (IOException e) {
                components = 1;
            }
            this.rowBytes = (int) (((long) width * components * image.getBitsPerComponent() + 7) / 8);
        }

        @Override
        void bands(int rows, boolean keyed, Band band) throws IOException {
            if (rows >= height) {
                band.accept(0, keyed ? image.getImage() : image.getOpaqueImage(null, 1));
                return;
            }
            COSStream original = image.getCOSObject();
            byte[] buffer = new byte[Math.multiplyExact(rowBytes, rows + 1)];
            try (InputStream in = decoded(original)) {
                for (int y = 0, n; y < height; y += n) {
                    PdfFiles.stopIfInterrupted();
                    n = rows(rows, y);
                    int read = in.readNBytes(buffer, 0, rowBytes * n);
                    if (read < rowBytes * n) {
                        Arrays.fill(buffer, read, rowBytes * n, (byte) 0);
                    }
                    band.accept(y, decode(original, buffer, n, keyed));
                }
            }
        }

        private static InputStream decoded(COSStream stream) throws IOException {
            List<COSName> filters = filters(stream);
            if (filters == null || filters.size() != 1 || !PIPED.contains(filters.get(0))) {
                return filters != null && filters.isEmpty() ? stream.createRawInputStream() : stream.createInputStream();
            }
            Filter filter = FilterFactory.INSTANCE.getFilter(filters.get(0));
            PipedInputStream in = new PipedInputStream(1 << 16);
            PipedOutputStream out = new PipedOutputStream(in);
            Thread writer = Thread.ofVirtual().start(() -> {
                try (InputStream raw = stream.createRawInputStream(); OutputStream o = out) {
                    filter.decode(raw, o, stream, 0);
                } catch (IOException e) {
                    closeQuietly(in);
                }
            });
            return new FilterInputStream(in) {
                @Override
                public void close() throws IOException {
                    super.close();
                    try {
                        writer.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
        }

        private static void closeQuietly(InputStream in) {
            try {
                in.close();
            } catch (IOException e) {
                Thread.currentThread().interrupt();
            }
        }

        private BufferedImage decode(COSStream original, byte[] buffer, int rows, boolean keyed) throws IOException {
            COSStream part = new COSStream();
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
