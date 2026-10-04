package stirling.software.officeconvert.topdf.pptx;

interface Advances {

    // Advance in ems, or NaN when the emulated face has no glyph for the code point
    float advance(int codePoint);
}
