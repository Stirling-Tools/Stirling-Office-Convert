package stirling.software.officeconvert.topdf.font;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

public final class PdfFonts implements Closeable {

    public record Embedded(COSDictionary font, FontFace face) {}

    private final PDDocument document;

    private final FontLibrary library;

    private final Map<FontProgram, Embedded> loaded = new IdentityHashMap<>();

    private final List<Closeable> open = new ArrayList<>();

    private final List<String> substitutions = new ArrayList<>();

    private final Set<String> noted = new HashSet<>();

    private final Map<COSDictionary, Use> uses = new IdentityHashMap<>();

    private final Map<COSDictionary, SubsetFont> subsets = new IdentityHashMap<>();

    private final Map<FontProgram, Embedded> missing = new IdentityHashMap<>();

    private final Map<COSDictionary, Map<String, Integer>> missingCodes = new IdentityHashMap<>();

    private final Set<Integer> uncovered = new LinkedHashSet<>();

    private long uncoveredCount;

    static final int MAX_MISSING = 0xFFFE;

    static final int MAX_CODE = 0xFFFF;

    private static final int MAX_UNCOVERED = 4096;

    private static final int LISTED_UNCOVERED = 12;

    private boolean closed;

    private boolean finished;

    private static final class Use {

        final FontProgram program;

        final BitSet glyphs = new BitSet();

        final Map<Integer, String> text = new HashMap<>();

        final BitSet trailing = new BitSet();

        final BitSet texted = new BitSet();

        final BitSet subsetCodes = new BitSet();

        final Map<String, Integer> aliases = new HashMap<>();

        final List<Integer> aliasGlyphs = new ArrayList<>();

        final List<String> aliasTexts = new ArrayList<>();

        boolean shaped;

        Use(FontProgram program) {
            this.program = program;
        }
    }

    public PdfFonts(PDDocument document, FontLibrary library) {
        this.document = Objects.requireNonNull(document, "document");
        this.library = Objects.requireNonNull(library, "library");
    }

    public Embedded font(FontFace face) throws IOException {
        Objects.requireNonNull(face, "face");
        if (closed) {
            throw new IllegalStateException("The fonts were closed");
        }
        String note = face.note();
        if (note != null && noted.add(note)) {
            substitutions.add(note);
        }
        Embedded known = loaded.get(face.program());
        if (known != null) {
            return known.face().program() == face.program() ? new Embedded(known.font(), face) : known;
        }
        Embedded made;
        try {
            made = new Embedded(load(face.program()), face);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            FontFace stand = standIn(face);
            if (stand == null) {
                throw new IOException("The font " + face.family() + " cannot be embedded: " + e.getMessage(), e);
            }
            substitutions.add("The font " + face.program().entry().describe() + " could not be embedded ("
                    + e.getMessage() + "); its text uses " + stand.family());
            Embedded standing = loaded.get(stand.program());
            if (standing == null) {
                standing = new Embedded(load(stand.program()), stand);
                loaded.put(stand.program(), standing);
            }
            made = new Embedded(standing.font(), stand);
        }
        loaded.put(face.program(), made);
        return made;
    }

    /** A face whose missing-glyph box is visible, for faces that leave theirs empty. */
    public FontFace boxFace(boolean bold, boolean italic) {
        FontFace f = library.lastResort(bold, italic);
        return f.notdefVisible() ? f : library.lastResort(false, false);
    }

    /**
     * A font whose every code draws the missing-glyph box of {@code face} (or of a face whose box shows), with a
     * ToUnicode entry per code so that characters no font has still extract as text.
     */
    public Embedded missing(FontFace face) throws IOException {
        Objects.requireNonNull(face, "face");
        FontEntry entry = face.program().entry();
        FontFace box = face.notdefVisible() && !entry.noSubsetting() && !entry.postScript() ? face
                : boxFace(face.boldStyle(), face.italicStyle());
        Embedded known = missing.get(box.program());
        if (known != null) {
            return known;
        }
        Embedded made;
        try {
            made = new Embedded(missingFont(box.program()), box);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            FontFace last = library.lastResort(false, false);
            if (last.program() == box.program()) {
                throw e;
            }
            return missing(last);
        }
        missing.put(box.program(), made);
        return made;
    }

    /** The code that draws the box for {@code codePoint} in a font from {@link #missing}; 0 draws it without text. */
    public int missingCode(Embedded font, int codePoint) {
        String text = new String(Character.toChars(codePoint));
        Map<String, Integer> codes = missingCodes.get(font.font());
        if (codes == null) {
            return 0;
        }
        Integer known = codes.get(text);
        if (known != null) {
            return known;
        }
        if (codes.size() >= MAX_MISSING) {
            return 0;
        }
        int made = codes.size() + 1;
        codes.put(text, made);
        return made;
    }

    private COSDictionary missingFont(FontProgram program) throws IOException {
        FontProgram.Opened opened = FontProgram.open(program.entry());
        try {
            SubsetFont subset = new SubsetFont(document, opened.font());
            COSDictionary font = subset.dictionary();
            open.add(opened);
            subsets.put(font, subset);
            missingCodes.put(font, new LinkedHashMap<>());
            return font;
        } catch (IOException | RuntimeException e) {
            opened.close();
            throw e;
        }
    }

    /** An installed face that has a character {@code like} lacks, drawn in its place; null when none has it. */
    public FontFace fallback(int codePoint, FontFace like) {
        FontFace f = library.fallback(codePoint, like);
        return f != null && f.covers(codePoint) && (!f.sameProgram(like) || f.symbolStandIn() && !f.equals(like)) ? f
                : null;
    }

    public void uncovered(int codePoint) {
        uncoveredCount++;
        if (uncovered.size() < MAX_UNCOVERED) {
            uncovered.add(codePoint);
        }
    }

    public List<String> substitutions() {
        if (uncovered.isEmpty()) {
            return Collections.unmodifiableList(substitutions);
        }
        StringBuilder b = new StringBuilder("No installed font has ").append(uncovered.size())
                .append(uncovered.size() == MAX_UNCOVERED ? " or more" : "").append(" of the characters, drawn ")
                .append(uncoveredCount).append(uncoveredCount == 1 ? " time" : " times").append(" as boxes:");
        int listed = 0;
        for (int cp : uncovered) {
            if (listed++ == LISTED_UNCOVERED) {
                b.append(" and others");
                break;
            }
            b.append(listed == 1 ? " " : ", ").append(String.format(Locale.ROOT, "U+%04X", cp));
            if (!Character.isISOControl(cp) && Character.getType(cp) != Character.PRIVATE_USE) {
                b.append(' ').appendCodePoint(cp);
            }
        }
        List<String> all = new ArrayList<>(substitutions);
        all.add(b.toString());
        return Collections.unmodifiableList(all);
    }

    public int encoded(Embedded font, int glyph, int codePoint) {
        Use u = uses.get(font.font());
        if (u == null || glyph <= 0 || codePoint <= 0) {
            return glyph;
        }
        int canonical = u.program.codePointOf(glyph);
        String assigned = u.text.get(glyph);
        if (canonical == codePoint && !presentationForm(canonical)
                && (assigned == null || assigned.codePointCount(0, assigned.length()) == 1
                        && assigned.codePointAt(0) == codePoint)) {
            return glyph;
        }
        String text = new String(Character.toChars(codePoint));
        String known = assigned != null ? assigned : canonical > 0 && !presentationForm(canonical)
                ? new String(Character.toChars(canonical)) : null;
        if (known == null || known.equals(text)) {
            u.text.putIfAbsent(glyph, text);
            u.texted.set(glyph);
            return glyph;
        }
        u.text.putIfAbsent(glyph, known);
        u.texted.set(glyph);
        u.shaped = true;
        return alias(u, glyph, text);
    }

    public void used(Embedded font, int[] glyphs, int[] codePoints, int count) {
        Use u = uses.get(font.font());
        if (u == null) {
            return;
        }
        SubsetFont subset = subsets.get(font.font());
        for (int i = 0; i < count; i++) {
            if (glyphs[i] > 0) {
                u.glyphs.set(glyphs[i]);
                if (codePoints[i] > 0 && !u.texted.get(glyphs[i])) {
                    u.texted.set(glyphs[i]);
                    u.text.putIfAbsent(glyphs[i], new String(Character.toChars(codePoints[i])));
                }
                if (subset != null && (codePoints[i] < 0 || !u.subsetCodes.get(codePoints[i]))) {
                    if (codePoints[i] >= 0) {
                        u.subsetCodes.set(codePoints[i]);
                    }
                    subset.addCodePoint(codePoints[i]);
                }
            } else if (glyphs[i] == 0) {
                u.glyphs.set(0);
            }
        }
    }

    // The code to draw each glyph of a shaped run with: a group's carrier stands for the group's text, under a
    // code of its own when its glyph already stands for other text, and the group's other glyphs for nothing
    public int[] used(Embedded font, GlyphRun run) {
        int[] codes = run.glyphs();
        Use u = uses.get(font.font());
        if (u == null) {
            return codes;
        }
        u.shaped = true;
        Set<Integer> added = new HashSet<>();
        int[] groups = run.groups();
        int end = -1;
        int carrier = -1;
        String group = null;
        for (int i = 0; i < run.size(); i++) {
            if (groups[i] > 0) {
                end = groups[i];
                carrier = run.carrier(i, end);
                group = run.groupText(i, end);
            }
            int g = run.glyph(i);
            if (g <= 0) {
                continue;
            }
            if (!u.glyphs.get(g)) {
                u.glyphs.set(g);
                added.add(g);
            }
            String t = run.clusterText(i);
            if (i < end) {
                t = i == carrier ? group : null;
            }
            if (t == null || t.isEmpty()) {
                u.trailing.set(g);
            } else {
                codes[i] = code(u, g, t);
            }
        }
        SubsetFont subset = subsets.get(font.font());
        if (!added.isEmpty() && subset != null) {
            subset.addGlyphs(added);
        }
        return codes;
    }

    // A glyph keeps its own character; text it stands in for elsewhere gets a code of its own
    private static int code(Use u, int glyph, String text) {
        int cp = u.program.codePointOf(glyph);
        String known = cp > 0 && !presentationForm(cp) ? new String(Character.toChars(cp)) : u.text.get(glyph);
        if (known == null || known.equals(text)) {
            u.text.putIfAbsent(glyph, text);
            u.texted.set(glyph);
            return glyph;
        }
        return alias(u, glyph, text);
    }

    private static int alias(Use u, int glyph, String text) {
        String key = glyph + "\u0000" + text;
        Integer alias = u.aliases.get(key);
        if (alias != null) {
            return alias;
        }
        int next = u.program.glyphCount() + u.aliasGlyphs.size();
        if (next > MAX_CODE) {
            return glyph;
        }
        u.aliases.put(key, next);
        u.aliasGlyphs.add(glyph);
        u.aliasTexts.add(text);
        return next;
    }

    public void finish() throws IOException {
        if (finished || closed) {
            return;
        }
        finished = true;
        for (Map.Entry<COSDictionary, Use> e : uses.entrySet()) {
            COSDictionary font = e.getKey();
            Use u = e.getValue();
            boolean whole = false;
            SubsetFont subset = subsets.get(font);
            if (subset != null && subset.postScript()) {
                for (int k = 0; k < u.aliasGlyphs.size(); k++) {
                    subset.alias(u.program.glyphCount() + k, u.aliasGlyphs.get(k));
                }
            }
            if (subset != null) {
                try {
                    subset.subset();
                } catch (IOException | RuntimeException ex) {
                    whole = true;
                    embedWhole(font, u, ex);
                }
            }
            if (!u.aliasGlyphs.isEmpty()) {
                aliases(font, u);
            }
            if (u.shaped || whole) {
                toUnicode(font, u);
            }
        }
        for (Embedded e : missing.values()) {
            finishMissing(e);
        }
    }

    // Codes past the font's own glyphs draw the glyph they alias at its width
    private void aliases(COSDictionary font, Use u) throws IOException {
        COSDictionary cid = descendant(font);
        if (COSName.CID_FONT_TYPE0.equals(cid.getCOSName(COSName.SUBTYPE))) {
            COSArray widths = cid.getDictionaryObject(COSName.W) instanceof COSArray w ? w : new COSArray();
            float scale = 1000f / u.program.metrics().unitsPerEm();
            for (int k = 0; k < u.aliasGlyphs.size(); k++) {
                COSArray one = new COSArray();
                one.add(COSInteger.get(Math.round(u.program.advanceOfGlyph(u.aliasGlyphs.get(k)) * scale)));
                widths.add(COSInteger.get(u.program.glyphCount() + k));
                widths.add(one);
            }
            cid.setItem(COSName.W, widths);
            return;
        }
        int last = u.program.glyphCount() + u.aliasGlyphs.size() - 1;
        byte[] map = new byte[2 * (last + 1)];
        if (cid.getDictionaryObject(COSName.CID_TO_GID_MAP) instanceof COSStream stream) {
            try (InputStream in = stream.createInputStream()) {
                byte[] known = in.readNBytes(map.length);
                System.arraycopy(known, 0, map, 0, known.length);
            }
        } else {
            for (int g = 0; g < u.program.glyphCount(); g++) {
                map[2 * g] = (byte) (g >> 8);
                map[2 * g + 1] = (byte) g;
            }
        }
        COSArray widths = cid.getDictionaryObject(COSName.W) instanceof COSArray w ? w : new COSArray();
        float scale = 1000f / u.program.metrics().unitsPerEm();
        for (int k = 0; k < u.aliasGlyphs.size(); k++) {
            int code = u.program.glyphCount() + k;
            int glyph = u.aliasGlyphs.get(k);
            map[2 * code] = map[2 * glyph];
            map[2 * code + 1] = map[2 * glyph + 1];
            COSArray one = new COSArray();
            one.add(COSInteger.get(Math.round(u.program.advanceOfGlyph(glyph) * scale)));
            widths.add(COSInteger.get(code));
            widths.add(one);
        }
        COSStream stream = document.getDocument().createCOSStream();
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(map);
        }
        cid.setItem(COSName.CID_TO_GID_MAP, stream);
        cid.setItem(COSName.W, widths);
        PDFontDescriptor fd = descriptor(font);
        if (fd != null) {
            fd.getCOSObject().removeItem(COSName.CID_SET);
        }
    }

    // Every code maps to glyph 0, the box, at its own width; the ToUnicode gives each code its character
    private void finishMissing(Embedded e) throws IOException {
        COSDictionary font = e.font();
        FontProgram program = e.face().program();
        Map<String, Integer> codes = missingCodes.get(font);
        try {
            subsets.get(font).subset();
        } catch (IOException | RuntimeException ex) {
            PDFontDescriptor fd = descriptor(font);
            if (fd == null) {
                throw new IOException("The missing-glyph font has no descriptor", ex);
            }
            byte[] data = program.whole();
            PDStream stream = new PDStream(document, new ByteArrayInputStream(data), COSName.FLATE_DECODE);
            stream.getCOSObject().setInt(COSName.LENGTH1, data.length);
            fd.setFontFile2(stream);
        }
        PDFontDescriptor fd = descriptor(font);
        if (fd != null) {
            fd.getCOSObject().removeItem(COSName.CID_SET);
        }
        COSDictionary cid = descendant(font);
        cid.removeItem(COSName.W);
        cid.setInt(COSName.DW, Math.round(program.advanceOfGlyph(0) * 1000f / program.metrics().unitsPerEm()));
        COSStream map = document.getDocument().createCOSStream();
        try (OutputStream out = map.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(new byte[2 * (codes.size() + 1)]);
        }
        cid.setItem(COSName.CID_TO_GID_MAP, map);
        List<Integer> glyphs = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (Map.Entry<String, Integer> c : codes.entrySet()) {
            glyphs.add(c.getValue());
            texts.add(c.getKey());
        }
        toUnicode(font, glyphs, texts);
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        for (Closeable c : open) {
            try {
                c.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        open.clear();
        loaded.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private COSDictionary load(FontProgram program) throws IOException {
        FontEntry entry = program.entry();
        if (!entry.usable()) {
            throw new IOException(entry.unusable() == null ? "the font is damaged" : entry.unusable());
        }
        FontProgram.Opened opened = FontProgram.open(entry);
        try {
            boolean subset = !entry.noSubsetting();
            if (!subset && entry.index() >= 0) {
                throw new IOException("its licence forbids subsetting and a collection cannot be embedded whole");
            }
            if (!subset && entry.postScript()) {
                throw new IOException("its licence forbids subsetting and its PostScript outlines cannot be embedded"
                        + " whole");
            }
            COSDictionary font;
            if (subset) {
                SubsetFont s = new SubsetFont(document, opened.font(), entry.postScript() ? entry : null);
                font = s.dictionary();
                subsets.put(font, s);
            } else {
                font = PDType0Font.load(document, opened.font(), false).getCOSObject();
            }
            open.add(opened);
            uses.put(font, new Use(program));
            return font;
        } catch (IOException | RuntimeException e) {
            opened.close();
            throw e;
        }
    }

    private void embedWhole(COSDictionary font, Use u, Exception why) {
        String name = u.program.entry().describe();
        String reason = why.getMessage() == null ? why.getClass().getSimpleName() : why.getMessage();
        COSDictionary cid = descendant(font);
        if (u.program.entry().postScript()) {
            substitutions.add("The font " + name + " could not be subset (" + reason + "); its text may not display");
            return;
        }
        cid.setItem(COSName.CID_TO_GID_MAP, COSName.IDENTITY);
        cid.setItem(COSName.W, widths(u));
        PDFontDescriptor fd = descriptor(font);
        try {
            if (fd == null) {
                throw new IOException("the font has no descriptor");
            }
            fd.getCOSObject().removeItem(COSName.CID_SET);
            byte[] data = u.program.whole();
            PDStream stream = new PDStream(document, new ByteArrayInputStream(data), COSName.FLATE_DECODE);
            stream.getCOSObject().setInt(COSName.LENGTH1, data.length);
            fd.setFontFile2(stream);
            substitutions.add("The font " + name + " could not be subset (" + reason + "); it was embedded whole");
        } catch (IOException | RuntimeException e) {
            substitutions.add("The font " + name + " could not be subset (" + reason + ") or embedded (" + e.getMessage()
                    + "); its text may not display");
        }
    }

    private static COSArray widths(Use u) {
        COSArray w = new COSArray();
        float scale = 1000f / u.program.metrics().unitsPerEm();
        int g = u.glyphs.nextSetBit(0);
        while (g >= 0) {
            COSArray run = new COSArray();
            int start = g;
            while (g >= 0 && g == start + run.size()) {
                run.add(COSInteger.get(Math.round(u.program.advanceOfGlyph(g) * scale)));
                g = u.glyphs.nextSetBit(g + 1);
            }
            w.add(COSInteger.get(start));
            w.add(run);
        }
        return w;
    }

    private void toUnicode(COSDictionary font, Use u) throws IOException {
        List<Integer> glyphs = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int g = u.glyphs.nextSetBit(0); g >= 0; g = u.glyphs.nextSetBit(g + 1)) {
            String t = unicode(u, g);
            if (t != null && !t.isEmpty()) {
                glyphs.add(g);
                texts.add(t);
            }
        }
        for (int k = 0; k < u.aliasGlyphs.size(); k++) {
            glyphs.add(u.program.glyphCount() + k);
            texts.add(u.aliasTexts.get(k));
        }
        toUnicode(font, glyphs, texts);
    }

    // The text a glyph was first drawn for, else the character the font maps to it; the glyphs a letter or syllable
    // is drawn with beside the one that carries its text stand for nothing
    private static String unicode(Use u, int glyph) {
        String known = u.text.get(glyph);
        if (known != null) {
            return known;
        }
        int cp = u.program.codePointOf(glyph);
        if (cp <= 0 || presentationForm(cp) || u.trailing.get(glyph)) {
            return null;
        }
        return new String(Character.toChars(cp));
    }

    private void toUnicode(COSDictionary font, List<Integer> glyphs, List<String> texts) throws IOException {
        StringBuilder b = new StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                + "/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n"
                + "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
        for (int i = 0; i < glyphs.size(); i += 100) {
            int end = Math.min(glyphs.size(), i + 100);
            b.append(end - i).append(" beginbfchar\n");
            for (int k = i; k < end; k++) {
                b.append('<').append(hex(glyphs.get(k), 4)).append("> <");
                for (byte x : texts.get(k).getBytes(StandardCharsets.UTF_16BE)) {
                    b.append(hex(x & 0xFF, 2));
                }
                b.append(">\n");
            }
            b.append("endbfchar\n");
        }
        b.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        COSStream stream = document.getDocument().createCOSStream();
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(b.toString().getBytes(StandardCharsets.US_ASCII));
        }
        font.setItem(COSName.TO_UNICODE, stream);
    }

    private static COSDictionary descendant(COSDictionary font) {
        return (COSDictionary) ((COSArray) font.getDictionaryObject(COSName.DESCENDANT_FONTS)).getObject(0);
    }

    private static PDFontDescriptor descriptor(COSDictionary font) {
        COSDictionary fd = descendant(font).getCOSDictionary(COSName.FONT_DESC);
        return fd == null ? null : new PDFontDescriptor(fd);
    }

    private static String hex(int value, int digits) {
        return String.format(Locale.ROOT, "%0" + digits + "X", value);
    }

    private static boolean presentationForm(int cp) {
        return cp >= 0xFB00 && cp <= 0xFDFF || cp >= 0xFE70 && cp <= 0xFEFF || cp >= 0xE000 && cp <= 0xF8FF;
    }

    private FontFace standIn(FontFace face) {
        for (String family : Substitutes.generic(face.family())) {
            FontFace f = library.exact(family, face.boldStyle(), face.italicStyle());
            if (f != null && f.program() != face.program()) {
                return f;
            }
        }
        return null;
    }
}
