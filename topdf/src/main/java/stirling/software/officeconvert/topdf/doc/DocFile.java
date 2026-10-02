package stirling.software.officeconvert.topdf.doc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import stirling.software.officeconvert.topdf.doc6.Word6Upgrade;
import stirling.software.officeconvert.topdf.io.LegacyOffice;

public final class DocFile {

    public record Rewritten(DocPackage.Outcome outcome, List<String> upgradeWarnings) {
        public Rewritten {
            upgradeWarnings = List.copyOf(upgradeWarnings);
        }
    }

    private DocFile() {}

    public static boolean is(Path source) {
        try {
            if (!LegacyOffice.ole2(source)) {
                return false;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        try (POIFSFileSystem fs = LegacyOffice.open(source)) {
            return DocPackage.isDocument(fs.getRoot());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static Rewritten rewrite(Path source, String password, OutputStream out) throws IOException {
        try (POIFSFileSystem fs = LegacyOffice.open(source)) {
            if (!word6(fs)) {
                return new Rewritten(DocPackage.write(fs.getRoot(), out, password), List.of());
            }
            Word6Upgrade.Upgraded up = Word6Upgrade.upgrade(fs.getRoot());
            try (POIFSFileSystem upgraded = up.fs()) {
                return new Rewritten(DocPackage.write(upgraded.getRoot(), out, password, up.anchors()), up.warnings());
            }
        }
    }

    private static boolean word6(POIFSFileSystem fs) {
        try (InputStream in = fs.getRoot().createDocumentInputStream("WordDocument")) {
            return Word6Upgrade.isWord6(in.readNBytes(4));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
