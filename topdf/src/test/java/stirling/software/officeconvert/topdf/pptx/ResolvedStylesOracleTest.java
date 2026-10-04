package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.PoiPackages;
import stirling.software.officeconvert.topdf.odf.OdfPackage;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class ResolvedStylesOracleTest {

    private static final String CORPUS = "TOPDF_STYLE_ORACLE";

    @TempDir
    Path dir;

    @Test
    void everyResolvedPropertyMatchesPoiOnAStyledDeck() throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(styledDeck()))) {
            StyleOracle.Report report = StyleOracle.check(ppt);
            assertEquals(List.of(), report.mismatches());
            assertTrue(report.runs() > 60, "runs " + report.runs());
        }
    }

    @Test
    void inheritedPropertiesComeFromTheLayoutAndMaster() throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(styledDeck()))) {
            XSLFTextShape body = ppt.getSlides().get(0).getPlaceholder(1);
            XSLFTextParagraph p = body.getTextParagraphs().get(2);
            XSLFTextRun r = p.getTextRuns().get(0);
            ResolvedStyles.Level level = new ResolvedStyles().of(p);
            assertEquals(r.getFontSize(), level.run().fontSize(r));
            assertTrue(level.run().fontSize(r) > 0);
            assertEquals(p.getLeftMargin(), level.paragraph().leftMargin(p));
            assertTrue(level.paragraph().isBullet(p));
            assertEquals(p.getBulletCharacter(), level.paragraph().bulletCharacter(p));
        }
    }

    @Test
    void everyResolvedPropertyMatchesPoiOnTheCorpus() throws IOException {
        String corpus = System.getenv(CORPUS);
        assumeTrue(corpus != null && !corpus.isBlank(), CORPUS + " lists the decks to check");
        List<String> failures = new ArrayList<>();
        int decks = 0;
        long runs = 0;
        long checks = 0;
        for (Path deck : decks(corpus)) {
            try (OfficeZip zip = OfficeZip.open(presentation(deck)); XMLSlideShow ppt = PoiPackages.slideShow(zip)) {
                PlaceholderOrder.fix(ppt);
                StyleOracle.Report report = StyleOracle.check(ppt);
                decks++;
                runs += report.runs();
                checks += report.checks();
                for (String m : report.mismatches()) {
                    failures.add(deck.getFileName() + ": " + m);
                }
            } catch (IOException | RuntimeException e) {
                System.out.println("skipped " + deck + ": " + e);
            }
        }
        System.out.printf("style oracle: %d decks, %d runs, %d checks, %d mismatches%n", decks, runs, checks,
                failures.size());
        assertEquals(List.of(), failures.subList(0, Math.min(50, failures.size())));
        assertTrue(decks > 0);
    }

    private Path presentation(Path deck) throws IOException {
        String name = deck.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".odp") && !name.endsWith(".otp") && !name.endsWith(".fodp")) {
            return deck;
        }
        Path out = dir.resolve(deck.getFileName() + ".pptx");
        try (OutputStream os = Files.newOutputStream(out)) {
            OdfPackage.write(deck, os, FontLibrary.system());
        }
        return out;
    }

    private static List<Path> decks(String corpus) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String entry : corpus.split(File.pathSeparator)) {
            Path p = Path.of(entry.strip());
            if (Files.isDirectory(p)) {
                try (Stream<Path> files = Files.walk(p)) {
                    files.filter(ResolvedStylesOracleTest::presentationFile).sorted().forEach(out::add);
                }
            } else if (Files.isRegularFile(p)) {
                out.add(p);
            }
        }
        return out;
    }

    private static boolean presentationFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(p) && (n.endsWith(".pptx") || n.endsWith(".pptm") || n.endsWith(".potx")
                || n.endsWith(".ppsx") || n.endsWith(".odp") || n.endsWith(".otp") || n.endsWith(".fodp"));
    }

    private static byte[] styledDeck() {
        byte[] base = Decks.deck(ppt -> {
            XSLFSlideMaster master = ppt.getSlideMasters().get(0);
            XSLFSlide slide = ppt.createSlide(master.getLayout(SlideLayout.TITLE_AND_CONTENT));
            slide.getPlaceholder(0).setText("Inherited title");
            XSLFTextShape body = slide.getPlaceholder(1);
            body.clearText();
            for (int level = 0; level < 5; level++) {
                XSLFTextParagraph p = body.addNewTextParagraph();
                p.setIndentLevel(level);
                p.addNewTextRun().setText("Level " + level);
                XSLFTextRun own = p.addNewTextRun();
                own.setText(" own");
                own.setBold(level % 2 == 0);
                own.setFontSize(12.0 + level);
                own.setFontColor(new Color(20 * level, 40, 90));
            }
            body.getTextParagraphs().get(0).setTextAlign(TextAlign.CENTER);
            XSLFTable table = slide.createTable(2, 2);
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 2; c++) {
                    table.getCell(r, c).setText("Cell " + r + c);
                }
            }
            XSLFSlide title = ppt.createSlide(master.getLayout(SlideLayout.TITLE));
            title.getPlaceholder(0).setText("Cover");
            title.getPlaceholder(1).setText("Subtitle");
        });
        String styled = "<p:sp " + Decks.NS + "><p:nvSpPr><p:cNvPr id=\"40\" name=\"Styled\"/><p:cNvSpPr/><p:nvPr/>"
                + "</p:nvSpPr><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"3000000\" cy=\"2000000\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr><p:style><a:lnRef idx=\"1\">"
                + "<a:schemeClr val=\"accent1\"/></a:lnRef><a:fillRef idx=\"3\"><a:schemeClr val=\"accent1\"/></a:fillRef>"
                + "<a:effectRef idx=\"2\"><a:schemeClr val=\"accent1\"/></a:effectRef><a:fontRef idx=\"minor\">"
                + "<a:schemeClr val=\"lt1\"/></a:fontRef></p:style><p:txBody><a:bodyPr><a:normAutofit fontScale=\"85000\""
                + " lnSpcReduction=\"10000\"/></a:bodyPr><a:lstStyle><a:lvl2pPr marL=\"457200\" algn=\"r\">"
                + "<a:buFont typeface=\"Wingdings\"/><a:buChar char=\"q\"/><a:defRPr sz=\"2000\" b=\"1\" cap=\"small\">"
                + "<a:latin typeface=\"+mj-lt\"/><a:ea typeface=\"+mn-ea\"/></a:defRPr></a:lvl2pPr></a:lstStyle>"
                + "<a:p><a:pPr algn=\"just\" rtl=\"1\"><a:lnSpc><a:spcPct val=\"90000\"/></a:lnSpc>"
                + "<a:spcBef><a:spcPts val=\"600\"/></a:spcBef><a:buClr><a:srgbClr val=\"FF0000\"/></a:buClr>"
                + "<a:buSzPct val=\"80000\"/><a:buAutoNum type=\"arabicPeriod\" startAt=\"3\"/><a:tabLst>"
                + "<a:tab pos=\"914400\" algn=\"ctr\"/></a:tabLst><a:defRPr sz=\"1600\" i=\"1\"/></a:pPr>"
                + Decks.run("Plain", "") + Decks.run("Hollow", "u=\"sng\" strike=\"sngStrike\" spc=\"120\" kern=\"1200\"")
                + "<a:r><a:rPr lang=\"en-US\" baseline=\"30000\"><a:ln w=\"12700\"><a:solidFill><a:srgbClr"
                + " val=\"00FF00\"/></a:solidFill></a:ln><a:noFill/><a:effectLst><a:outerShdw blurRad=\"38100\""
                + " dist=\"38100\" dir=\"2700000\"><a:srgbClr val=\"000000\"/></a:outerShdw></a:effectLst>"
                + "<a:highlight><a:srgbClr val=\"FFFF00\"/></a:highlight><a:latin typeface=\"+mn-lt\"/>"
                + "<a:hlinkClick r:id=\"\"/></a:rPr><a:t>Effects</a:t></a:r><a:br><a:rPr lang=\"en-US\"/></a:br>"
                + "<a:fld id=\"{1}\" type=\"slidenum\"><a:rPr lang=\"en-US\"><a:gradFill><a:gsLst><a:gs pos=\"0\">"
                + "<a:schemeClr val=\"accent2\"/></a:gs></a:gsLst></a:gradFill></a:rPr><a:t>1</a:t></a:fld>"
                + "<a:endParaRPr lang=\"en-US\" sz=\"900\"/></a:p>"
                + "<a:p><a:pPr lvl=\"1\"/>" + Decks.run("Second level", "") + "<a:endParaRPr lang=\"en-US\"/></a:p>"
                + "<a:p><a:pPr lvl=\"1\"><a:buNone/></a:pPr>" + Decks.run("No bullet", "cap=\"all\"") + "</a:p>"
                + "<a:p><a:pPr lvl=\"6\"><a:buBlip><a:blip r:embed=\"rId99\"/></a:buBlip></a:pPr>"
                + Decks.run("Deep", "") + "</a:p><a:p><a:pPr lvl=\"3\"/><a:endParaRPr lang=\"en-US\"/></a:p>"
                + "</p:txBody></p:sp>";
        String box = Decks.textBox(41, 0, 2000000, 3000000, 1000000, "<a:bodyPr/>", "<a:p><a:pPr lvl=\"2\"/>"
                + Decks.run("Default text style", "") + "</a:p><a:p>" + Decks.run("Theme", "")
                + "<a:r><a:rPr lang=\"ja-JP\" altLang=\"en-US\"><a:ea typeface=\"+mj-ea\"/></a:rPr><a:t>日本語</a:t></a:r>"
                + "</a:p>");
        return Fixtures.edit(base).insertBefore("ppt/slides/slide1.xml", "</p:spTree>", styled + box).bytes();
    }
}
