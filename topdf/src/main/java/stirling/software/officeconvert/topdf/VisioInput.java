package stirling.software.officeconvert.topdf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.vsdx.VsdxPackage;

final class VisioInput {

    private VisioInput() {}

    static Long estimate(Path source) throws IOException {
        if (!VsdxPackage.is(source)) {
            return null;
        }
        return VsdxPackage.estimate(Files.size(source)) + 2 * Admission.BASE_BYTES;
    }

    static Result render(Path source, OutputStream sink, Options options, OfficeToPdf.Renderer renderer)
            throws IOException {
        if (!VsdxPackage.is(source)) {
            return null;
        }
        Path pptx = Files.createTempFile("office-to-pdf-", ".pptx");
        try {
            VsdxPackage.Outcome outcome;
            Admission.Ticket ticket = Admission.jvm().enter(VsdxPackage.estimate(Files.size(source)));
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(pptx), 1 << 16)) {
                outcome = VsdxPackage.write(source, os);
            } finally {
                ticket.close();
            }
            OfficeToPdf.stopIfInterrupted();
            Result r = OfficeToPdf.render(pptx, OfficeToPdf.Format.PPTX, sink, options,
                    (s, job) -> renderer.render(source, job), OfficeToPdf.REWRITTEN);
            List<String> warnings = new ArrayList<>(r.warnings());
            for (String w : outcome.warnings()) {
                String c = RenderJob.clean(w);
                if (c != null && !warnings.contains(c)) {
                    warnings.add(c);
                }
            }
            return new Result(r.pages(), r.truncated() || outcome.lost(), warnings, r.pageLimitReached());
        } finally {
            OfficeToPdf.deleteQuietly(pptx);
        }
    }
}
