package stirling.software.officeconvert.optimize;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.FilterOutputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

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
        Path folder = PdfFiles.outputFolder(out);
        Path part = Files.createTempFile(folder, ".optimize-", ".part");
        Path lines = Files.createTempFile(folder, ".lines-", ".part");
        try {
            int[] shown = {0};
            String[] failed = {null};
            Rewrite.Run run = Deadline.run(options.timeout(), () -> {
                Admission.Ticket ticket = Admission.jvm().enter(estimate(size));
                try {
                    Rewrite.Run done = attempt(in, lines, part, folder, options, size, shown);
                    if (done.result().rewritten()) {
                        try {
                            check(part, options.password(), shown[0]);
                        } catch (InterruptedIOException e) {
                            throw e;
                        } catch (IOException e) {
                            failed[0] = e.getMessage();
                        }
                    }
                    return done;
                } finally {
                    ticket.close();
                }
            });
            Result result = run.result();
            PdfFiles.stopIfInterrupted();
            if (failed[0] != null) {
                if (shown[0] == 0) {
                    throw new IOException("The PDF could not be repaired: the rebuilt file failed its own check ("
                            + failed[0] + ")");
                }
                List<String> warnings = new ArrayList<>(result.warnings());
                warnings.removeIf(w -> w.startsWith("The PDF was damaged") || w.startsWith("The PDF's line endings"));
                warnings.add("The rewritten PDF failed its own check (" + failed[0] + "), so the input was copied"
                        + " unchanged");
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                move(part, out);
                return new Result(size, size, false, result.objects(), result.mergedObjects(), result.recodedStreams(),
                        warnings);
            }
            if (run.repaired() && result.rewritten()) {
                move(part, out);
                return result;
            }
            if (!result.rewritten() || !options.alwaysRewrite() && result.outputBytes() >= size) {
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
            Files.deleteIfExists(lines);
        }
    }

    private static Rewrite.Run attempt(Path in, Path lines, Path part, Path folder, Options options, long size,
            int[] shown) throws IOException {
        List<String> notes = new ArrayList<>();
        Path source = in;
        int restored = LineEndings.restore(in, lines);
        if (restored > 0) {
            source = lines;
            notes.add("The PDF's line endings had been converted to CRLF, which corrupts binary data; " + restored
                    + (restored == 1 ? " stream was restored to its declared length"
                            : " streams were restored to their declared lengths"));
        }
        PDDocument doc;
        Path rebuilt = null;
        Salvage.Rebuilt salvaged = null;
        try {
            doc = PdfFiles.open(source, options.password());
        } catch (InvalidPasswordException | InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            rebuilt = Files.createTempFile(folder, ".salvage-", ".part");
            boolean opened = false;
            try {
                salvaged = Salvage.rebuild(source, rebuilt);
                if (salvaged == null) {
                    String unsupported = Salvage.unsupported(source);
                    throw unsupported == null ? e : new IOException("The PDF could not be opened: " + unsupported, e);
                }
                try {
                    doc = PdfFiles.open(rebuilt, options.password());
                } catch (IOException again) {
                    throw e;
                }
                opened = true;
            } finally {
                if (!opened) {
                    Files.deleteIfExists(rebuilt);
                }
            }
        }
        try (PDDocument open = doc) {
            if (salvaged == null) {
                shown[0] = PageTree.census(open.getDocument(), false);
            }
            if (salvaged == null && !open.isEncrypted() && open.getNumberOfPages() == 0) {
                Path emptyRepair = Files.createTempFile(folder, ".salvage-", ".part");
                try {
                    var root = open.getDocumentCatalog().getCOSObject().getItem(COSName.PAGES);
                    Long offset = root instanceof COSObject ref
                            ? open.getDocument().getXrefTable().get(ref.getKey()) : null;
                    Salvage.Rebuilt recovered = offset == null ? null
                            : Salvage.rebuildEmpty(source, emptyRepair, offset);
                    if (recovered != null) {
                        try (PDDocument recoveredDoc = PdfFiles.open(emptyRepair, options.password());
                                OutputStream os = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16)) {
                            Rewrite.Run r = Rewrite.run(recoveredDoc, os, options, size);
                            return repaired(r, recovered, notes);
                        }
                    }
                } finally {
                    Files.deleteIfExists(emptyRepair);
                }
            }
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16)) {
                Rewrite.Run r = Rewrite.run(open, os, options, size);
                return repaired(r, salvaged, notes);
            }
        } finally {
            if (rebuilt != null) {
                Files.deleteIfExists(rebuilt);
            }
        }
    }

    static int check(Path pdf, String password, int pages) throws IOException {
        try (PDDocument doc = PdfFiles.open(pdf, password)) {
            int found = PageTree.census(doc.getDocument(), true);
            if (found < pages) {
                throw new IOException("it has " + found + " usable pages where the input has " + pages);
            }
            return found;
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static Rewrite.Run repaired(Rewrite.Run run, Salvage.Rebuilt salvaged, List<String> notes) {
        Result result = run.result();
        if (!result.rewritten() || salvaged == null && notes.isEmpty()) {
            return run;
        }
        List<String> warnings = new ArrayList<>(notes);
        warnings.addAll(result.warnings());
        if (salvaged != null) {
            warnings.add("The PDF was damaged past its own repair data; its page tree was rebuilt from the "
                    + salvaged.pages() + " pages found in the file" + (salvaged.unpacked() > 0 ? ", and "
                            + salvaged.unpacked() + " objects were read back out of its object streams" : ""));
        }
        return new Rewrite.Run(new Result(result.inputBytes(), result.outputBytes(), true, result.objects(),
                result.mergedObjects(), result.recodedStreams(), warnings), true);
    }

    public static Result optimize(PDDocument document, OutputStream out, Options options) throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        PdfFiles.stopIfInterrupted();
        PdfFiles.checkOpen(document);
        Path saved = Files.createTempFile("pdf-optimize-", ".pdf");
        try {
            return Deadline.run(options.timeout(), () -> {
                Admission.Ticket saving = Admission.jvm().enter(Admission.BASE_BYTES * 8);
                try {
                    save(document, saved, MAX_INPUT_BYTES);
                } finally {
                    saving.close();
                }
                long size = Files.size(saved);
                Admission.Ticket ticket = Admission.jvm().enter(estimate(size));
                try {
                    Result r;
                    try (PDDocument doc = PdfFiles.open(saved, options.password())) {
                        r = Rewrite.run(doc, out, options, -1).result();
                    }
                    if (r.rewritten()) {
                        return r;
                    }
                    Files.copy(saved, out);
                    return new Result(r.inputBytes(), size, false, r.objects(), r.mergedObjects(),
                            r.recodedStreams(), r.warnings());
                } finally {
                    ticket.close();
                }
            });
        } finally {
            Files.deleteIfExists(saved);
        }
    }

    static void save(PDDocument document, Path saved, long limit) throws IOException {
        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(saved), 1 << 16)) {
            document.save(new FilterOutputStream(file) {
                private long written;

                private void check(int count) throws IOException {
                    PdfFiles.stopIfInterrupted();
                    if (count > limit - written) {
                        throw new IOException("The PDF is larger than " + limit + " bytes");
                    }
                    written += count;
                }

                @Override
                public void write(int value) throws IOException {
                    check(1);
                    out.write(value);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    Objects.checkFromIndexSize(offset, length, bytes.length);
                    check(length);
                    out.write(bytes, offset, length);
                }
            });
        }
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
