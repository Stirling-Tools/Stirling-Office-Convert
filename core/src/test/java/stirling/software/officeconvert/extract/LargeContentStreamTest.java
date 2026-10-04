package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class LargeContentStreamTest {

    private static final int SIZE = 96 * 1024 * 1024;

    @Test
    void aHugeCommentStreamIsTokenisedWithoutCopyingItWhole() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            COSStream content = document.getDocument().createCOSStream();
            try (OutputStream out = content.createOutputStream(COSName.FLATE_DECODE)) {
                out.write("0 0 10 10 re f\n".getBytes(StandardCharsets.US_ASCII));
                byte[] line = new byte[1 << 16];
                Arrays.fill(line, (byte) 'x');
                line[0] = '%';
                line[line.length - 1] = '\n';
                for (int written = 0; written < SIZE; written += line.length) {
                    out.write(line);
                }
                out.write("0 0 5 5 re f\n".getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, content);

            com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long before = threads.getCurrentThreadAllocatedBytes();
            StreamRunner.Tokens tokens = ContentTokens.open(page);
            int operators = 0;
            for (Object token; (token = tokens.next()) != null; ) {
                if (token instanceof Operator) {
                    operators++;
                }
            }
            long allocated = threads.getCurrentThreadAllocatedBytes() - before;

            assertEquals(4, operators);
            assertTrue(allocated < SIZE, "allocated " + (allocated >> 20) + " MB for a " + (SIZE >> 20) + " MB stream");
        }
    }
}
