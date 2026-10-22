package stirling.software.officeconvert.topdf;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;

import stirling.software.officeconvert.topdf.io.BoundedZip;

final class HtmlPackage implements HtmlAssets, Closeable {

    private final BoundedZip zip;
    private final String page;
    private final Map<String, ZipEntry> byName = new HashMap<>();
    private final Map<String, ZipEntry> byFolded = new HashMap<>();

    private HtmlPackage(BoundedZip zip, String page, List<? extends ZipEntry> entries) {
        this.zip = zip;
        this.page = page;
        for (ZipEntry e : entries) {
            if (!e.isDirectory()) {
                String name = BoundedZip.normalised(e.getName());
                byName.putIfAbsent(name, e);
                byFolded.putIfAbsent(name.toLowerCase(Locale.ROOT), e);
            }
        }
    }

    static HtmlPackage open(Path source) throws IOException {
        BoundedZip zip = BoundedZip.open(source);
        try {
            List<? extends ZipEntry> entries = zip.entries();
            String page = page(entries);
            if (page == null) {
                zip.close();
                return null;
            }
            return new HtmlPackage(zip, page, entries);
        } catch (IOException | RuntimeException e) {
            zip.close();
            throw e;
        }
    }

    static String page(List<? extends ZipEntry> entries) {
        String best = null;
        for (ZipEntry e : entries) {
            String name = BoundedZip.normalised(e.getName());
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("[content_types].xml") || lower.equals("mimetype") || lower.equals("meta-inf/manifest.xml")) {
                return null;
            }
            if (e.isDirectory() || !HtmlAssets.packagePath(name) || lower.startsWith("__macosx/")
                    || !(lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml"))) {
                continue;
            }
            if (best == null || PAGE_ORDER.compare(name, best) < 0) {
                best = name;
            }
        }
        return best;
    }

    private static final Comparator<String> PAGE_ORDER = Comparator.<String>comparingInt(n -> n.split("/").length)
            .thenComparing(n -> !index(n))
            .thenComparing(n -> n.toLowerCase(Locale.ROOT));

    private static boolean index(String name) {
        String file = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return file.startsWith("index.");
    }

    @Override
    public String base() {
        return page;
    }

    @Override
    public byte[] read(String reference) throws IOException {
        if (!HtmlAssets.packagePath(reference)) {
            return null;
        }
        ZipEntry e = byName.get(reference);
        if (e == null) {
            e = byFolded.get(reference.toLowerCase(Locale.ROOT));
        }
        if (e == null || e.getSize() > MAX_ASSET_BYTES) {
            return null;
        }
        return zip.read(e, MAX_ASSET_BYTES);
    }

    void extractPage(Path target) throws IOException {
        ZipEntry e = byName.get(page);
        try (OutputStream out = Files.newOutputStream(target)) {
            zip.copy(e, out, HtmlInput.MAX_BYTES);
        }
    }

    long pageSize() {
        long size = byName.get(page).getSize();
        return size < 0 ? HtmlInput.MAX_BYTES : size;
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
