package stirling.software.officeconvert.topdf.testing;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

public final class HostileImagePlugin extends ImageReaderSpi implements AutoCloseable {

    private final AtomicInteger created = new AtomicInteger();

    private HostileImagePlugin() {
        super("nonet", "1", new String[] {"svg", "SVG", "wmf", "WMF"}, new String[] {"svg", "svgz", "wmf"},
                new String[] {"image/svg+xml", "image/x-wmf", "image/wmf"}, HostileImagePlugin.class.getName(),
                new Class<?>[] {ImageInputStream.class}, null, false, null, null, null, null, false, null, null, null,
                null);
    }

    public static HostileImagePlugin register() {
        HostileImagePlugin p = new HostileImagePlugin();
        IIORegistry.getDefaultInstance().registerServiceProvider(p, ImageReaderSpi.class);
        return p;
    }

    public int readersCreated() {
        return created.get();
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!(source instanceof ImageInputStream in)) {
            return false;
        }
        byte[] head = new byte[512];
        in.mark();
        int n;
        try {
            n = in.read(head);
        } finally {
            in.reset();
        }
        if (n >= 4 && (head[0] & 0xFF) == 0xD7 && (head[1] & 0xFF) == 0xCD && (head[2] & 0xFF) == 0xC6
                && (head[3] & 0xFF) == 0x9A) {
            return true;
        }
        String text = n <= 0 ? "" : new String(head, 0, n, StandardCharsets.ISO_8859_1);
        return text.toLowerCase(Locale.ROOT).contains("<svg");
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        created.incrementAndGet();
        return new ImageReader(this) {
            @Override
            public int getNumImages(boolean allowSearch) throws IOException {
                throw new IOException("the hostile plugin was used");
            }

            @Override
            public int getWidth(int imageIndex) throws IOException {
                throw new IOException("the hostile plugin was used");
            }

            @Override
            public int getHeight(int imageIndex) throws IOException {
                throw new IOException("the hostile plugin was used");
            }

            @Override
            public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
                throw new IOException("the hostile plugin was used");
            }

            @Override
            public IIOMetadata getStreamMetadata() throws IOException {
                throw new IOException("the hostile plugin was used");
            }

            @Override
            public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
                throw new IOException("the hostile plugin was used");
            }

            @Override
            public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
                throw new IOException("the hostile plugin was used");
            }
        };
    }

    @Override
    public String getDescription(Locale locale) {
        return "Stands in for a host SVG/WMF ImageIO plugin that could fetch or script";
    }

    public void assertNeverUsed() {
        if (created.get() != 0) {
            throw new AssertionError("A third-party ImageIO plugin was asked to decode a picture " + created.get()
                    + " time(s)");
        }
    }

    @Override
    public void close() {
        IIORegistry.getDefaultInstance().deregisterServiceProvider(this, ImageReaderSpi.class);
    }
}
