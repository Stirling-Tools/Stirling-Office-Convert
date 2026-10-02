package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;
import java.util.List;

record CellText(Kind kind, List<TextRun> runs, Color color, boolean general, double number) {

    enum Kind {
        TEXT,
        NUMBER,
        BOOLEAN,
        ERROR
    }

    String plain() {
        if (runs.size() == 1) {
            return runs.get(0).text();
        }
        StringBuilder b = new StringBuilder();
        for (TextRun r : runs) {
            b.append(r.text());
        }
        return b.toString();
    }

    boolean isEmpty() {
        for (TextRun r : runs) {
            if (!r.text().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    boolean numeric() {
        return kind == Kind.NUMBER;
    }

    CellText withText(String text) {
        FontSpec f = runs.get(0).font();
        return new CellText(kind, List.of(new TextRun(text, f)), color, general, number);
    }
}
