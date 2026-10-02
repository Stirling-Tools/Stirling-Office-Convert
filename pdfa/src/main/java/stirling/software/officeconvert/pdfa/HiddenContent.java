package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;

final class HiddenContent {

    private static final Set<String> PAINT = Set.of("f", "F", "f*", "B", "B*", "b", "b*", "S", "s");

    private static final Set<String> DROP = Set.of("sh", "Do", "BI", "MP", "DP", "BMC", "BDC", "EMC");

    private record State(COSDictionary font, float size, float charSpacing, float wordSpacing) {}

    private final COSDictionary resources;

    private final Map<COSDictionary, Optional<PDFont>> fonts = new IdentityHashMap<>();

    private final Deque<State> saved = new ArrayDeque<>();

    private State state = new State(null, 0, 0, 0);

    HiddenContent(COSDictionary resources) {
        this.resources = resources;
    }

    void track(String name, List<Object> operation) {
        switch (name) {
            case "q" -> {
                if (saved.size() < 4096) {
                    saved.push(state);
                }
            }
            case "Q" -> {
                if (!saved.isEmpty()) {
                    state = saved.pop();
                }
            }
            case "Tf" -> {
                if (operation.size() == 3 && operation.get(0) instanceof COSName f
                        && operation.get(1) instanceof COSNumber size) {
                    state = new State(FontUsage.font(resources, f), size.floatValue(), state.charSpacing(),
                            state.wordSpacing());
                }
            }
            case "Tc" -> state = new State(state.font(), state.size(), number(operation, 0, state.charSpacing()),
                    state.wordSpacing());
            case "Tw" -> state = new State(state.font(), state.size(), state.charSpacing(),
                    number(operation, 0, state.wordSpacing()));
            case "\"" -> state = new State(state.font(), state.size(), number(operation, 1, state.charSpacing()),
                    number(operation, 0, state.wordSpacing()));
            default -> {
            }
        }
    }

    List<Object> replace(String name, List<Object> operation) {
        if (PAINT.contains(name)) {
            return List.of(Operator.getOperator("n"));
        }
        if (DROP.contains(name)) {
            return List.of();
        }
        return switch (name) {
            case "Tj" -> advance(List.of(), operation.size() == 2 ? operation.get(0) : null);
            case "'" -> advance(List.of(Operator.getOperator("T*")), operation.size() == 2 ? operation.get(0) : null);
            case "\"" -> operation.size() == 4 ? advance(List.of(operation.get(0), Operator.getOperator("Tw"),
                    operation.get(1), Operator.getOperator("Tc"), Operator.getOperator("T*")), operation.get(2))
                    : List.of();
            case "TJ" -> advance(List.of(), operation.size() == 2 ? operation.get(0) : null);
            default -> operation;
        };
    }

    private List<Object> advance(List<Object> before, Object shown) {
        List<Object> out = new ArrayList<>(before);
        PDFont font = font();
        if (font == null || state.size() == 0 || shown == null) {
            return out;
        }
        double thousandths = 0;
        if (shown instanceof COSString s) {
            thousandths = width(font, s.getBytes());
        } else if (shown instanceof COSArray a) {
            for (int i = 0; i < a.size(); i++) {
                COSBase b = a.getObject(i);
                if (b instanceof COSString s) {
                    thousandths += width(font, s.getBytes());
                } else if (b instanceof COSNumber n) {
                    thousandths -= n.floatValue();
                }
            }
        }
        COSArray move = new COSArray();
        move.add(new COSFloat((float) -thousandths));
        out.add(move);
        out.add(Operator.getOperator("TJ"));
        return out;
    }

    private double width(PDFont font, byte[] bytes) {
        double total = 0;
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        try {
            while (in.available() > 0) {
                int before = in.available();
                int code = font.readCode(in);
                double w = font.getDisplacement(code).getX() * 1000;
                double space = before - in.available() == 1 && code == 32 ? state.wordSpacing() : 0;
                total += w + (state.charSpacing() + space) * 1000 / state.size();
            }
        } catch (IOException | RuntimeException e) {
            return total;
        }
        return total;
    }

    private PDFont font() {
        COSDictionary d = state.font();
        if (d == null) {
            return null;
        }
        return fonts.computeIfAbsent(d, k -> {
            try {
                return Optional.of(PDFontFactory.createFont(k));
            } catch (IOException | RuntimeException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    private static float number(List<Object> operation, int i, float fallback) {
        return i < operation.size() - 1 && operation.get(i) instanceof COSNumber n ? n.floatValue() : fallback;
    }
}
