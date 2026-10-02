package stirling.software.officeconvert.topdf.xls;

/** SpreadsheetML names for the codes the binary formats (BIFF5, BIFF8, BIFF12) share for fills, borders and
 * alignment. */
public final class StyleNames {

    private static final String[] PATTERNS = {"none", "solid", "mediumGray", "darkGray", "lightGray",
        "darkHorizontal", "darkVertical", "darkDown", "darkUp", "darkGrid", "darkTrellis", "lightHorizontal",
        "lightVertical", "lightDown", "lightUp", "lightGrid", "lightTrellis", "gray125", "gray0625"};

    private static final String[] BORDERS = {"none", "thin", "medium", "dashed", "dotted", "thick", "double", "hair",
        "mediumDashed", "dashDot", "mediumDashDot", "dashDotDot", "mediumDashDotDot", "slantDashDot"};

    private static final String[] HORIZONTAL = {"general", "left", "center", "right", "fill", "justify",
        "centerContinuous", "distributed"};

    private static final String[] VERTICAL = {"top", "center", "bottom", "justify", "distributed"};

    private StyleNames() {}

    public static String pattern(int code) {
        return code >= 0 && code < PATTERNS.length ? PATTERNS[code] : "none";
    }

    public static String border(int code) {
        return code >= 0 && code < BORDERS.length ? BORDERS[code] : "thin";
    }

    /** null for general, the default. */
    public static String horizontal(int code) {
        return code > 0 && code < HORIZONTAL.length ? HORIZONTAL[code] : null;
    }

    /** null for bottom, the default. */
    public static String vertical(int code) {
        return code != 2 && code >= 0 && code < VERTICAL.length ? VERTICAL[code] : null;
    }

    public static String underline(int code) {
        return switch (code) {
            case 1 -> "single";
            case 2 -> "double";
            case 0x21 -> "singleAccounting";
            case 0x22 -> "doubleAccounting";
            default -> null;
        };
    }
}
