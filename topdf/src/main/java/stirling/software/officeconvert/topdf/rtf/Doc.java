package stirling.software.officeconvert.topdf.rtf;

import java.nio.charset.Charset;

final class Doc {

    final FontTable fonts = new FontTable();

    final ColorTable colors = new ColorTable();

    final StyleSheet styles = new StyleSheet();

    final ListTable lists = new ListTable();

    final CharProps defChp = new CharProps();

    final ParaProps defPap = new ParaProps();

    final Media media = new Media();

    Charset ansi = CodePages.WINDOWS_1252;

    int defaultFont = -1;

    int loFont = -1;

    int hiFont = -1;

    int eaFont = -1;

    int biFont = -1;

    int defaultTab = 720;

    boolean widowControl;

    boolean facingPages;

    boolean autoHyphenation;

    boolean libreOffice;

    int background = -1;

    boolean endnotesAtSectionEnd;

    String title;

    String subject;

    String author;

    String keywords;

    final java.util.Map<String, int[]> times = new java.util.HashMap<>();

    int effectiveFont(CharProps c, ParaProps p) {
        if (c.has(CharProps.FONT)) {
            return c.font;
        }
        if (c.style >= 0) {
            Integer f = styleFont(styles.character(c.style), 0);
            if (f != null) {
                return f;
            }
        }
        if (p != null) {
            Integer f = styleFont(styles.paragraph(p.style), 0);
            if (f != null) {
                return f;
            }
        }
        if (defChp.has(CharProps.FONT)) {
            return defChp.font;
        }
        return loFont >= 0 ? loFont : defaultFont;
    }

    private Integer styleFont(StyleSheet.Style s, int depth) {
        if (s == null || depth > 32) {
            return null;
        }
        if (s.chp.has(CharProps.FONT)) {
            return s.chp.font;
        }
        return styleFont(styles.basedOn(s), depth + 1);
    }

    Charset charset(int font) {
        return fonts.charset(font, ansi);
    }
}
