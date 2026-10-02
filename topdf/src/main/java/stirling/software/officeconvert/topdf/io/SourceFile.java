package stirling.software.officeconvert.topdf.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SourceFile {

    private SourceFile() {}

    public static InputStream open(Path file) throws IOException {
        OfficeZip.checkNotInterrupted();
        return Files.newInputStream(file);
    }

    public static long size(Path file) throws IOException {
        return Files.size(file);
    }

    public static byte[] head(Path file, int n) throws IOException {
        try (InputStream in = open(file)) {
            return in.readNBytes(n);
        }
    }

    public static byte[] read(Path file, long max) throws IOException {
        long cap = Math.min(max, Integer.MAX_VALUE - 16);
        if (size(file) > cap) {
            throw new OfficeZip.Oversized("The document is too large: over " + Math.max(1, cap >> 20) + " MB");
        }
        try (InputStream in = open(file)) {
            byte[] data = in.readNBytes((int) cap + 1);
            if (data.length > cap) {
                throw new OfficeZip.Oversized("The document is too large: over " + Math.max(1, cap >> 20) + " MB");
            }
            return data;
        }
    }
}
