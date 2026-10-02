package stirling.software.officeconvert.topdf.grid;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class GridFormatsTest {

    @TempDir
    Path dir;

    private String convert(String name, byte[] data) throws IOException {
        Path in = Files.write(dir.resolve(name), data);
        Path pdf = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, pdf);
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(d);
        }
    }

    @Test
    void sylkCellsKeepTheirCachedValuesAndFormats() throws IOException {
        String slk = "ID;PWXL;N;E\r\nP;PGeneral\r\nP;P0.00%\r\nF;W1 1 20\r\nC;Y1;X1;K\"Item;;name\"\r\nC;X2;K\"Total\"\r\n"
                + "F;P1;FG0R;SDB;Y2;X2\r\nC;Y2;X1;K\"Ratio\"\r\nC;X2;K0.25;ER2C1*2\r\nC;Y3;X1;KTRUE\r\nE\r\n";
        String t = convert("table.slk", slk.getBytes(StandardCharsets.US_ASCII));
        assertTrue(t.contains("Item;name") && t.contains("Total") && t.contains("25.00%") && t.contains("TRUE"), t);
    }

    @Test
    void difTuplesBecomeRows() throws IOException {
        String dif = "TABLE\r\n0,1\r\n\"EXCEL\"\r\nVECTORS\r\n0,2\r\n\"\"\r\nTUPLES\r\n0,2\r\n\"\"\r\nDATA\r\n0,0\r\n\"\"\r\n"
                + "-1,0\r\nBOT\r\n1,0\r\n\"Name\"\r\n1,0\r\n\"Count\"\r\n-1,0\r\nBOT\r\n1,0\r\n\"Apples\"\r\n0,12\r\nV\r\n"
                + "-1,0\r\nEOD\r\n";
        String t = convert("data.dif", dif.getBytes(StandardCharsets.US_ASCII));
        assertTrue(t.contains("Name") && t.contains("Apples") && t.contains("12"), t);
    }

    @Test
    void dbaseRecordsFollowTheFieldNamesAndDeletedOnesAreLeftOut() throws IOException {
        String[][] rows = {{" ", "Widget    ", "  12.50", "20040305", "T"}, {"*", "Gone      ", "   1.00", "20040101", "F"},
            {" ", "Gadget    ", "   3.25", "19991231", "F"}};
        int recordLength = 1 + 10 + 7 + 8 + 1;
        ByteBuffer b = ByteBuffer.allocate(32 + 4 * 32 + 1 + rows.length * recordLength + 1)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 3).put((byte) 104).put((byte) 3).put((byte) 5).putInt(rows.length).putShort((short) (32 + 4 * 32 + 1))
                .putShort((short) recordLength);
        b.position(29);
        b.put((byte) 0x03);
        b.position(32);
        field(b, "NAME", 'C', 10, 0);
        field(b, "PRICE", 'N', 7, 2);
        field(b, "SOLD", 'D', 8, 0);
        field(b, "ACTIVE", 'L', 1, 0);
        b.put((byte) 0x0D);
        for (String[] r : rows) {
            b.put(String.join("", r).getBytes(StandardCharsets.US_ASCII));
        }
        b.put((byte) 0x1A);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(b.array(), 0, b.position());
        String t = convert("stock.dbf", out.toByteArray());
        assertTrue(t.contains("NAME") && t.contains("Widget") && t.contains("12.50") && t.contains("3/5/2004")
                && t.contains("Gadget"), t);
        assertFalse(t.contains("Gone"), t);
    }

    private static void field(ByteBuffer b, String name, char type, int length, int decimals) {
        byte[] n = new byte[11];
        System.arraycopy(name.getBytes(StandardCharsets.US_ASCII), 0, n, 0, name.length());
        b.put(n).put((byte) type).putInt(0).put((byte) length).put((byte) decimals);
        b.put(new byte[14]);
    }
}
