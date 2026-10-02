package stirling.software.officeconvert.topdf.odf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.font.FontLibrary;

public final class OdfPackage {

    public record Outcome(List<String> warnings, boolean lost) {
        public Outcome {
            warnings = List.copyOf(warnings);
        }
    }

    private OdfPackage() {}

    public static OdfDocument.Kind sniff(Path file) {
        return OdfDocument.sniff(file);
    }

    public static long estimate(Path file) {
        return OdfDocument.estimate(file);
    }

    public static Outcome write(Path source, OutputStream out) throws IOException {
        return write(source, out, null);
    }

    public static Outcome write(Path source, OutputStream out, FontLibrary fonts) throws IOException {
        try (OdfDocument doc = OdfDocument.open(source)) {
            PackageOut pkg = new PackageOut(out);
            List<String> warnings = new ArrayList<>();
            if (doc.hasMacros()) {
                warnings.add("Skipped active content: macros (not run)");
            }
            warnings.addAll(switch (doc.kind()) {
                case TEXT -> new OdtWriter(doc, pkg).write();
                case SPREADSHEET -> new OdsWriter(doc, pkg).write();
                case PRESENTATION -> new OdpWriter(doc, pkg, fonts).write();
            });
            if (doc.damaged()) {
                warnings.add("Left out a damaged part: the document's XML is not well-formed, so only its readable"
                        + " start was converted");
            }
            if (doc.work.spent()) {
                warnings.add(WorkBudget.WARNING);
            }
            return new Outcome(warnings, doc.lost());
        }
    }
}
