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

import stirling.software.officeconvert.topdf.crypt.EncryptedWord;

record WordFile(DirectoryNode root, boolean defused) {

    private static final String[] STREAMS = {"WordDocument", "1Table", "0Table", "Data"};

    static WordFile read(DirectoryNode root, String password) throws IOException {
        Map<String, byte[]> changed = new LinkedHashMap<>();
        Map<String, byte[]> decrypted = Map.of();
        Blips blips = new Blips();
        for (String name : STREAMS) {
            byte[] bytes = decrypted.get(name);
            if (bytes == null) {
                if (!root.hasEntry(name)) {
                    continue;
                }
                try (InputStream in = root.createDocumentInputStream(name)) {
                    bytes = in.readAllBytes();
                } catch (RuntimeException e) {
                    continue;
                }
            }
            if (name.equals("WordDocument") && EncryptedWord.encrypted(bytes)) {
                decrypted = EncryptedWord.decrypt(root, bytes, password);
                changed.putAll(decrypted);
                bytes = decrypted.get(name);
            }
            if (blips.defuse(bytes) || decrypted.containsKey(name)) {
                changed.put(name, bytes);
            }
        }
        if (changed.isEmpty()) {
            return new WordFile(root, false);
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
        return new WordFile(fs.getRoot(), true);
    }
}
