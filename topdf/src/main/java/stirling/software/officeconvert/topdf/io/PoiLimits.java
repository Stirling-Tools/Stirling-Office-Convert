package stirling.software.officeconvert.topdf.io;

import org.apache.poi.openxml4j.util.ZipSecureFile;

final class PoiLimits {

    private PoiLimits() {}

    /** Raises POI's JVM-wide zip entry limit to {@code parts}. It is never lowered again: one large package lifts
     * the limit for every POI user in the JVM until it restarts, since restoring it would race other conversions. */
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
