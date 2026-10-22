package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WebFontsHostileTest {

    private static final byte[] CALL_FIRST_GSUBR = FontPrograms.concat(FontPrograms.num2(-107), new byte[] {29});

    @Test
    void aCffSubroutineCallingItselfIsRejected() {
        assertRejected(cff(new byte[][] {CALL_FIRST_GSUBR}, 2, CALL_FIRST_GSUBR), WebFonts.FontKind.CFF);
    }

    @Test
    void cffSubroutinesThatDoubleAtEveryLevelAreRejected() {
        int depth = 26;
        byte[][] gsubrs = new byte[depth][];
        for (int i = 0; i < depth - 1; i++) {
            byte[] call = FontPrograms.concat(FontPrograms.num2(i + 1 - 107), new byte[] {29});
            gsubrs[i] = FontPrograms.concat(call, call, new byte[] {11});
        }
        gsubrs[depth - 1] = FontPrograms.concat(FontPrograms.num2(1), FontPrograms.num2(1), new byte[] {5, 11});
        assertRejected(cff(gsubrs, 40, CALL_FIRST_GSUBR), WebFonts.FontKind.CFF);
    }

    @Test
    void cffSubroutinesWithinTheLimitsStillConvert() throws Exception {
        int depth = 8;
        byte[][] gsubrs = new byte[depth][];
        for (int i = 0; i < depth - 1; i++) {
            byte[] call = FontPrograms.concat(FontPrograms.num2(i + 1 - 107), new byte[] {29});
            gsubrs[i] = FontPrograms.concat(call, call, new byte[] {11});
        }
        gsubrs[depth - 1] = FontPrograms.concat(FontPrograms.num2(1), FontPrograms.num2(1), new byte[] {5, 11});
        byte[] ttf = WebFonts.toTrueType(cff(gsubrs, 3, CALL_FIRST_GSUBR), WebFonts.FontKind.CFF, Map.of());
        assertTrue(TrueTypeSanity.sane(ttf));
    }

    @Test
    void aType1SubroutineCallingItselfIsRejected() {
        byte[] callZero = FontPrograms.concat(FontPrograms.num(0), new byte[] {10});
        assertRejected(type1(List.of(callZero), callZero), WebFonts.FontKind.TYPE1);
    }

    @Test
    void type1SubroutinesThatDoubleAtEveryLevelAreRejected() {
        int depth = 26;
        List<byte[]> subrs = new ArrayList<>();
        for (int i = 0; i < depth - 1; i++) {
            byte[] call = FontPrograms.concat(FontPrograms.num(i + 1), new byte[] {10});
            subrs.add(FontPrograms.concat(call, call, new byte[] {11}));
        }
        subrs.add(FontPrograms.concat(FontPrograms.num(1), FontPrograms.num(1), new byte[] {5, 11}));
        assertRejected(type1(subrs, FontPrograms.concat(FontPrograms.num(0), new byte[] {10})),
                WebFonts.FontKind.TYPE1);
    }

    @Test
    void type1AccentsThatDoubleAtEveryLevelAreRejected() {
        List<String> letters = List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O",
                "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z");
        Map<String, byte[]> glyphs = new LinkedHashMap<>();
        for (int i = 0; i < letters.size() - 1; i++) {
            int next = 'A' + i + 1;
            glyphs.put(letters.get(i), FontPrograms.concat(FontPrograms.num(0), FontPrograms.num(500),
                    new byte[] {13}, FontPrograms.num(0), FontPrograms.num(0), FontPrograms.num(0),
                    FontPrograms.num(next), FontPrograms.num(next), new byte[] {12, 6}));
        }
        glyphs.put("Z", FontPrograms.concat(FontPrograms.num(0), FontPrograms.num(500), new byte[] {13},
                FontPrograms.repeat(FontPrograms.concat(FontPrograms.num(1), FontPrograms.num(1), new byte[] {5}),
                        100),
                new byte[] {14}));
        assertRejected(type1(List.of(new byte[] {11}), glyphs), WebFonts.FontKind.TYPE1);
    }

    @Test
    void trueTypeGlyphsSharingTheWholeGlyfTableAreRejected() throws Exception {
        try (org.apache.fontbox.ttf.TrueTypeFont lib = Samples.liberation()) {
            java.util.TreeMap<String, byte[]> tables = new java.util.TreeMap<>();
            for (String tag : List.of("glyf", "hhea", "hmtx", "maxp")) {
                tables.put(tag, lib.getTableBytes(lib.getTableMap().get(tag)).clone());
            }
            int glyf = tables.get("glyf").length;
            int glyphs = 65_535;
            java.nio.ByteBuffer loca = java.nio.ByteBuffer.allocate(4 * (glyphs + 1));
            for (int i = 0; i <= glyphs; i++) {
                loca.putInt(i % 2 == 0 ? 0 : glyf);
            }
            tables.put("loca", loca.array());
            java.nio.ByteBuffer head = java.nio.ByteBuffer.wrap(lib.getTableBytes(lib.getTableMap().get("head")).clone());
            head.putShort(50, (short) 1);
            tables.put("head", head.array());
            java.nio.ByteBuffer.wrap(tables.get("maxp")).putShort(4, (short) glyphs);
            assertRejected(Sfnt.write(tables), WebFonts.FontKind.TRUETYPE);
        }
    }

    private static void assertRejected(byte[] program, WebFonts.FontKind kind) {
        assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> assertThrows(IOException.class, () -> WebFonts.toTrueType(program, kind, Map.of())));
    }

    private static byte[] cff(byte[][] gsubrs, int glyphCount, byte[] body) {
        byte[][] glyphs = new byte[glyphCount][];
        glyphs[0] = FontPrograms.concat(FontPrograms.num2(250), new byte[] {14});
        for (int i = 1; i < glyphCount; i++) {
            glyphs[i] = FontPrograms.concat(FontPrograms.num2(500), FontPrograms.num2(0), FontPrograms.num2(0),
                    new byte[] {21}, body, new byte[] {14});
        }
        byte[] name = FontPrograms.index(new byte[][] {"Hostile".getBytes(StandardCharsets.US_ASCII)});
        byte[] strings = FontPrograms.index(new byte[0][]);
        byte[] gsubrIndex = FontPrograms.index(gsubrs);
        byte[] charset = new byte[1 + 2 * (glyphCount - 1)];
        for (int i = 1; i < glyphCount; i++) {
            charset[2 * i] = (byte) (i + 1);
        }
        byte[] charstrings = FontPrograms.index(glyphs);
        byte[] priv = FontPrograms.concat(FontPrograms.num2(0), new byte[] {20}, FontPrograms.num2(0), new byte[] {21});
        byte[] probe = FontPrograms.index(new byte[][] {top(0, 0, 0, 0)});
        int charsetAt = 4 + name.length + probe.length + strings.length + gsubrIndex.length;
        int charstringsAt = charsetAt + charset.length;
        int privateAt = charstringsAt + charstrings.length;
        byte[] topIndex = FontPrograms.index(new byte[][] {top(charsetAt, charstringsAt, priv.length, privateAt)});
        return FontPrograms.concat(new byte[] {1, 0, 4, 4}, name, topIndex, strings, gsubrIndex, charset, charstrings,
                priv);
    }

    private static byte[] top(int charset, int charstrings, int privateSize, int privateAt) {
        return FontPrograms.concat(FontPrograms.int5(charset), new byte[] {15}, FontPrograms.int5(charstrings),
                new byte[] {17}, FontPrograms.int5(privateSize), FontPrograms.int5(privateAt), new byte[] {18});
    }

    private static byte[] type1(List<byte[]> subrs, byte[] body) {
        return type1(subrs, Map.of("A", FontPrograms.concat(FontPrograms.num(0), FontPrograms.num(500),
                new byte[] {13}, body, new byte[] {14})));
    }

    private static byte[] type1(List<byte[]> subrs, Map<String, byte[]> glyphs) {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        write(plain, "dup /Private 9 dict dup begin\n/RD{string currentfile exch readstring pop}executeonly def\n"
                + "/ND{noaccess def}executeonly def\n/NP{noaccess put}executeonly def\n/lenIV 4 def\n"
                + "/BlueValues [] def\n/password 5839 def\n/Subrs " + subrs.size() + " array\n");
        for (int i = 0; i < subrs.size(); i++) {
            byte[] enc = FontPrograms.charstring(subrs.get(i));
            write(plain, "dup " + i + " " + enc.length + " RD ");
            plain.writeBytes(enc);
            write(plain, " NP\n");
        }
        write(plain, "ND\n2 index /CharStrings " + (glyphs.size() + 1) + " dict dup begin\n");
        glyph(plain, ".notdef", FontPrograms.concat(FontPrograms.num(0), FontPrograms.num(250), new byte[] {13, 14}));
        glyphs.forEach((n, body) -> glyph(plain, n, body));
        write(plain, "end\nend\nreadonly put\nnoaccess put\ndup/FontName get exch definefont pop\n"
                + "mark currentfile closefile\n");
        String clear = "%!PS-AdobeFont-1.0: Hostile 001\n12 dict begin\n/FontName /Hostile def\n"
                + "/Encoding StandardEncoding def\n/PaintType 0 def\n/FontType 1 def\n"
                + "/FontMatrix [0.001 0 0 0.001 0 0] readonly def\n/FontBBox {0 0 1000 1000} readonly def\n"
                + "currentdict end\ncurrentfile eexec\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(clear.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(FontPrograms.eexec(plain.toByteArray()));
        out.writeBytes((("0".repeat(64) + "\n").repeat(8) + "cleartomark\n").getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    private static void glyph(ByteArrayOutputStream plain, String name, byte[] body) {
        byte[] enc = FontPrograms.charstring(body);
        write(plain, "/" + name + " " + enc.length + " RD ");
        plain.writeBytes(enc);
        write(plain, " ND\n");
    }

    private static void write(ByteArrayOutputStream o, String s) {
        o.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
