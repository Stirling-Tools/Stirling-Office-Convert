package stirling.software.officeconvert.jpx;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;

public final class JpxImageIO {

    public static final String READER_PROPERTY = "stirling.officeconvert.jpxReader";

    private JpxImageIO() {}

    public static synchronized void install() {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        ImageReaderSpi ours = registry.getServiceProviderByClass(JpxImageReaderSpi.class);
        if (ours == null) {
            ours = new JpxImageReaderSpi();
            registry.registerServiceProvider(ours, ImageReaderSpi.class);
        }
        order(registry, ours);
    }

    public static boolean preferBuiltIn() {
        return !"imageio".equalsIgnoreCase(System.getProperty(READER_PROPERTY, "builtin").trim());
    }

    static void order(ServiceRegistry registry, ImageReaderSpi ours) {
        boolean builtIn = preferBuiltIn();
        List<ImageReaderSpi> others = new ArrayList<>();
        Iterator<ImageReaderSpi> it = registry.getServiceProviders(ImageReaderSpi.class, false);
        while (it.hasNext()) {
            ImageReaderSpi spi = it.next();
            if (spi != ours && readsJpeg2000(spi)) {
                others.add(spi);
            }
        }
        for (ImageReaderSpi other : others) {
            if (builtIn) {
                registry.setOrdering(ImageReaderSpi.class, ours, other);
            } else {
                registry.setOrdering(ImageReaderSpi.class, other, ours);
            }
        }
    }

    private static boolean readsJpeg2000(ImageReaderSpi spi) {
        String[] names = spi.getFormatNames();
        if (names == null) {
            return false;
        }
        for (String n : names) {
            if (n.toLowerCase(Locale.ROOT).replace(" ", "").equals("jpeg2000")) {
                return true;
            }
        }
        return false;
    }
}
