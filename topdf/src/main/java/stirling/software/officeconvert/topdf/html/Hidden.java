package stirling.software.officeconvert.topdf.html;

import java.util.Locale;
import java.util.Map;

/** Content a browser lays out but nobody sees: screen-reader-only text, skip links parked off the page,
 * collapsed panels and fully transparent elements. */
final class Hidden {

    private Hidden() {}

    static boolean visually(Map<String, String> d, float em) {
        String opacity = d.get("opacity");
        if (opacity != null && opacity.strip().matches("0+([.]0*)?%?|[.]0+")) {
            return true;
        }
        if (collapsed(d, em)) {
            return true;
        }
        String position = d.getOrDefault("position", "").strip().toLowerCase(Locale.ROOT);
        if (!position.equals("absolute") && !position.equals("fixed")) {
            return false;
        }
        String clip = d.getOrDefault("clip", "").toLowerCase(Locale.ROOT).replace(" ", "");
        String clipPath = d.getOrDefault("clip-path", "").toLowerCase(Locale.ROOT).replace(" ", "");
        if (clip.startsWith("rect(0") || clip.startsWith("rect(1px,1px") || clipPath.startsWith("inset(50%")
                || clipPath.startsWith("inset(100%")) {
            return true;
        }
        return far(d.get("left"), em) || far(d.get("top"), em) || tiny(d.get("width"), em) && tiny(d.get("height"), em);
    }

    private static boolean collapsed(Map<String, String> d, float em) {
        String overflow = d.getOrDefault("overflow", d.getOrDefault("overflow-y", "")).strip().toLowerCase(Locale.ROOT);
        boolean clips = overflow.equals("hidden") || overflow.equals("clip");
        String h = d.get("height");
        String max = d.get("max-height");
        return clips && (zero(h, em) || zero(max, em));
    }

    // A percentage is of a box this flow does not know, so it never says the element is empty or tiny
    private static boolean zero(String v, float em) {
        Float pt = v == null || v.strip().endsWith("%") ? null : Css.length(v, em, 0);
        return pt != null && pt <= 0.01f;
    }

    private static boolean tiny(String v, float em) {
        Float pt = v == null || v.strip().endsWith("%") ? null : Css.length(v, em, 0);
        return pt != null && pt <= 1f;
    }

    private static boolean far(String v, float em) {
        Float pt = v == null ? null : Css.length(v, em, 0);
        return pt != null && pt <= -500f;
    }
}
