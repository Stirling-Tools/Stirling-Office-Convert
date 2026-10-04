package stirling.software.officeconvert.jpx;

import java.io.IOException;
import java.util.Locale;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageInputStream;

public final class JpxImageReaderSpi extends ImageReaderSpi {

    public JpxImageReaderSpi() {
        super("Stirling Tools", "1.0", new String[] {"jpeg2000", "JPEG2000", "jpeg 2000", "JPEG 2000"},
                new String[] {"jp2", "jpx", "jpf", "j2k", "j2c", "jpc"},
                new String[] {"image/jp2", "image/jpx", "image/j2c", "image/jpeg2000"}, JpxImageReader.class.getName(),
                new Class<?>[] {ImageInputStream.class}, null, false, null, null, null, null, false, null, null, null, null);
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!(source instanceof ImageInputStream in)) {
            return false;
        }
        byte[] head = new byte[12];
        in.mark();
        try {
            in.readFully(head);
        } catch (IOException e) {
            return false;
        } finally {
            in.reset();
        }
        boolean codestream = (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0x4F && (head[2] & 0xFF) == 0xFF
                && (head[3] & 0xFF) == 0x51;
        return codestream || Bytes.u32(head, 4) == 0x6A502020L && Bytes.u32(head, 0) == 12;
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new JpxImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "Stirling JPEG 2000 reader";
    }

    @Override
    public void onRegistration(ServiceRegistry registry, Class<?> category) {
        JpxImageIO.order(registry, this);
    }
}
