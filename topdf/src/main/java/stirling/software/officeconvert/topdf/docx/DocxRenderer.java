package stirling.software.officeconvert.topdf.docx;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import stirling.software.officeconvert.topdf.RenderJob;

public final class DocxRenderer {

    private DocxRenderer() {}

    public static void render(Path source, RenderJob job) throws IOException {
        DocxPackage pkg = new DocxPackage(job.zip(), job);
        try {
            pkg.load();
            Ctx ctx = new Ctx(pkg);
            PageFlow flow = PageFlow.layout(ctx);
            new Painter(job).background(pkg.pageColor).paint(flow.pages);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
