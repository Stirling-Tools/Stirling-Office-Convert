package stirling.software.officeconvert.topdf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.uof.UofPackage;

final class UofInput {

    private UofInput() {}

    static Long estimate(Path source) throws IOException {
        return UofPackage.sniff(source) == null ? null : UofPackage.estimate(source) + 2 * Admission.BASE_BYTES;
    }

    static Result render(Path source, OutputStream sink, Options options, OfficeToPdf.Renderer renderer)
            throws IOException {
        UofPackage.Kind kind = UofPackage.sniff(source);
        if (kind == null) {
            return null;
        }
        Path ooxml = Files.createTempFile("office-to-pdf-", "." + UofPackage.extension(kind));
        try {
            Admission.Ticket ticket = Admission.jvm().enter(UofPackage.estimate(source) + Admission.BASE_BYTES);
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(ooxml), 1 << 16)) {
                UofPackage.write(source, kind, os);
            } finally {
                ticket.close();
            }
            OfficeToPdf.stopIfInterrupted();
            OfficeToPdf.Format format = switch (kind) {
                case TEXT -> OfficeToPdf.Format.DOCX;
                case SPREADSHEET -> OfficeToPdf.Format.XLSX;
                case PRESENTATION -> OfficeToPdf.Format.PPTX;
            };
            return OfficeToPdf.render(ooxml, format, sink, options, (s, job) -> renderer.render(s, job),
                    OfficeToPdf.REWRITTEN);
        } finally {
            OfficeToPdf.deleteQuietly(ooxml);
        }
    }
}
