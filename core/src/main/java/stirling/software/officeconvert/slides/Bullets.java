package stirling.software.officeconvert.slides;

import java.util.Locale;

import stirling.software.officeconvert.build.PlacedContent;
import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.FontNames;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.Marker;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.layout.Word;
import stirling.software.officeconvert.model.RunStyle;

final class Bullets {

    private Bullets() {}

    static Bullet of(ParaDraft d) {
        Line first = d.first();
        if (first.words.size() < 2 || rightToLeft(first) && Marker.startIndex(first) == 0) {
            return null;
        }
        Word word = first.words.get(Marker.startIndex(first));
        Marker m = Marker.leading(first);
        if (m == null || !m.isBullet() && !numberable(m)) {
            return null;
        }
        float gap = Marker.gapAfter(first);
        boolean tab = Marker.tabAfter(first);
        if (!tab && gap <= (m.isBullet() ? 0.15f : 0.35f) * first.size) {
            return null;
        }
        RunStyle style = PlacedContent.style(word.first(), d.size());
        String symbols = symbolFamily(word.first().font);
        if (symbols != null && isPrivateUse(word.text)) {
            style = style.withFont(symbols);
        }
        return new Bullet(m, style);
    }

    static String symbolFamily(FontInfo font) {
        String ps = FontNames.stripSubset(font.postScriptName());
        if (ps == null) {
            return null;
        }
        String key = ps.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (key.startsWith("wingdings3")) {
            return "Wingdings 3";
        }
        if (key.startsWith("wingdings2")) {
            return "Wingdings 2";
        }
        if (key.startsWith("wingdings")) {
            return "Wingdings";
        }
        if (key.startsWith("webdings")) {
            return "Webdings";
        }
        return key.startsWith("symbol") ? "Symbol" : null;
    }

    private static boolean isPrivateUse(String s) {
        return !s.isEmpty() && s.charAt(0) >= 0xE000 && s.charAt(0) <= 0xF8FF;
    }

    static float textStart(ParaDraft d) {
        return d.first().words.get(1).x;
    }

    static float textEnd(ParaDraft d) {
        Line first = d.first();
        return first.words.get(Marker.nextIndex(first)).right;
    }

    private static boolean numberable(Marker m) {
        String p = m.prefix();
        String s = m.suffix();
        boolean eastAsian = m.kind() == Marker.Kind.CHINESE || m.kind() == Marker.Kind.FULL_WIDTH;
        boolean dotOrParen = p.isEmpty() && (s.equals(".") || s.equals(")") || eastAsian && s.equals("\uFF0E"))
                || p.equals("(") && s.equals(")");
        return dotOrParen && m.kind() != Marker.Kind.GANADA && m.value() > 0 && m.value() < 10000;
    }

    private static boolean rightToLeft(Line l) {
        for (Word w : l.words) {
            for (int i = 0; i < w.text.length(); i++) {
                byte dir = Character.getDirectionality(w.text.charAt(i));
                if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                    return true;
                }
            }
        }
        return false;
    }
}
