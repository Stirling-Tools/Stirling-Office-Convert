package stirling.software.officeconvert.jpx;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

public final class JpxImageReader extends ImageReader {

    static final long MAX_INPUT = Integer.MAX_VALUE - 64;

    private interface Decoding<T> {
        T run() throws IOException;
    }

    private byte[] data;

    private int[] size;

    private BufferedImage full;

    public JpxImageReader(ImageReaderSpi spi) {
        super(spi);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        data = null;
        size = null;
        full = null;
    }

    @Override
    public int getNumImages(boolean allowSearch) {
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        check(imageIndex);
        return guarded(this::size)[0];
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        check(imageIndex);
        return guarded(this::size)[1];
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        check(imageIndex);
        return List.of(guarded(() -> ImageTypeSpecifier.createFromRenderedImage(decodeFull()))).iterator();
    }

    @Override
    public IIOMetadata getStreamMetadata() {
        return null;
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) {
        return null;
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        check(imageIndex);
        return guarded(() -> param == null ? decodeFull() : read(param));
    }

    private BufferedImage read(ImageReadParam param) throws IOException {
        int[] dims = size();
        Rectangle region = new Rectangle(0, 0, dims[0], dims[1]);
        if (param.getSourceRegion() != null) {
            region = region.intersection(param.getSourceRegion());
        }
        int sx = Math.max(1, param.getSourceXSubsampling());
        int sy = Math.max(1, param.getSourceYSubsampling());
        int ox = param.getSubsamplingXOffset();
        int oy = param.getSubsamplingYOffset();
        region.translate(ox, oy);
        region.width -= ox;
        region.height -= oy;
        if (region.isEmpty()) {
            throw new IIOException("The requested JPEG 2000 region is empty");
        }
        boolean whole = region.x == 0 && region.y == 0 && region.width == dims[0] && region.height == dims[1];
        if (whole && sx == 1 && sy == 1) {
            return decodeFull();
        }
        int reduce = 31 - Integer.numberOfLeadingZeros(Math.min(sx, sy));
        JpxOptions options = JpxOptions.defaults().withReduce(reduce).withRegion(whole ? null : region);
        JpxImage jpx = JpxDecoder.decode(bytes(), options);
        BufferedImage reduced = jpx.toBufferedImage();
        return Subsample.pick(reduced, region, sx, sy, jpx.reduce());
    }

    private void check(int imageIndex) {
        if (imageIndex != 0) {
            throw new IndexOutOfBoundsException("A JPEG 2000 file holds one image");
        }
        if (!(getInput() instanceof ImageInputStream)) {
            throw new IllegalStateException("No JPEG 2000 input set");
        }
    }

    private int[] size() throws IOException {
        if (size == null) {
            size = JpxDecoder.size(bytes());
        }
        return size;
    }

    private BufferedImage decodeFull() throws IOException {
        size();
        if (full == null) {
            full = JpxDecoder.decode(bytes()).toBufferedImage();
        }
        return full;
    }

    private static <T> T guarded(Decoding<T> decoding) throws IOException {
        try {
            return decoding.run();
        } catch (RuntimeException | OutOfMemoryError e) {
            throw new IIOException("The JPEG 2000 image could not be decoded", e);
        }
    }

    private byte[] bytes() throws IOException {
        if (data != null) {
            return data;
        }
        ImageInputStream in = (ImageInputStream) getInput();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + (long) n > MAX_INPUT) {
                throw new IIOException("The JPEG 2000 image is too large");
            }
            out.write(buf, 0, n);
        }
        data = out.toByteArray();
        return data;
    }
}
