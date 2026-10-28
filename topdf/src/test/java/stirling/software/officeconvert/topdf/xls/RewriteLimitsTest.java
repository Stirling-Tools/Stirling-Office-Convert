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

    private static Path manyBofs(Path dir, int blocks) throws IOException {
        byte[] d = new byte[512 + blocks * 64];
        for (int at = 512; at + 64 <= d.length; at += 64) {
            d[at] = 0x09;
            d[at + 1] = 0x08;
            d[at + 2] = 16;
            d[at + 5] = 0x06;
            d[at + 6] = 5;
            d[at + 20] = (byte) 0xFC;
            d[at + 22] = 40;
        }
        return java.nio.file.Files.write(dir.resolve("bofs.bin"), d);
    }

    @Test
    void salvagingAFileOfRepeatedWorkbookStartsStaysWithinABudget(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Path file = manyBofs(dir, 1 << 17);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(20),
                () -> assertEquals(null, Repair.salvage(file)));
    }

    @Test
    void salvageStopsWhenTheConversionIsCancelled(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = manyBofs(dir, 64);
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> Repair.salvage(file));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void everyConditionalRuleThatCannotBeConvertedIsCountedAndTheRestKept() throws Exception {
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            HSSFSheet sheet = workbook.createSheet("Rules");
            sheet.createRow(0).createCell(0).setCellValue(10);
            var formatting = sheet.getSheetConditionalFormatting();
            for (int i = 0; i < 3; i++) {
                var rule = formatting.createConditionalFormattingRule(ComparisonOperator.GT, "5");
                rule.createPatternFormatting().setFillBackgroundColor((short) 10);
                formatting.addConditionalFormatting(new CellRangeAddress[] {new CellRangeAddress(i, i, 0, 0)},
                        rule);
            }
            workbook.write(source);
        }
        byte[] stream;
        try (var fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem(
                new ByteArrayInputStream(source.toByteArray()))) {
            stream = fs.createDocumentInputStream("Workbook").readAllBytes();
        }
        ByteArrayOutputStream edited = new ByteArrayOutputStream();
        int headers = 0;
        int rules = 0;
        for (int at = 0; at + 4 <= stream.length;) {
            int sid = stream[at] & 0xFF | (stream[at + 1] & 0xFF) << 8;
            int len = stream[at + 2] & 0xFF | (stream[at + 3] & 0xFF) << 8;
            byte[] rec = java.util.Arrays.copyOfRange(stream, at, at + 4 + len);
            if (sid == 0x01B0 && headers++ == 0) {
                rec = java.util.Arrays.copyOf(rec, rec.length - 8);
                rec[2] = (byte) (len - 8);
                rec[16] = 0;
                rec[17] = 0;
            } else if (sid == 0x01B1 && rules++ == 1) {
                rec[rec.length - 3] = 0x17;
            }
            edited.writeBytes(rec);
            at += 4 + len;
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        try (var fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem()) {
            fs.createDocument(new ByteArrayInputStream(edited.toByteArray()), "Workbook");
            fs.writeFilesystem(file);
        }
        try (HSSFWorkbook workbook = new HSSFWorkbook(new ByteArrayInputStream(file.toByteArray()))) {
            CondFormats converted = new CondFormats();
            String xml = converted.sheet(workbook.getSheetAt(0));
            assertEquals(2, converted.skipped);
            assertEquals(1, xml.split("<cfRule ", -1).length - 1, xml);
            assertTrue(xml.contains("sqref=\"A3\""), xml);
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
