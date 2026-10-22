package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.OfficeToPdf.Format;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.text.Converted;

/** Draws a Word package we wrote from another format, adding what writing it lost to the warnings. */
final class Rewritten {

    private Rewritten() {}

    static Result render(Path pkg, Path source, Converted outcome, OutputStream sink, Options options,
            OfficeToPdf.Renderer renderer) throws IOException {
        Result r = OfficeToPdf.render(pkg, Format.DOCX, sink, options, (s, job) -> renderer.render(source, job),
                OfficeToPdf.REWRITTEN);
        List<String> warnings = new ArrayList<>(r.warnings());
        for (String w : outcome.warnings()) {
            String c = RenderJob.clean(w);
            if (c != null && !warnings.contains(c)) {
                warnings.add(c);
            }
        }
        return new Result(r.pages(), r.truncated() || outcome.lost(), warnings, r.pageLimitReached());
    }
}
