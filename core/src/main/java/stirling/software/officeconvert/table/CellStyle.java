package stirling.software.officeconvert.table;

public record CellStyle(
        boolean bold,
        boolean italic,
        float fontSize,
        Integer textRgb,
        Integer fillRgb,
        HorizontalAlignment horizontal,
        VerticalAlignment vertical,
        boolean borderTop,
        boolean borderBottom,
        boolean borderLeft,
        boolean borderRight) {

    public static final CellStyle PLAIN =
            new CellStyle(
                    false,
                    false,
                    0f,
                    null,
                    null,
                    HorizontalAlignment.GENERAL,
                    VerticalAlignment.TOP,
                    false,
                    false,
                    false,
                    false);

    public enum HorizontalAlignment {
        GENERAL,
        LEFT,
        CENTER,
        RIGHT
    }

    public enum VerticalAlignment {
        TOP,
        CENTER,
        BOTTOM
    }
}
