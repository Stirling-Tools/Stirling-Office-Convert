package stirling.software.officeconvert.extract;

import java.io.InterruptedIOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.contentstream.operator.Operator;

public final class OperatorBudget {

    private static final Log LOG = LogFactory.getLog(OperatorBudget.class);

    public static final long MAX_OPERATORS = 5_000_000;

    public static final int MAX_FORMS = 50_000;

    private static final int CHECK_EVERY = 1024;

    private final String pass;
    private long operators;
    private int forms;
    private boolean spent;
    private boolean interrupted;

    public OperatorBudget(String pass) {
        this.pass = pass;
    }

    public boolean run(Operator operator) throws InterruptedIOException {
        if (interrupted || (++operators & (CHECK_EVERY - 1)) == 0 && Thread.currentThread().isInterrupted()) {
            interrupted = true;
            throw new InterruptedIOException("Conversion interrupted");
        }
        return (operators <= MAX_OPERATORS || spend("operators")) && StreamGuard.inlineFits(operator);
    }

    public boolean form() {
        return ++forms <= MAX_FORMS && !spent || spend("forms");
    }

    private boolean spend(String what) {
        if (!spent) {
            spent = true;
            LOG.warn("A page ran past " + (what.equals("forms") ? MAX_FORMS : MAX_OPERATORS) + " " + what + " while "
                    + pass + "; the rest of it is left out");
        }
        return false;
    }
}
