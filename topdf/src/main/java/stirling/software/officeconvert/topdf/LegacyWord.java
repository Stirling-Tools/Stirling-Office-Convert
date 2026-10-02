package stirling.software.officeconvert.topdf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.doc.DocFile;
import stirling.software.officeconvert.topdf.doc.DocPackage;

final class LegacyWord {

    private LegacyWord() {}

    static Long estimate(Path source) throws IOException {
        if (!DocFile.is(source)) {
            return null;
        }
        return DocPackage.estimate(Files.size(source)) + 2 * Admission.BASE_BYTES;
    }

    static Result render(Path source, OutputStream sink, Options options, OfficeToPdf.Renderer renderer)
            throws IOException {
        if (ancient(source)) {
            throw new IOException("The file is a Word 2.0 or older document, which is not supported; save it as .docx");
        }
        if (!DocFile.is(source)) {
            return null;
        }
        Path docx = null;
        try {
            DocFile.Rewritten rewritten;
            docx = Files.createTempFile("office-to-pdf-", ".docx");
            Admission.Ticket ticket = Admission.jvm().enter(DocPackage.estimate(Files.size(source)));
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(docx), 1 << 16)) {
                rewritten = DocFile.rewrite(source, options.password(), os);
            } finally {
                ticket.close();
            }
            DocPackage.Outcome outcome = rewritten.outcome();
            List<String> upgradeWarnings = rewritten.upgradeWarnings();
            OfficeToPdf.stopIfInterrupted();
            Result r = OfficeToPdf.render(docx, OfficeToPdf.Format.DOCX, sink, options,
                    (s, job) -> renderer.render(source, job), OfficeToPdf.REWRITTEN);
            List<String> warnings = new ArrayList<>();
            for (String w : r.warnings()) {
                if (w.startsWith("Only the first ") || w.startsWith("Stopped at the page limit")) {
                    warnings.add(w);
                }
            }
            for (String w : upgradeWarnings) {
                if (!warnings.contains(w)) {
                    warnings.add(w);
                }
            }
            for (String w : outcome.warnings()) {
                String c = RenderJob.clean(w);
                if (c != null && !warnings.contains(c)) {
                    warnings.add(c);
                }
            }
            for (String w : r.warnings()) {
                if (!warnings.contains(w)) {
                    warnings.add(w);
                }
            }
            return new Result(r.pages(), r.truncated() || outcome.lost() || !upgradeWarnings.isEmpty(), warnings,
                    r.pageLimitReached());
        } finally {
            if (docx != null) {
                OfficeToPdf.deleteQuietly(docx);
            }
        }
    }

    private static boolean ancient(Path source) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(source)) {
            head = in.readNBytes(0x20);
        }
        if (head.length < 0x20 || (head[1] & 0xFF) != 0xA5
                || (head[0] & 0xFF) != 0xDB && (head[0] & 0xFF) != 0x9B) {
            return false;
        }
        ByteBuffer fib = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int nFib = fib.getShort(2) & 0xFFFF;
        long fcMin = fib.getInt(0x18) & 0xFFFFFFFFL;
        long fcMac = fib.getInt(0x1C) & 0xFFFFFFFFL;
        return nFib >= 1 && nFib < 101 && fcMin >= 0x20 && fcMin <= fcMac && fcMac <= Files.size(source);
    }
}
