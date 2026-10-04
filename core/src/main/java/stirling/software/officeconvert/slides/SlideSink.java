package stirling.software.officeconvert.slides;

import java.io.IOException;

import stirling.software.officeconvert.build.PlacedContent;
import stirling.software.officeconvert.model.Scripts;

public interface SlideSink extends PlacedContent.MediaStore, AutoCloseable {

    void begin(float width, float height, int firstPage, int slideCount) throws IOException;

    default void scripts(Scripts.Profile profile) {}

    void slide(Slide slide) throws IOException;

    void finish(String title, String author) throws IOException;

    @Override
    void close() throws IOException;
}
