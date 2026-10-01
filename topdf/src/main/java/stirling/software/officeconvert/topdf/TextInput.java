package stirling.software.officeconvert.topdf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Format;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.text.Converted;
import stirling.software.officeconvert.topdf.text.CsvPackage;
import stirling.software.officeconvert.topdf.text.TextFormats;
import stirling.software.officeconvert.topdf.text.TextPackage;

final class TextInput {

    private static final String PAST_LIMIT = "past the page limit";

    private TextInput() {}

    static TextFormats.Kind kind(Path source) throws IOException {
        TextFormats.Kind kind = TextFormats.kind(source);
        if (kind == null) {
            return null;
        }
        try (InputStream in = Files.newInputStream(source)) {
            byte[] head = in.readNBytes(4);
            boolean zip = head.length == 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
            boolean ole2 = head.length == 4 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF
                    && head[2] == 0x11 && (head[3] & 0xFF) == 0xE0;
            return zip || ole2 ? null : kind;
        }
    }

    static Long estimate(Path source) throws IOException {
        TextFormats.Kind kind = kind(source);
        if (kind == null) {
            return null;
        }
        long bytes = Files.size(source);
        return (kind == TextFormats.Kind.PLAIN ? TextPackage.estimate(bytes) : CsvPackage.estimate(bytes))
                + 2 * Admission.BASE_BYTES;
    }

    static Result render(Path source, OutputStream sink, Options options, OfficeToPdf.Renderer renderer)
            throws IOException {
        TextFormats.Kind kind = kind(source);
        if (kind == null) {
            return null;
        }
        boolean plain = kind == TextFormats.Kind.PLAIN;
        Path pkg = Files.createTempFile("office-to-pdf-", plain ? ".docx" : ".xlsx");
        try {
            Converted outcome;
            long bytes = Files.size(source);
            Admission.Ticket ticket = Admission.jvm().enter(plain ? TextPackage.estimate(bytes)
                    : CsvPackage.estimate(bytes));
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(pkg), 1 << 16)) {
                outcome = plain ? TextPackage.write(source, os, options.maxPages())
                        : CsvPackage.write(source, os, kind == TextFormats.Kind.CSV ? ',' : '\t', sheetName(source),
                                options.maxPages(), options.fontLibrary());
            } finally {
                ticket.close();
            }
            OfficeToPdf.stopIfInterrupted();
            Result r = OfficeToPdf.render(pkg, plain ? Format.DOCX : Format.XLSX, sink, options,
                    (s, job) -> renderer.render(source, job), OfficeToPdf.REWRITTEN);
            List<String> warnings = new ArrayList<>(r.warnings());
            for (String w : outcome.warnings()) {
                String c = RenderJob.clean(w);
                if (c != null && !warnings.contains(c) && !(r.pageLimitReached() && c.contains(PAST_LIMIT))) {
                    warnings.add(c);
                }
            }
            return new Result(r.pages(), r.truncated() || outcome.lost(), warnings, r.pageLimitReached());
        } finally {
            OfficeToPdf.deleteQuietly(pkg);
        }
    }

    private static String sheetName(Path source) {
        Path name = source.getFileName();
        String n = name == null ? "" : name.toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}
