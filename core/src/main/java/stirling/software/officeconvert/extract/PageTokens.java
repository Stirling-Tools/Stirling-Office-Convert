package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;

// Several figures are drawn from one page, so its parsed content is kept for the next draw of the same page
public final class PageTokens {

    static final int MAX_TOKENS = 200_000;

    static final long MAX_BYTES = 8L * 1024 * 1024;

    static final String REPLAY = "OfficeConvertReplay";

    private static final byte[] REPLAY_CONTENT = (REPLAY + "\n").getBytes(StandardCharsets.US_ASCII);

    private COSBase page;
    private COSBase contents;
    private Object[] tokens;

    synchronized Object[] of(PDPage target) throws IOException {
        COSBase key = target.getCOSObject();
        COSBase stream = target.getCOSObject().getDictionaryObject(org.apache.pdfbox.cos.COSName.CONTENTS);
        if (tokens != null && key == page && stream == contents) {
            return tokens;
        }
        page = null;
        contents = null;
        tokens = null;
        List<Object> parsed = new ArrayList<>();
        long bytes = 0;
        boolean inline = false;
        PDFStreamParser parser = new PDFStreamParser(target);
        for (Object token = parser.parseNextToken(); token != null; token = parser.parseNextToken()) {
            bytes += ParsedStreams.weight(token);
            if (parsed.size() >= MAX_TOKENS || bytes > MAX_BYTES) {
                return null;
            }
            if ((parsed.size() & 4095) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            parsed.add(token);
            inline |= token instanceof Operator op && OperatorName.BEGIN_INLINE_IMAGE.equals(op.getName());
        }
        Object[] all = parsed.toArray();
        if (!inline) {
            page = key;
            contents = stream;
            tokens = all;
        }
        return all;
    }

    // The same page whose content is one marker operator, so the stock engine sets up state and then replays
    static final class Replayed extends PDPage {

        private final PDPage page;

        Replayed(PDPage page) {
            super(page.getCOSObject());
            this.page = page;
        }

        @Override
        public PDResources getResources() {
            return page.getResources();
        }

        @Override
        public boolean hasContents() {
            return true;
        }

        @Override
        public RandomAccessRead getContentsForStreamParsing() {
            return new RandomAccessReadBuffer(REPLAY_CONTENT);
        }
    }
}
