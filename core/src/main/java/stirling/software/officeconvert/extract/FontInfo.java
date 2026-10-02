package stirling.software.officeconvert.extract;

import java.util.Objects;

public record FontInfo(
        String family,
        boolean bold,
        boolean italic,
        boolean serif,
        boolean mono,
        boolean symbolic,
        String postScriptName,
        boolean substituted,
        boolean smallCaps,
        boolean icons,
        boolean exact) {

    public FontInfo(String family, boolean bold, boolean italic, boolean serif, boolean mono, boolean symbolic,
            String postScriptName, boolean substituted) {
        this(family, bold, italic, serif, mono, symbolic, postScriptName, substituted, false, false, false);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof FontInfo f && bold == f.bold && italic == f.italic && serif == f.serif
                && mono == f.mono && symbolic == f.symbolic && substituted == f.substituted && smallCaps == f.smallCaps
                && icons == f.icons && exact == f.exact && Objects.equals(family, f.family)
                && Objects.equals(postScriptName, f.postScriptName);
    }

    @Override
    public int hashCode() {
        int h = Objects.hashCode(family);
        h = h * 31 + Boolean.hashCode(bold);
        h = h * 31 + Boolean.hashCode(italic);
        h = h * 31 + Boolean.hashCode(serif);
        h = h * 31 + Boolean.hashCode(mono);
        h = h * 31 + Boolean.hashCode(symbolic);
        h = h * 31 + Objects.hashCode(postScriptName);
        h = h * 31 + Boolean.hashCode(substituted);
        h = h * 31 + Boolean.hashCode(smallCaps);
        h = h * 31 + Boolean.hashCode(icons);
        return h * 31 + Boolean.hashCode(exact);
    }

    public static final FontInfo DEFAULT =
            new FontInfo("Times New Roman", false, false, true, false, false, "Times-Roman", false);
}
