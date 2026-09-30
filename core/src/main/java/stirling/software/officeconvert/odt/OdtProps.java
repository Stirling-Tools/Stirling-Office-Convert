package stirling.software.officeconvert.odt;

import java.util.Objects;
import java.util.Set;

import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Scripts;

final class OdtProps {

    private OdtProps() {}

    static String text(RunStyle s, RunStyle base, Set<String> fonts) {
        return text(s, base, fonts, null, null);
    }

    static String text(RunStyle s, RunStyle base, Set<String> fonts, String text, Scripts.Profile profile) {
        StringBuilder sb = new StringBuilder();
        String family = s.font() != null ? s.font() : base.font();
        Scripts.Fonts f = Scripts.fonts(family, text, profile);
        if (s.font() != null && !s.font().equals(base.font()) || !Objects.equals(f.latin(), family)
                || !Objects.equals(f.eastAsian(), family) || !Objects.equals(f.complex(), family)) {
            fonts.add(f.latin());
            fonts.add(f.eastAsian());
            fonts.add(f.complex());
            sb.append(" style:font-name=\"").append(OdtXml.esc(f.latin())).append("\" style:font-name-asian=\"")
                    .append(OdtXml.esc(f.eastAsian())).append("\" style:font-name-complex=\"").append(OdtXml.esc(f.complex()))
                    .append('"');
        }
        sb.append(Scripts.odfLanguages(Scripts.beyond(Scripts.languages(text, profile), profile)));
        if (Math.abs(s.size() - base.size()) > 0.01f) {
            String size = OdtXml.pt(Math.max(1f, Math.min(1638f, s.size())));
            sb.append(" fo:font-size=\"").append(size).append("\" style:font-size-asian=\"").append(size)
                    .append("\" style:font-size-complex=\"").append(size).append('"');
        }
        if (s.bold() != base.bold()) {
            String w = s.bold() ? "bold" : "normal";
            sb.append(" fo:font-weight=\"").append(w).append("\" style:font-weight-asian=\"").append(w)
                    .append("\" style:font-weight-complex=\"").append(w).append('"');
        }
        if (s.italic() != base.italic()) {
            String i = s.italic() ? "italic" : "normal";
            sb.append(" fo:font-style=\"").append(i).append("\" style:font-style-asian=\"").append(i)
                    .append("\" style:font-style-complex=\"").append(i).append('"');
        }
        if (s.smallCaps() != base.smallCaps()) {
            sb.append(" fo:font-variant=\"").append(s.smallCaps() ? "small-caps" : "normal").append('"');
        }
        if (s.underline() != base.underline()) {
            sb.append(s.underline()
                    ? " style:text-underline-style=\"solid\" style:text-underline-width=\"auto\" style:text-underline-color=\"font-color\""
                    : " style:text-underline-style=\"none\"");
        }
        if (s.strike() != base.strike()) {
            sb.append(s.strike()
                    ? " style:text-line-through-style=\"solid\" style:text-line-through-type=\"single\""
                    : " style:text-line-through-style=\"none\"");
        }
        if ((s.rgb() & 0xFFFFFF) != (base.rgb() & 0xFFFFFF)) {
            sb.append(" fo:color=\"").append(OdtXml.colour(s.rgb())).append('"');
        }
        if (s.highlight() != base.highlight()) {
            sb.append(" fo:background-color=\"").append(s.highlight() >= 0 ? OdtXml.colour(s.highlight()) : "transparent")
                    .append('"');
        }
        if (s.vertAlign() != base.vertAlign()) {
            sb.append(" style:text-position=\"")
                    .append(s.vertAlign() > 0 ? "super 58%" : s.vertAlign() < 0 ? "sub 58%" : "0% 100%").append('"');
        }
        if (Math.abs(s.spacing() - base.spacing()) >= 0.05f) {
            sb.append(" fo:letter-spacing=\"").append(s.spacing() == 0 ? "normal" : OdtXml.pt(s.spacing())).append('"');
        }
        if (s.scale() != base.scale()) {
            sb.append(" style:text-scale=\"").append(Math.max(1, Math.min(600, s.scale()))).append("%\"");
        }
        return sb.toString();
    }
}
