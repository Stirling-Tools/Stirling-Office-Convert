package stirling.software.officeconvert.topdf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.doc.DocPackage;
import stirling.software.officeconvert.topdf.io.LegacyOffice;

// A Word 97-2003 document is rewritten as a DOCX package first, whatever its extension, and drawn from that
final class LegacyWord {

    private LegacyWord() {}

    private static POIFSFileSystem open(Path source) {
        try {
            if (!LegacyOffice.ole2(source)) {
                return null;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        POIFSFileSystem fs;
        try {
            fs = new POIFSFileSystem(source.toFile(), true);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (DocPackage.isDocument(fs.getRoot())) {
            return fs;
        }
        close(fs);
        return null;
    }

    static Long estimate(Path source) throws IOException {
        POIFSFileSystem fs = open(source);
        if (fs == null) {
            return null;
        }
        close(fs);
        return DocPackage.estimate(Files.size(source)) + 2 * Admission.BASE_BYTES;
    }

    static Result render(Path source, OutputStream sink, Options options, OfficeToPdf.Renderer renderer)
            throws IOException {
        POIFSFileSystem fs = open(source);
        if (fs == null) {
            return null;
        }
        Path docx = null;
        try {
            DocPackage.Outcome outcome;
            try (fs) {
                docx = Files.createTempFile("office-to-pdf-", ".docx");
                Admission.Ticket ticket = Admission.jvm().enter(DocPackage.estimate(Files.size(source)));
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(docx), 1 << 16)) {
                    outcome = DocPackage.write(fs.getRoot(), os);
                } finally {
                    ticket.close();
                }
            }
            OfficeToPdf.stopIfInterrupted();
            Result r = OfficeToPdf.render(docx, OfficeToPdf.Format.DOCX, sink, options,
                    (s, job) -> renderer.render(source, job), OfficeToPdf.REWRITTEN);
            List<String> warnings = new ArrayList<>();
            for (String w : r.warnings()) {
                if (w.startsWith("Only the first ") || w.startsWith("Stopped at the page limit")) {
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
            return new Result(r.pages(), r.truncated() || outcome.lost(), warnings, r.pageLimitReached());
        } finally {
            if (docx != null) {
                OfficeToPdf.deleteQuietly(docx);
            }
        }
    }

    private static void close(POIFSFileSystem fs) {
        try {
            fs.close();
        } catch (IOException | RuntimeException e) {
            return;
        }
    }
}
