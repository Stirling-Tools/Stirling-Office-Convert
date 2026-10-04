package stirling.software.officeconvert.topdf.font;

import java.util.Objects;

public record FontRun(FontFace face, int start, int end, String text) {

    public FontRun {
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(text, "text");
        if (start < 0 || end < start || end - start != text.length()) {
            throw new IllegalArgumentException("Bad run bounds " + start + "-" + end + " for " + text.length() + " chars");
        }
    }
}
