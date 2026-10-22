package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Outlines;

class XlsxOutlineTest {

    @TempDir
    Path dir;

    @Test
    void eachPrintedSheetIsBookmarkedAtItsFirstPage() throws IOException {
        Path in = dir.resolve("book.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(in)) {
            fill(wb.createSheet("Revenue"), 120);
            wb.createSheet("Empty");
            fill(wb.createSheet("Costs & Fees"), 3);
            fill(wb.createSheet("Hidden"), 3);
            wb.setSheetHidden(3, true);
            wb.write(out);
        }
        Path pdf = dir.resolve("book.pdf");
        OfficeToPdf.convert(in, pdf);
        int pages;
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            pages = d.getNumberOfPages();
        }
        assertTrue(pages > 2, "pages: " + pages);
        assertEquals(List.of("1 p1 Revenue", "1 p" + pages + " Costs & Fees"), Outlines.titles(pdf));
    }

    private static void fill(Sheet sheet, int rows) {
        for (int r = 0; r < rows; r++) {
            sheet.createRow(r).createCell(0).setCellValue("Row " + r);
        }
    }
}
