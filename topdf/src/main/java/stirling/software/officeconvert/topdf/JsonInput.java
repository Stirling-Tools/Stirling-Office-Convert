package stirling.software.officeconvert.topdf;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import stirling.software.officeconvert.docx.DocxWriter;
import stirling.software.officeconvert.json.Json;
import stirling.software.officeconvert.json.JsonModel;
import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Format;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.text.Converted;

/** The document model as JSON (what PDF to JSON writes) is replayed into a Word package and drawn. */
final class JsonInput {

    static final long MAX_BYTES = 256L << 20;

    private JsonInput() {}

    static boolean is(Path source, Format requested) throws IOException {
        if (requested != null) {
            return requested == Format.JSON;
        }
        Path name = source.getFileName();
        return name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(".json");
    }

    static long estimate(long bytes) {
        // parsed JSON takes up to about 30 bytes of heap per byte of small objects
        long v = Admission.BASE_BYTES + Math.max(0, bytes) * 32;
        return v < 0 ? Long.MAX_VALUE : v;
    }

    static Long estimate(Path source) throws IOException {
        return is(source, null) ? estimate(Files.size(source)) + 2 * Admission.BASE_BYTES : null;
    }

    static Result render(Path source, Format requested, OutputStream sink, Options options,
            OfficeToPdf.Renderer renderer) throws IOException {
        if (!is(source, requested)) {
            return null;
        }
        long bytes = Files.size(source);
        if (bytes > MAX_BYTES) {
            throw new IOException("The JSON file is larger than " + (MAX_BYTES >> 20) + " MB");
        }
        Path pkg = Files.createTempFile("office-to-pdf-", ".docx");
        try {
            List<String> warnings = new ArrayList<>();
            Admission.Ticket ticket = Admission.jvm().enter(estimate(bytes));
            try (Reader in = new BufferedReader(new InputStreamReader(Files.newInputStream(source), StandardCharsets.UTF_8),
                    1 << 20);
                    OutputStream os = new BufferedOutputStream(Files.newOutputStream(pkg), 1 << 16);
                    DocxWriter writer = new DocxWriter(os)) {
                JsonModel.replay(Json.parse(in, bytes), writer, warnings);
            } finally {
                ticket.close();
            }
            OfficeToPdf.stopIfInterrupted();
            return Rewritten.render(pkg, source, new Converted(warnings, false), sink, options, renderer);
        } finally {
            OfficeToPdf.deleteQuietly(pkg);
        }
    }
}
