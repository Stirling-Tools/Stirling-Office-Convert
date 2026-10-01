package stirling.software.officeconvert.topdf.odf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.font.FontLibrary;

/** An OpenDocument text, spreadsheet or presentation rewritten in memory as the matching Office Open XML package
 * (WordprocessingML, SpreadsheetML or PresentationML) for the existing renderers. Formulas are never evaluated (cached
 * values only), macros and scripts are never run, and nothing outside the package is ever read. */
public final class OdfPackage {

    /** What the rewrite left out: warnings for the result, and whether content is missing. */
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

    /** The fonts measure text that must shrink to fit its shape, as PowerPoint stores the shrink and ODF does not. */
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
            return new Outcome(warnings, false);
        }
    }
}
