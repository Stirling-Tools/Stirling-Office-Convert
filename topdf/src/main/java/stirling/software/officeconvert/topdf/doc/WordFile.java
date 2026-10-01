package stirling.software.officeconvert.topdf.doc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

record WordFile(DirectoryNode root, boolean defused, boolean encrypted) {

    private static final String[] STREAMS = {"WordDocument", "1Table", "0Table", "Data"};

    static WordFile read(DirectoryNode root) throws IOException {
        Map<String, byte[]> changed = new LinkedHashMap<>();
        Blips blips = new Blips();
        boolean encrypted = false;
        for (String name : STREAMS) {
            if (!root.hasEntry(name)) {
                continue;
            }
            byte[] bytes;
            try (InputStream in = root.createDocumentInputStream(name)) {
                bytes = in.readAllBytes();
            } catch (RuntimeException e) {
                continue;
            }
            if (name.equals("WordDocument") && bytes.length > 11) {
                encrypted = (bytes[11] & 0x01) != 0;
            }
            if (blips.defuse(bytes)) {
                changed.put(name, bytes);
            }
        }
        if (changed.isEmpty()) {
            return new WordFile(root, false, encrypted);
        }
        POIFSFileSystem fs = new POIFSFileSystem();
        for (Entry e : root) {
            if (!(e instanceof DocumentEntry doc)) {
                continue;
            }
            byte[] bytes = changed.get(e.getName());
            if (bytes == null) {
                try (InputStream in = root.createDocumentInputStream(doc)) {
                    bytes = in.readAllBytes();
                }
            }
            fs.createDocument(new ByteArrayInputStream(bytes), e.getName());
        }
        return new WordFile(fs.getRoot(), true, encrypted);
    }
}
