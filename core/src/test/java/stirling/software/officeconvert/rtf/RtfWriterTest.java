package stirling.software.officeconvert.rtf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.sink.SampleDocument;

class RtfWriterTest {

    private static String convert() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (RtfWriter w = new RtfWriter(out)) {
            SampleDocument.write(w);
        }
        return out.toString(StandardCharsets.US_ASCII);
    }

    private static int count(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    void groupsBalanceAndTheTextIsAscii() throws IOException {
        String rtf = convert();
        assertTrue(rtf.startsWith("{\\rtf1\\ansi"));
        int depth = 0;
        for (int i = 0; i < rtf.length(); i++) {
            char c = rtf.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                assertTrue(depth >= 0, "closed a group never opened at " + i);
            }
            assertTrue(c < 0x80, "non-ASCII byte at " + i);
        }
        assertEquals(0, depth);
        assertTrue(rtf.contains("\\u1513?\\u1500?\\u1493?\\u1501?"), "Hebrew as Unicode control words");
        assertTrue(rtf.contains("\\rtlch"), "right-to-left text marked as such");
    }

    @Test
    void writesTablesListsNotesAndPictures() throws IOException {
        String rtf = convert();
        assertTrue(rtf.contains("{\\fonttbl{\\f0\\fswiss\\fcharset0\\fprq2 Arial;}"));
        assertEquals(6, count(rtf, "\\\\trowd"), "each row defined before its cells and again at its end");
        assertTrue(rtf.contains("\\clvmgf") && rtf.contains("\\clvmrg"), "vertical merge");
        assertTrue(rtf.contains("\\cellx4400"), "the header cell spans two columns: 100 + 120 points, in twips");
        assertTrue(rtf.contains("\\trhdr"));
        assertTrue(rtf.contains("{\\*\\listtable") && rtf.contains("\\levelstartat3"));
        assertTrue(rtf.contains("\\ls1\\ilvl1"));
        assertTrue(rtf.contains("\\chftn{\\footnote ") && rtf.contains("The note text."));
        assertTrue(rtf.contains("\\pngblip"));
        assertTrue(rtf.contains("{\\sp{\\sn shapeType}{\\sv 202}}"), "text box");
        assertTrue(rtf.contains("{\\sp{\\sn txflTextFlow}{\\sv 3}}"), "turned text flows top to bottom");
        assertTrue(rtf.contains("\\shpbypara") && rtf.contains("{\\sp{\\sn posrelv}{\\sv 2}}"), "riding shape");
        assertTrue(rtf.contains("HYPERLINK \"https://example.com/a?b=1&c=2\""));
        assertTrue(rtf.contains("\\tqr\\tldot\\tx4000"));
        assertTrue(rtf.contains("{\\*\\bkmkstart _Pg1}"));
    }

    @Test
    void sectionsOpenWithTheirPropertiesAndCloseWithSect() throws IOException {
        String rtf = convert();
        assertEquals(2, count(rtf, "\\\\sect\\n"), "two sections end before the last");
        assertEquals(3, count(rtf, "\\\\sectd"));
        assertTrue(rtf.contains("\\sbknone\\pgwsxn12240"), "continuous section");
        assertTrue(rtf.contains("\\cols2\\colsx360"));
        assertTrue(rtf.contains("{\\header ") && rtf.contains("{\\footer ") && rtf.contains(" NUMPAGES "));
        assertTrue(rtf.contains("\\column "));
        assertTrue(rtf.indexOf("{\\header ") < rtf.indexOf("Annual"), "the running header precedes the body");
    }
}
