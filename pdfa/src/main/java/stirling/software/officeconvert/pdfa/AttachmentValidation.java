package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.VeraPDFFoundry;
import org.verapdf.pdfa.flavours.PDFAFlavour;

import stirling.software.officeconvert.extract.PdfFiles;

final class AttachmentValidation {

    private AttachmentValidation() {}

    static boolean compliant(byte[] bytes, String profile) throws IOException {
        PdfFiles.stopIfInterrupted();
        PDFAFlavour flavour = PDFAFlavour.fromString(profile);
        try (PDFAParser parser = Foundry.INSTANCE.createParser(new ByteArrayInputStream(bytes), flavour)) {
            return Foundry.INSTANCE.createValidator(flavour, false).validate(parser).isCompliant();
        } catch (Exception e) {
            PdfFiles.stopIfInterrupted();
            return false;
        }
    }

    private static final class Foundry {
        private static final VeraPDFFoundry INSTANCE = initialise();

        private static VeraPDFFoundry initialise() {
            VeraGreenfieldFoundryProvider.initialise();
            return Foundries.defaultInstance();
        }
    }
}
