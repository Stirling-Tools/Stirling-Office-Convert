package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

import stirling.software.officeconvert.topdf.RenderJob;

public final class DocxRenderer {

    private DocxRenderer() {}

    public static void render(Path source, RenderJob job) throws IOException {
        try {
            Laid laid = layout(job);
            new Painter(job).background(laid.color()).paint(laid.pages());
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private record Laid(List<PageBox> pages, Color color) {}

    // Only the laid-out pages outlive this, so the document model can go while the pages are painted
    private static Laid layout(RenderJob job) throws IOException {
        DocxPackage pkg = new DocxPackage(job.zip(), job);
        pkg.load();
        return new Laid(PageFlow.layout(new Ctx(pkg)).pages, pkg.pageColor);
    }
}
