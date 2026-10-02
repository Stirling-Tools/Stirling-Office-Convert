package stirling.software.officeconvert.pdfa;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.extract.PdfFiles;

final class ContentBudget {

    static final long BYTES_PER_TOKEN = 96;

    private ContentBudget() {}

    static long peakBytes(PDDocument doc) throws IOException {
        long most = 0;
        for (ContentGraph.Node n : ContentGraph.of(doc).nodes()) {
            PdfFiles.stopIfInterrupted();
            byte[] content = ContentTokens.checked(ContentTokens.bytes(n.streams()));
            most = Math.max(most, content.length + ContentTokens.tokens(content) * BYTES_PER_TOKEN);
        }
        return most;
    }
}
