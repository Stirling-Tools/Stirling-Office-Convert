package stirling.software.officeconvert.extract;

import java.io.InterruptedIOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.contentstream.operator.Operator;

import stirling.software.officeconvert.memory.Admission;

public final class OperatorBudget {

    private static final Log LOG = LogFactory.getLog(OperatorBudget.class);

    public static final long MAX_OPERATORS = 5_000_000;

    public static final int MAX_FORMS = 50_000;

    private static final int CHECK_EVERY = 1024;

    private final String subject;
    private final String pass;
    private final String rest;
    private long operators;
    private int forms;
    private boolean spent;
    private boolean interrupted;

    public OperatorBudget(String pass) {
        this("A page", pass, "the rest of it is left out");
    }

    OperatorBudget(String subject, String pass, String rest) {
        this.subject = subject;
        this.pass = pass;
        this.rest = rest;
    }

    public boolean run(Operator operator) throws InterruptedIOException {
        if (interrupted || (++operators & (CHECK_EVERY - 1)) == 0 && Thread.currentThread().isInterrupted()) {
            interrupted = true;
            throw new InterruptedIOException("Conversion interrupted");
        }
        if ((operators & (CHECK_EVERY - 1)) == 0) {
            Admission.checkpoint();
        }
        return (operators <= MAX_OPERATORS || spend("operators")) && StreamGuard.inlineFits(operator);
    }

    public boolean form() {
        return ++forms <= MAX_FORMS && !spent || spend("forms");
    }

    public boolean spent() {
        return spent;
    }

    private boolean spend(String what) {
        if (!spent) {
            spent = true;
            LOG.warn(subject + " ran past " + (what.equals("forms") ? MAX_FORMS : MAX_OPERATORS) + " " + what
                    + " while " + pass + "; " + rest);
        }
        return false;
    }
}
