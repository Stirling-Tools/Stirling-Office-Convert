package stirling.software.officeconvert.topdf.uof;

import java.util.Locale;

final class UofUnits {

    private final double twipsPerUnit;

    private UofUnits(double twipsPerUnit) {
        this.twipsPerUnit = twipsPerUnit;
    }

    static UofUnits of(String unit) {
        String u = unit == null ? "" : unit.strip().toLowerCase(Locale.ROOT);
        return new UofUnits(switch (u) {
            case "inch", "in" -> 1440;
            case "cm" -> 1440 / 2.54;
            case "mm" -> 1440 / 25.4;
            case "pica", "pc" -> 240;
            case "emu" -> 1440 / 914400.0;
            default -> 20;
        });
    }

    long twips(String value) {
        double v = Uof.number(value);
        if (Double.isNaN(v) || Math.abs(v) > 1e7) {
            return Long.MIN_VALUE;
        }
        String t = value.strip().toLowerCase(Locale.ROOT);
        double k = twipsPerUnit;
        if (t.endsWith("cm")) {
            k = 1440 / 2.54;
        } else if (t.endsWith("mm")) {
            k = 1440 / 25.4;
        } else if (t.endsWith("in") || t.endsWith("inch")) {
            k = 1440;
        } else if (t.endsWith("pt")) {
            k = 20;
        }
        return Math.round(v * k);
    }

    static boolean known(long twips) {
        return twips != Long.MIN_VALUE;
    }
}
