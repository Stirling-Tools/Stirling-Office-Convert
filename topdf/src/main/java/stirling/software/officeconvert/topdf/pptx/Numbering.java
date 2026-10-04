package stirling.software.officeconvert.topdf.pptx;

import org.apache.poi.sl.usermodel.AutoNumberingScheme;

final class Numbering {

    private static final int LEVELS = 10;

    private final int[] counts = new int[LEVELS];

    private final AutoNumberingScheme[] schemes = new AutoNumberingScheme[LEVELS];

    int next(int level, AutoNumberingScheme scheme, int startAt) {
        int l = Math.max(0, Math.min(LEVELS - 1, level));
        deeper(l);
        if (schemes[l] != scheme) {
            schemes[l] = scheme;
            counts[l] = startAt;
        } else {
            counts[l]++;
        }
        return counts[l];
    }

    void plain(int level) {
        int l = Math.max(0, Math.min(LEVELS - 1, level));
        deeper(l);
        schemes[l] = null;
        counts[l] = 0;
    }

    private void deeper(int level) {
        for (int i = level + 1; i < LEVELS; i++) {
            schemes[i] = null;
            counts[i] = 0;
        }
    }
}
