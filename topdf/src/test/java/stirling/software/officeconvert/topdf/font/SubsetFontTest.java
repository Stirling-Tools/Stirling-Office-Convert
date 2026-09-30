package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.testing.TestFonts;

class SubsetFontTest {

    private static final String TEXT = "Hello, world! ÀÉîõü 0123456789 ​­ fi ffl € 😀";

    @Test
    void writesWhatPdfBoxWritesForTheSameSubset() throws IOException {
        same(TestFonts.bundled(), TEXT, Set.of(3, 40));
        same(TestFonts.renamed("Subset Face"), TEXT, Set.of());
        same(TestFonts.bundled(), "", Set.of());
    }

    @Test
    void failsWherePdfBoxFails() throws IOException {
        same(TestFonts.damagedGlyph("Damaged Face", 'A'), "ABC", Set.of());
    }

    private static void same(byte[] font, String text, Set<Integer> glyphs) throws IOException {
        try (PDDocument a = new PDDocument(); PDDocument b = new PDDocument()) {
            PDType0Font pdfbox = PDType0Font.load(a, parse(font), true);
            SubsetFont ours = new SubsetFont(b, parse(font));
            text.codePoints().forEach(cp -> {
                pdfbox.addToSubset(cp);
                ours.addCodePoint(cp);
            });
            if (!glyphs.isEmpty()) {
                pdfbox.addGlyphsToSubset(glyphs);
                ours.addGlyphs(glyphs);
            }
            compare(pdfbox.getCOSObject(), ours.dictionary(), new ArrayList<>(), true);
            String theirs = failure(pdfbox::subset);
            assertEquals(theirs, failure(ours::subset));
            compare(pdfbox.getCOSObject(), ours.dictionary(), new ArrayList<>(), theirs != null);
            assertEquals(a.getVersion(), b.getVersion());
        }
    }

    private interface Step {
        void run() throws IOException;
    }

    private static String failure(Step step) {
        try {
            step.run();
            return null;
        } catch (IOException | RuntimeException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    private static TrueTypeFont parse(byte[] font) throws IOException {
        return new TTFParser().parse(new RandomAccessReadBuffer(font));
    }

    // Until a subset is made PDFBox holds a width for every glyph, which PdfFonts replaces either way; ours holds none
    private static void compare(COSBase x, COSBase y, List<String> path, boolean loaded) throws IOException {
        x = x instanceof COSObject o ? o.getObject() : x;
        y = y instanceof COSObject o ? o.getObject() : y;
        String at = String.join("/", path);
        if (loaded && at.equals("DescendantFonts/0/W")) {
            return;
        }
        if (x instanceof COSDictionary dx) {
            COSDictionary dy = assertInstanceOf(COSDictionary.class, y, at);
            assertEquals(new ArrayList<>(dx.keySet()), new ArrayList<>(dy.keySet()), at);
            for (COSName key : dx.keySet()) {
                path.add(key.getName());
                compare(dx.getItem(key), dy.getItem(key), path, loaded);
                path.removeLast();
            }
            if (x instanceof COSStream sx) {
                assertArrayEquals(bytes(sx), bytes(assertInstanceOf(COSStream.class, y, at)), at);
            }
        } else if (x instanceof COSArray ax) {
            COSArray ay = assertInstanceOf(COSArray.class, y, at);
            assertEquals(ax.size(), ay.size(), at);
            for (int i = 0; i < ax.size(); i++) {
                path.add(Integer.toString(i));
                compare(ax.get(i), ay.get(i), path, loaded);
                path.removeLast();
            }
        } else {
            assertEquals(String.valueOf(x), String.valueOf(y), at);
        }
    }

    private static byte[] bytes(COSStream s) throws IOException {
        try (InputStream in = s.createRawInputStream()) {
            return in.readAllBytes();
        }
    }
}
