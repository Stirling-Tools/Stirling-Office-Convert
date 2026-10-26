package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        for (ContentGraph.Node n : graph.nodes()) {
            PdfFiles.stopIfInterrupted();
            List<Object> tokens;
            try {
                tokens = ContentTokens.salvage(n.streams()).tokens();
            } catch (IOException e) {
                Decoded.rethrowFatal(e);
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
                if (ContentGraph.dict(v) instanceof COSDictionary font && !targets.containsKey(font)) {
                    targets.put(font, unusedCodes(font));
                }
            }
        }
        List<String> embedded = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        FontLibrary library = null;
        for (Map.Entry<COSDictionary, TreeSet<Integer>> e : targets.entrySet()) {
            PdfFiles.stopIfInterrupted();
            PDFont font;
            try {
                font = PDFontFactory.createFont(e.getKey());
            } catch (IOException | RuntimeException x) {
                PdfFiles.stopIfInterrupted();
                failed.add(name(e.getKey()) + ": " + x.getMessage());
                continue;
            }
            if (font instanceof PDType3Font || FontCheck.embedded(font instanceof PDType0Font t0
                    && t0.getDescendantFont() != null ? t0.getDescendantFont().getFontDescriptor()
                            : font.getFontDescriptor())) {
                continue;
            }
            if (library == null) {
                library = fonts.library();
            }
            try {
                embedded.add(FontFixer.substitute(doc, font, e.getValue(), PdfALevel.A2B, library,
                        usage.bytesPerCode(e.getKey())));
            } catch (IOException | RuntimeException x) {
                PdfFiles.stopIfInterrupted();
                failed.add(FontRebuild.cleanName(font.getName()) + ": " + x.getMessage());
            }
        }
        return new Result(embedded, failed);
    }

    private static TreeSet<Integer> unusedCodes(COSDictionary font) {
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
