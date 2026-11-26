package stirling.software.officeconvert;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import javax.imageio.ImageIO;

/** Points ImageIO's disk cache at a deleted folder, so any code that still uses it fails. */
public final class NoImageCache {

    private NoImageCache() {}

    public static <T> T run(Callable<T> work) throws Exception {
        boolean use = ImageIO.getUseCache();
        File dir = ImageIO.getCacheDirectory();
        Path gone = Files.createTempDirectory("imageio-cache-");
        ImageIO.setUseCache(true);
        ImageIO.setCacheDirectory(gone.toFile());
        Files.delete(gone);
        try {
            return work.call();
        } finally {
            ImageIO.setCacheDirectory(dir);
            ImageIO.setUseCache(use);
        }
    }
}
