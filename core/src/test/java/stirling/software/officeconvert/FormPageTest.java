package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormPageTest {

    private static final int ROWS = 15;
    private static final int COLS = 3;
    private static final float TOP = 740;
    private static final float ROW = 24;
    private static final float LEFT = 40;
    private static final float CELL = 176;

    private static final Pattern BOX_TOP = Pattern.compile(
            "name=\"Text Box \\d+\"(?:(?!</wp:anchor>).)*?</wp:anchor>", Pattern.DOTALL);

    @TempDir Path dir;

    @Test
    void fillableFormKeepsItsCellsInPlace() throws Exception {
        Path pdf = form(true);
        String xml = document(convert(pdf));
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                assertTrue(xml.contains(label(r, c)), "label kept: " + label(r, c));
            }
        }
        assertFalse(xml.contains("<w:tbl>"), "cells are not rebuilt as a table");
        Matcher boxes = BOX_TOP.matcher(xml);
        int placed = 0;
        while (boxes.find()) {
            if (boxes.group().contains("Field ")) {
                placed++;
            }
        }
        assertEquals(ROWS * COLS, placed, "every label sits in its own positioned text box");
        assertTrue(count(xml, "name=\"Shape ") >= ROWS + COLS, "the grid is drawn as shapes");
        assertEquals(0, count(xml, "w:pageBreakBefore"), "one source page stays one page");
    }

    @Test
    void ruledPageWithoutFieldsIsNotAForm() throws Exception {
        String xml = document(convert(form(false)));
        assertTrue(count(xml, "name=\"Text Box ") < ROWS, "an ordinary ruled page keeps the normal layout");
    }

    @Test
    void formSlideKeepsEveryLabel() throws Exception {
        Path pdf = form(true);
        Path pptx = dir.resolve("form.pptx");
        PdfToPptx.convert(pdf, pptx, PdfToPptx.Options.defaults());
        String xml;
        try (ZipFile zip = new ZipFile(pptx.toFile())) {
            xml = new String(zip.getInputStream(zip.getEntry("ppt/slides/slide1.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
        for (int r = 0; r < ROWS; r++) {
            assertTrue(xml.contains(label(r, 0)), "label kept: " + label(r, 0));
        }
    }

    private static String label(int r, int c) {
        return "Field " + (char) ('A' + c) + (r + 1) + " name";
    }

    private Path convert(Path pdf) throws IOException {
        Path docx = dir.resolve(pdf.getFileName() + ".docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        return docx;
    }

    private static String document(Path docx) throws IOException {
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            return new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) {
            n++;
        }
        return n;
    }

    private Path form(boolean fields) throws IOException {
        Path pdf = dir.resolve(fields ? "form.pdf" : "ruled.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.5f);
                for (int r = 0; r < ROWS; r++) {
                    for (int c = 0; c < COLS; c++) {
                        cs.addRect(LEFT + c * CELL, TOP - (r + 1) * ROW, CELL, ROW);
                    }
                }
                cs.stroke();
                for (int r = 0; r < ROWS; r++) {
                    for (int c = 0; c < COLS; c++) {
                        cs.beginText();
                        cs.setFont(font, 7);
                        cs.newLineAtOffset(LEFT + c * CELL + 3, TOP - r * ROW - 8);
                        cs.showText(label(r, c));
                        cs.endText();
                    }
                }
            }
            if (fields) {
                PDAcroForm acro = new PDAcroForm(doc);
                doc.getDocumentCatalog().setAcroForm(acro);
                PDResources dr = new PDResources();
                dr.put(COSName.getPDFName("Helv"), font);
                acro.setDefaultResources(dr);
                acro.setDefaultAppearance("/Helv 0 Tf 0 g");
                for (int r = 0; r < ROWS; r++) {
                    PDTextField field = new PDTextField(acro);
                    field.setPartialName("f" + r);
                    PDAnnotationWidget widget = field.getWidgets().getFirst();
                    widget.setRectangle(new PDRectangle(LEFT + 2, TOP - (r + 1) * ROW + 2, CELL - 4, ROW - 12));
                    widget.setPage(page);
                    page.getAnnotations().add(widget);
                    acro.getFields().add(field);
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }
}
