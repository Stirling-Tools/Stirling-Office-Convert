package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.ValidationResult;
import org.verapdf.pdfa.validation.profiles.RuleId;

final class VeraPdf {

    static {
        VeraGreenfieldFoundryProvider.initialise();
    }

    private VeraPdf() {}

    static Map<String, Integer> failures(Path pdf, PdfALevel level) throws Exception {
        PDFAFlavour flavour = PDFAFlavour.fromString(level.part() + level.conformance().toLowerCase());
        try (InputStream in = Files.newInputStream(pdf);
                PDFAParser parser = Foundries.defaultInstance().createParser(in, flavour)) {
            PDFAValidator v = Foundries.defaultInstance().createValidator(flavour, false);
            ValidationResult r = v.validate(parser);
            Map<String, Integer> out = new TreeMap<>();
            for (Map.Entry<RuleId, Integer> e : r.getFailedChecks().entrySet()) {
                out.put(e.getKey().getClause() + "-" + e.getKey().getTestNumber(), e.getValue());
            }
            return out;
        }
    }

    static void assertCompliant(Path pdf, PdfALevel level) throws Exception {
        Map<String, Integer> f = failures(pdf, level);
        assertTrue(f.isEmpty(), level.label() + " rules failed: " + f);
    }
}
