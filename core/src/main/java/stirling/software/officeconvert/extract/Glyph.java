package stirling.software.officeconvert.extract;

public final class Glyph {

    public final String text;
    public final float x;
    public final float width;
    public final float baseline;
    public final float size;
    public final float ascent;
    public final float descent;
    public final FontInfo font;
    public final int rgb;
    public final int seq;
    public final float spaceWidth;

    public boolean bold;
    public boolean italic;
    public boolean underline;
    public boolean strike;
    public int highlightRgb = -1;
    public String link;
    public int footnote = -1;
    public int vertAlign;

    public float hscale = 1f;

    public boolean upright;

    public boolean invisible;

    public IconShape icon;

    public Glyph(
            String text,
            float x,
            float width,
            float baseline,
            float size,
            float ascent,
            float descent,
            FontInfo font,
            int rgb,
            int seq,
            float spaceWidth,
            boolean bold,
            boolean italic) {
        this.text = text;
        this.x = x;
        this.width = width;
        this.baseline = baseline;
        this.size = size;
        this.ascent = ascent;
        this.descent = descent;
        this.font = font;
        this.rgb = rgb;
        this.seq = seq;
        this.spaceWidth = spaceWidth;
        this.bold = bold;
        this.italic = italic;
    }

    public Glyph scaled(float k) {
        Glyph g = new Glyph(text, x * k, width * k, baseline * k, size * k, ascent * k, descent * k, font, rgb, seq,
                spaceWidth * k, bold, italic);
        g.underline = underline;
        g.strike = strike;
        g.highlightRgb = highlightRgb;
        g.link = link;
        g.footnote = footnote;
        g.vertAlign = vertAlign;
        g.hscale = hscale;
        g.invisible = invisible;
        g.icon = icon == null ? null : new IconShape(icon.scaled(k), icon.rgb(), icon.key() + "|" + k);
        return g;
    }

    public float right() {
        return x + width;
    }

    public float top() {
        return baseline - ascent;
    }

    public float bottom() {
        return baseline + descent;
    }

    public float centreX() {
        return x + width / 2f;
    }

    public boolean isSpace() {
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i)) && text.charAt(i) != ' ') {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return text + "@" + x + "," + baseline;
    }
}
