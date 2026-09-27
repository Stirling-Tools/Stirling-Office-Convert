package stirling.software.officeconvert.rtf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.extract.FontNames;

final class RtfTables {

    private final Map<String, Integer> fonts = new HashMap<>();
    private final List<String> fontOrder = new ArrayList<>();
    private final Map<Integer, Integer> colours = new HashMap<>();
    private final List<Integer> colourOrder = new ArrayList<>();

    int font(String name) {
        return fonts.computeIfAbsent(name, n -> {
            fontOrder.add(n);
            return fontOrder.size() - 1;
        });
    }

    int colour(int rgb) {
        return colours.computeIfAbsent(rgb & 0xFFFFFF, c -> {
            colourOrder.add(c);
            return colourOrder.size();
        });
    }

    void fontTable(StringBuilder sb) {
        sb.append("{\\fonttbl");
        for (int i = 0; i < fontOrder.size(); i++) {
            String f = fontOrder.get(i);
            boolean symbol = FontNames.isSymbolFamily(f);
            boolean mono = FontNames.looksMono(f);
            String family = symbol ? "\\ftech" : mono ? "\\fmodern" : FontNames.looksSerif(f) ? "\\froman" : "\\fswiss";
            sb.append("{\\f").append(i).append(family).append("\\fcharset").append(symbol ? 2 : 0).append("\\fprq")
                    .append(mono ? 1 : 2).append(' ');
            RtfText.text(sb, f);
            sb.append(";}");
        }
        sb.append('}');
    }

    void colourTable(StringBuilder sb) {
        sb.append("{\\colortbl;");
        for (int c : colourOrder) {
            sb.append("\\red").append(c >> 16 & 0xFF).append("\\green").append(c >> 8 & 0xFF).append("\\blue").append(c & 0xFF)
                    .append(';');
        }
        sb.append('}');
    }
}
