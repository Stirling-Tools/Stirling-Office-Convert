package stirling.software.officeconvert.topdf.io;

import org.apache.poi.openxml4j.util.ZipSecureFile;

final class PoiLimits {

    private PoiLimits() {}

    static void allow(int parts) {
        if (parts <= ZipSecureFile.getMaxFileCount()) {
            return;
        }
        synchronized (PoiLimits.class) {
            if (parts > ZipSecureFile.getMaxFileCount()) {
                ZipSecureFile.setMaxFileCount(parts);
            }
        }
    }
}
