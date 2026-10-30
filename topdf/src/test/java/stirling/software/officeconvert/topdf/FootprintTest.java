package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import stirling.software.officeconvert.topdf.io.OfficeZip;

class FootprintTest {

    @TempDir Path dir;

    private static byte[] part(int size, String head, String tail) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) ' ');
        byte[] h = head.getBytes(StandardCharsets.ISO_8859_1);
        byte[] t = tail.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(h, 0, b, 0, h.length);
        System.arraycopy(t, 0, b, b.length - t.length, t.length);
        return b;
    }

    private static Map<String, byte[]> workbook() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.ISO_8859_1));
        m.put("xl/workbook.xml", "<workbook/>".getBytes(StandardCharsets.ISO_8859_1));
        return m;
    }

    private long estimate(String name, Map<String, byte[]> parts) throws IOException {
        Path file = dir.resolve(name);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(file))) {
            zos.setMethod(ZipOutputStream.STORED);
            for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                byte[] data = e.getValue();
                CRC32 crc = new CRC32();
                crc.update(data);
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCompressedSize(data.length);
                entry.setCrc(crc.getValue());
                zos.putNextEntry(entry);
                zos.write(data);
                zos.closeEntry();
            }
        }
        try (OfficeZip zip = OfficeZip.open(file)) {
            return Footprint.estimate(zip, OfficeToPdf.Format.XLSX);
        }
    }

    @Test
    void streamedSheetDataCostsFarLessThanTreeParsedParts() throws IOException {
        int bulk = 10 << 20;

        Map<String, byte[]> streamed = workbook();
        streamed.put(
                "xl/worksheets/sheet1.xml",
                part(bulk, "<worksheet><sheetData>", "</sheetData></worksheet>"));

        Map<String, byte[]> styles = workbook();
        styles.put("xl/styles.xml", part(bulk, "<styleSheet>", "</styleSheet>"));

        Map<String, byte[]> tail = workbook();
        tail.put(
                "xl/worksheets/sheet1.xml",
                part(bulk, "<worksheet><sheetData/><extLst>", "</extLst></worksheet>"));

        long streamedEstimate = estimate("streamed.xlsx", streamed);
        long stylesEstimate = estimate("styles.xlsx", styles);
        long tailEstimate = estimate("tail.xlsx", tail);

        assertTrue(
                stylesEstimate > streamedEstimate * 5,
                "tree-parsed styles.xml should cost far more than streamed sheet data");
        assertTrue(
                tailEstimate > streamedEstimate * 5,
                "worksheet markup outside <sheetData> should be charged as tree-parsed");
    }

    @Test
    void theWorkbookPartIsChargedAsTreeParsed() throws IOException {
        int size = 10 << 20;
        Map<String, byte[]> small = workbookWithMainPart("<workbook/>".getBytes(StandardCharsets.ISO_8859_1));
        Map<String, byte[]> large = workbookWithMainPart(part(size, "<workbook><definedNames>", "</definedNames></workbook>"));
        long difference = estimate("large.xlsx", large) - estimate("small.xlsx", small);
        assertTrue(difference >= (long) size * 20, "the workbook part should be charged as tree-parsed, got " + difference);
    }

    private static Map<String, byte[]> workbookWithMainPart(byte[] workbook) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("[Content_Types].xml", ("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "</Types>").getBytes(StandardCharsets.ISO_8859_1));
        m.put("_rels/.rels", ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>").getBytes(StandardCharsets.ISO_8859_1));
        m.put("xl/workbook.xml", workbook);
        return m;
    }

    @ParameterizedTest
    @ValueSource(ints = {(8 << 20) - 5, 9 << 20, (10 << 20) - 6})
    void markupAfterLargeSheetDataIsChargedAsTreeParsed(int close) throws IOException {
        int size = 12 << 20;
        Map<String, byte[]> streamed = workbook();
        streamed.put(
                "xl/worksheets/sheet1.xml",
                part(size, "<worksheet><sheetData>", "</sheetData></worksheet>"));

        byte[] sheet = part(size, "<worksheet><sheetData>", "</mergeCells></worksheet>");
        byte[] mid = "</sheetData><mergeCells>".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(mid, 0, sheet, close, mid.length);
        Map<String, byte[]> trailing = workbook();
        trailing.put("xl/worksheets/sheet1.xml", sheet);

        long tail = size - close - "</sheetData>".length();
        long difference = estimate("trailing.xlsx", trailing) - estimate("streamed.xlsx", streamed);
        assertTrue(
                difference >= tail * 20,
                "markup after the sheet data should be charged as tree-parsed, got " + difference);
    }

    @Test
    void anElementThatOnlyStartsLikeSheetDataDoesNotHideTheTree() throws IOException {
        int size = 6 << 20;
        Map<String, byte[]> streamed = workbook();
        streamed.put("xl/worksheets/sheet1.xml", part(size, "<worksheet><sheetData>", "</sheetData></worksheet>"));
        Map<String, byte[]> fake = workbook();
        fake.put("xl/worksheets/sheet1.xml",
                part(size, "<worksheet><sheetDataX><mergeCells>", "</mergeCells></sheetDataX><!--</sheetData>--></worksheet>"));
        long difference = estimate("fake.xlsx", fake) - estimate("streamed.xlsx", streamed);
        assertTrue(difference >= (long) size * 20, "a worksheet without real sheet data is tree-parsed, got " + difference);
    }

    @Test
    void sheetDataMarkersInCommentsAreChargedForTheBytesTheReaderHolds() throws IOException {
        int size = 10 << 20;
        Map<String, byte[]> streamed = workbook();
        streamed.put("xl/worksheets/sheet1.xml", part(size, "<worksheet><sheetData>", "</sheetData></worksheet>"));
        Map<String, byte[]> commented = workbook();
        commented.put("xl/worksheets/sheet1.xml",
                part(size, "<worksheet><!--<sheetData>--></sheetData><mergeCells>", "</mergeCells><!--</sheetData>--></worksheet>"));
        long difference = estimate("commented.xlsx", commented) - estimate("streamed.xlsx", streamed);
        assertTrue(difference >= (long) size * 2, "the bytes held while cutting out the sheet data count, got " + difference);
    }
}
