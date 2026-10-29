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
}
