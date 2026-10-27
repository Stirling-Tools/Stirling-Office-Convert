package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;

import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.font.FontSet;

public final class FontEmbedder {

    public record Result(List<String> embedded, List<String> failed) {

        public Result {
            embedded = List.copyOf(embedded);
            failed = List.copyOf(failed);
        }
    }

    private FontEmbedder() {}

    public static Result embedMissing(PDDocument document) throws IOException {
        return embedMissing(document, FontSet.system());
    }

    public static Result embedMissing(PDDocument document, FontSet fonts) throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(fonts, "fonts");
        PdfFiles.stopIfInterrupted();
        try {
            return run(document, fonts);
        } catch (RuntimeException e) {
            PdfFiles.stopIfInterrupted();
            throw new IOException("The fonts could not be embedded: " + e, e);
        }
    }

    private static Result run(PDDocument doc, FontSet fonts) throws IOException {
        FontUsage usage = new FontUsage();
        ContentGraph graph = ContentGraph.of(doc);
        Set<ContentGraph.Node> unreadable = new HashSet<>();
        for (ContentGraph.Node n : graph.nodes()) {
            PdfFiles.stopIfInterrupted();
            List<Object> tokens;
            try {
                tokens = ContentTokens.salvage(n.streams()).tokens();
            } catch (IOException e) {
                Decoded.rethrowFatal(e);
                unreadable.add(n);
                continue;
            }
            usage.scan(tokens, n.resources(), n.owner());
        }
        usage.resolve();
        Map<COSDictionary, TreeSet<Integer>> targets = new IdentityHashMap<>(usage.codes());
        for (ContentGraph.Node n : graph.nodes()) {
            COSDictionary dict = n.resources() == null ? null
                    : ContentGraph.dict(n.resources().getDictionaryObject(COSName.FONT));
            if (dict == null) {
                continue;
            }
            for (COSBase v : dict.getValues()) {
                if (ContentGraph.dict(v) instanceof COSDictionary font) {
                    TreeSet<Integer> codes = targets.computeIfAbsent(font, k -> new TreeSet<>());
                    if (unreadable.contains(n)) {
                        codes.addAll(allCodes(font));
                    }
                }
            }
        }
        List<String> embedded = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        FontLibrary library = null;
        for (List<COSDictionary> group : groups(targets)) {
            PdfFiles.stopIfInterrupted();
            COSDictionary leader = group.get(0);
            TreeSet<Integer> codes = new TreeSet<>();
            for (COSDictionary d : group) {
                codes.addAll(targets.get(d));
            }
            PDFont font;
            try {
                font = PDFontFactory.createFont(leader);
            } catch (IOException | RuntimeException x) {
                PdfFiles.stopIfInterrupted();
                failed.add(name(leader) + ": " + x.getMessage());
                continue;
            }
            if (font instanceof PDType3Font || FontCheck.embedded(font instanceof PDType0Font t0
                    && t0.getDescendantFont() != null ? t0.getDescendantFont().getFontDescriptor()
                            : font.getFontDescriptor()) && readable(font)) {
                continue;
            }
            if (library == null) {
                library = fonts.library();
            }
            try {
                boolean declared = leader.containsKey(COSName.TO_UNICODE);
                TreeMap<Integer, String> text = new TreeMap<>();
                for (int code : codes) {
                    try {
                        String unicode = font.toUnicode(code);
                        if (unicode != null && !unicode.isEmpty()) {
                            text.put(code, unicode);
                        }
                    } catch (RuntimeException e) {
                        PdfFiles.stopIfInterrupted();
                    }
                }
                embedded.add(FontFixer.substitute(doc, font, codes, PdfALevel.A2B, library,
                        usage.bytesPerCode(leader), false));
                if (!text.isEmpty() && (declared || !(font instanceof PDType0Font) || !keepsText(leader, text))) {
                    leader.setItem(COSName.TO_UNICODE, ToUnicodeWriter.preserve(doc, text,
                            font instanceof PDType0Font ? usage.bytesPerCode(leader) : 1));
                }
            } catch (IOException | RuntimeException x) {
                PdfFiles.stopIfInterrupted();
                failed.add(FontRebuild.cleanName(font.getName()) + ": " + x.getMessage());
                continue;
            }
            for (COSDictionary d : group.subList(1, group.size())) {
                d.clear();
                d.addAll(leader);
            }
        }
        return new Result(embedded, failed);
    }

    private static boolean keepsText(COSDictionary font, Map<Integer, String> text) throws IOException {
        font.removeItem(COSName.TO_UNICODE);
        try {
            PDFont fresh = PDFontFactory.createFont(font);
            for (Map.Entry<Integer, String> entry : text.entrySet()) {
                if (!entry.getValue().equals(fresh.toUnicode(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            PdfFiles.stopIfInterrupted();
            return false;
        }
    }

    private static List<List<COSDictionary>> groups(Map<COSDictionary, TreeSet<Integer>> targets) {
        Map<String, List<List<COSDictionary>>> byName = new HashMap<>();
        List<List<COSDictionary>> out = new ArrayList<>();
        for (COSDictionary font : targets.keySet()) {
            String key = font.getNameAsString(COSName.SUBTYPE) + "/" + font.getNameAsString(COSName.BASE_FONT);
            List<List<COSDictionary>> candidates = byName.computeIfAbsent(key, k -> new ArrayList<>());
            List<COSDictionary> match = null;
            for (List<COSDictionary> g : candidates) {
                if (CosEquality.same(g.get(0), font)) {
                    match = g;
                    break;
                }
            }
            if (match == null) {
                match = new ArrayList<>();
                candidates.add(match);
                out.add(match);
            }
            match.add(font);
        }
        return out;
    }

    private static boolean readable(PDFont font) throws IOException {
        boolean damaged = !font.isEmbedded() || font.isDamaged() || font instanceof PDType0Font t0 && t0.getDescendantFont() != null
                && FontCheck.isDamaged(t0.getDescendantFont());
        if (!damaged) {
            return true;
        }
        try {
            WebFonts.toTrueType(font);
            return true;
        } catch (IOException e) {
            Decoded.rethrowFatal(e);
            PdfFiles.stopIfInterrupted();
            return false;
        }
    }

    private static TreeSet<Integer> allCodes(COSDictionary font) {
        TreeSet<Integer> codes = new TreeSet<>();
        if (COSName.TYPE0.equals(font.getCOSName(COSName.SUBTYPE))) {
            return codes;
        }
        int first = Math.max(0, font.getInt(COSName.FIRST_CHAR, 32));
        int last = Math.min(255, font.getInt(COSName.LAST_CHAR, 255));
        for (int c = first; c <= last; c++) {
            codes.add(c);
        }
        return codes;
    }

    private static String name(COSDictionary font) {
        String n = font.getNameAsString(COSName.BASE_FONT);
        return n == null ? "A font" : FontRebuild.cleanName(n);
    }
}
