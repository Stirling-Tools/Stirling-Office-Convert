package stirling.software.officeconvert.sheet;

public record Conventions(DecimalMark decimals, DateOrder dates) {

    public enum DecimalMark {
        POINT,
        COMMA,
        UNKNOWN
    }

    public enum DateOrder {
        MDY,
        DMY,
        UNKNOWN
    }

    public static final Conventions UNKNOWN = new Conventions(DecimalMark.UNKNOWN, DateOrder.UNKNOWN);

    public Conventions or(Conventions fallback) {
        return new Conventions(
                decimals == DecimalMark.UNKNOWN ? fallback.decimals : decimals,
                dates == DateOrder.UNKNOWN ? fallback.dates : dates);
    }
}
