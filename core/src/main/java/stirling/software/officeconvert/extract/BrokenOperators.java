package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.io.InterruptedIOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.contentstream.operator.Operator;

final class BrokenOperators {

    private static final Log LOG = LogFactory.getLog(BrokenOperators.class);

    private BrokenOperators() {}

    static void brokenStream(IOException e) throws IOException {
        if (e instanceof InterruptedIOException) {
            throw e;
        }
        LOG.debug("Content stream broke off; keeping what came before", e);
    }

    static void skip(Operator operator, Exception e) throws InterruptedIOException {
        if (e instanceof InterruptedIOException stop) {
            throw stop;
        }
        if (e instanceof PageTooComplexException tooMuch) {
            throw tooMuch;
        }
        LOG.debug("Skipped a broken " + operator.getName() + " operator", e);
    }
}
