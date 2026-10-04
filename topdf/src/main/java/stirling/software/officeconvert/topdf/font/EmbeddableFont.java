package stirling.software.officeconvert.topdf.font;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;

import org.apache.fontbox.ttf.TrueTypeFont;

public final class EmbeddableFont implements Closeable {

    private final FontProgram.Opened opened;

    private final String description;

    private EmbeddableFont(FontProgram.Opened opened, String description) {
        this.opened = opened;
        this.description = description;
    }

    public static EmbeddableFont open(FontFace face) throws IOException {
        Objects.requireNonNull(face, "face");
        FontEntry entry = face.program().entry();
        return new EmbeddableFont(FontProgram.open(entry), entry.describe());
    }

    public TrueTypeFont font() {
        return opened.font();
    }

    public String description() {
        return description;
    }

    @Override
    public void close() throws IOException {
        opened.close();
    }
}
