package stirling.software.officeconvert.slides;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import stirling.software.officeconvert.extract.FontNames;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.layout.Word;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;

final class OfficeFonts {

    private static final List<String[]> FAMILIES = List.of(
            new String[] {"franklingothicdemicond", "Franklin Gothic Demi Cond"},
            new String[] {"franklingothicdemi", "Franklin Gothic Demi"},
            new String[] {"franklingothicbook", "Franklin Gothic Book"},
            new String[] {"franklingothicheavy", "Franklin Gothic Heavy"},
            new String[] {"franklingothicmediumcond", "Franklin Gothic Medium Cond"},
            new String[] {"twcenmtcondensedextrabold", "Tw Cen MT Condensed Extra Bold"},
            new String[] {"twcenmtcondensed", "Tw Cen MT Condensed"},
            new String[] {"twcenmt", "Tw Cen MT"},
            new String[] {"gillsansmtcondensed", "Gill Sans MT Condensed"},
            new String[] {"gillsansultrabold", "Gill Sans Ultra Bold"},
            new String[] {"gillsans", "Gill Sans MT"},
            new String[] {"aptosdisplay", "Aptos Display"},
            new String[] {"aptosnarrow", "Aptos Narrow"},
            new String[] {"aptosblack", "Aptos Black"},
            new String[] {"aptosextrabold", "Aptos ExtraBold"},
            new String[] {"aptossemibold", "Aptos SemiBold"},
            new String[] {"aptoslight", "Aptos Light"},
            new String[] {"arialnovalight", "Arial Nova Light"},
            new String[] {"arialnovacond", "Arial Nova Cond"},
            new String[] {"arialnova", "Arial Nova"},
            new String[] {"arialroundedmtbold", "Arial Rounded MT Bold"},
            new String[] {"rockwellcondensed", "Rockwell Condensed"},
            new String[] {"rockwellextrabold", "Rockwell Extra Bold"},
            new String[] {"rockwell", "Rockwell"},
            new String[] {"bodonimt", "Bodoni MT"},
            new String[] {"perpetua", "Perpetua"},
            new String[] {"goudyoldstyle", "Goudy Old Style"},
            new String[] {"calistomt", "Calisto MT"},
            new String[] {"baskervilleoldface", "Baskerville Old Face"},
            new String[] {"segoeuisemilight", "Segoe UI Semilight"},
            new String[] {"segoeuiblack", "Segoe UI Black"},
            new String[] {"bahnschrift", "Bahnschrift"},
            new String[] {"tenorite", "Tenorite"},
            new String[] {"grandview", "Grandview"},
            new String[] {"seaford", "Seaford"},
            new String[] {"avenirnextltpro", "Avenir Next LT Pro"});

    private static final String[] WEIGHTS = {"Demi", "Bold", "Black", "Heavy", "Light"};

    private OfficeFonts() {}

    static String family(Glyph g) {
        if (!g.font.substituted()) {
            return null;
        }
        String ps = FontNames.stripSubset(g.font.postScriptName());
        if (ps == null) {
            return null;
        }
        String key = ps.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String[] f : FAMILIES) {
            if (key.startsWith(f[0])) {
                return f[1];
            }
        }
        return null;
    }

    static void restore(Paragraph runs, ParaDraft d) {
        Map<String, String> byLook = new HashMap<>();
        boolean any = false;
        for (Line l : d.lines) {
            for (Word w : l.words) {
                for (Glyph g : w.glyphs) {
                    String key = look(g.font.family(), g.bold, g.italic);
                    String family = family(g);
                    any |= family != null;
                    byLook.merge(key, family == null ? "" : family, (a, b) -> Objects.equals(a, b) ? a : "");
                }
            }
        }
        if (!any) {
            return;
        }
        for (int i = 0; i < runs.inlines.size(); i++) {
            if (runs.inlines.get(i) instanceof Inline.Text t && t.style().font() != null) {
                String family = byLook.get(look(t.style().font(), t.style().bold(), t.style().italic()));
                if (family != null && !family.isEmpty()) {
                    runs.inlines.set(i, new Inline.Text(t.text(), restyle(t.style(), family), t.link(), t.anchorPage()));
                }
            }
        }
    }

    private static String look(String family, boolean bold, boolean italic) {
        return family + "|" + bold + "|" + italic;
    }

    private static RunStyle restyle(RunStyle s, String family) {
        boolean weighted = false;
        for (String w : WEIGHTS) {
            weighted |= family.contains(w);
        }
        return new RunStyle(family, s.size(), s.bold() && !weighted, s.italic(), s.underline(), s.strike(), s.rgb(),
                s.highlight(), s.vertAlign(), s.symbol(), 0f, 100, s.smallCaps());
    }
}
