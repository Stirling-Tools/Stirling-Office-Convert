package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.time.Duration;

final class Deadline {

    private static final stirling.software.officeconvert.memory.Deadline WORKER =
            new stirling.software.officeconvert.memory.Deadline("pdf-to-pdfa", "Conversion interrupted", "convert",
                    "converting", PdfToPdfA.TimedOut::new);

    @FunctionalInterface
    interface Work<T> extends stirling.software.officeconvert.memory.Deadline.Work<T> {}

    private Deadline() {}

    static <T> T run(Duration timeout, Work<T> work) throws IOException {
        return WORKER.run(timeout, work);
    }

    static <T> T run(Duration timeout, Duration grace, Work<T> work) throws IOException {
        return WORKER.run(timeout, grace, work);
    }
}
