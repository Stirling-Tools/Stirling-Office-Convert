package stirling.software.officeconvert.topdf.pptx;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import org.apache.poi.xslf.usermodel.XMLSlideShow;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.io.PoiPackages;

public final class PptxRenderer {

    private PptxRenderer() {}

    public static void render(Path source, RenderJob job) throws IOException {
        Objects.requireNonNull(job, "job");
        try (XMLSlideShow ppt = PoiPackages.slideShow(job.zip())) {
            new Deck(job, ppt).render();
        }
    }
}
