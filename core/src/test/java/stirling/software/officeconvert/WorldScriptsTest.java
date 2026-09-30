package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.WorldPdfs.Text;

class WorldScriptsTest {

    private static final String ARABIC = "\u0645\u0631\u062D\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645\u060C \u0647\u0630\u0627 \u0646\u0635 \u062A\u062C\u0631\u064A\u0628\u064A (\u0645\u062B\u0627\u0644) \u0631\u0642\u0645 123 \u0648 Windows 10.";
    private static final String HEBREW = "\u05D4\u05D2\u05E8\u05E1\u05D4 \u05D4\u05D7\u05D3\u05E9\u05D4 \u05E9\u05DC Microsoft Word 2016 (\u05E2\u05DD \u05EA\u05D9\u05E7\u05D5\u05E0\u05D9\u05DD) \u05D9\u05E6\u05D0\u05D4 \u05D1-12/05.";
    private static final String MIXED = "The word \u05E9\u05DC\u05D5\u05DD means peace (and hello), see page 12.";

    @Test
    void rightToLeftLinesComeOutInLogicalOrderWithBracketsTheRightWayRound() throws Exception {
        byte[] pdf = WorldPdfs.mapped(List.of(Text.line(ARABIC, true, 100), Text.line(HEBREW, true, 160),
                Text.line(MIXED, false, 220)));
        List<String> lines = lines(text(pdf));
        assertEquals(List.of(ARABIC, HEBREW, MIXED), lines);
    }

    @Test
    void mixedDirectionLinesFollowTheBidiAlgorithm() throws Exception {
        List<String> lines = List.of(
                "\u0631\u0642\u0645 \u0627\u0644\u0647\u0627\u062A\u0641 +44 20 7946 0958 \u0648\u0627\u0644\u0628\u0631\u064A\u062F info@example.org \u0645\u062A\u0627\u062D\u0627\u0646 [\u062F\u0627\u0626\u0645\u0627\u064B].",
                "\u0627\u0644\u0645\u0639\u0627\u062F\u0644\u0629: (3 + 4) \u00D7 2 = 14\u060C \u0648\u0627\u0644\u0646\u0633\u0628\u0629 12.5% \u0645\u0646 {\u0627\u0644\u0645\u062C\u0645\u0648\u0639}.",
                "\u062A\u0627\u0631\u064A\u062E \u0627\u0644\u0625\u0639\u0644\u0627\u0646: 10 \u062F\u064A\u0633\u0645\u0628\u0631 1948\u060C \u0627\u0644\u0645\u0648\u0627\u062F 1-30.",
                "\u0627\u0633\u062A\u062E\u062F\u0645 \u0628\u0631\u0646\u0627\u0645\u062C LibreOffice Writer \u0623\u0648 Microsoft Word \u0644\u0641\u062A\u062D \u0627\u0644\u0645\u0644\u0641 report-2026.docx \u0627\u0644\u0622\u0646.",
                "\u05D4\u05DE\u05E9\u05D5\u05D5\u05D0\u05D4: (3 + 4) \u00D7 2 = 14, \u05D5\u05D4\u05D9\u05D7\u05E1 12.5% \u05DE\u05EA\u05D5\u05DA {\u05D4\u05E1\u05DB\u05D5\u05DD}.",
                "\u05DE\u05E1\u05E4\u05E8 \u05D4\u05D8\u05DC\u05E4\u05D5\u05DF +972 3 555 1234 \u05D5\u05D4\u05D3\u05D5\u05D0\u05F4\u05DC info@example.org \u05D6\u05DE\u05D9\u05E0\u05D9\u05DD [\u05EA\u05DE\u05D9\u05D3].",
                "\u05D4\u05E9\u05EA\u05DE\u05E9\u05D5 \u05D1-LibreOffice Writer \u05D0\u05D5 \u05D1-Microsoft Word \u05DB\u05D3\u05D9 \u05DC\u05E4\u05EA\u05D5\u05D7 \u05D0\u05EA \u05D4\u05E7\u05D5\u05D1\u05E5 report-2026.docx \u05E2\u05DB\u05E9\u05D9\u05D5.",
                "\u05E1\u05E2\u05D9\u05E3 \u05E8\u05D0\u05E9\u05D5\u05DF: 100 \u05D9\u05D7\u05D9\u05D3\u05D5\u05EA (50%)",
                "\u05E8\u05D0\u05D5 page=2 \u05D1\u05D3\u05D5\u05D7.",
                "\u05D2\u05D5\u05D3\u05DC \u05D4\u05E7\u05D5\u05D1\u05E5 2.5MB \u05D1\u05DC\u05D1\u05D3.",
                "\u05D4\u05E0\u05D5\u05E1\u05D7\u05D4 x+y=10 \u05E0\u05DB\u05D5\u05E0\u05D4.",
                "\u05DC\u05E4\u05E8\u05D8\u05D9\u05DD: https://example.com/view?id=42 \u05D1\u05DC\u05D1\u05D3.",
                "\u05E1\u05E2\u05D9\u05E3 5: Windows \u05E0\u05EA\u05DE\u05DA.",
                "\u05E8\u05D5\u05D7\u05D1 100px \u05D5\u05E8\u05D5\u05D7\u05D1 50% \u05D1\u05DC\u05D1\u05D3.",
                "\u0627\u0644\u0645\u0648\u0642\u0639 https://example.org/a?b=1 \u0645\u062A\u0627\u062D.");
        List<String> wrong = new java.util.ArrayList<>();
        for (String line : lines) {
            List<String> got = lines(text(WorldPdfs.mapped(List.of(Text.line(line, true, 100).at(40, 9, 530)))));
            if (!got.equals(List.of(line))) {
                wrong.add(got + " for " + line);
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void numbersInsideLeftToRightTextKeepTheirOrder() throws Exception {
        String line = "The phrase \u05E9\u05DC\u05D5\u05DD 123 \u05E2\u05D5\u05DC\u05DD appears twice";
        byte[] pdf = WorldPdfs.mapped(List.of(Text.line(line, false, 100)));
        assertEquals(List.of(line), lines(text(pdf)));
    }

    @Test
    void actualTextReplacesTheGlyphMap() throws Exception {
        byte[] pdf = WorldPdfs.mapped(List.of(Text.line(HEBREW, true, 100).withActualText("#"),
                Text.line(ARABIC, true, 160).withActualText("\u0004")));
        assertEquals(List.of(HEBREW, ARABIC), lines(text(pdf)));
    }

    @Test
    void combiningMarksStayOnTheirLetters() throws Exception {
        String hebrew = "\u05D1\u05B0\u05BC\u05E8\u05B5\u05D0\u05E9\u05B4\u05C1\u05D9\u05EA \u05D1\u05B8\u05BC\u05E8\u05B8\u05D0";
        String arabic = "\u0628\u0650\u0633\u0652\u0645\u0650 \u0627\u0644\u0644\u064E\u0651\u0647\u0650";
        String thai = "\u0E20\u0E32\u0E29\u0E32\u0E17\u0E35\u0E48\u0E2A\u0E27\u0E22\u0E07\u0E32\u0E21";
        byte[] pdf = WorldPdfs.mapped(List.of(Text.line(hebrew, true, 100).withMarks(), Text.line(arabic, true, 160).withMarks(),
                Text.line(thai, false, 220).withMarks()));
        List<String> lines = lines(text(pdf));
        assertEquals(List.of(nfc(hebrew), nfc(arabic), nfc(thai)), lines.stream().map(WorldScriptsTest::nfc).toList());
    }

    @Test
    void indicVowelsDrawnBeforeTheConsonantGoBackAfterIt() throws Exception {
        List<String> hindi = List.of("\u093F", "\u0915", "\u0924", "\u093E", "\u092C", " ", "\u0915", "\u093E", "\u092F", "\u0930\u094D");
        List<String> tamil = List.of("\u0BC6", "\u0B95", "\u0BBE", "\u0BA3", "\u0BCD", "\u0B9F", "\u0BC1");
        List<String> myanmar = List.of("\u1031", "\u103C", "\u1000", "\u1038", "\u1019");
        List<String> khmer = List.of("\u17D2\u179A", "\u1780", "\u17BB", "\u1798");
        List<String> marked = List.of("\u093F", "\u0915", "\u0902", "\u0938");
        byte[] pdf = WorldPdfs.mapped(List.of(Text.glyphs(hindi, false, 100), Text.glyphs(tamil, false, 160),
                Text.glyphs(myanmar, false, 220), Text.glyphs(khmer, false, 280), Text.glyphs(marked, false, 340)));
        List<String> lines = lines(text(pdf));
        assertEquals(List.of("\u0915\u093F\u0924\u093E\u092C \u0915\u093E\u0930\u094D\u092F", nfc("\u0B95\u0BCA\u0BA3\u0BCD\u0B9F\u0BC1"),
                "\u1000\u103C\u1031\u1038\u1019", "\u1780\u17D2\u179A\u17BB\u1798", "\u0915\u093F\u0902\u0938"),
                lines.stream().map(WorldScriptsTest::nfc).toList());
    }

    @Test
    void thaiAndChineseLinesJoinWithoutSpaces() throws Exception {
        String thai = "\u0E20\u0E32\u0E29\u0E32\u0E44\u0E17\u0E22\u0E40\u0E1B\u0E47\u0E19\u0E20\u0E32\u0E29\u0E32\u0E17\u0E35\u0E48\u0E2A\u0E27\u0E22\u0E07\u0E32\u0E21\u0E21\u0E32\u0E01".repeat(6);
        String chinese = "\u4E2D\u6587\u6587\u672C\u793A\u4F8B\u8FD9\u662F\u4E00\u4E2A\u6D4B\u8BD5\u6211\u4EEC".repeat(7);
        for (String paragraph : List.of(thai, chinese)) {
            int per = paragraph.length() / 3;
            List<Text> lines = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String part = paragraph.substring(Math.min(paragraph.length(), i * per), Math.min(paragraph.length(), (i + 1) * per));
                if (!part.isEmpty()) {
                    lines.add(Text.line(part, false, 100 + 20 * i).at(72, 10, 400));
                }
            }
            String text = text(WorldPdfs.mapped(lines));
            assertTrue(text.contains(paragraph), text);
        }
    }

    @Test
    void chineseParagraphsEndWhereTheLastLineStopsShort() throws Exception {
        String first = "\u4E2D\u6587\u6587\u672C\u793A\u4F8B\u8FD9\u662F\u4E00\u4E2A\u6D4B\u8BD5\u6211\u4EEC".repeat(5) + "\u5B8C";
        String second = "\u6587\u672C\u793A\u4F8B\u8FD9\u662F\u4E00\u4E2A\u6D4B\u8BD5\u6211\u4EEC\u4E2D\u6587".repeat(5);
        List<Text> lines = new java.util.ArrayList<>();
        int per = 28;
        float y = 100;
        for (String paragraph : List.of(first, second)) {
            for (int i = 0; i < paragraph.length(); i += per) {
                lines.add(Text.line(paragraph.substring(i, Math.min(paragraph.length(), i + per)), false, y).at(72, 10, 400));
                y += 20;
            }
            y += 4;
        }
        List<String> got = lines(text(WorldPdfs.mapped(lines)));
        assertEquals(List.of(first, second), got);
    }

    @Test
    void aJoinerTakesNoRoomSoTheSpaceAfterItSurvives() throws Exception {
        List<String> units = List.of("\u0D35", "\u0D15\u0D41", "\u0D2A\u0D4D\u0D2A\u0D4D", "\u200C", " ", "2", ".", " ", "\u0D1C",
                "\u0D3E\u0D24\u0D3F");
        byte[] pdf = WorldPdfs.mapped(List.of(Text.glyphs(units, false, 100)));
        assertTrue(text(pdf).contains("\u0D4D\u200C 2."), text(pdf));
        String body = parts(convert(pdf, OfficeConvert.Format.DOCX)).get("word/document.xml").replaceAll("<[^>]+>", "");
        assertTrue(body.contains("\u0D4D\u200C 2."), body);
    }

    @Test
    void aJoinerGivenAsActualTextOverASpaceGlyphLeavesTheSpace() throws Exception {
        String word = "\u0D35\u0D15\u0D41\u0D2A\u0D4D\u0D2A\u0D4D";
        List<Text> texts = new java.util.ArrayList<>(List.of(Text.line(word, false, 100)));
        float x = 72 + WorldPdfs.width(texts, 0);
        texts.add(Text.glyphs(List.of("\u200C"), false, 100).at(x, 12, 20).withActualText(" "));
        texts.add(Text.line("2.", false, 100).at(x + 3.3f, 12, 20));
        String body = parts(convert(WorldPdfs.mapped(texts), OfficeConvert.Format.DOCX)).get("word/document.xml")
                .replaceAll("<[^>]+>", "");
        assertTrue(body.contains(word + "\u200C 2."), body);
    }

    @Test
    void chineseParagraphsKeepTheirPunctuationInsideTheLine() throws Exception {
        String paragraph = "\u4E2D\u6587\u6587\u672C\u793A\u4F8B\u8FD9\u662F\u4E00\u4E2A\u6D4B\u8BD5\u6211\u4EEC".repeat(5);
        List<Text> lines = new java.util.ArrayList<>();
        for (int i = 0; i < paragraph.length(); i += 28) {
            lines.add(Text.line(paragraph.substring(i, Math.min(paragraph.length(), i + 28)), false, 100 + 20 * i / 28).at(72, 10, 400));
        }
        byte[] pdf = WorldPdfs.mapped(lines);
        String body = parts(convert(pdf, OfficeConvert.Format.DOCX)).get("word/document.xml");
        assertTrue(body.contains("<w:overflowPunct w:val=\"0\"/>"), body);
        assertTrue(parts(convert(pdf, OfficeConvert.Format.PPTX)).get("ppt/slides/slide1.xml").contains("hangingPunct=\"0\""));
        assertTrue(parts(convert(pdf, OfficeConvert.Format.ODT)).get("content.xml").contains("style:punctuation-wrap=\"simple\""));
        assertTrue(new String(convert(pdf, OfficeConvert.Format.RTF), StandardCharsets.US_ASCII).contains("\\nooverflow"));
    }

    @Test
    void wordRunsCarryScriptFontsAndLanguages() throws Exception {
        byte[] pdf = WorldPdfs.mapped(List.of(Text.line(ARABIC, true, 100), Text.line("\u0939\u093F\u0928\u094D\u0926\u0940 \u092D\u093E\u0937\u093E", false, 160),
                Text.line("\u65E5\u672C\u8A9E\u306E\u30C6\u30AD\u30B9\u30C8\u3067\u3059", false, 220), Text.line("\u6771\u4EAC\u90FD", false, 280)));
        Map<String, String> docx = parts(convert(pdf, OfficeConvert.Format.DOCX));
        String body = docx.get("word/document.xml");
        String styles = docx.get("word/styles.xml");
        assertTrue(body.contains("<w:bidi/>"), "right-to-left paragraph");
        assertTrue(body.contains("<w:rtl/>"), "right-to-left run");
        assertTrue(body.contains("w:cs=\"Mangal\""), "Devanagari gets an Indic font");
        assertTrue(body.contains("w:eastAsia=\"MS Gothic\" w:cs=\"Arial\" w:hint=\"eastAsia\""), "Japanese gets a Japanese font");
        assertTrue(body.contains("w:bidi=\"hi-IN\""), "Hindi language");
        assertTrue(styles.contains("w:eastAsia=\"ja-JP\""), "kanji-only text is Japanese in a Japanese document");
        assertTrue(styles.contains("w:bidi=\"ar-SA\""), "Arabic is the document's complex script");
        assertFalse(body.contains("zh-CN"), "no Chinese language on Japanese kanji");
    }

    @Test
    void persianAndHebrewDocumentsSayWhichLanguage() throws Exception {
        String persian = "\u0627\u06CC\u0646 \u06CC\u06A9 \u0645\u062A\u0646 \u0641\u0627\u0631\u0633\u06CC \u0627\u0633\u062A \u06A9\u0647 \u06F1\u06F2\u06F3 \u0639\u062F\u062F \u062F\u0627\u0631\u062F.";
        Map<String, String> fa = parts(convert(WorldPdfs.mapped(List.of(Text.line(persian, true, 100))), OfficeConvert.Format.DOCX));
        assertTrue(fa.get("word/styles.xml").contains("w:bidi=\"fa-IR\""));
        Map<String, String> he = parts(convert(WorldPdfs.mapped(List.of(Text.line(HEBREW, true, 100))), OfficeConvert.Format.DOCX));
        assertTrue(he.get("word/styles.xml").contains("w:bidi=\"he-IL\""));
    }

    @Test
    void slidesMarkRightToLeftParagraphsAndRuns() throws Exception {
        byte[] pdf = WorldPdfs.mapped(List.of(Text.line(ARABIC, true, 100), Text.line("\u0939\u093F\u0928\u094D\u0926\u0940 \u092D\u093E\u0937\u093E", false, 160)));
        String slide = parts(convert(pdf, OfficeConvert.Format.PPTX)).get("ppt/slides/slide1.xml");
        assertTrue(slide.contains("rtl=\"1\""), "right-to-left paragraph");
        assertTrue(slide.contains("lang=\"ar-SA\""), "Arabic run language");
        assertTrue(slide.contains("<a:rtl/>"), "right-to-left run");
        assertTrue(slide.contains("<a:cs typeface=\"Mangal\"/>"), "Indic complex-script font");
        String odp = parts(convert(pdf, OfficeConvert.Format.ODP)).get("content.xml");
        assertTrue(odp.contains("style:writing-mode=\"rl-tb\""), "right-to-left ODP paragraph");
        assertTrue(odp.contains("style:language-complex=\"hi\""), "Hindi ODP language");
    }

    @Test
    void openDocumentAndRtfCarryComplexScriptFontsAndLanguages() throws Exception {
        byte[] pdf = WorldPdfs.mapped(List.of(Text.line("\u0939\u093F\u0928\u094D\u0926\u0940 \u092D\u093E\u0937\u093E \u092E\u0947\u0902", false, 100), Text.line(HEBREW, true, 160)));
        Map<String, String> odt = parts(convert(pdf, OfficeConvert.Format.ODT));
        String all = odt.get("content.xml") + odt.get("styles.xml");
        assertTrue(all.contains("style:font-name-complex=\"Mangal\""), "Indic font in ODT");
        assertTrue(all.contains("style:language-complex=\"hi\" style:country-complex=\"IN\""), "Hindi in ODT");
        assertTrue(all.contains("style:writing-mode=\"rl-tb\""), "right-to-left ODT paragraph");
        String rtf = new String(convert(pdf, OfficeConvert.Format.RTF), StandardCharsets.US_ASCII);
        assertTrue(rtf.contains("\\adeflang1037"), "Hebrew is the RTF's main complex language");
        assertTrue(rtf.contains("\\alang1081"), "Hindi runs say so");
                assertTrue(rtf.contains("Mangal;"), "Indic font in the RTF font table");
        assertTrue(rtf.contains("\\rtlpar"), "right-to-left RTF paragraph");
    }

    @Test
    void documentLanguagesAreRecognised() throws Exception {
        Map<String, String> samples = Map.of(
                "fr-FR", "Tous les \u00EAtres humains naissent libres et \u00E9gaux en dignit\u00E9 et en droits. Ils sont dou\u00E9s de raison et de conscience.",
                "de-DE", "Alle Menschen sind frei und gleich an W\u00FCrde und Rechten geboren. Sie sind mit Vernunft und Gewissen begabt.",
                "vi-VN", "T\u1EA5t c\u1EA3 m\u1ECDi ng\u01B0\u1EDDi sinh ra \u0111\u1EC1u \u0111\u01B0\u1EE3c t\u1EF1 do v\u00E0 b\u00ECnh \u0111\u1EB3ng v\u1EC1 nh\u00E2n ph\u1EA9m v\u00E0 quy\u1EC1n l\u1EE3i.",
                "uk-UA", "\u0412\u0441\u0456 \u043B\u044E\u0434\u0438 \u043D\u0430\u0440\u043E\u0434\u0436\u0443\u044E\u0442\u044C\u0441\u044F \u0432\u0456\u043B\u044C\u043D\u0438\u043C\u0438 \u0456 \u0440\u0456\u0432\u043D\u0438\u043C\u0438 \u0443 \u0441\u0432\u043E\u0457\u0439 \u0433\u0456\u0434\u043D\u043E\u0441\u0442\u0456 \u0442\u0430 \u043F\u0440\u0430\u0432\u0430\u0445.",
                "ur-PK", "\u062A\u0645\u0627\u0645 \u0627\u0646\u0633\u0627\u0646 \u0622\u0632\u0627\u062F \u0627\u0648\u0631 \u062D\u0642\u0648\u0642 \u0648 \u0639\u0632\u062A \u06A9\u06D2 \u0627\u0639\u062A\u0628\u0627\u0631 \u0633\u06D2 \u0628\u0631\u0627\u0628\u0631 \u067E\u06CC\u062F\u0627 \u06C1\u0648\u0626\u06D2 \u06C1\u06CC\u06BA\u06D4");
        for (Map.Entry<String, String> e : samples.entrySet()) {
            boolean rtl = e.getKey().startsWith("ur");
            byte[] pdf = WorldPdfs.mapped(List.of(Text.line(e.getValue(), rtl, 100).at(72, 9, 520)));
            String styles = parts(convert(pdf, OfficeConvert.Format.DOCX)).get("word/styles.xml");
            String attr = rtl ? "w:bidi=\"" : "w:val=\"";
            assertTrue(styles.contains(attr + e.getKey() + "\""), e.getKey() + " in " + styles.substring(0, 600));
        }
    }

    @Test
    void latinLanguagesOutsideTheListStayNeutral() throws Exception {
        Map<String, String> samples = Map.of(
                "sv", "Alla människor är födda fria och lika i värde och rättigheter. De har utrustats med förnuft och samvete och bör handla gentemot varandra i en anda av broderskap. Var och en är berättigad till alla de rättigheter och friheter som uttalas i denna förklaring.",
                "da", "Alle mennesker er født frie og lige i værdighed og rettigheder. De er udstyret med fornuft og samvittighed, og de bør handle mod hverandre i en broderskabets ånd. Enhver har krav på alle de rettigheder og friheder, som nævnes i denne erklæring.",
                "nb", "Alle mennesker er født frie og med samme menneskeverd og menneskerettigheter. De er utstyrt med fornuft og samvittighet og bør handle mot hverandre i brorskapets ånd. Enhver har krav på alle de rettigheter og friheter som er nevnt i denne erklæring.",
                "ro", "Toate ființele umane se nasc libere și egale în demnitate și în drepturi. Ele sunt înzestrate cu rațiune și conștiință și trebuie să se comporte unele față de altele în spiritul fraternității. Fiecare om se poate prevala de toate drepturile și libertățile proclamate în prezenta Declarație.",
                "ca", "Tots els éssers humans neixen lliures i iguals en dignitat i en drets. Són dotats de raó i de consciència, i han de comportar-se fraternalment els uns amb els altres. Tothom té tots els drets i llibertats proclamats en aquesta Declaració.",
                "es-ES", "Todos los seres humanos nacen libres e iguales en dignidad y derechos y, dotados como están de razón y conciencia, deben comportarse fraternalmente los unos con los otros.",
                "it-IT", "Tutti gli esseri umani nascono liberi ed eguali in dignità e diritti. Essi sono dotati di ragione e di coscienza e devono agire gli uni verso gli altri in spirito di fratellanza.",
                "pt-BR", "Todos os seres humanos nascem livres e iguais em dignidade e em direitos. Dotados de razão e de consciência, devem agir uns para com os outros em espírito de fraternidade.",
                "nl-NL", "Alle mensen worden vrij en gelijk in waardigheid en rechten geboren. Zij zijn begiftigd met verstand en geweten, en behoren zich jegens elkander in een geest van broederschap te gedragen.");
        for (Map.Entry<String, String> e : samples.entrySet()) {
            byte[] pdf = WorldPdfs.mapped(List.of(Text.line(e.getValue(), false, 100).at(72, 9, 520)));
            String styles = parts(convert(pdf, OfficeConvert.Format.DOCX)).get("word/styles.xml");
            String want = e.getKey().contains("-") ? e.getKey() : "en-US";
            assertTrue(styles.contains("w:val=\"" + want + "\""), e.getKey() + " in " + styles.substring(0, 600));
        }
    }

    @Test
    void spreadsheetCellsGetScriptFontsAndReadingOrder() throws Exception {
        byte[] pdf = WorldPdfs.mapped(List.of(Text.line(HEBREW, true, 100), Text.line("\u0939\u093F\u0928\u094D\u0926\u0940 \u092D\u093E\u0937\u093E \u092E\u0947\u0902", false, 160)));
        Map<String, String> xlsx = parts(convert(pdf, OfficeConvert.Format.XLSX));
        String styles = xlsx.get("xl/styles.xml");
        assertTrue(styles.contains("readingOrder=\"2\""), "right-to-left cells");
        assertTrue(styles.contains("<name val=\"Mangal\"/>"), "Devanagari cells in an Indic font");
        assertTrue(styles.contains("<name val=\"Arial\"/>"), "Hebrew cells in a font with Hebrew");
    }

    @Test
    void rightToLeftTablesKeepTheirFirstColumnOnTheRight() throws Exception {
        String[][] cells = {{"\u05DE\u05E1\u05E4\u05E8", "\u05DB\u05D5\u05EA\u05E8\u05EA", "\u05D8\u05E7\u05E1\u05D8"}, {"\u05D0\u05D7\u05D3", "\u05E9\u05DC\u05D5\u05DD", "\u05E2\u05D5\u05DC\u05DD"}, {"\u05E9\u05E0\u05D9\u05D9\u05DD", "\u05D1\u05D5\u05E7\u05E8", "\u05D8\u05D5\u05D1"}};
        List<Text> texts = new java.util.ArrayList<>();
        List<float[]> rules = new java.util.ArrayList<>();
        float[] cols = {100, 250, 400, 550};
        for (int r = 0; r < cells.length; r++) {
            for (int c = 0; c < 3; c++) {
                float left = cols[2 - c];
                texts.add(Text.line(cells[r][c], true, 120 + 20 * r).at(left + 4, 11, 142));
            }
        }
        for (int r = 0; r <= cells.length; r++) {
            rules.add(new float[] {cols[0], 105 + 20 * r, cols[3], 105 + 20 * r});
        }
        for (float x : cols) {
            rules.add(new float[] {x, 105, x, 105 + 20 * cells.length});
        }
        byte[] pdf = WorldPdfs.mapped(texts, rules);
        String body = parts(convert(pdf, OfficeConvert.Format.DOCX)).get("word/document.xml");
        assertTrue(body.contains("<w:bidiVisual/>"), "right-to-left table");
        assertTrue(body.indexOf("\u05DE\u05E1\u05E4\u05E8") < body.indexOf("\u05DB\u05D5\u05EA\u05E8\u05EA") && body.indexOf("\u05DB\u05D5\u05EA\u05E8\u05EA") < body.indexOf("\u05D8\u05E7\u05E1\u05D8"),
                "first logical column comes first");
        String slide = parts(convert(pdf, OfficeConvert.Format.PPTX)).get("ppt/slides/slide1.xml");
        assertTrue(slide.contains("<a:tblPr rtl=\"1\"/>"), "right-to-left slide table");
        String odt = parts(convert(pdf, OfficeConvert.Format.ODT)).get("content.xml");
        assertTrue(odt.indexOf("\u05DE\u05E1\u05E4\u05E8") < odt.indexOf("\u05D8\u05E7\u05E1\u05D8"), "ODT table in logical order");
        String text = text(pdf);
        assertTrue(text.contains("\u05DE\u05E1\u05E4\u05E8	\u05DB\u05D5\u05EA\u05E8\u05EA	\u05D8\u05E7\u05E1\u05D8"), text);
        Map<String, String> xlsx = parts(convert(pdf, OfficeConvert.Format.XLSX));
        String sheet = xlsx.get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("rightToLeft=\"1\""), "right-to-left sheet");
        List<String> strings = java.util.Arrays.stream(xlsx.get("xl/sharedStrings.xml").split("<si>")).skip(1).toList();
        int first = -1;
        for (int i = 0; i < strings.size(); i++) {
            first = strings.get(i).contains("\u05DE\u05E1\u05E4\u05E8") ? i : first;
        }
        assertTrue(java.util.regex.Pattern.compile("<c r=\"A\\d+\"[^>]*t=\"s\"><v>" + first + "</v>").matcher(sheet).find(),
                "the first logical column is column A, drawn on the right");
        String ods = parts(convert(pdf, OfficeConvert.Format.ODS)).get("content.xml");
        assertTrue(ods.contains("style:writing-mode=\"rl-tb\""), "right-to-left ODS table");
        assertTrue(ods.indexOf("\u05DE\u05E1\u05E4\u05E8") < ods.indexOf("\u05D8\u05E7\u05E1\u05D8"), "ODS cells in logical order");
    }

    @Test
    void mixedLineThatStartsOnTheRightKeepsItsRightToLeftParagraph() throws Exception {
        String hebrew = "\u05DB\u05DC \u05D1\u05E0\u05D9 \u05D0\u05D3\u05DD \u05E0\u05D5\u05DC\u05D3\u05D5 \u05D1\u05E0\u05D9 \u05D7\u05D5\u05E8\u05D9\u05DF";
        String mixed = hebrew + " (UDHR 1948, \u05E1\u05E2\u05D9\u05E3 \u05D0.) https://www.ohchr.org/en/human-rights 75%";
        String body = "\u05D4\u05D5\u05D0\u05D9\u05DC \u05D5\u05D4\u05DB\u05E8\u05D4 \u05D1\u05DB\u05D1\u05D5\u05D3 \u05D4\u05D8\u05D1\u05E2\u05D9 \u05D0\u05E9\u05E8 \u05DC\u05DB\u05DC";
        float size = 12;
        for (int pass = 0; pass < 2; pass++) {
            byte[] pdf = WorldPdfs.mapped(List.of(Text.line(body, true, 100), Text.line(body, true, 140),
                    Text.line(mixed, true, 200).at(72, size, 468), Text.line("[30]", true, 200 + size * 1.2f).at(72, size, 468)));
            if (pass == 0) {
                size = size * 462 / lineWidth(pdf, 200);
                continue;
            }
            String doc = parts(convert(pdf, OfficeConvert.Format.DOCX)).get("word/document.xml");
            int at = doc.indexOf("UDHR");
            String para = doc.substring(doc.lastIndexOf("<w:p>", at), doc.indexOf("</w:p>", at));
            assertTrue(para.contains("<w:bidi/>"), para);
            assertTrue(para.indexOf("\u05DB\u05DC \u05D1\u05E0\u05D9") < para.indexOf("UDHR") && para.indexOf("75%") < para.indexOf("[30]"),
                    "logical order starts with the Hebrew on the right: " + para);
        }
    }

    @Test
    void singleMixedLineEndingInRightToLeftTextReadsFromTheRight() throws Exception {
        String hebrew = "כל בני אדם";
        String mixed = hebrew + " (UDHR 1948, סעיף א.) https://www.ohchr.org/en 75% [30]";
        String body = "הואיל והכרה בכבוד הטבעי אשר לכל";
        byte[] pdf = WorldPdfs.mapped(List.of(Text.line(body, true, 100), Text.line(body, true, 140), Text.line(mixed, true, 200),
                Text.line(body, true, 240)));
        for (OfficeConvert.Format format : new OfficeConvert.Format[] {OfficeConvert.Format.DOCX, OfficeConvert.Format.PPTX}) {
            Map<String, String> parts = parts(convert(pdf, format));
            String doc = format == OfficeConvert.Format.DOCX ? parts.get("word/document.xml") : parts.get("ppt/slides/slide1.xml");
            int at = doc.indexOf("UDHR");
            String para = doc.substring(doc.lastIndexOf(format == OfficeConvert.Format.DOCX ? "<w:p>" : "<a:p>", at), at);
            assertTrue(para.contains("<w:bidi/>") || para.contains("rtl=\"1\""), para);
            assertTrue(para.contains("כל בני") && !para.contains("https"), "logical order starts on the right: " + para);
        }
    }

    @Test
    void hyphenEndingAnEnglishRunInARightToLeftLineStaysWithIt() throws Exception {
        String hebrew = "כל בני אדם נולדו בני חורין";
        List<String> first = new java.util.ArrayList<>();
        "https://www.ohchr.org/en/human-".codePoints().forEach(c -> first.add(Character.toString(c)));
        first.add(" ");
        new StringBuilder(hebrew).reverse().codePoints().forEach(c -> first.add(Character.toString(c)));
        List<String> second = new java.util.ArrayList<>();
        "[٣٠] rights".codePoints().forEach(c -> second.add(Character.toString(c)));
        String body = "הואיל והכרה בכבוד הטבעי אשר לכל";
        byte[] pdf = WorldPdfs.mapped(List.of(Text.line(body, true, 100), Text.glyphs(first, true, 160),
                Text.glyphs(second, true, 160 + 14.4f), Text.line(body, true, 240)));
        String doc = parts(convert(pdf, OfficeConvert.Format.DOCX)).get("word/document.xml");
        String text = doc.replaceAll("<[^>]+>", "");
        assertTrue(text.contains("human-rights [٣٠]") && text.indexOf(hebrew) < text.indexOf("https"), text);
    }

    @Test
    void indentedRightToLeftLinesSharingTheirStartAreStartAligned() throws Exception {
        String first = "כל אדם זכאי לחיים ולחירות";
        String second = "כל אדם יש לו";
        String body = "הואיל והכרה בכבוד הטבעי";
        byte[] pdf = WorldPdfs.mapped(List.of(Text.line(body, true, 100), Text.line(first, true, 160).at(72, 12, 428),
                Text.line(second, true, 220).at(72, 12, 428), Text.line(body, true, 280)));
        String doc = parts(convert(pdf, OfficeConvert.Format.DOCX)).get("word/document.xml");
        for (String word : new String[] {"זכאי", "יש לו"}) {
            int at = doc.indexOf(word);
            String para = doc.substring(doc.lastIndexOf("<w:p>", at), at);
            assertTrue(para.contains("<w:bidi/>") && !para.contains("<w:jc "), para);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("w:left=\"(\\d+)\"").matcher(para);
            assertTrue(m.find() && Math.abs(Integer.parseInt(m.group(1)) - 800) < 60,
                    "start indent of 40 pt, which Word applies on the right: " + para);
        }
    }

    private static float lineWidth(byte[] pdf, float y) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            float[] extent = {Float.MAX_VALUE, -Float.MAX_VALUE};
            float top = doc.getPage(0).getMediaBox().getHeight() - y;
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper() {
                @Override
                protected void processTextPosition(org.apache.pdfbox.text.TextPosition t) {
                    if (Math.abs(t.getTextMatrix().getTranslateY() - top) < 0.5f && !t.getUnicode().isBlank()) {
                        extent[0] = Math.min(extent[0], t.getXDirAdj());
                        extent[1] = Math.max(extent[1], t.getXDirAdj() + t.getWidthDirAdj());
                    }
                }
            };
            stripper.getText(doc);
            return extent[1] - extent[0];
        }
    }

    @Test
    void rightToLeftListMarkersBecomeNumbering() throws Exception {
        String[] items = {"\u05DB\u05DC \u05D0\u05D3\u05DD \u05D6\u05DB\u05D0\u05D9 \u05DC\u05D7\u05D9\u05D9\u05DD",
                "\u05DB\u05DC \u05D0\u05D3\u05DD \u05D6\u05DB\u05D0\u05D9 \u05DC\u05D7\u05D9\u05E8\u05D5\u05EA",
                "\u05DB\u05DC \u05D0\u05D3\u05DD \u05D6\u05DB\u05D0\u05D9 \u05DC\u05D1\u05D9\u05D8\u05D7\u05D5\u05DF"};
        byte[] pdf = WorldPdfs.mapped(list(HEBREW, new String[] {"\u05D0.", "\u05D1.", "\u05D2."}, items, true));
        Map<String, String> docx = parts(convert(pdf, OfficeConvert.Format.DOCX));
        String body = docx.get("word/document.xml");
        assertTrue(docx.get("word/numbering.xml").contains("<w:numFmt w:val=\"hebrew1\"/>"), docx.get("word/numbering.xml"));
        assertEquals(3, body.split("<w:numPr>", -1).length - 1, body);
        assertFalse(body.contains("\u05D0.") || body.contains(".\u05D0"), "marker left in the text");
        assertTrue(body.contains(items[2]), "item text in logical order");
        String slide = parts(convert(pdf, OfficeConvert.Format.PPTX)).get("ppt/slides/slide1.xml");
        assertTrue(slide.contains("<a:buAutoNum type=\"hebrew2Minus\""), slide);
        Map<String, String> odt = parts(convert(pdf, OfficeConvert.Format.ODT));
        assertTrue((odt.get("content.xml") + odt.get("styles.xml")).contains("style:num-format=\"\u05D0, \u05D9, \u05E7, ...\""));
        String rtf = new String(convert(pdf, OfficeConvert.Format.RTF), StandardCharsets.US_ASCII);
        assertTrue(rtf.contains("\\levelnfc45"), "Hebrew RTF numbering");

        String persian = "\u0627\u06CC\u0646 \u06CC\u06A9 \u0645\u062A\u0646 \u0641\u0627\u0631\u0633\u06CC \u0627\u0633\u062A";
        byte[] bullets = WorldPdfs.mapped(list(persian, new String[] {"\u2022", "\u2022"}, new String[] {persian, persian}, true));
        String bulleted = parts(convert(bullets, OfficeConvert.Format.DOCX)).get("word/numbering.xml");
        assertTrue(bulleted.contains("<w:numFmt w:val=\"bullet\"/>"), bulleted);
    }

    @Test
    void aListItemThatHappensToSitMidColumnStaysStartAligned() throws Exception {
        String words = "Everyone has the right to freedom of movement and residence within the borders of each state and to leave";
        String item = null;
        for (int n = 20; n <= words.length() && item == null; n++) {
            float w = WorldPdfs.width(List.of(Text.line(words.substring(0, n), false, 150).at(112, 12, 410)), 0);
            item = w >= 404 && w <= 416 ? words.substring(0, n) : null;
        }
        assertTrue(item != null);
        List<Text> texts = List.of(Text.line(item, false, 150).at(112, 12, 410),
                Text.line("The preamble line that sets the right edge of the column", true, 100),
                Text.line("A second line that sets its left edge", false, 120), Text.line("1.", false, 150).at(90, 12, 20),
                Text.line("2.", false, 170).at(90, 12, 20), Text.line("Everyone has the right to leave", false, 170).at(112, 12, 410));
        String body = parts(convert(WorldPdfs.mapped(texts), OfficeConvert.Format.DOCX)).get("word/document.xml");
        assertEquals(2, body.split("<w:numPr>", -1).length - 1, body);
        assertFalse(body.contains("<w:jc w:val=\"center\"/>"), body);
    }

    @Test
    void eastAsianListMarkersBecomeNumbering() throws Exception {
        String chinese = "\u4EBA\u4EBA\u751F\u800C\u81EA\u7531\uFF0C\u5728\u5C0A\u4E25\u548C\u6743\u5229\u4E0A\u4E00\u5F8B\u5E73\u7B49\u3002";
        String[] items = {chinese, chinese, chinese};
        byte[] pdf = WorldPdfs.mapped(list(chinese, new String[] {"\u4E00.", "\u4E8C.", "\u4E09."}, items, false));
        String numbering = parts(convert(pdf, OfficeConvert.Format.DOCX)).get("word/numbering.xml");
        assertTrue(numbering.contains("<w:numFmt w:val=\"chineseCounting\"/>"), numbering);
        String korean = "\uBAA8\uB4E0 \uC0AC\uB78C\uC740 \uC790\uC720\uB86D\uB2E4";
        byte[] ganada = WorldPdfs.mapped(list(korean, new String[] {"\uAC00.", "\uB098."}, new String[] {korean, korean}, false));
        assertTrue(parts(convert(ganada, OfficeConvert.Format.DOCX)).get("word/numbering.xml").contains("\"ganada\""));
    }

    @Test
    void verticalEastAsianColumnsBecomeUprightVerticalText() throws Exception {
        String[] columns = {"\u4EBA\u4EBA\u751F\u800C\u81EA\u7531\uFF0C\u5728\u5C0A\u4E25", "\u548C\u6743\u5229\u4E0A\u4E00\u5F8B\u5E73\u7B49\u3002\u4ED6",
                "\u4EEC\u8D4B\u6709\u7406\u6027\u548C\u826F\u5FC3"};
        List<Text> texts = new java.util.ArrayList<>();
        texts.add(Text.line("\u7B2C\u4E00\u6761\u3000\u4EBA\u4EBA\u751F\u800C\u81EA\u7531\u3002", false, 100));
        for (int i = 0; i < columns.length; i++) {
            texts.add(Text.column(columns[i], 400 - 24 * i, 200, 12));
        }
        byte[] pdf = WorldPdfs.mapped(texts);
        String body = parts(convert(pdf, OfficeConvert.Format.DOCX)).get("word/document.xml");
        assertTrue(body.contains("vert=\"eaVert\""), "upright vertical text box");
        assertTrue(body.indexOf(columns[0]) >= 0 && body.indexOf(columns[0]) < body.indexOf(columns[1])
                && body.indexOf(columns[1]) < body.indexOf(columns[2]), "columns read right to left, top to bottom");
        String slide = parts(convert(pdf, OfficeConvert.Format.PPTX)).get("ppt/slides/slide1.xml");
        assertTrue(slide.contains("<a:bodyPr vert=\"eaVert\""), "upright vertical slide text");
        String odt = parts(convert(pdf, OfficeConvert.Format.ODT)).get("content.xml");
        assertTrue(odt.contains("style:writing-mode=\"tb-rl\""), "vertical ODT frame");
        assertTrue(new String(convert(pdf, OfficeConvert.Format.RTF), StandardCharsets.US_ASCII).contains("{\\sn txflTextFlow}{\\sv 1}"));
    }

    @Test
    void verticalBoxesSitInTheTextWhereTheyAppearOnThePage() throws Exception {
        String above = "\u7B2C\u4E00\u6761\u4EBA\u4EBA\u751F\u800C\u81EA\u7531\u3002";
        String[] cells = {"\u7B2C\u4E00\u6761", "\u4EBA\u4EBA\u751F\u800C", "\u7B2C\u4E8C\u6761", "\u4EBA\u4EBA\u6709\u8CC7"};
        String[] columns = {"\u4EBA\u4EBA\u751F\u800C\u81EA\u7531\uFF0C\u5728\u5C0A\u4E25", "\u548C\u6743\u5229\u4E0A\u4E00\u5F8B"};
        List<Text> texts = new java.util.ArrayList<>(List.of(Text.line(above, false, 100)));
        for (int i = 0; i < cells.length; i++) {
            texts.add(Text.line(cells[i], false, 150 + 30 * (i / 2)).at(i % 2 == 0 ? 76 : 276, 12, 180));
        }
        for (int i = 0; i < columns.length; i++) {
            texts.add(Text.column(columns[i], 400 - 24 * i, 230, 12));
        }
        List<float[]> rules = new java.util.ArrayList<>();
        for (float y : new float[] {132, 162, 192}) {
            rules.add(new float[] {72, y, 472, y});
        }
        for (float x : new float[] {72, 272, 472}) {
            rules.add(new float[] {x, 132, x, 192});
        }
        String body = parts(convert(WorldPdfs.mapped(texts, rules), OfficeConvert.Format.DOCX)).get("word/document.xml");
        int box = body.indexOf(columns[0]);
        assertTrue(body.indexOf("<w:tbl>") > 0 && body.indexOf("</w:tbl>") < box, body);
        assertTrue(body.contains(columns[0] + columns[1]), "a full column runs on into the next");
    }

    @Test
    void tallAndWordspaceScriptsKeepTheirParagraphsWhole() throws Exception {
        String tsheg = "\u0F0B";
        String syllables = ("\u0F60\u0F42\u0FB2\u0F7C" + tsheg + "\u0F56" + tsheg + "\u0F58\u0F72\u0F60\u0F72" + tsheg).repeat(6);
        List<Text> tibetan = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tibetan.add(Text.line(syllables, false, 100 + 36 * i).at(72, 11, 468));
        }
        tibetan.add(Text.line("\u0F60\u0F42\u0FB2\u0F7C" + tsheg + "\u0F56\u0F0D", false, 208).at(72, 11, 468));
        String body = parts(convert(WorldPdfs.mapped(tibetan), OfficeConvert.Format.DOCX)).get("word/document.xml");
        assertTrue(body.contains(syllables + syllables + syllables), "Tibetan lines form one paragraph without spaces");

        String amharic = "\u1230\u12CD" + "\u1361" + "\u1201\u1209" + "\u1361";
        List<Text> ethiopic = List.of(Text.line(amharic.repeat(8), false, 100), Text.line(amharic.repeat(2) + "\u1362", false, 115));
        String words = parts(convert(WorldPdfs.mapped(ethiopic), OfficeConvert.Format.DOCX)).get("word/document.xml");
        assertTrue(words.contains(amharic.repeat(10)), "no space after an Ethiopic wordspace at a line end");
    }

    private static List<Text> list(String lead, String[] markers, String[] items, boolean rtl) {
        List<Text> texts = new java.util.ArrayList<>();
        texts.add(Text.line(lead, rtl, 100));
        for (int i = 0; i < markers.length; i++) {
            float y = 130 + 20 * i;
            texts.add(rtl ? Text.line(markers[i], true, y).at(500, 12, 40) : Text.line(markers[i], false, y).at(90, 12, 30));
            texts.add(rtl ? Text.line(items[i], true, y).at(120, 12, 398) : Text.line(items[i], false, y).at(126, 12, 400));
        }
        return texts;
    }

    private static String nfc(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    private static List<String> lines(String text) {
        return text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return PdfToText.text(doc, PdfToDocx.Options.defaults());
        }
    }

    private static byte[] convert(byte[] pdf, OfficeConvert.Format format) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            OfficeConvert.convert(doc, out, format, OfficeConvert.Settings.defaults());
            return out.toByteArray();
        }
    }

    private static Map<String, String> parts(byte[] zip) throws Exception {
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                parts.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }
}
