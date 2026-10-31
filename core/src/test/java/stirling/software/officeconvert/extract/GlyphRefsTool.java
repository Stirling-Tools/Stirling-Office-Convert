package stirling.software.officeconvert.extract;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.NameRecord;
import org.apache.fontbox.ttf.NamingTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;

public final class GlyphRefsTool {

    private record Source(String file, int style, boolean extrasOnly) {

        Source(String file, int style) {
            this(file, style, false);
        }
    }

    private static final List<Source> SOURCES = List.of(
            new Source("cmr10.ttf", 0),
            new Source("cmbx10.ttf", GlyphRef.BOLD),
            new Source("cmti10.ttf", GlyphRef.ITALIC),
            new Source("cmss10.ttf", GlyphRef.SANS),
            new Source("cmtt10.ttf", GlyphRef.MONO),
            new Source("cmmi10.ttf", GlyphRef.SANS | GlyphRef.ITALIC),
            new Source("LiberationSerif-Regular.ttf", 0),
            new Source("LiberationSerif-Bold.ttf", GlyphRef.BOLD),
            new Source("LiberationSerif-Italic.ttf", GlyphRef.ITALIC),
            new Source("LiberationSerif-BoldItalic.ttf", GlyphRef.BOLD | GlyphRef.ITALIC),
            new Source("LiberationSans-Regular.ttf", GlyphRef.SANS),
            new Source("LiberationSans-Bold.ttf", GlyphRef.SANS | GlyphRef.BOLD),
            new Source("LiberationSans-Italic.ttf", GlyphRef.SANS | GlyphRef.ITALIC),
            new Source("Carlito-Regular.ttf", GlyphRef.SANS),
            new Source("Carlito-Bold.ttf", GlyphRef.SANS | GlyphRef.BOLD),
            new Source("Carlito-Italic.ttf", GlyphRef.SANS | GlyphRef.ITALIC),
            new Source("Caladea-Regular.ttf", 0),
            new Source("Caladea-Bold.ttf", GlyphRef.BOLD),
            new Source("Caladea-Italic.ttf", GlyphRef.ITALIC),
            new Source("DejaVuSansMono.ttf", GlyphRef.SANS | GlyphRef.MONO),
            new Source("LiberationMono-Regular.ttf", GlyphRef.MONO),
            new Source("DejaVuSerif.ttf", 0),
            new Source("DejaVuSerif-Bold.ttf", GlyphRef.BOLD),
            new Source("DejaVuSans.ttf", GlyphRef.SANS),
            new Source("DejaVuSans-Bold.ttf", GlyphRef.SANS | GlyphRef.BOLD),
            new Source("DejaVuSans-Oblique.ttf", GlyphRef.SANS | GlyphRef.ITALIC, true),
            new Source("DejaVuSans-BoldOblique.ttf", GlyphRef.SANS | GlyphRef.BOLD | GlyphRef.ITALIC, true),
            new Source("LiberationSans-BoldItalic.ttf", GlyphRef.SANS | GlyphRef.BOLD | GlyphRef.ITALIC, true),
            new Source("NotoSans-Regular.ttf", GlyphRef.SANS, true),
            new Source("NotoSans-Italic.ttf", GlyphRef.SANS | GlyphRef.ITALIC, true));

    private static final String[][] NAMED = {
        {".", "period"}, {",", "comma"}, {";", "semicolon"}, {":", "colon"}, {"!", "exclam"}, {"?", "question"},
        {"’", "quoteright"}, {"‘", "quoteleft"}, {"“", "quotedblleft"}, {"”", "quotedblright"},
        {"(", "parenleft"}, {")", "parenright"}, {"[", "bracketleft"}, {"]", "bracketright"}, {"-", "hyphen"},
        {"–", "endash"}, {"—", "emdash"}, {"/", "slash"}, {"*", "asterisk"}, {"+", "plus"}, {"=", "equal"},
        {"%", "percent"}, {"&", "ampersand"}, {"•", "bullet"}, {"·", "periodcentered"}, {"fi", "fi"},
        {"fl", "fl"}, {"ff", "ff"}, {"ffi", "ffi"}, {"ffl", "ffl"}
    };

    private static final String EXTRA = "_@#\\|~'\"\u2026\u2023\u00B4`\u02C6\u02DC\u00A8\u02DA\u00B8\u0131"
            + "\u00E0\u00E1\u00E2\u00E3\u00E4\u00E5\u00E7\u00E8\u00E9\u00EA\u00EB\u00EC\u00ED\u00EE\u00EF"
            + "\u00F1\u00F2\u00F3\u00F4\u00F5\u00F6\u00F8\u00F9\u00FA\u00FB\u00FC\u00FD\u00FF\u00DF"
            + "\u00C0\u00C1\u00C2\u00C3\u00C4\u00C5\u00C7\u00C8\u00C9\u00CA\u00CB\u00CD\u00D1\u00D3\u00D4"
            + "\u00D5\u00D6\u00D8\u00DA\u00DC"
            + "\u0393\u0394\u0398\u039B\u03A0\u03A3\u03A6\u03A8\u03A9"
            + "\u03B1\u03B2\u03B3\u03B4\u03B5\u03B6\u03B7\u03B8\u03B9\u03BA\u03BB\u03BC\u03BD\u03BE\u03C0"
            + "\u03C1\u03C2\u03C3\u03C4\u03C5\u03C6\u03C7\u03C8\u03C9";

    private static final Map<String, Integer> LIGATURES =
            Map.of("ff", 0xFB00, "fi", 0xFB01, "fl", 0xFB02, "ffi", 0xFB03, "ffl", 0xFB04);

    private static final String[] DIGITS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"};

    private GlyphRefsTool() {}

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: GlyphRefsTool <font folder> <glyph-refs.txt> <notice file>");
        }
        Path fonts = Path.of(args[0]);
        try (PrintWriter refs = new PrintWriter(Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.US_ASCII));
                PrintWriter notice = new PrintWriter(Files.newBufferedWriter(Path.of(args[2]), StandardCharsets.UTF_8))) {
            refs.println("# Reference glyph prints: ink box and advance in 1/1000 em, then a " + GlyphPrint.GRID + "x"
                    + GlyphPrint.GRID + " ink grid in hex.");
            refs.println("# Built by GlyphRefsTool from the fonts listed in META-INF/licenses/glyph-refs/NOTICE.txt.");
            refs.println("# Line: text|style bits|box|grid");
            notice.println("The reference glyph prints in stirling/software/officeconvert/extract/glyph-refs.txt are");
            notice.println("16x16 ink grids and ink boxes measured from the fonts below by GlyphRefsTool. No outlines or");
            notice.println("other font software are included. Each entry is read from the font file's own name table.");
            for (Source source : SOURCES) {
                Path file = fonts.resolve(source.file());
                TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBufferedFile(file.toFile()));
                try {
                    describe(notice, file, ttf);
                    for (String[] ch : source.extrasOnly() ? extras() : characters()) {
                        GlyphRef ref = reference(ttf, ch[0], ch[1], source.style());
                        if (ref != null) {
                            refs.println(line(ref));
                        }
                    }
                } finally {
                    ttf.close();
                }
            }
        }
    }

    private static List<String[]> characters() {
        List<String[]> out = new ArrayList<>();
        for (char c = 'a'; c <= 'z'; c++) {
            out.add(new String[] {String.valueOf(c), String.valueOf(c)});
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            out.add(new String[] {String.valueOf(c), String.valueOf(c)});
        }
        for (int i = 0; i < 10; i++) {
            out.add(new String[] {String.valueOf((char) ('0' + i)), DIGITS[i]});
        }
        out.addAll(List.of(NAMED));
        out.addAll(extras());
        return out;
    }

    private static List<String[]> extras() {
        List<String[]> out = new ArrayList<>();
        EXTRA.codePoints().forEach(cp -> out.add(new String[] {Character.toString(cp), String.format("uni%04X", cp)}));
        return out;
    }

    private static GlyphRef reference(TrueTypeFont ttf, String text, String name, int style) throws IOException {
        int gid = ttf.getPostScript() == null ? 0 : ttf.nameToGID(name);
        CmapLookup cmap = ttf.getUnicodeCmapLookup(false);
        boolean single = text.codePointCount(0, text.length()) == 1;
        if (gid <= 0 && cmap != null && (single || LIGATURES.containsKey(text))) {
            gid = cmap.getGlyphId(LIGATURES.getOrDefault(text, text.codePointAt(0)));
        }
        GlyphData data = gid > 0 ? ttf.getGlyph().getGlyph(gid) : null;
        if (data == null) {
            return null;
        }
        float upem = ttf.getUnitsPerEm();
        GeneralPath path = new GeneralPath(data.getPath());
        path.transform(AffineTransform.getScaleInstance(1 / upem, 1 / upem));
        GlyphPrint print = GlyphPrint.of(path, ttf.getAdvanceWidth(gid) / upem);
        return print == null ? null : new GlyphRef(text, style, print);
    }

    private static String line(GlyphRef ref) {
        StringBuilder sb = new StringBuilder();
        ref.text().codePoints().forEach(cp -> sb.append(sb.isEmpty() ? "" : " ").append(String.format("%04X", cp)));
        GlyphPrint p = ref.print();
        sb.append('|').append(ref.style()).append('|').append(milli(p.left())).append(' ').append(milli(p.bottom()))
                .append(' ').append(milli(p.right())).append(' ').append(milli(p.top())).append(' ')
                .append(milli(p.advance())).append('|');
        for (float c : p.cells()) {
            sb.append(Character.forDigit(Math.clamp(Math.round(c * 15), 0, 15), 16));
        }
        return sb.toString();
    }

    private static int milli(float value) {
        return Math.round(value * 1000);
    }

    private static void describe(PrintWriter notice, Path file, TrueTypeFont ttf)
            throws IOException, NoSuchAlgorithmException {
        NamingTable names = ttf.getNaming();
        notice.println();
        notice.println(file.getFileName() + "  " + name(names, NameRecord.NAME_FULL_FONT_NAME) + ", "
                + name(names, NameRecord.NAME_VERSION));
        notice.println("  SHA-256 " + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        notice.println(indented(name(names, NameRecord.NAME_COPYRIGHT)));
        notice.println("  Licence: " + name(names, 14));
        notice.println(indented(name(names, 13)));
    }

    private static String indented(String text) {
        StringBuilder sb = new StringBuilder();
        text.lines().map(String::strip)
                .forEach(l -> sb.append(sb.isEmpty() ? "" : "\n").append(l.isEmpty() ? "" : "    ").append(l));
        return sb.toString();
    }

    private static String name(NamingTable names, int id) {
        if (names == null) {
            return "";
        }
        for (NameRecord r : names.getNameRecords()) {
            if (r.getNameId() == id && r.getPlatformId() == NameRecord.PLATFORM_WINDOWS && r.getLanguageId() == 0x409) {
                return r.getString().strip();
            }
        }
        for (NameRecord r : names.getNameRecords()) {
            if (r.getNameId() == id) {
                return r.getString().strip();
            }
        }
        return "";
    }
}
