package stirling.software.officeconvert.topdf.xls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;

import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.ComparisonOperator;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;

class RewriteLimitsTest {

    @Test
    void packageOwnershipEndsOnFailureWithoutClosingTheCaller() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        Parts parts = new Parts(output);
        assertThrows(IOException.class, () -> {
            try (parts) {
                parts.put("part.xml", "<part/>");
                throw new IOException("stop");
            }
        });
        assertFalse(closed.get());
        assertThrows(IOException.class, () -> parts.put("late.xml", "<late/>"));
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals("part.xml", zip.getNextEntry().getName());
            assertEquals("<part/>", new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void cancelledRewritesPreserveTheInterruptAndCallerStream() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class,
                    () -> XlsFile.rewrite(Path.of("not-opened.xls"), null, output));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, output.size());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rulesPastTheWorkbookLimitAreReported() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            HSSFSheet sheet = workbook.createSheet("Rules");
            sheet.createRow(0).createCell(0).setCellValue(10);
            var formatting = sheet.getSheetConditionalFormatting();
            var rule = formatting.createConditionalFormattingRule(ComparisonOperator.GT, "5");
            rule.createPatternFormatting().setFillBackgroundColor((short) 10);
            for (int i = 0; i < 10_001; i++) {
                formatting.addConditionalFormatting(new CellRangeAddress[] {new CellRangeAddress(0, 0, 0, 0)}, rule);
            }
            CondFormats converted = new CondFormats();
            converted.sheet(sheet);
            assertEquals(1, converted.skipped);
            AtomicBoolean closed = new AtomicBoolean();
            ByteArrayOutputStream output = new ByteArrayOutputStream() {
                @Override
                public void close() {
                    closed.set(true);
                }
            };
            ByteArrayOutputStream source = new ByteArrayOutputStream();
            workbook.write(source);
            XlsPackage.Outcome outcome;
            try (var file = new org.apache.poi.poifs.filesystem.POIFSFileSystem(
                    new ByteArrayInputStream(source.toByteArray()))) {
                outcome = XlsPackage.write(file.getRoot(), output);
            }
            assertTrue(outcome.lost());
            assertTrue(outcome.warnings().stream().anyMatch(s -> s.contains("conditional formatting") && s.contains("1")));
            assertFalse(closed.get());
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
                assertTrue(zip.getNextEntry() != null);
            }
        }
    }
}
