package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.ValidationResult;
import org.verapdf.pdfa.validation.profiles.RuleId;

/** The PDF/A promise: the output passes the level it claims, or the conversion refuses or says what it could not fix. */
final class Conformance {

    private static final Pattern PART = Pattern.compile("pdfaid:part(?:=\"|>)\\s*(\\d)");

    private static final Pattern CONFORMANCE = Pattern.compile("pdfaid:conformance(?:=\"|>)\\s*([ABUabu])");

    static {
        VeraPdf.rules(PdfALevel.A2B);
    }

    private Conformance() {}

    /** Returns null when the outcome keeps the promise, otherwise what broke it. */
    static String defect(Path in, Path out, PdfALevel level, boolean fallback) throws Exception {
        String o = outcome(in, out, PdfToPdfA.Options.defaults().level(level).levelFallback(fallback));
        return o.startsWith("defect") ? o : null;
    }

    /** One of pass, reduced, refused, warned (veraPDF fails a rule the result lists as unverified) or defect. */
    static String outcome(Path in, Path out, PdfToPdfA.Options options) throws Exception {
        PdfALevel level = options.level();
        PdfToPdfA.Result r;
        try {
            r = PdfToPdfA.convert(in, out, options);
        } catch (IOException refused) {
            return Files.exists(out) ? "defect: refused but wrote " + out.getFileName() : "refused: " + refused;
        } catch (RuntimeException | StackOverflowError e) {
            return "defect: threw " + e;
        }
        PdfALevel got = r.achievedLevel();
        if (got != level && (!options.levelFallback() || got != level.untagged() || r.warnings().isEmpty())) {
            return "defect: claimed " + got.label() + " for " + level.label() + " without fallback and a warning";
        }
        String label = label(out);
        if (!(got.part() + got.conformance()).equals(label)) {
            return "defect: XMP says " + label + " but the result says " + got.label();
        }
        Map<String, Integer> failed = new TreeMap<>();
        if (!compliant(out, got, failed)) {
            return (r.unverified().isEmpty() ? "defect: " : "warned " + r.unverified() + ": ") + failed + " at "
                    + got.label();
        }
        return got == level ? "pass" : "reduced to " + got.label();
    }

    static boolean compliant(Path pdf, PdfALevel level, Map<String, Integer> failed) throws Exception {
        PDFAFlavour flavour = PDFAFlavour.fromString(level.part() + level.conformance().toLowerCase());
        try (InputStream in = Files.newInputStream(pdf);
                PDFAParser parser = Foundries.defaultInstance().createParser(in, flavour)) {
            ValidationResult v = Foundries.defaultInstance().createValidator(flavour, false).validate(parser);
            for (Map.Entry<RuleId, Integer> e : v.getFailedChecks().entrySet()) {
                failed.put(e.getKey().getClause() + "-" + e.getKey().getTestNumber(), e.getValue());
            }
            if (!v.isCompliant() && failed.isEmpty()) {
                failed.put("not compliant, no rule named", 1);
            }
            return v.isCompliant() && failed.isEmpty();
        }
    }

    static String label(Path pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            PDMetadata m = d.getDocumentCatalog().getMetadata();
            if (m == null) {
                return "none";
            }
            String xmp = new String(m.toByteArray(), StandardCharsets.UTF_8);
            Matcher p = PART.matcher(xmp);
            Matcher c = CONFORMANCE.matcher(xmp);
            return (p.find() ? p.group(1) : "?") + (c.find() ? c.group(1).toUpperCase() : "");
        }
    }
}
