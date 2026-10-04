package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class WorksheetReaderTest {

    private static final String HEAD = "<?xml version=\"1.0\"?><worksheet " + RawXlsx.NS + "><sheetPr/>";

    private static final String TAIL = "<pageMargins left=\"0.5\" right=\"0.5\" top=\"1\" bottom=\"1\" header=\"0.3\""
            + " footer=\"0.3\"/><pageSetup orientation=\"landscape\"/></worksheet>";

    private static String rows(int n) {
        StringBuilder b = new StringBuilder();
        for (int r = 1; r <= n; r++) {
            b.append("<row r=\"").append(r).append("\"><c r=\"A").append(r).append("\"><v>").append(r)
                    .append("</v></c></row>");
        }
        return b.toString();
    }

    private static InputStream trickle(String s, int step) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, step));
            }
        };
    }

    private static String strip(String xml, int step) throws IOException {
        byte[] out = WorksheetReader.withoutData(trickle(xml, step));
        return out == null ? null : new String(out, StandardCharsets.UTF_8);
    }

    @Test
    void theRowsAreCutOutWhereverTheReadsBreak() throws IOException {
        String expected = HEAD + "<sheetData/>" + TAIL;
        String small = HEAD + "<sheetData>" + rows(300) + "</sheetData>" + TAIL;
        for (int step : new int[] {1, 3, 7, 8, 9, 10, 64}) {
            assertEquals(expected, strip(small, step), "step " + step);
        }
        String big = HEAD + "<sheetData>" + rows(20_000) + "</sheetData>" + TAIL;
        for (int step : new int[] {1000, 1 << 16, Integer.MAX_VALUE}) {
            assertEquals(expected, strip(big, step), "step " + step);
        }
    }

    @Test
    void prefixesAttributesAndEmptyDataAreKept() throws IOException {
        String prefixed = "<x:worksheet xmlns:x=\"" + RawXlsx.MAIN + "\"><x:sheetData>" + rows(3)
                + "</x:sheetData><x:pageSetup/></x:worksheet>";
        assertEquals("<x:worksheet xmlns:x=\"" + RawXlsx.MAIN + "\"><x:sheetData/><x:pageSetup/></x:worksheet>",
                strip(prefixed, 5));
        String empty = HEAD + "<sheetData/>" + TAIL;
        assertEquals(empty, strip(empty, 4));
        assertEquals(HEAD + TAIL, strip(HEAD + TAIL, 2));
        assertEquals(HEAD + "<sheetData\n/>" + TAIL, strip(HEAD + "<sheetData\n>" + rows(2) + "</sheetData >" + TAIL, 6));
        assertNull(strip(HEAD + "<sheetData>" + rows(2), 3));
        assertEquals(HEAD + "<sheetData", strip(HEAD + "<sheetData", 3));
    }

    @Test
    void theLastClosingTagEndsTheData() throws IOException {
        String cdata = "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t><![CDATA[</sheetData>]]></t></is></c></row>";
        assertEquals(HEAD + "<sheetData/>" + TAIL, strip(HEAD + "<sheetData>" + cdata + "</sheetData>" + TAIL, 11));
    }

    @Test
    void theSkeletonAndTheRowsBothComeFromTheStream() throws IOException {
        String xml = HEAD + "<sheetData>" + rows(200) + "</sheetData>" + TAIL;
        int[] opened = new int[1];
        WorksheetReader reader = new WorksheetReader(() -> {
            opened[0]++;
            return trickle(xml, 4096);
        });
        assertEquals(1, opened[0]);
        assertTrue(reader.skeleton().isSetPageSetup());
        assertEquals(0.5, reader.skeleton().getPageMargins().getLeft(), 1e-9);
        List<Integer> rows = new ArrayList<>();
        reader.rows(row -> rows.add(row.index()), null);
        assertEquals(2, opened[0]);
        assertEquals(200, rows.size());
        assertEquals(199, rows.get(199));
    }
}
