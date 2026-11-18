package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.extract.FontGuard;
import stirling.software.officeconvert.testing.RunawayFonts;

class RunawayFontTest {

    private static final Duration LIMIT = Duration.ofSeconds(30);

    private static final COSName TYPE1C = COSName.getPDFName("Type1C");

    @TempDir
    Path dir;

    @Test
    void anEbookCoverSkipsACffFontWhoseSubroutinesFanOut() throws Exception {
        try (PDDocument doc = RunawayFonts.simpleFontPdf(COSName.FONT_FILE3, RunawayFonts.cff(9, 100), TYPE1C, true)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTimeoutPreemptively(LIMIT, () -> PdfToEpub.convert(doc, out, PdfToDocx.Options.defaults()));
            assertTrue(out.size() > 0);
        }
    }

    @Test
    void theFileEntryPointSkipsTheSameFont() throws Exception {
        Path pdf = dir.resolve("runaway.pdf");
        try (PDDocument doc = RunawayFonts.simpleFontPdf(COSName.FONT_FILE3, RunawayFonts.cff(9, 100), TYPE1C, true)) {
            doc.save(pdf.toFile());
        }
        Path epub = dir.resolve("runaway.epub");
        Path azw3 = dir.resolve("runaway.azw3");
        assertTimeoutPreemptively(LIMIT, () -> {
            PdfToEpub.convert(pdf, epub, PdfToDocx.Options.defaults());
            PdfToAzw3.convert(pdf, azw3, PdfToDocx.Options.defaults());
        });
        assertTrue(Files.size(epub) > 0 && Files.size(azw3) > 0);
    }

    @Test
    void aFontWithoutWidthsIsNotMeasuredFromItsRunawayProgram() throws Exception {
        try (PDDocument doc = RunawayFonts.simpleFontPdf(COSName.FONT_FILE3, RunawayFonts.cff(9, 100), TYPE1C,
                false)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTimeoutPreemptively(LIMIT, () -> PdfToDocx.convert(doc, out, PdfToDocx.Options.defaults()));
            assertTrue(out.size() > 0);
        }
    }

    @Test
    void aCidKeyedCffFontWithRunawayLocalSubroutinesIsSkipped() throws Exception {
        try (PDDocument doc = RunawayFonts.cidFontPdf(RunawayFonts.cidCff(9, 100), false)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTimeoutPreemptively(LIMIT, () -> PdfToEpub.convert(doc, out, PdfToDocx.Options.defaults()));
            assertTrue(out.size() > 0);
        }
    }

    @Test
    void aType1FontWithRunawaySubroutinesIsSkipped() throws Exception {
        try (PDDocument doc = RunawayFonts.type1Pdf(RunawayFonts.type1(40), false)) {
            ByteArrayOutputStream docx = new ByteArrayOutputStream();
            ByteArrayOutputStream epub = new ByteArrayOutputStream();
            assertTimeoutPreemptively(LIMIT, () -> {
                PdfToDocx.convert(doc, docx, PdfToDocx.Options.defaults());
                PdfToEpub.convert(doc, epub, PdfToDocx.Options.defaults());
            });
            assertTrue(docx.size() > 0 && epub.size() > 0);
        }
    }

    @Test
    void onlyTheRunawayProgramIsLeftOut() throws Exception {
        try (PDDocument runaway = RunawayFonts.simpleFontPdf(COSName.FONT_FILE3, RunawayFonts.cff(9, 100), TYPE1C, true);
                PDDocument small = RunawayFonts.simpleFontPdf(COSName.FONT_FILE3, RunawayFonts.cff(3, 2), TYPE1C, true);
                PDDocument cid = RunawayFonts.cidFontPdf(RunawayFonts.cidCff(3, 2), true);
                PDDocument type1 = RunawayFonts.type1Pdf(RunawayFonts.type1(3), true);
                PDDocument cidRunaway = RunawayFonts.cidFontPdf(RunawayFonts.cidCff(9, 100), true);
                PDDocument type1Runaway = RunawayFonts.type1Pdf(RunawayFonts.type1(40), true)) {
            assertEquals(1, FontGuard.check(cidRunaway));
            assertEquals(1, FontGuard.check(type1Runaway));
            assertFalse(descriptor(type1Runaway).containsKey(COSName.FONT_FILE));
            assertEquals(1, FontGuard.check(runaway));
            assertFalse(descriptor(runaway).containsKey(COSName.FONT_FILE3));
            assertEquals(0, FontGuard.check(small));
            assertTrue(descriptor(small).containsKey(COSName.FONT_FILE3));
            assertEquals(0, FontGuard.check(cid));
            assertEquals(0, FontGuard.check(type1));
            assertTrue(descriptor(type1).containsKey(COSName.FONT_FILE));
            assertTrue(PdfToText.text(small, PdfToDocx.Options.defaults()).contains("A"));
        }
    }

    private static COSDictionary descriptor(PDDocument doc) {
        COSDictionary fonts = doc.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT);
        return fonts.getCOSDictionary(COSName.getPDFName("F1")).getCOSDictionary(COSName.FONT_DESC);
    }
}
