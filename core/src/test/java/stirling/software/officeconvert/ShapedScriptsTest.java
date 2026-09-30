package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

class ShapedScriptsTest {

    private record Sample(String name, String face, boolean rtl, String text, String... fonts) {}

    private static final List<Sample> SAMPLES = List.of(
            new Sample("arabic", null, true, "\u0645\u0631\u062D\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645\u060C \u0647\u0630\u0627 \u0646\u0635 \u062A\u062C\u0631\u064A\u0628\u064A (\u0645\u062B\u0627\u0644) \u0631\u0642\u0645 123 \u0648 Windows 10.",
                    "arial.ttf", "NotoSansArabic-Regular.ttf", "DejaVuSans.ttf"),
            new Sample("arabic marks", null, true, "\u0628\u0650\u0633\u0652\u0645\u0650 \u0627\u0644\u0644\u064E\u0651\u0647\u0650 \u0627\u0644\u0631\u064E\u0651\u062D\u0652\u0645\u064E\u0670\u0646\u0650 \u0627\u0644\u0631\u064E\u0651\u062D\u0650\u064A\u0645\u0650", "arial.ttf", "NotoSansArabic-Regular.ttf"),
            new Sample("hebrew", null, true, "\u05D4\u05D2\u05E8\u05E1\u05D4 \u05D4\u05D7\u05D3\u05E9\u05D4 \u05E9\u05DC Microsoft Word 2016 (\u05E2\u05DD \u05EA\u05D9\u05E7\u05D5\u05E0\u05D9\u05DD) \u05D9\u05E6\u05D0\u05D4 \u05D1-12/05.",
                    "arial.ttf", "NotoSansHebrew-Regular.ttf", "DejaVuSans.ttf"),
            new Sample("hebrew points", null, true, "\u05D1\u05B0\u05BC\u05E8\u05B5\u05D0\u05E9\u05B4\u05C1\u05D9\u05EA \u05D1\u05B8\u05BC\u05E8\u05B8\u05D0 \u05D0\u05B1\u05DC\u05B9\u05D4\u05B4\u05D9\u05DD", "NotoSerifHebrew-Regular.ttf",
                    "NotoSansHebrew-Regular.ttf", "arial.ttf"),
            new Sample("hindi", "Nirmala UI", false, "\u0939\u093F\u0928\u094D\u0926\u0940 \u092D\u093E\u0937\u093E \u092E\u0947\u0902 \u0915\u093F\u0924\u093E\u092C \u092A\u0922\u093C\u093F\u090F \u0914\u0930 \u0915\u093E\u0930\u094D\u092F \u0915\u0930\u0947\u0902\u0964", "Nirmala.ttc",
                    "NotoSansDevanagari-Regular.ttf"),
            new Sample("tamil", "Nirmala UI", false, "\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD \u0BAE\u0BCA\u0BB4\u0BBF \u0B95\u0BCA\u0BA3\u0BCD\u0B9F\u0BC1 \u0BB5\u0BA8\u0BCD\u0BA4\u0BC7\u0BA9\u0BCD", "Nirmala.ttc", "NotoSansTamil-Regular.ttf"),
            new Sample("bengali", "Nirmala UI", false, "\u09AC\u09BE\u0982\u09B2\u09BE \u09AD\u09BE\u09B7\u09BE \u0995\u09C7\u09AE\u09A8 \u0986\u099B\u09C7\u09A8", "Nirmala.ttc", "NotoSansBengali-Regular.ttf"),
            new Sample("thai", null, false, "\u0E20\u0E32\u0E29\u0E32\u0E44\u0E17\u0E22\u0E40\u0E1B\u0E47\u0E19\u0E20\u0E32\u0E29\u0E32\u0E17\u0E35\u0E48\u0E2A\u0E27\u0E22\u0E07\u0E32\u0E21\u0E21\u0E32\u0E01", "LeelawUI.ttf", "NotoSansThai-Regular.ttf", "tahoma.ttf"),
            new Sample("korean", null, false, "\uD55C\uAD6D\uC5B4 \uD14D\uC2A4\uD2B8\uC785\uB2C8\uB2E4.", "malgun.ttf", "NotoSansKR-Regular.ttf"));

    @Test
    void shapedTextComesBackInLogicalOrder() throws Exception {
        List<String> wrong = new ArrayList<>();
        int tried = 0;
        for (Sample s : SAMPLES) {
            Path font = WorldPdfs.font(s.fonts());
            if (font == null) {
                continue;
            }
            for (WorldPdfs.Mode mode : WorldPdfs.Mode.values()) {
                byte[] pdf = WorldPdfs.shaped(font, s.face(), mode, List.of(WorldPdfs.Text.line(s.text(), s.rtl(), 100)));
                try (PDDocument doc = Loader.loadPDF(pdf)) {
                    String got = PdfToText.text(doc, PdfToDocx.Options.defaults()).strip();
                    tried++;
                    if (!nfc(got).equals(nfc(s.text()))) {
                        wrong.add(s.name() + " " + mode + ": " + got);
                    }
                }
            }
        }
        assumeTrue(tried > 0, "no script fonts installed");
        assertEquals(List.of(), wrong);
    }

    private static String nfc(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }
}
