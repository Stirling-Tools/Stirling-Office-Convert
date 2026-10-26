package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public interface HtmlAssets {

    HtmlAssets NONE = reference -> null;

    long MAX_ASSET_BYTES = 64L << 20;

    default String base() {
        return "";
    }

    byte[] read(String reference) throws IOException;

    default boolean absoluteWeb() {
        return false;
    }

    static HtmlAssets folder(Path root, String page) throws IOException {
        Objects.requireNonNull(page, "page");
        Path real = root.toRealPath();
        return new HtmlAssets() {
            @Override
            public String base() {
                return page;
            }

            @Override
            public byte[] read(String reference) throws IOException {
                if (!packagePath(reference)) {
                    return null;
                }
                Path file = real.resolve(reference).normalize();
                if (!file.startsWith(real) || !Files.exists(file)) {
                    return null;
                }
                Path resolved = file.toRealPath();
                if (!resolved.startsWith(real) || !Files.isRegularFile(resolved)
                        || Files.size(resolved) > MAX_ASSET_BYTES) {
                    return null;
                }
                try (InputStream in = Files.newInputStream(resolved)) {
                    byte[] bytes = in.readNBytes((int) MAX_ASSET_BYTES + 1);
                    return bytes.length > MAX_ASSET_BYTES ? null : bytes;
                }
            }
        };
    }

    static boolean packagePath(String reference) {
        if (reference == null || reference.isEmpty() || reference.startsWith("/") || reference.indexOf(':') >= 0
                || reference.indexOf('\\') >= 0 || reference.indexOf('\0') >= 0) {
            return false;
        }
        for (String step : reference.split("/", -1)) {
            if (step.isEmpty() || step.equals(".") || step.equals("..")) {
                return false;
            }
        }
        return true;
    }
}
