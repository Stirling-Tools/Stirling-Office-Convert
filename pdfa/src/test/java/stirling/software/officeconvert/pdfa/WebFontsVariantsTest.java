package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeMap;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.junit.jupiter.api.Test;

class WebFontsVariantsTest {

    private static final int[] ADVANCES = {600, 700, 800};

    private static byte[] trueType(List<Cmaps.Subtable> cmaps) throws IOException {
        TrueTypeWriter w = new TrueTypeWriter(1000);
        GeneralPath a = new GeneralPath();
        a.append(new Rectangle2D.Double(100, 0, 400, 700), false);
        GeneralPath b = new GeneralPath();
        b.moveTo(50, 0);
        b.lineTo(650, 0);
        b.lineTo(350, 650);
        b.closePath();
        GeneralPath c = new GeneralPath();
        c.moveTo(100, 0);
        c.lineTo(700, 0);
        c.lineTo(700, 100);
        c.lineTo(200, 100);
        c.lineTo(200, 600);
        c.lineTo(100, 600);
        c.closePath();
        w.addOutline(a, ADVANCES[0]);
        w.addOutline(b, ADVANCES[1]);
        w.addOutline(c, ADVANCES[2]);
        return w.build("Variant", Cmaps.table(cmaps));
    }

    private static TreeMap<Integer, Integer> map(int first) {
        TreeMap<Integer, Integer> m = new TreeMap<>();
        for (int i = 0; i < 3; i++) {
            m.put(first + i, i + 1);
        }
        return m;
    }

    private static COSStream stream(PDDocument d, byte[] data, COSName subtype) throws IOException {
        COSStream s = d.getDocument().createCOSStream();
        try (OutputStream o = s.createOutputStream(COSName.FLATE_DECODE)) {
            o.write(data);
        }
        if (subtype != null) {
            s.setItem(COSName.SUBTYPE, subtype);
        }
        return s;
    }

    private static COSStream toUnicode(PDDocument d, int bytes, int[] codes, String text) throws IOException {
        StringBuilder b = new StringBuilder("/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n1 "
                + "begincodespacerange <" + "00".repeat(bytes) + "> <" + "FF".repeat(bytes)
                + "> endcodespacerange\n" + codes.length + " beginbfchar\n");
        for (int i = 0; i < codes.length; i++) {
            b.append(String.format("<%0" + 2 * bytes + "X> <%04X>\n", codes[i], (int) text.charAt(i)));
        }
        b.append("endbfchar endcmap CMapName currentdict /CMap defineresource pop end end\n");
        return stream(d, b.toString().getBytes(StandardCharsets.US_ASCII), null);
    }

    private static COSDictionary descriptor(boolean symbolic, COSName key, COSStream file) {
        COSDictionary fd = new COSDictionary();
        fd.setItem(COSName.TYPE, COSName.FONT_DESC);
        fd.setName(COSName.FONT_NAME, "Variant");
        fd.setInt(COSName.FLAGS, symbolic ? 4 : 32);
        COSArray box = new COSArray();
        for (int v : new int[] {0, 0, 800, 700}) {
            box.add(COSInteger.get(v));
        }
        fd.setItem(COSName.FONT_BBOX, box);
        fd.setInt(COSName.ITALIC_ANGLE, 0);
        fd.setInt(COSName.ASCENT, 700);
        fd.setInt(COSName.DESCENT, -200);
        fd.setInt(COSName.CAP_HEIGHT, 700);
        fd.setInt(COSName.STEM_V, 80);
        fd.setItem(key, file);
        return fd;
    }

    private static COSDictionary simple(PDDocument d, COSName subtype, COSName key, COSStream file, boolean symbolic,
            COSBase encoding, int[] codes, int[] widths) {
        COSDictionary f = new COSDictionary();
        f.setItem(COSName.TYPE, COSName.FONT);
        f.setItem(COSName.SUBTYPE, subtype);
        f.setName(COSName.BASE_FONT, "Variant");
        if (encoding != null) {
            f.setItem(COSName.ENCODING, encoding);
        }
        int first = codes[0];
        int last = codes[codes.length - 1];
        f.setInt(COSName.FIRST_CHAR, first);
        f.setInt(COSName.LAST_CHAR, last);
        COSArray w = new COSArray();
        for (int c = first; c <= last; c++) {
            int at = java.util.Arrays.binarySearch(codes, c);
            w.add(COSInteger.get(at >= 0 ? widths[at] : 0));
        }
        f.setItem(COSName.WIDTHS, w);
        f.setItem(COSName.FONT_DESC, descriptor(symbolic, key, file));
        return f;
    }

    private static COSDictionary type0(PDDocument d, COSName cidSubtype, COSName key, COSStream file, COSBase encoding,
            COSBase cidToGid) {
        COSDictionary cid = new COSDictionary();
        cid.setItem(COSName.TYPE, COSName.FONT);
        cid.setItem(COSName.SUBTYPE, cidSubtype);
        cid.setName(COSName.BASE_FONT, "Variant");
        COSDictionary info = new COSDictionary();
        info.setString(COSName.REGISTRY, "Adobe");
        info.setString(COSName.ORDERING, "Identity");
        info.setInt(COSName.SUPPLEMENT, 0);
        cid.setItem(COSName.CIDSYSTEMINFO, info);
        cid.setItem(COSName.FONT_DESC, descriptor(true, key, file));
        if (cidToGid != null) {
            cid.setItem(COSName.CID_TO_GID_MAP, cidToGid);
        }
        COSDictionary t0 = new COSDictionary();
        t0.setItem(COSName.TYPE, COSName.FONT);
        t0.setItem(COSName.SUBTYPE, COSName.TYPE0);
        t0.setName(COSName.BASE_FONT, "Variant");
        t0.setItem(COSName.ENCODING, encoding);
        COSArray kids = new COSArray();
        kids.add(cid);
        t0.setItem(COSName.DESCENDANT_FONTS, kids);
        return t0;
    }

    private static void assertMatches(COSDictionary dict, int[] codes, String label) throws IOException {
        PDFont font = PDFontFactory.createFont(dict);
        byte[] ttf = WebFonts.toTrueType(font);
        assertTrue(TrueTypeSanity.sane(ttf), label);
        try (TrueTypeFont t = new TTFParser().parse(new RandomAccessReadBuffer(ttf))) {
            for (int code : codes) {
                String u = font.toUnicode(code);
                assertTrue(u != null && u.codePointCount(0, u.length()) == 1, label + " code " + code + " has " + u);
                int gid = t.getUnicodeCmapLookup().getGlyphId(u.codePointAt(0));
                assertTrue(gid > 0, label + " code " + code + " U+" + Integer.toHexString(u.codePointAt(0)));
                double k = 1000.0 / t.getUnitsPerEm();
                assertEquals(font.getWidthFromFont(code), t.getAdvanceWidth(gid) * k, 1, label + " advance " + code);
                GeneralPath reference = ((PDVectorFont) font).getNormalizedPath(code);
                if (font instanceof org.apache.pdfbox.pdmodel.font.PDType1CFont) {
                    java.awt.geom.AffineTransform m = font.getFontMatrix().createAffineTransform();
                    m.preConcatenate(java.awt.geom.AffineTransform.getScaleInstance(1000, 1000));
                    reference.transform(m);
                }
                Rectangle2D expected = reference.getBounds2D();
                GeneralPath p = t.getGlyph().getGlyph(gid).getPath();
                p.transform(java.awt.geom.AffineTransform.getScaleInstance(k, k));
                Rectangle2D actual = p.getBounds2D();
                assertTrue(expected.getWidth() > 0, label + " reference glyph " + code + " is empty");
                assertEquals(expected.getMinX(), actual.getMinX(), 3, label + " glyph " + code);
                assertEquals(expected.getMaxX(), actual.getMaxX(), 3, label + " glyph " + code);
                assertEquals(expected.getMinY(), actual.getMinY(), 3, label + " glyph " + code);
                assertEquals(expected.getMaxY(), actual.getMaxY(), 3, label + " glyph " + code);
            }
        }
    }

    @Test
    void simpleTrueTypeFontsWithEveryCmapKind() throws Exception {
        int[] abc = {65, 66, 67};
        try (PDDocument d = new PDDocument()) {
            assertMatches(simple(d, COSName.TRUE_TYPE, COSName.FONT_FILE2,
                    stream(d, trueType(List.of(new Cmaps.Subtable(3, 1, map(0x41)))), null), false,
                    COSName.WIN_ANSI_ENCODING, abc, ADVANCES), abc, "nonsymbolic (3,1)");
            assertMatches(simple(d, COSName.TRUE_TYPE, COSName.FONT_FILE2,
                    stream(d, trueType(List.of(new Cmaps.Subtable(1, 0, map(0x41)))), null), false,
                    COSName.MAC_ROMAN_ENCODING, abc, ADVANCES), abc, "nonsymbolic (1,0)");
            COSDictionary diff = new COSDictionary();
            COSArray names = new COSArray();
            names.add(COSInteger.get(1));
            for (String n : new String[] {"B", "C", "A"}) {
                names.add(COSName.getPDFName(n));
            }
            diff.setItem(COSName.DIFFERENCES, names);
            int[] low = {1, 2, 3};
            assertMatches(simple(d, COSName.TRUE_TYPE, COSName.FONT_FILE2,
                    stream(d, trueType(List.of(new Cmaps.Subtable(3, 1, map(0x41)))), null), false, diff, low,
                    new int[] {700, 800, 600}), low, "nonsymbolic Differences");
            for (Cmaps.Subtable cmap : List.of(new Cmaps.Subtable(3, 0, map(0xF041)),
                    new Cmaps.Subtable(3, 0, map(0x41)), new Cmaps.Subtable(1, 0, map(0x41)))) {
                COSDictionary f = simple(d, COSName.TRUE_TYPE, COSName.FONT_FILE2, stream(d, trueType(List.of(cmap)),
                        null), true, null, abc, ADVANCES);
                f.setItem(COSName.TO_UNICODE, toUnicode(d, 1, abc, "☀☁☂"));
                assertMatches(f, abc, "symbolic (" + cmap.platform() + "," + cmap.encoding() + ") from "
                        + Integer.toHexString(cmap.map().firstKey()));
            }
        }
    }

    @Test
    void cidFontsWithIdentityCustomAndVerticalCMaps() throws Exception {
        try (PDDocument d = new PDDocument()) {
            byte[] program = trueType(List.of(new Cmaps.Subtable(3, 1, map(0x41))));
            int[] cids = {1, 2, 3};
            for (COSName encoding : new COSName[] {COSName.IDENTITY_H, COSName.getPDFName("Identity-V")}) {
                COSDictionary f = type0(d, COSName.CID_FONT_TYPE2, COSName.FONT_FILE2, stream(d, program, null),
                        encoding, COSName.IDENTITY);
                f.setItem(COSName.TO_UNICODE, toUnicode(d, 2, cids, "XYZ"));
                assertMatches(f, cids, encoding.getName());
            }
            COSStream cmap = stream(d, ("/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n"
                    + "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> def\n"
                    + "/CMapName /Custom def\n1 begincodespacerange <00> <FF> endcodespacerange\n"
                    + "1 begincidrange <61> <63> 10 endcidrange\n"
                    + "endcmap CMapName currentdict /CMap defineresource pop end end\n")
                    .getBytes(StandardCharsets.US_ASCII), null);
            cmap.setName(COSName.TYPE, "CMap");
            cmap.setName(COSName.CMAPNAME, "Custom");
            byte[] gids = new byte[13 * 2];
            for (int i = 0; i < 3; i++) {
                gids[2 * (10 + i) + 1] = (byte) (i + 1);
            }
            int[] codes = {0x61, 0x62, 0x63};
            COSDictionary custom = type0(d, COSName.CID_FONT_TYPE2, COSName.FONT_FILE2, stream(d, program, null), cmap,
                    stream(d, gids, null));
            custom.setItem(COSName.TO_UNICODE, toUnicode(d, 1, codes, "abc"));
            assertMatches(custom, codes, "custom CMap");
            COSDictionary cff = type0(d, COSName.CID_FONT_TYPE0, COSName.FONT_FILE3,
                    stream(d, FontPrograms.cidCff("VariantCid"), COSName.getPDFName("CIDFontType0C")), cmap, null);
            cff.setItem(COSName.TO_UNICODE, toUnicode(d, 1, codes, "abc"));
            assertMatches(cff, codes, "CIDFontType0 custom CMap");
        }
    }

    @Test
    void type1CustomEncodingsReachSeacAccents() throws Exception {
        try (PDDocument d = new PDDocument()) {
            COSDictionary f = OutlineCompactionTest.font(d, false);
            COSDictionary enc = new COSDictionary();
            COSArray diff = new COSArray();
            diff.add(COSInteger.get(65));
            diff.add(COSName.getPDFName("Aacute"));
            diff.add(COSName.getPDFName("g"));
            enc.setItem(COSName.DIFFERENCES, diff);
            f.setItem(COSName.ENCODING, enc);
            assertMatches(f, new int[] {65, 66}, "Type 1 Differences");
        }
    }

    @Test
    void cffFontMatricesAndHintOperatorsKeepTheirGeometry() throws Exception {
        try (PDDocument d = new PDDocument()) {
            byte[] matrix = FontPrograms.concat(FontPrograms.real(0.0005), FontPrograms.real(0), FontPrograms.real(0),
                    FontPrograms.real(0.0005), FontPrograms.real(0), FontPrograms.real(0), new byte[] {12, 7});
            byte[] skew = FontPrograms.concat(FontPrograms.real(0.001), FontPrograms.real(0),
                    FontPrograms.real(0.0002), FontPrograms.real(0.001), FontPrograms.real(0), FontPrograms.real(0),
                    new byte[] {12, 7});
            for (byte[] top : new byte[][] {new byte[0], matrix, skew}) {
                for (boolean hinted : new boolean[] {false, true}) {
                    COSDictionary f = OutlineCompactionTest.font(d, true);
                    COSDictionary fd = (COSDictionary) f.getDictionaryObject(COSName.FONT_DESC);
                    fd.setItem(COSName.FONT_FILE3, stream(d, FontPrograms.cff("TestCff", top, hinted),
                            COSName.getPDFName("Type1C")));
                    assertMatches(f, new int[] {65, 81, 103}, "CFF top " + top.length + " hinted " + hinted);
                }
            }
        }
    }
}
