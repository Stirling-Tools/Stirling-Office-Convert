package stirling.software.officeconvert.topdf.io;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ScratchFile {

    @FunctionalInterface
    public interface Writer {
        void write(OutputStream out) throws IOException;
    }

    private ScratchFile() {}

    public static Path write(String suffix, Writer writer) throws IOException {
        Path file = Files.createTempFile("office-to-pdf-", suffix);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
            writer.write(out);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
        return file;
    }

    public static void delete(Path file) throws IOException {
        Files.deleteIfExists(file);
    }
}
