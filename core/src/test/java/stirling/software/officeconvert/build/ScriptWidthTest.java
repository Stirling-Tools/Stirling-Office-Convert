package stirling.software.officeconvert.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.LineBuilder;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.ScriptWidths;

class ScriptWidthTest {

    private static final FontInfo STAND_IN = new FontInfo("Arial", false, false, false, false, false, "NotoSans-Regular", true);

    @Test
    void standInWidthFixesLeaveOtherScriptsAlone() {
        List<Glyph> glyphs = new ArrayList<>();
        float x = place(glyphs, "Wide Latin words here ", 72, 8f);
        place(glyphs, "\u0939\u093F\u0928\u094D\u0926\u0940 \u092D\u093E\u0937\u093E \u0915\u0940 \u0915\u093F\u0924\u093E\u092C", x, 6f);
        Paragraph p = new Paragraph();
        new RunBuilder(false).fill(p, LineBuilder.build(glyphs), 0, 72, 540, 10);
        boolean latinFixed = false;
        for (Inline in : p.inlines) {
            if (in instanceof Inline.Text t && t.text().codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.DEVANAGARI)) {
                assertEquals(100, t.style().scale(), t.text());
                assertEquals(0f, t.style().spacing(), 0.001f, t.text());
            } else if (in instanceof Inline.Text t && t.text().contains("Latin")) {
                latinFixed = t.style().scale() != 100 || t.style().spacing() != 0f;
            }
        }
        assertTrue(latinFixed, "Latin text still gets its width fix");
    }

    @Test
    void greekCyrillicAndHebrewStandInsGetTheirOwnWidthFixes() {
        List<Glyph> glyphs = new ArrayList<>();
        float x = exact(glyphs, "Latin words ", 72);
        x = place(glyphs, "Привет κόσμε ", x, 7f);
        place(glyphs, "שלום עולם", x, 7f);
        Paragraph p = new Paragraph();
        new RunBuilder(false).fill(p, LineBuilder.build(glyphs), 0, 72, 540, 10);
        boolean cyrillic = false;
        boolean hebrew = false;
        for (Inline in : p.inlines) {
            if (!(in instanceof Inline.Text t)) {
                continue;
            }
            boolean fixed = t.style().scale() != 100 || t.style().spacing() > 0f;
            if (t.text().contains("П")) {
                cyrillic = fixed;
            } else if (t.text().contains("ש")) {
                hebrew = fixed;
            } else if (t.text().contains("Latin")) {
                assertEquals(0f, t.style().spacing(), 0.001f, "Latin at Arial's own widths keeps them");
            }
        }
        assertTrue(cyrillic, "Cyrillic and Greek are measured against Arial's widths");
        assertTrue(hebrew, "Hebrew gets a fix of its own");
        assertTrue(SubstituteMetrics.width("ЖΩ", "Times New Roman", true, false, 10) > 0);
    }

    @Test
    void sansArabicStandsInAsTahomaWithItsJoiningWidths() {
        FontInfo sans = new FontInfo("Arial", false, false, false, false, false, "NotoSansArabic-Regular", true);
        FontInfo naskh = new FontInfo("Arial", false, false, false, false, false, "NotoNaskhArabic-Regular", true);
        String beh = "ب";
        float[] forms = {ArabicWidths.width(beh + "ـ", false, 10) - ArabicWidths.width("ـ", false, 10),
            ArabicWidths.width("ـ" + beh + "ـ", false, 10) - 2 * ArabicWidths.width("ـ", false, 10),
            ArabicWidths.width("ـ" + beh, false, 10) - ArabicWidths.width("ـ", false, 10)};
        for (float stretch : new float[] {1f, 1.04f}) {
            List<Glyph> glyphs = new ArrayList<>();
            float x = 72;
            for (int k = 0; k < 3; k++) {
                float[] visual = {forms[2], forms[1], forms[1], forms[0]};
                for (float w : visual) {
                    glyphs.add(new Glyph(beh, x, w * stretch, 100, 10, 7.5f, 2.5f, sans, 0, glyphs.size(), 3.18f, false, false));
                    x += w * stretch;
                }
                x += 3.18f;
            }
            glyphs.add(new Glyph(beh, x, 5, 100, 10, 7.5f, 2.5f, naskh, 0, glyphs.size(), 2.8f, false, false));
            Paragraph p = new Paragraph();
            p.bidi = true;
            new RunBuilder(false).fill(p, LineBuilder.build(glyphs), 0, 72, 540, 10);
            List<Inline.Text> runs = p.inlines.stream().filter(Inline.Text.class::isInstance).map(Inline.Text.class::cast).toList();
            assertEquals("Arial", runs.getFirst().style().font(), "Naskh keeps Arial, whose Arabic is Naskh too");
            assertEquals("Tahoma", runs.getLast().style().font(), "sans Arabic stands in as Tahoma");
            float spacing = runs.getLast().style().spacing();
            assertTrue(stretch == 1f ? Math.abs(spacing) < 0.001f : spacing > 0f, "spacing " + spacing + " at " + stretch);
        }
    }

    @Test
    void indicStandInsAreScaledToTheirScriptFontWidths() {
        FontInfo serif = new FontInfo("Times New Roman", false, false, true, false, false, "NotoSerifTelugu-Regular", true);
        String[] words = {"మానవులు", "స్వతంత్రులు",
            "సమానులు", "హక్కులు", "కలిగి"};
        for (float k : new float[] {1f, 0.9f}) {
            List<Glyph> glyphs = new ArrayList<>();
            float x = 72;
            float space = ScriptWidths.space(Character.UnicodeScript.TELUGU, false) * 10 * k;
            for (int rep = 0; rep < 2; rep++) {
                for (String w : words) {
                    float width = ScriptWidths.width(w, Character.UnicodeScript.TELUGU, false) * 10 * k;
                    glyphs.add(new Glyph(w, x, width, 100, 10, 7.5f, 2.5f, serif, 0, glyphs.size(), space, false, false));
                    glyphs.add(new Glyph(" ", x + width, space, 100, 10, 7.5f, 2.5f, serif, 0, glyphs.size(), space, false, false));
                    x += width + space;
                }
            }
            Paragraph p = new Paragraph();
            new RunBuilder(false).fill(p, LineBuilder.build(glyphs), 0, 72, 540, 10);
            for (Inline in : p.inlines) {
                if (in instanceof Inline.Text t) {
                    assertEquals(Math.round(100 * k), t.style().scale(), t.text());
                    assertEquals(0f, t.style().spacing(), 0.001f);
                }
            }
        }
    }

    private static float exact(List<Glyph> out, String text, float x) {
        for (char c : text.toCharArray()) {
            float w = SubstituteMetrics.width(String.valueOf(c), "Arial", false, false, 10);
            out.add(new Glyph(String.valueOf(c), x, w, 100, 10, 7.5f, 2.5f, STAND_IN, 0, out.size(), 2.78f, false, false));
            x += w;
        }
        return x;
    }

    private static float place(List<Glyph> out, String text, float x, float advance) {
        for (char c : text.toCharArray()) {
            float w = c == ' ' ? 2.8f : advance;
            out.add(new Glyph(String.valueOf(c), x, w, 100, 10, 7.5f, 2.5f, STAND_IN, 0, out.size(), 2.8f, false, false));
            x += w;
        }
        return x;
    }
}
