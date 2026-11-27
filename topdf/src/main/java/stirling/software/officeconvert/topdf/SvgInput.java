package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Format;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.html.SvgPages;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;

final class SvgInput {

    static final long MAX_BYTES = 64L << 20;

    private SvgInput() {}

    static boolean is(Path source, Format requested) {
        if (requested != null) {
            return requested == Format.SVG;
        }
        Path name = source.getFileName();
        String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".svg") || n.endsWith(".svgz");
    }

    static long estimate(long bytes) {
        long v = 2 * Admission.BASE_BYTES + Math.max(0, bytes) * 48 + (48L << 20);
        return v < 0 ? Long.MAX_VALUE : v;
    }

    static Long estimate(Path source) throws IOException {
        return is(source, null) ? estimate(unpacked(source)) : null;
    }

    private static long unpacked(Path source) throws IOException {
        long size = Files.size(source);
        byte[] head;
        try (var in = Files.newInputStream(source)) {
            head = in.readNBytes(2);
        }
        boolean gzip = head.length == 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B;
        return gzip ? Math.min(MAX_BYTES, size * 32) : size;
    }

    static Result render(Path source, Format requested, OutputStream sink, Options options) throws IOException {
        if (!is(source, requested)) {
            return null;
        }
        long bytes = Files.size(source);
        if (bytes > MAX_BYTES) {
            throw new IOException("The SVG file is larger than " + (MAX_BYTES >> 20) + " MB");
        }
        List<String> warnings = new ArrayList<>();
        try (Admission.Ticket _ = Admission.jvm().enter(estimate(unpacked(source)));
                PdfOutput output = new PdfOutput(options.fontLibrary(), options.maxScratchBytes())) {
            SvgPages.draw(Files.readAllBytes(source), output, options.fontLibrary(), warnings);
            OfficeToPdf.stopIfInterrupted();
            output.save(sink);
        }
        return new Result(1, false, warnings);
    }
}
