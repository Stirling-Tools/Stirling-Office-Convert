package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.TestFonts;

class SubstitutionTest {

    @TempDir
    Path dir;

    private FontLibrary library(String... families) throws IOException {
        Path fonts = Files.createDirectories(dir.resolve(String.join("-", families).replace(' ', '_')));
        for (String f : families) {
            Files.write(fonts.resolve(f.replace(' ', '_') + ".ttf"), TestFonts.renamed(f));
        }
        return FontLibrary.of(List.of(fonts));
    }

    @Test
    void symbolAndWingdingsCharactersDrawAsTheirUnicodeLookAlikes() throws Exception {
        FontLibrary lib = library("DejaVu Sans");
        FontFace symbol = lib.find("Symbol", false, false);
        assertEquals("DejaVu Sans", symbol.family());
        assertTrue(symbol.symbolStandIn());
        assertTrue(symbol.covers(0xF0B7));
        assertEquals(0x2022, symbol.encodedCodePoint(0xF0B7));
        assertEquals(symbol.glyph(0x2022), symbol.glyph(0xF0B7));
        assertEquals(Math.round(0.460f * symbol.unitsPerEm()), symbol.advance(0xF0B7));
        assertEquals(symbol.advance(0xF0B7), symbol.advance(0xB7));
        assertEquals(0x2022, symbol.encodedCodePoint(0xB7));
        assertEquals(0x3B1, symbol.encodedCodePoint('a'));
        FontFace wingdings = lib.find("Wingdings", false, false);
        assertEquals(0x25AA, wingdings.encodedCodePoint(0xF0A7));
        assertTrue(wingdings.covers(0xF0A7));
        assertEquals(0x25CF, wingdings.encodedCodePoint(0xF06C));
        assertFalse(lib.find("Arial", false, false).covers(0xF0B7));
        assertFalse(lib.find("Arial", false, false).symbolStandIn());
        FontFace bold = symbol.withSynthetic(true, false);
        assertTrue(bold.symbolStandIn());
        assertEquals(0x2022, bold.encodedCodePoint(0xF0B7));
    }

    @Test
    void aSymbolCharacterWithNoLookAlikeInTheFaceStaysUncovered() throws Exception {
        FontLibrary lib = library("DejaVu Sans");
        FontFace wingdings = lib.find("Wingdings", false, false);
        assertFalse(wingdings.covers(0xF0FC));
        assertEquals(0xF0FC, wingdings.encodedCodePoint(0xF0FC));
    }

    @Test
    void postScriptNamesAndStyleWordsFindTheFamilyAndItsStyle() {
        assertEquals(new FontNames.Alias("Arial", true, false), FontNames.alias("Arial-BoldMT"));
        assertEquals(new FontNames.Alias("Arial", false, false), FontNames.alias("ArialMT"));
        assertEquals(new FontNames.Alias("Times New Roman", true, true),
                FontNames.alias("TimesNewRomanPS-BoldItalicMT"));
        assertEquals(new FontNames.Alias("Courier New", false, false), FontNames.alias("CourierNewPSMT"));
        assertEquals(new FontNames.Alias("Times New Roman", true, false), FontNames.alias("Times New Roman Grassetto"));
        assertEquals(new FontNames.Alias("Arial", false, true), FontNames.alias("Arial Italic"));
        assertEquals(new FontNames.Alias("MS Gothic", false, false), FontNames.alias("MSGothic"));
        assertEquals(new FontNames.Alias("Symbol", false, false), FontNames.alias("SymbolMT"));
        assertNull(FontNames.alias("Wingdings 2"));
        assertNull(FontNames.alias("Calibri Light"));
        assertNull(FontNames.alias("Gill Sans MT"));
        assertNull(FontNames.alias("Arial"));
    }

    @Test
    void aStyleNamedFamilyUsesTheBoldFaceOfItsFamily() throws Exception {
        FontLibrary lib = library("Liberation Sans");
        FontFace face = lib.find("Arial-BoldMT", false, false);
        assertEquals("Liberation Sans", face.family());
        assertTrue(face.boldStyle());
        assertEquals("Arial-BoldMT", face.requestedFamily());
        assertTrue(face.note().startsWith("Arial-BoldMT is not installed"), face.note());
    }

    @Test
    void condensedFamiliesPreferACondensedFaceAndMonotypeIsNotMonospaced() {
        assertEquals("Liberation Sans Narrow", Substitutes.generic("Tw Cen MT Condensed").get(0));
        assertFalse(Substitutes.generic("Monotype Corsiva").contains("Courier New"));
        assertEquals("Courier New", Substitutes.generic("Some Mono").get(0));
        assertEquals("Times New Roman", Substitutes.generic("Monotype Corsiva").get(0));
        assertEquals("Times New Roman", Substitutes.generic("Calisto MT").get(0));
        assertEquals("Times New Roman", Substitutes.generic("Cooper Black").get(0));
        assertEquals("Times New Roman", Substitutes.generic("Goudy Old Style").get(0));
        assertEquals("Arial", Substitutes.generic("Franklin Gothic Demi").get(0));
        assertEquals("Arial", Substitutes.generic("Copperplate Gothic Bold").get(0));
        assertEquals("Yu Gothic", Substitutes.generic("MS UI Gothic").get(0));
    }

    @Test
    void aMissingOfficeFontKeepsItsOwnWidthsAndLineHeight() throws Exception {
        FontLibrary lib = library("DejaVu Sans", "Liberation Sans");
        FontFace verdana = lib.find("Verdana", false, false);
        assertEquals("DejaVu Sans", verdana.family());
        assertEquals(2025, verdana.advance('W'), 1);
        assertEquals(1993, verdana.advance(0x0416), 2);
        assertEquals(verdana.advance(0x0416), verdana.width("Ж", 2048), 1e-3);
        assertTrue(verdana.glyphStretch() > 1 && verdana.glyphStretch() < 1.2f, "" + verdana.glyphStretch());
        CloudFonts.Emulation e = new CloudFonts(lib).emulate("Verdana", false, false);
        assertEquals(100, e.scale());
        assertNull(e.metrics());
        assertEquals(2059 / 2048f, e.vertical()[0], 1e-3);
        assertEquals(430 / 2048f, e.vertical()[1], 1e-3);
        FontFace narrow = lib.find("Arial Narrow", false, false);
        assertEquals("Liberation Sans", narrow.family());
        assertEquals(0.82f, narrow.glyphStretch(), 0.01f);
        FontFace arial = lib.find("Arial", false, false);
        assertEquals(1, arial.glyphStretch());
        assertNull(new CloudFonts(lib).emulate("Arial", false, false).vertical());
        assertEquals(1, lib.find("Symbol", false, false).glyphStretch());
    }

    @Test
    void metricCompatibleStandInsAreNotEmulatedButOthersAre() throws Exception {
        FontLibrary lib = library("Carlito", "Caladea");
        CloudFonts cloud = new CloudFonts(lib);
        assertNull(cloud.emulate("Calibri", false, false).vertical());
        assertEquals(1, lib.find("Calibri", false, false).glyphStretch());
        CloudFonts.Emulation cambria = cloud.emulate("Cambria", false, false);
        assertNotNull(cambria.vertical());
        assertEquals(1946 / 2048f, cambria.vertical()[0], 1e-3);
        assertNotNull(cloud.emulate("Calibri Light", false, false).vertical());
        assertNotNull(cloud.emulate("Aptos", false, false).metrics());
    }

    @Test
    void calibrisScreenWidthsReplaceCarlitosWhereTheirHintingDiffers() throws Exception {
        FontLibrary lib = library("Liberation Sans").withFonts(List.of(TestFonts.renamed("Carlito")));
        FontFace calibri = lib.find("Calibri", false, false);
        assertEquals("Carlito", calibri.family());
        for (char digit = '0'; digit <= '9'; digit++) {
            assertEquals(7, calibri.hintedAdvance(digit, 15));
        }
        assertEquals(4, calibri.hintedAdvance('i', 12));
        FontFace carlito = lib.find("Carlito", false, false);
        assertEquals(carlito.hintedAdvance('W', 15), calibri.hintedAdvance('W', 15));
        assertEquals(-1, carlito.hintedAdvance('0', 15));
    }

    @Test
    void aMissingEastAsianCharacterTakesAFullEm() throws Exception {
        FontLibrary lib = library("Liberation Sans");
        FontFace mincho = lib.find("MS Mincho", false, false);
        assertFalse(mincho.covers(0x4E00));
        assertEquals(mincho.unitsPerEm(), mincho.advance(0x4E00));
        assertEquals(mincho.unitsPerEm(), mincho.advance(0xAC00));
        assertEquals(mincho.unitsPerEm(), mincho.advance(0xFF21));
        assertTrue(mincho.advance(0x0E01) < mincho.unitsPerEm());
    }

    @Test
    void eastAsianFamiliesUnderTheirLocalNamesKeepTheirWidthsAndLineHeight() throws Exception {
        FontLibrary lib = library("Liberation Sans", "Liberation Serif");
        assertEquals(new FontNames.Alias("MS Gothic", false, false), FontNames.alias("ＭＳ ゴシック"));
        assertEquals(new FontNames.Alias("Malgun Gothic", false, false), FontNames.alias("맑은 고딕"));
        FontFace mincho = lib.find("ＭＳ 明朝", false, false);
        assertEquals("Liberation Serif", mincho.family());
        assertTrue(mincho.emulated());
        assertTrue(mincho.eastAsian());
        assertEquals(mincho.unitsPerEm() / 2, mincho.advance('m'), 1);
        assertEquals(mincho.unitsPerEm() / 2, mincho.advance('1'), 1);
        assertTrue(mincho.glyphScale('m') < 1 && mincho.glyphScale('i') > 1);
        assertEquals(mincho.unitsPerEm(), mincho.advance(0x4E00));
        CloudFonts.Emulation e = new CloudFonts(lib).emulate("ＭＳ 明朝", false, false);
        assertEquals(0.8594f, e.vertical()[0], 1e-3);
        assertEquals(0.1406f, e.vertical()[1], 1e-3);
        FontFace yaHei = lib.find("微软雅黑", false, false);
        assertEquals("Liberation Sans", yaHei.family());
        assertTrue(yaHei.eastAsian());
        assertEquals("Liberation Serif", lib.find("SimSun", false, false).family());
        assertTrue(lib.find("MS PMincho", false, false).eastAsian());
        assertFalse(lib.find("Arial", false, false).eastAsian());
        assertFalse(lib.find("Century Gothic", false, false).eastAsian());
    }

    @Test
    void aMissingEastAsianFontPrefersFacesOfItsOwnLanguage() {
        List<String> mincho = Substitutes.generic("MS Mincho");
        assertTrue(mincho.indexOf("Noto Sans JP") < mincho.indexOf("Noto Sans SC"));
        assertTrue(mincho.indexOf("Liberation Serif") < mincho.indexOf("Liberation Sans"));
        List<String> simSun = Substitutes.generic("SimSun");
        assertTrue(simSun.indexOf("Noto Serif SC") < simSun.indexOf("Noto Sans SC"));
        assertTrue(simSun.indexOf("Noto Sans SC") < simSun.indexOf("Noto Sans JP"));
        List<String> gulim = Substitutes.generic("굴림");
        assertTrue(gulim.indexOf("Noto Sans KR") < gulim.indexOf("Noto Sans JP"));
        assertTrue(gulim.indexOf("Liberation Sans") < gulim.indexOf("Liberation Serif"));
        List<String> mingLiU = Substitutes.generic("MingLiU");
        assertTrue(mingLiU.indexOf("Noto Sans TC") < mingLiU.indexOf("Noto Sans SC"));
    }

    @Test
    void aFallbackForHebrewKeepsTheWidthTheRequestedFontDrawsItWith() throws Exception {
        FontLibrary lib = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("none"))))
                .withFonts(List.of(TestFonts.renamed("DejaVu Sans")));
        FontFace times = lib.find("Times New Roman", false, false);
        FontFace plain = lib.fallback(0x05D0, false, false);
        FontFace hebrew = lib.fallback(0x05D0, times);
        assertTrue(hebrew.sameProgram(plain));
        assertEquals(ScriptWidths.scale("Times New Roman", plain.program(), ScriptWidths.HEBREW, false),
                hebrew.glyphStretch());
        assertEquals(0.3872f, sampleWidth(hebrew, times, ScriptWidths.HEBREW), 0.002f);
        assertEquals(Math.round(plain.advance(0x05D0) * hebrew.glyphStretch()), hebrew.advance(0x05D0));
        GlyphRun run = hebrew.shape("שלום", true);
        assertEquals(plain.shape("שלום", true).advance() * hebrew.glyphStretch(), run.advance(), 1);
        assertEquals(hebrew, lib.fallback(0x05D1, times));
        assertEquals(plain, lib.fallback(0x05D0, lib.find("Verdana", false, false)));
        assertEquals(plain, lib.fallback(0x0416, times));
    }

    @Test
    void aNarrowArabicFontNarrowsAWideStandInPastSixTenths() throws Exception {
        Path wide = Files.createDirectories(dir.resolve("wide"));
        int[] letters = ScriptWidths.ARABIC.codePoints().filter(c -> c != ' ').distinct().toArray();
        Files.write(wide.resolve("Wide.ttf"), TestFonts.withGlyphs("Wide Arabic", 'W', letters));
        FontProgram program = FontLibrary.of(List.of(wide)).find("Wide Arabic", false, false).program();
        float k = ScriptWidths.scale("Arabic Typesetting", program, ScriptWidths.ARABIC, false);
        assertTrue(k > 0 && k < 0.6f, "" + k);
    }

    // Average advance in ems over a sample whose letters come from one face and spaces from another
    private static float sampleWidth(FontFace letters, FontFace spaces, String sample) {
        float units = 0;
        for (int i = 0; i < sample.length(); i++) {
            char c = sample.charAt(i);
            units += c == ' ' ? spaces.advance(c) : letters.advance(c);
        }
        return units / letters.unitsPerEm() / sample.length();
    }

    @Test
    void aStandInThatDrawsHebrewItselfKeepsTheRequestedFontsHebrewWidth() throws Exception {
        FontLibrary lib = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("bundled-hebrew"))));
        FontFace arial = lib.find("Arial", false, false);
        FontFace times = lib.find("Times New Roman", false, false);
        FontFace liberation = lib.lastResort(false, false);
        assertTrue(arial.sameProgram(liberation) && arial.covers(0x05D0));
        assertTrue(arial.scaledPerGlyph() && times.scaledPerGlyph());
        assertFalse(liberation.scaledPerGlyph());
        assertEquals(0.4425f, sampleWidth(arial, arial, ScriptWidths.HEBREW), 0.002f);
        assertEquals(0.3872f, sampleWidth(times, times, ScriptWidths.HEBREW), 0.002f);
        assertTrue(sampleWidth(liberation, liberation, ScriptWidths.HEBREW) > 0.46f);
        FontFace arialBold = lib.find("Arial", true, false);
        assertEquals(0.4633f, sampleWidth(arialBold, arialBold, ScriptWidths.HEBREW), 0.002f);
        float k = arial.glyphScale(0x05E9);
        assertTrue(k > 0.85f && k < 0.97f, "" + k);
        assertEquals(Math.round(liberation.advance(0x05E9) * k), arial.advance(0x05E9));
        assertEquals(liberation.advance('W'), arial.advance('W'));
        assertEquals(liberation.advance(' '), arial.advance(' '));
        assertEquals(1, arial.glyphScale('W'));
        GlyphRun run = arial.shape("שלום", true);
        assertEquals(k, run.stretch());
        assertEquals(liberation.shape("שלום", true).advance() * k, run.advance(), 1);
        assertEquals(1, arial.shape("Word", false).stretch());
        assertEquals(k, arial.withSynthetic(true, false).glyphScale(0x05E9));
        FontFace symbol = lib.find("Symbol", false, false);
        assertFalse(symbol.scaledPerGlyph());
    }

    // The same font with another OS/2 weight class, as a variable font whose default instance is Thin reports
    private static byte[] withWeight(byte[] ttf, int weight) {
        ByteBuffer b = ByteBuffer.wrap(ttf);
        int tables = b.getShort(4) & 0xFFFF;
        for (int i = 0; i < tables; i++) {
            int at = 12 + 16 * i;
            if (new String(ttf, at, 4, StandardCharsets.ISO_8859_1).equals("OS/2")) {
                b.putShort(b.getInt(at + 8) + 4, (short) weight);
                return ttf;
            }
        }
        throw new IllegalArgumentException("The font has no OS/2 table");
    }

    @Test
    void familyNamesSayHowHeavyTheyAre() {
        assertEquals(400, Weights.of("Arial"));
        assertEquals(900, Weights.of("Arial Black"));
        assertEquals(900, Weights.of("ArialBlack"));
        assertEquals(900, Weights.of("Arial-Black"));
        assertEquals(600, Weights.of("Segoe UI Semibold"));
        assertEquals(300, Weights.of("Calibri Light"));
        assertEquals(800, Weights.of("Gill Sans Ultra Bold"));
        assertEquals(800, Weights.of("Aptos ExtraBold"));
        assertEquals(900, Weights.of("Impact"));
        assertEquals(400, Weights.of("Noto Sans SC"));
        assertEquals(400, Weights.of("Black Chancery"));
    }

    @Test
    void aLighterFaceIsDrawnWithAnOutlineThatBringsItToTheWeightItStandsFor() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("weights"));
        Files.write(fonts.resolve("Thin.ttf"), withWeight(TestFonts.renamed("Noto Sans SC"), 100));
        Files.write(fonts.resolve("Sans.ttf"), TestFonts.renamed("Liberation Sans"));
        FontLibrary lib = FontLibrary.of(List.of(fonts));
        float unit = Weights.STROKE_PER_UNIT;
        FontFace thin = lib.find("Noto Sans SC", false, false);
        assertEquals(100, thin.weight());
        float light = Weights.LIGHT_STROKE_PER_UNIT;
        assertEquals(300 * light, thin.embolden(), 1e-6, "a Thin stem is thinner than a Regular by more");
        FontFace thinBold = lib.find("Noto Sans SC", true, false);
        assertTrue(thinBold.syntheticBold());
        assertEquals(300 * light, thinBold.embolden(), 1e-6);
        FontFace black = lib.find("Arial Black", false, false);
        assertEquals("Liberation Sans", black.family());
        assertTrue(black.syntheticBold());
        assertEquals(200 * unit, black.embolden(), 1e-6);
        FontFace impact = lib.find("Impact", false, false);
        assertTrue(impact.boldStyle());
        assertTrue(impact.embolden() > 0);
        assertEquals(0, lib.find("Arial", false, false).embolden());
        assertEquals(0, lib.find("Arial", true, false).embolden());
        assertEquals(0, lib.find("Calibri Light", false, false).embolden());
        assertEquals(0, lib.find("Segoe UI Semibold", false, false).embolden());
        assertEquals(black.embolden(), black.withSynthetic(true, false).embolden());
        assertFalse(black.equals(lib.exact("Liberation Sans", true, false)));
        FontLibrary withImpact = lib.withFonts(List.of(TestFonts.renamed("Impact")));
        assertEquals("Impact", withImpact.find("Impact", false, false).family());
        assertEquals(0, withImpact.find("Impact", false, false).embolden());
        FontFace fallback = lib.fallback('A', lib.find("SimSun", false, false));
        assertEquals(0, fallback.embolden());
    }

    @Test
    void symbolFontsDrawTheirCodesInTheControlRange() throws Exception {
        FontLibrary lib = library("DejaVu Sans");
        FontFace wingdings3 = lib.find("Wingdings 3", false, false);
        assertTrue(wingdings3.symbolCode(0x84));
        assertTrue(wingdings3.covers(0x84));
        assertTrue(wingdings3.advance(0x84) > 0);
        assertEquals(0x25BA, wingdings3.encodedCodePoint(0x84));
        assertTrue(stirling.software.officeconvert.topdf.pdf.TextStyle.of(wingdings3, 10).width("") > 0);
        FontFace arial = lib.find("Arial", false, false);
        assertFalse(arial.symbolCode(0x84));
        assertEquals(0, arial.advance(0x84));
    }

    @Test
    void anEmulatedFacesScreenWidthsFollowTheOfficeFontsAdvances() throws Exception {
        FontLibrary lib = library("DejaVu Sans");
        FontFace tahoma = lib.find("Tahoma", false, false);
        assertTrue(tahoma.emulated());
        int ppem = 13;
        assertEquals(Math.round(tahoma.advance('0') * ppem / (float) tahoma.unitsPerEm()), tahoma.hintedAdvance('0', ppem));
        assertEquals(Math.round(tahoma.advance('W') * ppem / (float) tahoma.unitsPerEm()), tahoma.hintedAdvance('W', ppem));
    }

    @Test
    void aStandInDrawsTheUnderlineAndStrikeoutOfTheFontItReplaces() throws Exception {
        FontLibrary lib = library("Liberation Sans", "DejaVu Sans");
        FontMetrics own = lib.find("Liberation Sans", false, false).metrics();
        FontMetrics arial = lib.find("Arial", false, false).metrics();
        assertEquals(Math.round(-0.1060f * 2048), arial.underlinePosition());
        assertEquals(Math.round(0.0732f * 2048), arial.underlineThickness());
        assertEquals(Math.round(0.2588f * 2048), arial.strikeoutPosition());
        assertEquals(own.winAscent(), arial.winAscent());
        assertEquals(own.hheaLineGap(), arial.hheaLineGap());
        FontMetrics bold = lib.find("Arial", true, false).metrics();
        assertEquals(Math.round(0.1050f * 2048), bold.underlineThickness());
        FontMetrics verdana = lib.find("Verdana", false, false).metrics();
        assertEquals(Math.round(-0.0879f * 2048), verdana.underlinePosition());
        assertEquals(own.underlinePosition(), lib.find("Liberation Sans", false, false).metrics().underlinePosition());
        assertEquals(own, lib.find("Some Unknown Face", false, false).metrics());
        FontMetrics symbol = lib.find("Symbol", false, false).metrics();
        assertEquals(lib.find("DejaVu Sans", false, false).metrics(), symbol);
    }

    @Test
    void symbolLookAlikesFillTheSymbolFontsGlyphBoxAndAdvance() throws Exception {
        FontLibrary lib = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("bundled-only"))));
        FontFace symbol = lib.find("Symbol", false, false);
        float upm = symbol.unitsPerEm();
        float[] fit = symbol.symbolFit(0xF0B7);
        assertNotNull(fit);
        float[] own = symbol.program().glyphBox(symbol.glyph(0x2022));
        float x0 = fit[0] * own[0] / upm + fit[2];
        float x1 = fit[0] * own[2] / upm + fit[2];
        float y0 = fit[1] * own[1] / upm + fit[3];
        float y1 = fit[1] * own[3] / upm + fit[3];
        assertEquals(0.052f, x0, 0.01f);
        assertEquals(0.409f, x1, 0.01f);
        assertEquals(0.103f, y0, 0.01f);
        assertEquals(0.460f, y1, 0.01f);
        assertEquals(Math.round(0.460f * 15), symbol.hintedAdvance(0xF0B7, 15));
        assertEquals(0, symbol.kerning('A', 'V'));
        FontFace wingdings = lib.find("Wingdings", false, false);
        float[] square = wingdings.symbolFit(0xF0A7);
        float[] big = wingdings.program().glyphBox(wingdings.glyph(0xF0A7));
        assertEquals(0.290f, square[0] * (big[2] - big[0]) / upm, 0.01f);
        assertEquals(Math.round(0.458f * upm), wingdings.advance(0xF0A7));
        assertNull(symbol.symbolFit(' '));
        assertNull(lib.find("Arial", false, false).symbolFit(0xF0B7));
    }

    @Test
    void commonOfficeFamiliesTryTheirClosestFreeFaceFirst() {
        Map<String, String> first = Map.ofEntries(Map.entry("Calibri", "Carlito"), Map.entry("Calibri Light", "Carlito"),
                Map.entry("Cambria", "Caladea"), Map.entry("Arial", "Liberation Sans"),
                Map.entry("Helvetica", "Liberation Sans"), Map.entry("Arial Narrow", "Liberation Sans Narrow"),
                Map.entry("Times New Roman", "Liberation Serif"), Map.entry("Times", "Liberation Serif"),
                Map.entry("Courier New", "Liberation Mono"), Map.entry("Courier", "Liberation Mono"),
                Map.entry("Georgia", "Gelasio"), Map.entry("Segoe UI", "Selawik"),
                Map.entry("Segoe UI Light", "Selawik Light"), Map.entry("Century Gothic", "URW Gothic"),
                Map.entry("Book Antiqua", "P052"), Map.entry("Palatino Linotype", "P052"), Map.entry("Palatino", "P052"),
                Map.entry("Bookman Old Style", "URW Bookman"), Map.entry("Century Schoolbook", "C059"),
                Map.entry("Century", "C059"), Map.entry("Garamond", "EB Garamond"), Map.entry("Comic Sans MS", "Comic Neue"),
                Map.entry("Consolas", "Inconsolata"), Map.entry("Verdana", "DejaVu Sans"),
                Map.entry("Trebuchet MS", "Fira Sans"), Map.entry("Lucida Console", "DejaVu Sans Mono"),
                Map.entry("Lucida Sans", "Open Sans"), Map.entry("Franklin Gothic Book", "Source Sans 3"),
                Map.entry("Gill Sans MT", "Lato"), Map.entry("Candara", "Source Sans 3"), Map.entry("Corbel", "Carlito"),
                Map.entry("Constantia", "Source Serif 4"), Map.entry("Monotype Corsiva", "Z003"),
                Map.entry("Aptos", "Source Sans 3"), Map.entry("Aptos Display", "Source Sans 3"),
                Map.entry("Aptos Narrow", "Roboto Condensed"), Map.entry("Cascadia Code", "Source Code Pro"));
        Set<String> everywhere = Set.of("Liberation Sans", "Liberation Serif", "Liberation Mono",
                "Liberation Sans Narrow", "DejaVu Sans", "DejaVu Serif", "DejaVu Sans Mono");
        first.forEach((family, free) -> {
            List<String> chain = Substitutes.table(family);
            assertEquals(free, chain.get(0), family);
            assertTrue(chain.stream().anyMatch(everywhere::contains), family + " has no stand-in every image has");
        });
        assertTrue(Substitutes.table("Tahoma").contains("DejaVu Sans Condensed"));
        assertEquals(Substitutes.table("Arial"), Substitutes.table(" ARIAL "));
    }

    @Test
    void metricClonesStandInSilentlyWhileOtherStandInsAreReported() throws Exception {
        FontLibrary lib = library("Liberation Sans", "Liberation Serif", "Gelasio", "Selawik", "URW Gothic", "P052",
                "C059", "URW Bookman", "Source Sans 3");
        for (String family : List.of("Arial", "Helvetica", "ArialMT", "Times New Roman", "Georgia", "Segoe UI",
                "Century Gothic", "Book Antiqua", "Palatino", "Century Schoolbook", "Bookman Old Style")) {
            FontFace f = lib.find(family, false, false);
            assertTrue(f.substituted(), family);
            assertNull(f.note(), family + ": " + f.note());
        }
        assertEquals("Palatino Linotype is not installed; using P052",
                lib.find("Palatino Linotype", false, false).note());
        assertEquals("Aptos is not installed; using Source Sans 3", lib.find("Aptos", false, false).note());
        assertEquals("Verdana is not installed; using Liberation Sans", lib.find("Verdana", false, false).note());
        assertNotNull(lib.find("Georgia", false, true).note(), "a slanted upright face is not a clone");
        assertNotNull(lib.find("Arial", true, false).note(), "an emboldened regular face is not a clone");
        assertNotNull(lib.find("Arial Black", false, false).note(), "Arial Black is not Arial in bold");
        assertNotNull(lib.find("Segoe UI Semibold", false, false).note(), "nor is Segoe UI Semibold Segoe UI");
    }

    @Test
    void postScriptStyleWordsThatNameAFamilyStayInTheFamily() throws Exception {
        assertEquals(new FontNames.Alias("Calibri Light", false, false), FontNames.alias("Calibri-Light"));
        assertEquals(new FontNames.Alias("Segoe UI Semilight", false, true),
                FontNames.alias("SegoeUI-SemilightItalic"));
        assertEquals(new FontNames.Alias("Aptos Display", true, false), FontNames.alias("Aptos-DisplayBold"));
        assertEquals(new FontNames.Alias("Century Gothic", true, false), FontNames.alias("CenturyGothic-Bold"));
        assertEquals(new FontNames.Alias("Arial Narrow", true, true), FontNames.alias("ArialNarrow-BoldItalic"));
        assertEquals(new FontNames.Alias("Hiragino Kaku Gothic Pro", false, false),
                FontNames.alias("ヒラギノ角ゴ Pro W3"));
        FontLibrary lib = library("Selawik Light", "Carlito");
        FontFace light = lib.find("SegoeUI-Light", false, false);
        assertEquals("Selawik Light", light.family());
        assertNull(light.note());
        assertEquals("Carlito", lib.find("Calibri-Light", false, false).family());
    }
}
