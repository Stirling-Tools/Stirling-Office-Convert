package stirling.software.officeconvert.optimize;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.memory.Admission;

public final class PdfOptimizer {

    public static final long MAX_INPUT_BYTES = 1L << 30;

    public enum Effort {
        FAST, DEFAULT, MAX
    }

    public record Options(String password, Duration timeout, Effort effort, boolean keepPrivateData,
            boolean alwaysRewrite) {

        public Options {
            Objects.requireNonNull(timeout, "timeout");
            Objects.requireNonNull(effort, "effort");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("The timeout must be zero (none) or more, was " + timeout);
            }
        }

        public static Options defaults() {
            return new Options(null, Duration.ofMinutes(5), Effort.DEFAULT, false, false);
        }

        public Options password(String secret) {
            return new Options(secret, timeout, effort, keepPrivateData, alwaysRewrite);
        }

        public Options timeout(Duration limit) {
            return new Options(password, limit, effort, keepPrivateData, alwaysRewrite);
        }

        public Options effort(Effort level) {
            return new Options(password, timeout, level, keepPrivateData, alwaysRewrite);
        }

        public Options keepPrivateData(boolean keep) {
            return new Options(password, timeout, effort, keep, alwaysRewrite);
        }

        public Options alwaysRewrite(boolean always) {
            return new Options(password, timeout, effort, keepPrivateData, always);
        }

        @Override
        public String toString() {
            return "Options[password=" + (password == null ? "none" : "given") + ", timeout=" + timeout + ", effort="
                    + effort + ", keepPrivateData=" + keepPrivateData + ", alwaysRewrite=" + alwaysRewrite + "]";
        }
    }

    public record Result(long inputBytes, long outputBytes, boolean rewritten, int objects, int mergedObjects,
            int recodedStreams, List<String> warnings) {

        public Result {
            warnings = List.copyOf(warnings);
        }
    }

    public static final class TimedOut extends IOException {
        TimedOut(Duration limit) {
            super("The optimization took longer than " + limit.toMillis() + " ms and was stopped");
        }
    }

    private PdfOptimizer() {}

    public static Result optimize(Path in, Path out) throws IOException {
        return optimize(in, out, Options.defaults());
    }

    public static Result optimize(Path in, Path out, Options options) throws IOException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        PdfFiles.stopIfInterrupted();
        long size = Files.size(in);
        if (size > MAX_INPUT_BYTES) {
            throw new IOException("The PDF is larger than " + (MAX_INPUT_BYTES >> 20) + " MB");
        }
        Path part = Files.createTempFile(PdfFiles.outputFolder(out), ".optimize-", ".part");
        try {
            Result result = Deadline.run(options.timeout(), () -> {
                try (PDDocument doc = PdfFiles.open(in, options.password())) {
                    Admission.Ticket ticket = Admission.jvm().enter(estimate(size));
                    try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16)) {
                        return Rewrite.run(doc, os, options, size);
                    } finally {
                        ticket.close();
                    }
                }
            });
            PdfFiles.stopIfInterrupted();
            if (!options.alwaysRewrite() && result.outputBytes() >= size) {
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                result = new Result(size, size, false, result.objects(), result.mergedObjects(),
                        result.recodedStreams(), result.warnings());
            }
            move(part, out);
            return result;
        } catch (IOException e) {
            throw PdfFiles.interrupted(e);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    public static Result optimize(PDDocument document, OutputStream out, Options options) throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        PdfFiles.checkOpen(document);
        return Deadline.run(options.timeout(), () -> Rewrite.run(document, out, options, -1));
    }

    static long estimate(long bytes) {
        return Admission.BASE_BYTES * 8 + Math.min(bytes, 256L << 20) * 3 + Recoder.MAX_DECODED_BYTES * 3;
    }

    private static void move(Path part, Path out) throws IOException {
        try {
            Files.move(part, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, out, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
