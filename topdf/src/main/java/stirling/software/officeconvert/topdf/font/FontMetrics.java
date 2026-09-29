package stirling.software.officeconvert.topdf.font;

public record FontMetrics(int unitsPerEm, int hheaAscender, int hheaDescender, int hheaLineGap, int typoAscender,
        int typoDescender, int typoLineGap, boolean useTypoMetrics, int winAscent, int winDescent, int capHeight,
        int xHeight, int underlinePosition, int underlineThickness, int strikeoutPosition, int strikeoutSize,
        float italicAngle, boolean fixedPitch, int xMin, int yMin, int xMax, int yMax) {

    public float points(int units, float size) {
        return units * size / unitsPerEm;
    }

    public float winLineHeight(float size) {
        return points(winAscent + winDescent, size);
    }

    public int externalLeading() {
        return Math.max(0, hheaLineGap - (winAscent + winDescent - (hheaAscender - hheaDescender)));
    }

    public float gdiLineHeight(float size) {
        return points(winAscent + winDescent + externalLeading(), size);
    }

    public float hheaLineHeight(float size) {
        return points(hheaAscender - hheaDescender + hheaLineGap, size);
    }

    public float typoLineHeight(float size) {
        return points(typoAscender - typoDescender + typoLineGap, size);
    }
}
