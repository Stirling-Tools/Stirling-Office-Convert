package stirling.software.officeconvert.topdf.io;

import org.apache.poi.openxml4j.util.ZipSecureFile;

public final class PoiLimits {

    private static volatile boolean applied;

    private PoiLimits() {}

    public static void apply() {
        if (applied) {
            return;
        }
        synchronized (PoiLimits.class) {
            if (applied) {
                return;
            }
            OfficeZip.Limits limits = OfficeZip.Limits.DEFAULT;
            ZipSecureFile.setMinInflateRatio(Math.max(ZipSecureFile.getMinInflateRatio(), limits.minInflateRatio()));
            ZipSecureFile.setMaxEntrySize(Math.min(ZipSecureFile.getMaxEntrySize(), limits.maxEntryBytes()));
            ZipSecureFile.setMaxFileCount(Math.max(ZipSecureFile.getMaxFileCount(), limits.maxEntries()));
            applied = true;
        }
    }
}
