package stirling.software.officeconvert.topdf.rtf;

final class CharProps implements Cloneable {

    static final int MODE_NONE = 0;
    static final int MODE_LOCH = 1;
    static final int MODE_HICH = 2;
    static final int MODE_DBCH = 3;
    static final int MODE_RTL = 4;

    static final int FONT = 0;
    static final int H_FONT = 1;
    static final int EA_FONT = 2;
    static final int CS_FONT = 3;
    static final int SIZE = 4;
    static final int CS_SIZE = 5;
    static final int BOLD = 6;
    static final int ITALIC = 7;
    static final int BOLD_CS = 8;
    static final int ITALIC_CS = 9;
    static final int UNDERLINE = 10;
    static final int UNDERLINE_COLOR = 11;
    static final int STRIKE = 12;
    static final int DSTRIKE = 13;
    static final int CAPS = 14;
    static final int SMALL_CAPS = 15;
    static final int HIDDEN = 16;
    static final int OUTLINE = 17;
    static final int SHADOW = 18;
    static final int EMBOSS = 19;
    static final int IMPRINT = 20;
    static final int COLOR = 21;
    static final int HIGHLIGHT = 22;
    static final int SHADING = 23;
    static final int VERTICAL = 24;
    static final int POSITION = 25;
    static final int SPACING = 26;
    static final int SCALE = 27;
    static final int KERNING = 28;
    static final int RTL = 29;
    static final int BORDER = 30;
    static final int LANG = 31;
    static final int EA_LANG = 32;
    static final int CS_LANG = 33;

    long set;

    int font;
    int hFont;
    int eaFont;
    int csFont;
    int size = 24;
    int csSize = 24;
    boolean bold;
    boolean italic;
    boolean boldCs;
    boolean italicCs;
    String underline = "single";
    int underlineColor;
    boolean strike;
    boolean dstrike;
    boolean caps;
    boolean smallCaps;
    boolean hidden;
    boolean outline;
    boolean shadow;
    boolean emboss;
    boolean imprint;
    int color;
    int highlight;
    Shading shade = new Shading();
    int vertical;
    int position;
    int spacing;
    int scale = 100;
    int kerning;
    boolean rtl;
    Border border;
    int lang;
    int eaLang;
    int csLang;

    int mode;
    int style = -1;

    boolean has(int prop) {
        return (set & 1L << prop) != 0;
    }

    void mark(int prop) {
        set |= 1L << prop;
    }

    void unmark(int prop) {
        set &= ~(1L << prop);
    }

    CharProps copy() {
        try {
            CharProps c = (CharProps) super.clone();
            if (border != null) {
                c.border = border.copy();
            }
            c.shade = shade.copy();
            return c;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    void plain() {
        set = 0;
        style = -1;
        mode = MODE_NONE;
        border = null;
        shade.clear();
    }

    void inherit(CharProps from) {
        CharProps c = from.copy();
        for (int i = 0; i <= CS_LANG; i++) {
            if (c.has(i)) {
                copyProp(c, i);
            }
        }
    }

    private void copyProp(CharProps c, int i) {
        mark(i);
        switch (i) {
            case FONT -> font = c.font;
            case H_FONT -> hFont = c.hFont;
            case EA_FONT -> eaFont = c.eaFont;
            case CS_FONT -> csFont = c.csFont;
            case SIZE -> size = c.size;
            case CS_SIZE -> csSize = c.csSize;
            case BOLD -> bold = c.bold;
            case ITALIC -> italic = c.italic;
            case BOLD_CS -> boldCs = c.boldCs;
            case ITALIC_CS -> italicCs = c.italicCs;
            case UNDERLINE -> underline = c.underline;
            case UNDERLINE_COLOR -> underlineColor = c.underlineColor;
            case STRIKE -> strike = c.strike;
            case DSTRIKE -> dstrike = c.dstrike;
            case CAPS -> caps = c.caps;
            case SMALL_CAPS -> smallCaps = c.smallCaps;
            case HIDDEN -> hidden = c.hidden;
            case OUTLINE -> outline = c.outline;
            case SHADOW -> shadow = c.shadow;
            case EMBOSS -> emboss = c.emboss;
            case IMPRINT -> imprint = c.imprint;
            case COLOR -> color = c.color;
            case HIGHLIGHT -> highlight = c.highlight;
            case SHADING -> shade.set(c.shade);
            case VERTICAL -> vertical = c.vertical;
            case POSITION -> position = c.position;
            case SPACING -> spacing = c.spacing;
            case SCALE -> scale = c.scale;
            case KERNING -> kerning = c.kerning;
            case RTL -> rtl = c.rtl;
            case BORDER -> border = c.border;
            case LANG -> lang = c.lang;
            case EA_LANG -> eaLang = c.eaLang;
            case CS_LANG -> csLang = c.csLang;
            default -> {
            }
        }
    }
}
