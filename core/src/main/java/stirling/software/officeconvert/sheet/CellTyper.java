package stirling.software.officeconvert.sheet;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class CellTyper {

    private static final Pattern CODE =
            Pattern.compile("0[0-9]+|[0-9]+(\\.[0-9]+){2,}|[0-9]+(-[0-9]+)+|[0-9]{12,}");

    private static final float CODE_SHARE = 0.2f;

    private static final float WORD_SHARE = 0.6f;

    private static final Pattern NOTE_MARK = Pattern.compile("\\([0-9]{1,2}\\)");

    private CellTyper() {}

    public static CellValue type(String text, Conventions conventions) {
        if (text == null || text.isEmpty()) {
            return CellValue.text("");
        }
        if (!hasDigit(text) || text.indexOf('\n') >= 0 || NOTE_MARK.matcher(text.strip()).matches()) {
            return CellValue.text(text);
        }
        return typed(text, conventions);
    }

    private static CellValue typed(String text, Conventions conventions) {
        CellValue n = NumberReader.read(text, conventions.decimals());
        if (n != null) {
            return n;
        }
        CellValue d = DateReader.read(text, conventions.dates());
        return d != null ? d : CellValue.text(text);
    }

    public static List<CellValue> column(List<String> texts, Conventions fallback) {
        ConventionEvidence evidence = new ConventionEvidence();
        int filled = 0;
        int codes = 0;
        for (String t : texts) {
            if (t == null || t.isBlank()) {
                continue;
            }
            filled++;
            evidence.add(t);
            if (CODE.matcher(NumberReader.normalise(t)).matches()) {
                codes++;
            }
        }
        Conventions conventions = evidence.conventions(fallback);
        boolean codeColumn = codes > 0 && codes >= CODE_SHARE * filled;
        List<CellValue> out = new ArrayList<>(texts.size());
        int words = 0;
        for (String t : texts) {
            words += t != null && !t.isBlank() && type(t, conventions).isText() ? 1 : 0;
        }
        boolean wordColumn = words >= WORD_SHARE * filled;
        boolean amounts = false;
        for (String t : texts) {
            CellValue v = type(t, conventions);
            boolean bare = v.kind() == CellValue.Kind.NUMBER && v.format().kind() == NumberFormat.Kind.GENERAL;
            if (bare && (codeColumn || wordColumn)) {
                v = CellValue.text(t);
            }
            amounts |= v.kind() == CellValue.Kind.NUMBER;
            out.add(v);
        }
        for (int i = 0; amounts && i < out.size(); i++) {
            String t = texts.get(i);
            if (t != null && NOTE_MARK.matcher(t.strip()).matches()) {
                out.set(i, typed(t.strip(), conventions));
            }
        }
        return out;
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                return true;
            }
        }
        return false;
    }
}
