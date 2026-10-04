package stirling.software.officeconvert.topdf.pdf;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PageMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.util.DateConverter;

import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.font.PdfFonts;

public final class PdfOutput implements Closeable {

    public static final String CREATOR = "Stirling Office Convert";

    public static final long MEMORY_BYTES = 64L << 20;

    static final long MIN_MEMORY_BYTES = 4L << 20;

    private record Target(int page, float x, float yTop) {}

    private record Link(PDPage page, PDRectangle rect, Target target, String destination) {}

    private record Entry(String title, int level, Target target, String destination) {}

    private final PDDocument document;

    private final PdfFonts fonts;

    private final List<PdfCanvas> open = new ArrayList<>();

    private final Map<String, Target> destinations = new HashMap<>();

    private final List<Link> links = new ArrayList<>();

    private final List<Entry> outline = new ArrayList<>();

    private final Map<Long, PDExtendedGraphicsState> alphas = new HashMap<>();

    private DocumentInfo info = DocumentInfo.EMPTY;

    private boolean finished;

    private boolean closed;

    public PdfOutput(FontLibrary library) {
        this(library, 0);
    }

    public PdfOutput(FontLibrary library, long maxScratchBytes) {
        Objects.requireNonNull(library, "library");
        if (maxScratchBytes < 0) {
            throw new IllegalArgumentException("maxScratchBytes must be 0 (no limit) or more, was " + maxScratchBytes);
        }
        long inMemory = memoryBytes(Runtime.getRuntime().maxMemory());
        MemoryUsageSetting memory = maxScratchBytes == 0 ? MemoryUsageSetting.setupMixed(inMemory)
                : MemoryUsageSetting.setupMixed(Math.min(inMemory, maxScratchBytes), maxScratchBytes);
        this.document = new PDDocument(memory.streamCache);
        this.fonts = new PdfFonts(document, library);
    }

    static long memoryBytes(long maxHeap) {
        return Math.max(MIN_MEMORY_BYTES, Math.min(MEMORY_BYTES, maxHeap / 32));
    }

    public PDDocument document() {
        return document;
    }

    public PdfFonts fonts() {
        return fonts;
    }

    public PdfCanvas newPage(PageSize size) throws IOException {
        Objects.requireNonNull(size, "size");
        return newPage(size.width(), size.height());
    }

    public PdfCanvas newPage(float width, float height) throws IOException {
        checkWritable();
        PageSize size = new PageSize(width, height);
        PDPage page = new PDPage(new PDRectangle(size.width(), size.height()));
        document.addPage(page);
        PdfCanvas canvas = new PdfCanvas(this, page, document.getNumberOfPages() - 1);
        open.add(canvas);
        return canvas;
    }

    /** A canvas of the given size whose drawing becomes a form XObject, to draw once and place many times; the
     * margin around it is kept too, so nothing drawn a little outside the size is cut off. */
    public PdfCanvas newForm(float width, float height, float margin) throws IOException {
        checkWritable();
        if (!(width > 0 && height > 0 && margin >= 0)) {
            throw new IllegalArgumentException("A form needs a positive size, was " + width + "x" + height);
        }
        return new PdfCanvas(this, width, height, margin);
    }

    public int pageCount() {
        return document.getNumberOfPages();
    }

    public PDPage page(int index) {
        return document.getPage(index);
    }

    public void destination(String name, int pageIndex, float x, float yTop) {
        Objects.requireNonNull(name, "name");
        checkWritable();
        destinations.putIfAbsent(name, new Target(pageIndex, x, yTop));
    }

    public boolean hasDestination(String name) {
        return destinations.containsKey(name);
    }

    public void outline(String title, int level, int pageIndex, float yTop) {
        Objects.requireNonNull(title, "title");
        checkWritable();
        outline.add(new Entry(title, Math.max(1, level), new Target(pageIndex, 0, yTop), null));
    }

    public void outline(String title, int level, String destination) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(destination, "destination");
        checkWritable();
        outline.add(new Entry(title, Math.max(1, level), null, destination));
    }

    public void info(DocumentInfo value) {
        info = Objects.requireNonNull(value, "info");
    }

    public DocumentInfo info() {
        return info;
    }

    public void finish() throws IOException {
        if (finished) {
            return;
        }
        checkWritable();
        for (PdfCanvas c : new ArrayList<>(open)) {
            c.close();
        }
        fonts.finish();
        for (Link l : links) {
            PDPageXYZDestination dest = resolve(l.target(), l.destination());
            if (dest == null) {
                continue;
            }
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(l.rect());
            link.setBorderStyle(noBorder());
            link.setDestination(dest);
            link.setPrinted(true);
            PdfCanvas.addAnnotation(l.page(), link);
        }
        writeOutline();
        writeInfo();
        finished = true;
    }

    public void save(OutputStream target) throws IOException {
        Objects.requireNonNull(target, "target");
        finish();
        OutputStream kept = new FilterOutputStream(target) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                target.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        };
        BufferedOutputStream buffered = new BufferedOutputStream(kept, 1 << 16);
        document.save(buffered);
        buffered.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            document.close();
        } finally {
            fonts.close();
        }
    }

    void closed(PdfCanvas canvas) {
        open.remove(canvas);
    }

    void link(PDPage page, PDRectangle rect, int pageIndex, float x, float yTop) {
        links.add(new Link(page, rect, new Target(pageIndex, x, yTop), null));
    }

    void link(PDPage page, PDRectangle rect, String destination) {
        links.add(new Link(page, rect, null, destination));
    }

    PDExtendedGraphicsState alpha(float fill, float stroke) {
        long key = (long) Float.floatToIntBits(fill) << 32 | (Float.floatToIntBits(stroke) & 0xFFFFFFFFL);
        return alphas.computeIfAbsent(key, k -> {
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(fill);
            gs.setStrokingAlphaConstant(stroke);
            return gs;
        });
    }

    static PDBorderStyleDictionary noBorder() {
        PDBorderStyleDictionary border = new PDBorderStyleDictionary();
        border.setWidth(0);
        return border;
    }

    private void checkWritable() {
        if (closed || finished) {
            throw new IllegalStateException(closed ? "The PDF was closed" : "The PDF was finished");
        }
    }

    private PDPageXYZDestination resolve(Target target, String name) {
        Target t = target != null ? target : destinations.get(name);
        if (t == null || t.page() < 0 || t.page() >= document.getNumberOfPages()) {
            return null;
        }
        PDPage page = document.getPage(t.page());
        PDPageXYZDestination dest = new PDPageXYZDestination();
        dest.setPage(page);
        dest.setLeft(Math.round(Math.max(0, t.x())));
        dest.setTop(Math.round(page.getMediaBox().getHeight() - Math.max(0, t.yTop())));
        dest.setZoom(0);
        return dest;
    }

    private void writeOutline() {
        if (outline.isEmpty()) {
            return;
        }
        PDDocumentOutline root = new PDDocumentOutline();
        Deque<PDOutlineItem> parents = new ArrayDeque<>();
        Deque<Integer> levels = new ArrayDeque<>();
        boolean any = false;
        for (Entry e : outline) {
            PDPageXYZDestination dest = resolve(e.target(), e.destination());
            if (dest == null) {
                continue;
            }
            PDOutlineItem item = new PDOutlineItem();
            item.setTitle(DocumentInfo.clean(e.title()) == null ? " " : DocumentInfo.clean(e.title()));
            item.setDestination(dest);
            while (!levels.isEmpty() && levels.peek() >= e.level()) {
                levels.pop();
                parents.pop();
            }
            if (parents.isEmpty()) {
                root.addLast(item);
            } else {
                parents.peek().addLast(item);
            }
            parents.push(item);
            levels.push(e.level());
            any = true;
        }
        if (any) {
            PDDocumentCatalog catalog = document.getDocumentCatalog();
            catalog.setDocumentOutline(root);
            catalog.setPageMode(PageMode.USE_OUTLINES);
        }
    }

    private void writeInfo() {
        PDDocumentInformation pdi = document.getDocumentInformation();
        pdi.setTitle(info.title());
        pdi.setAuthor(info.author());
        pdi.setSubject(info.subject());
        pdi.setKeywords(info.keywords());
        pdi.setCreator(CREATOR);
        pdi.setProducer(CREATOR);
        String now = DateConverter.toString(Calendar.getInstance());
        pdi.getCOSObject().setString(COSName.CREATION_DATE, now);
        pdi.getCOSObject().setString(COSName.MOD_DATE, now);
        if (info.language() != null) {
            document.getDocumentCatalog().setLanguage(info.language());
        }
    }
}
