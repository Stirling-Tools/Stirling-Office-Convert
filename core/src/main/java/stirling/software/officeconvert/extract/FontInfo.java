package stirling.software.officeconvert.extract;

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

    public static final FontInfo DEFAULT =
            new FontInfo("Times New Roman", false, false, true, false, false, "Times-Roman", false);
}
