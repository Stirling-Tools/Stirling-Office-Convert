package stirling.software.officeconvert.topdf.font;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.Path;
import java.util.List;

final class FontEntry {

    private final Path file;

    private final byte[] data;

    private final int index;

    private final long offset;

    private final String postScriptName;

    private final String family;

    private final String subfamily;

    private final String fullName;

    private final List<String> legacyFamilies;

    private final List<String> typographicFamilies;

    private final int weight;

    private final boolean bold;

    private final boolean italic;

    private final boolean symbolCmap;

    private final boolean noSubsetting;

    private final String unusable;

    private volatile int[] coverage;

    private SoftReference<FontProgram> program;

    private IOException broken;

    FontEntry(Path file, byte[] data, int index, long offset, String postScriptName, String family, String subfamily,
            String fullName, List<String> legacyFamilies, List<String> typographicFamilies, int weight, boolean bold,
            boolean italic, boolean symbolCmap, boolean noSubsetting, String unusable) {
        this.file = file;
        this.data = data;
        this.index = index;
        this.offset = offset;
        this.postScriptName = postScriptName;
        this.family = family;
        this.subfamily = subfamily == null ? "Regular" : subfamily;
        this.fullName = fullName == null ? family : fullName;
        this.legacyFamilies = legacyFamilies;
        this.typographicFamilies = typographicFamilies;
        this.weight = weight;
        this.bold = bold;
        this.italic = italic;
        this.symbolCmap = symbolCmap;
        this.noSubsetting = noSubsetting;
        this.unusable = unusable;
    }

    Path file() {
        return file;
    }

    byte[] data() {
        return data;
    }

    int index() {
        return index;
    }

    long offset() {
        return offset;
    }

    String postScriptName() {
        return postScriptName;
    }

    String family() {
        return family;
    }

    String subfamily() {
        return subfamily;
    }

    String fullName() {
        return fullName;
    }

    List<String> legacyFamilies() {
        return legacyFamilies;
    }

    List<String> typographicFamilies() {
        return typographicFamilies;
    }

    int weight() {
        return weight;
    }

    boolean bold() {
        return bold || weight >= 600;
    }

    boolean italic() {
        return italic;
    }

    boolean symbolCmap() {
        return symbolCmap;
    }

    boolean noSubsetting() {
        return noSubsetting;
    }

    String unusable() {
        return unusable;
    }

    boolean usable() {
        synchronized (this) {
            return unusable == null && broken == null;
        }
    }

    boolean mayCover(int codePoint) {
        int[] ranges = coverage;
        if (ranges == null) {
            ranges = FontScanner.coverage(this);
            coverage = ranges;
        }
        int lo = 0;
        int hi = ranges.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (ranges[2 * mid + 1] < codePoint) {
                lo = mid + 1;
            } else if (ranges[2 * mid] > codePoint) {
                hi = mid - 1;
            } else {
                return true;
            }
        }
        return false;
    }

    synchronized FontProgram program() throws IOException {
        if (broken != null) {
            throw broken;
        }
        FontProgram p = program == null ? null : program.get();
        if (p == null) {
            try {
                p = FontProgram.load(this);
            } catch (IOException | RuntimeException e) {
                broken = new IOException("The font " + describe() + " could not be read: " + e.getMessage(), e);
                throw broken;
            }
            program = new SoftReference<>(p);
        }
        return p;
    }

    String describe() {
        String where = file != null ? file.getFileName().toString() : "embedded in the document";
        return family + " " + subfamily + " (" + where + ")";
    }

    @Override
    public String toString() {
        return describe();
    }
}
