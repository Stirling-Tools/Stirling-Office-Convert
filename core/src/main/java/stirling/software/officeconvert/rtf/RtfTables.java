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
            sb.append("{\\f").append(i).append(family).append("\\fcharset").append(symbol ? 2 : charset(f)).append("\\fprq")
                    .append(mono ? 1 : 2).append(' ');
            RtfText.text(sb, f);
            sb.append(";}");
        }
        sb.append('}');
    }

    private static int charset(String font) {
        return switch (font) {
            case "MS Mincho", "MS PMincho", "MS Gothic", "MS PGothic", "MS UI Gothic", "Yu Gothic", "Yu Mincho", "Meiryo" -> 128;
            case "SimSun", "NSimSun", "SimHei", "Microsoft YaHei", "DengXian", "KaiTi", "FangSong" -> 134;
            case "MingLiU", "PMingLiU", "Microsoft JhengHei", "DFKai-SB" -> 136;
            case "Malgun Gothic", "Batang", "BatangChe", "Gulim", "GulimChe", "Dotum", "DotumChe", "Gungsuh" -> 129;
            default -> 0;
        };
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
