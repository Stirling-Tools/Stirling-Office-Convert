package stirling.software.officeconvert.sheet;

import stirling.software.officeconvert.sheet.Conventions.DateOrder;
import stirling.software.officeconvert.sheet.Conventions.DecimalMark;

public final class ConventionEvidence {

    private static final int CONTRARY_DECIMALS = 2;

    private int point;
    private int comma;
    private int monthFirst;
    private int dayFirst;
    private int dollars;
    private int poundsEuros;

    public void add(String token) {
        String t = trimPunctuation(token);
        if (t.isEmpty()) {
            return;
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '$') {
                dollars++;
            } else if (c == '£' || c == '€') {
                poundsEuros++;
            }
        }
        switch (NumberReader.evidence(t)) {
            case POINT -> point++;
            case COMMA -> comma++;
            default -> { }
        }
        switch (DateReader.evidence(t)) {
            case MDY -> monthFirst++;
            case DMY -> dayFirst++;
            default -> { }
        }
    }

    public Conventions conventions() {
        DecimalMark decimals = comma > point ? DecimalMark.COMMA : point > comma ? DecimalMark.POINT : DecimalMark.UNKNOWN;
        DateOrder dates = dayFirst > monthFirst ? DateOrder.DMY : monthFirst > dayFirst ? DateOrder.MDY : DateOrder.UNKNOWN;
        if (dates == DateOrder.UNKNOWN && dollars != poundsEuros) {
            dates = dollars > poundsEuros ? DateOrder.MDY : DateOrder.DMY;
        }
        return new Conventions(decimals, dates);
    }

    public Conventions conventions(Conventions fallback) {
        Conventions own = conventions();
        DecimalMark known = fallback.decimals();
        if (known != DecimalMark.UNKNOWN && own.decimals() != DecimalMark.UNKNOWN && own.decimals() != known
                && Math.max(point, comma) < CONTRARY_DECIMALS) {
            own = new Conventions(known, own.dates());
        }
        return own.or(fallback);
    }

    private static String trimPunctuation(String token) {
        if (token == null) {
            return "";
        }
        int end = token.length();
        while (end > 0 && ",;:.!?\"'".indexOf(token.charAt(end - 1)) >= 0) {
            end--;
        }
        return token.substring(0, end).strip();
    }
}
