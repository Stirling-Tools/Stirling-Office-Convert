package stirling.software.officeconvert.topdf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.ooo1.Ooo1Package;

/** OpenOffice.org 1.x documents, rewritten as OpenDocument and converted as such. */
final class Ooo1Input {

    private Ooo1Input() {}

    static Long estimate(Path source) throws IOException {
        return Ooo1Package.sniff(source) == null ? null : 12 * Files.size(source) + 2 * Admission.BASE_BYTES;
    }

    static Result render(Path source, OfficeToPdf.Format requested, OutputStream sink, Options options,
            OfficeToPdf.Renderer renderer) throws IOException {
        Ooo1Package.Kind kind = Ooo1Package.sniff(source);
        if (kind == null) {
            return null;
        }
        Path odf = Files.createTempFile("office-to-pdf-", "." + Ooo1Package.extension(kind));
        try {
            Admission.Ticket ticket = Admission.jvm().enter(12 * Files.size(source) + Admission.BASE_BYTES);
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(odf), 1 << 16)) {
                Ooo1Package.write(source, kind, os);
            } finally {
                ticket.close();
            }
            OfficeToPdf.stopIfInterrupted();
            return OfficeToPdf.render(odf, requested, sink, options, renderer);
        } finally {
            OfficeToPdf.deleteQuietly(odf);
        }
    }
}
