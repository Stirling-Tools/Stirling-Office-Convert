package stirling.software.officeconvert.topdf.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextPackageTest {

    @TempDir
    Path dir;

    record Pkg(Map<String, String> parts, Converted outcome) {
        String body() {
            String d = parts.get("word/document.xml");
            return d.substring(d.indexOf("<w:body>") + 8, d.indexOf("<w:sectPr>"));
        }
    }

    Pkg convert(byte[] text, int maxPages) throws IOException {
        Path in = Files.write(dir.resolve("in.txt"), text);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Converted o = TextPackage.write(in, out, maxPages);
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                parts.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return new Pkg(parts, o);
    }

    Pkg convert(String text) throws IOException {
        return convert(text.getBytes(StandardCharsets.UTF_8), 0);
    }

    static int lines(String body) {
        return count(body, "<w:p>") + count(body, "<w:br/>");
    }

    static int count(String s, String what) {
        Matcher m = Pattern.compile(Pattern.quote(what)).matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    void eachLineIsALineWhateverItsEnding() throws IOException {
        String b = convert("one\r\ntwo\rthree\nfour\n").body();
        assertEquals(4, lines(b), b);
        assertTrue(b.contains(">one<") && b.contains(">two<") && b.contains(">three<") && b.contains(">four<"), b);
    }

    @Test
    void aFinalLineBreakAddsNoParagraphButBlankLinesDo() throws IOException {
        assertEquals(1, lines(convert("only\n").body()));
        assertEquals(1, lines(convert("only").body()));
        assertEquals(4, lines(convert("a\n\n\nb\n").body()));
        assertEquals(2, lines(convert("a\n\n").body()));
    }

    @Test
    void linesAreGroupedIntoParagraphsOfAPageAtMost() throws IOException {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            s.append("line ").append(i).append('\n');
        }
        String b = convert(s.toString()).body();
        assertEquals(4, count(b, "<w:p>"), b);
        assertEquals(200, lines(b));
        assertFalse(b.contains("<w:br/></w:r></w:p>"), "no paragraph ends with a line break");
        String paged = convert("abc\n\fdef\n").body();
        assertEquals("<w:p><w:r><w:t xml:space=\"preserve\">abc</w:t></w:r></w:p><w:p><w:r><w:br w:type=\"page\"/>"
                + "<w:t xml:space=\"preserve\">def</w:t></w:r></w:p>", paged);
    }

    @Test
    void spacesAreKeptAndTabsBecomeTabs() throws IOException {
        String b = convert("  two  spaces\tthen tab\n").body();
        assertTrue(b.contains("<w:t xml:space=\"preserve\">  two  spaces</w:t><w:tab/><w:t xml:space=\"preserve\">"
                + "then tab</w:t>"), b);
    }

    @Test
    void formFeedsBreakPages() throws IOException {
        String b = convert("one\fmid\n\f\fafter\n\f").body();
        assertEquals(4, count(b, "<w:br w:type=\"page\"/>"), b);
        assertTrue(b.indexOf(">one<") < b.indexOf("page") && b.indexOf(">mid<") > b.indexOf("page"), b);
    }

    @Test
    void controlCharactersAreDroppedAndMarkupEscaped() throws IOException {
        String b = convert("a\u0000b\u0007c\u001b[0m <x> & \"q\"\u0085\n").body();
        assertTrue(b.contains(">abc[0m &lt;x&gt; &amp; &quot;q&quot;<"), b);
    }

    @Test
    void anEmptyFileIsOneEmptyPage() throws IOException {
        Pkg p = convert(new byte[0], 0);
        assertEquals("<w:p/>", p.body());
        assertFalse(p.outcome().lost());
    }

    @Test
    void thePageIsA4WithTwoCentimetreMarginsAndMonospacedText() throws IOException {
        Pkg p = convert("x");
        String doc = p.parts().get("word/document.xml");
        assertTrue(doc.contains("<w:pgSz w:w=\"11906\" w:h=\"16838\"/>") && doc.contains("w:left=\"1134\"")
                && doc.contains("w:right=\"1134\"") && doc.contains("w:bottom=\"1134\""), doc);
        String styles = p.parts().get("word/styles.xml");
        assertTrue(styles.contains("w:ascii=\"Liberation Mono\"") && styles.contains("<w:sz w:val=\"20\"/>")
                && styles.contains("w:line=\"227\" w:lineRule=\"exact\"")
                && styles.contains("<w:widowControl w:val=\"0\"/>"), styles);
        assertTrue(p.parts().get("word/settings.xml").contains("<w:defaultTabStop w:val=\"709\"/>"));
    }

    @Test
    void aVeryLongLineIsSplitIntoBoundedParagraphs() throws IOException {
        String b = convert("y".repeat(TextBody.HARD_PARAGRAPH * 2 + 5)).body();
        assertEquals(3, count(b, "<w:p>"), b.length() + "");
        String words = "word ".repeat(TextBody.SOFT_PARAGRAPH / 5 + 10);
        String w = convert(words).body();
        assertEquals(2, count(w, "<w:p>"));
        assertTrue(w.contains("word </w:t></w:r></w:p><w:p><w:r><w:t xml:space=\"preserve\">word"), "breaks after a space");
    }

    @Test
    void textPastThePageLimitIsNotWritten() throws IOException {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            s.append("line ").append(i).append('\n');
        }
        Pkg p = convert(s.toString().getBytes(StandardCharsets.UTF_8), 2);
        assertEquals(3 * TextBody.LINES_PER_PAGE, lines(p.body()));
        assertTrue(p.outcome().lost());
        assertTrue(p.outcome().warnings().get(0).contains("page limit of 2 pages"), p.outcome().warnings().toString());
        assertFalse(convert(s.toString().getBytes(StandardCharsets.UTF_8), 40).outcome().lost());
    }

    @Test
    void wrappedLinesAndFormFeedsCountTowardsThePageLimit() throws IOException {
        String wide = "z".repeat(TextBody.COLUMNS * 10) + "\n";
        Pkg p = convert(wide.repeat(100).getBytes(StandardCharsets.UTF_8), 1);
        assertEquals(13, lines(p.body()));
        Pkg f = convert("a\f".repeat(100).getBytes(StandardCharsets.UTF_8), 3);
        assertEquals(4, count(f.body(), "<w:br w:type=\"page\"/>"));
        assertTrue(f.outcome().lost());
    }

    @Test
    void encodingsAreDecoded() throws IOException {
        String s = "café € 中\n";
        assertTrue(convert(s.getBytes(StandardCharsets.UTF_8), 0).body().contains("café € 中"));
        byte[] le = s.getBytes(StandardCharsets.UTF_16LE);
        byte[] bom = new byte[le.length + 2];
        bom[0] = (byte) 0xFF;
        bom[1] = (byte) 0xFE;
        System.arraycopy(le, 0, bom, 2, le.length);
        assertTrue(convert(bom, 0).body().contains("café € 中"));
        byte[] ansi = "café € “q”\n".getBytes(TextEncoding.WINDOWS_1252);
        assertTrue(convert(ansi, 0).body().contains("café € “q”"));
    }
}
