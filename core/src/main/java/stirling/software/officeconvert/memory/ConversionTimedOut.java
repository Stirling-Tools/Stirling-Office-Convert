package stirling.software.officeconvert.memory;

import java.io.IOException;

/** A conversion that ran past its timeout and was stopped, whichever direction it converted: one catch arm for the
 * {@code TimedOut} types of {@code OfficeConvert}, {@code OfficeToPdf} and {@code PdfToPdfA}. */
public abstract class ConversionTimedOut extends IOException {

    protected ConversionTimedOut(String message) {
        super(message);
    }
}
