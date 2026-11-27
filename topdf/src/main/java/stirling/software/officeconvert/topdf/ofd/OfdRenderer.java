package stirling.software.officeconvert.topdf.ofd;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.io.Dom;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.SourceFile;
import stirling.software.officeconvert.topdf.pdf.Crop;
import stirling.software.officeconvert.topdf.pdf.DocumentInfo;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;

public final class OfdRenderer {

    public record Drawn(int pages, int totalPages, List<String> warnings) {}

    static final float POINTS_PER_MM = 72f / 25.4f;

    static final int MAX_PAGES = 100_000;

    static final int MAX_OUTLINES = 10_000;

    static final int MAX_SEAL_DOCUMENTS = 200;

    private final OfdPackage pkg;

    private final PdfOutput output;

    private final Set<String> notes;

    private final int sealDepth;

    private final FontLibrary library;

    private final Map<String, OfdText.Program> embeddedFonts = new HashMap<>();

    private final Map<String, Optional<DecodedPicture>> pictures = new HashMap<>();

    private final Map<byte[], Optional<DecodedPicture>> sealPictures = new IdentityHashMap<>();

    private int sealDocuments;

    private OfdRenderer(OfdPackage pkg, PdfOutput output, FontLibrary library, Set<String> notes, int sealDepth) {
        this.pkg = pkg;
        this.output = output;
        this.library = library;
        this.notes = notes;
        this.sealDepth = sealDepth;
    }

    public static boolean is(Path file) {
        return OfdPackage.is(file);
    }

    public static long memoryBound(Path file) throws IOException {
        return (48L << 20) + SourceFile.size(file) * 12;
    }

    public static Drawn draw(Path source, PdfOutput output, FontLibrary library, int maxPages) throws IOException {
        Set<String> notes = new LinkedHashSet<>();
        try (OfdPackage pkg = OfdPackage.open(source)) {
            OfdRenderer r = new OfdRenderer(pkg, output, library, notes, 0);
            int[] counts = r.documents(maxPages <= 0 ? MAX_PAGES : Math.min(maxPages, MAX_PAGES));
            return new Drawn(counts[0], counts[1], List.copyOf(notes));
        }
    }

    private record Doc(String part, Element root, OfdResources res, OfdResources.ColorSpace space,
            Element pageArea, Map<String, String> templates, List<String[]> pages) {}

    private int[] documents(int maxPages) throws IOException {
        Element ofd = pkg.xml(rootPart());
        int drawn = 0;
        int total = 0;
        for (Element body : Dom.kids(ofd, "DocBody")) {
            String rootLoc = OfdPackage.resolve("", Dom.text(Dom.kid(body, "DocRoot")));
            if (rootLoc == null || !pkg.exists(rootLoc)) {
                continue;
            }
            if (drawn == 0 && total == 0) {
                info(Dom.kid(body, "DocInfo"));
            }
            Doc doc = load(rootLoc);
            Map<String, List<Element>> annots = annotations(doc);
            Map<String, List<Stamp>> stamps = stamps(body);
            Map<String, Integer> index = new HashMap<>();
            int first = output.pageCount();
            for (String[] p : doc.pages()) {
                total++;
                if (drawn >= maxPages) {
                    continue;
                }
                OfficeZip.checkNotInterrupted();
                index.put(p[0], output.pageCount());
                page(doc, p[0], p[1], annots.getOrDefault(p[0], List.of()), stamps.getOrDefault(p[0], List.of()));
                drawn++;
            }
            outlines(doc, index, first);
        }
        if (total == 0) {
            throw new IOException("The OFD document has no pages");
        }
        return new int[] {drawn, total};
    }

    private String rootPart() {
        return pkg.name("OFD.xml");
    }

    private void info(Element info) {
        if (info == null) {
            return;
        }
        String title = Dom.text(Dom.kid(info, "Title")).strip();
        String author = Dom.text(Dom.kid(info, "Author")).strip();
        String subject = Dom.text(Dom.kid(info, "Subject")).strip();
        List<String> words = new ArrayList<>();
        for (Element k : Dom.kids(Dom.kid(info, "Keywords"), "Keyword")) {
            String w = Dom.text(k).strip();
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        output.info(new DocumentInfo(title.isEmpty() ? null : title, author.isEmpty() ? null : author,
                subject.isEmpty() ? null : subject, words.isEmpty() ? null : String.join(", ", words), "zh-CN"));
    }

    private Doc load(String part) throws IOException {
        Element root = pkg.xml(part);
        Element common = Dom.kid(root, "CommonData");
        OfdResources res = new OfdResources();
        for (Element r : Dom.kids(common, "PublicRes")) {
            loadRes(res, part, Dom.text(r));
        }
        for (Element r : Dom.kids(common, "DocumentRes")) {
            loadRes(res, part, Dom.text(r));
        }
        OfdResources.ColorSpace space = null;
        Element dcs = Dom.kid(common, "DefaultCS");
        if (dcs != null) {
            space = res.colorSpace(Dom.text(dcs));
        }
        Map<String, String> templates = new HashMap<>();
        for (Element t : Dom.kids(common, "TemplatePage")) {
            String loc = OfdPackage.resolve(part, t.getAttribute("BaseLoc"));
            if (loc != null) {
                templates.put(t.getAttribute("ID").trim(), loc + '\u0000' + t.getAttribute("ZOrder").trim());
            }
        }
        List<String[]> pages = new ArrayList<>();
        for (Element p : Dom.kids(Dom.kid(root, "Pages"), "Page")) {
            if (pages.size() >= MAX_PAGES) {
                break;
            }
            String loc = OfdPackage.resolve(part, p.getAttribute("BaseLoc"));
            if (loc != null) {
                pages.add(new String[] {p.getAttribute("ID").trim(), loc});
            }
        }
        return new Doc(part, root, res, space, Dom.kid(common, "PageArea"), templates, pages);
    }

    private void loadRes(OfdResources res, String from, String loc) throws InterruptedIOException {
        String part = OfdPackage.resolve(from, loc);
        try {
            res.load(pkg, part);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            notes.add("A resource list of the OFD document could not be read: " + part);
        }
    }

    private void page(Doc doc, String id, String part, List<Element> annots, List<Stamp> stamps) throws IOException {
        Element page = null;
        try {
            page = pkg.exists(part) ? pkg.xml(part) : null;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            notes.add("Page " + (output.pageCount() + 1) + " of the OFD document could not be read and is blank");
        }
        Rectangle2D.Double box = box(page, doc.pageArea());
        PdfCanvas canvas = output.newPage((float) (box.width * POINTS_PER_MM), (float) (box.height * POINTS_PER_MM));
        canvas.scale(POINTS_PER_MM, POINTS_PER_MM);
        canvas.clipRect(0, 0, (float) box.width, (float) box.height);
        canvas.translate((float) -box.x, (float) -box.y);
        if (page != null) {
            content(canvas, doc, page, part);
        }
        OfdPainter painter = painter(canvas, doc, doc.res());
        for (Element a : annots) {
            annotation(canvas, painter, a);
        }
        for (Stamp s : stamps) {
            stamp(canvas, s);
        }
        canvas.close();
    }

    private void content(PdfCanvas canvas, Doc doc, Element page, String part) throws IOException {
        OfdResources res = new OfdResources(doc.res());
        for (Element r : Dom.kids(page, "PageRes")) {
            loadRes(res, part, Dom.text(r));
        }
        List<Element[]> back = new ArrayList<>();
        List<Element[]> front = new ArrayList<>();
        for (Element t : Dom.kids(page, "Template")) {
            String ref = doc.templates().get(t.getAttribute("TemplateID").trim());
            if (ref == null) {
                continue;
            }
            int cut = ref.indexOf('\u0000');
            String loc = ref.substring(0, cut);
            String z = t.hasAttribute("ZOrder") ? t.getAttribute("ZOrder").trim() : ref.substring(cut + 1);
            try {
                Element tpl = pkg.xml(loc);
                (z.equalsIgnoreCase("Foreground") ? front : back).add(new Element[] {tpl});
                for (Element r : Dom.kids(tpl, "PageRes")) {
                    loadRes(res, loc, Dom.text(r));
                }
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                notes.add("A template page of the OFD document could not be read and was left out");
            }
        }
        OfdPainter painter = painter(canvas, doc, res);
        for (Element[] t : back) {
            layers(painter, res, t[0]);
        }
        layers(painter, res, page);
        for (Element[] t : front) {
            layers(painter, res, t[0]);
        }
    }

    private OfdPainter painter(PdfCanvas canvas, Doc doc, OfdResources res) {
        OfdImages images = new OfdImages(pkg, res, output.document(), notes, pictures);
        OfdText text = new OfdText(pkg, res, library, notes, embeddedFonts);
        return new OfdPainter(canvas, res, doc.space(), text, images);
    }

    private static void layers(OfdPainter painter, OfdResources res, Element page) throws IOException {
        Element content = Dom.kid(page, "Content");
        if (content == null) {
            return;
        }
        List<Element> layers = Dom.kids(content, "Layer");
        for (String type : new String[] {"Background", "Body", "Foreground"}) {
            for (Element layer : layers) {
                String t = layer.getAttribute("Type").trim();
                if (t.isEmpty()) {
                    t = "Body";
                }
                if (t.equalsIgnoreCase(type)) {
                    painter.block(layer, res.drawParam(layer.getAttribute("DrawParam")), 0);
                }
            }
        }
    }

    private static Rectangle2D.Double box(Element page, Element pageArea) {
        Element area = page == null ? null : Dom.kid(page, "Area");
        for (Element a : new Element[] {area, pageArea}) {
            Rectangle2D.Double b = a == null ? null : OfdNumbers.box(Dom.text(Dom.kid(a, "PhysicalBox")));
            if (b != null && b.width > 1 && b.height > 1 && b.width < 20_000 && b.height < 20_000) {
                return b;
            }
        }
        return new Rectangle2D.Double(0, 0, 210, 297);
    }

    private Map<String, List<Element>> annotations(Doc doc) throws IOException {
        Map<String, List<Element>> out = new HashMap<>();
        Element loc = Dom.kid(doc.root(), "Annotations");
        String part = loc == null ? null : OfdPackage.resolve(doc.part(), Dom.text(loc));
        if (part == null || !pkg.exists(part)) {
            return out;
        }
        try {
            Element list = pkg.xml(part);
            for (Element p : Dom.kids(list, "Page")) {
                String file = OfdPackage.resolve(part, Dom.text(Dom.kid(p, "FileLoc")));
                if (file == null || !pkg.exists(file)) {
                    continue;
                }
                List<Element> mine = out.computeIfAbsent(p.getAttribute("PageID").trim(), k -> new ArrayList<>());
                for (Element a : Dom.kids(pkg.xml(file), "Annot")) {
                    if (!"false".equalsIgnoreCase(a.getAttribute("Visible").trim())
                            && !"true".equalsIgnoreCase(a.getAttribute("NoView").trim())) {
                        mine.add(a);
                    }
                }
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            notes.add("The annotations of the OFD document could not be read and were left out");
        }
        return out;
    }

    private static void annotation(PdfCanvas canvas, OfdPainter painter, Element annot) throws IOException {
        for (Element app : Dom.kids(annot, "Appearance")) {
            Rectangle2D.Double b = OfdNumbers.box(app.getAttribute("Boundary"));
            canvas.save();
            try {
                if (b != null) {
                    canvas.translate((float) b.x, (float) b.y);
                }
                painter.block(app, null, 0);
            } finally {
                canvas.restore();
            }
        }
    }

    private record Stamp(Rectangle2D.Double box, byte[] picture) {}

    private Map<String, List<Stamp>> stamps(Element body) throws IOException {
        Map<String, List<Stamp>> out = new HashMap<>();
        Element loc = Dom.kid(body, "Signatures");
        String part = loc == null ? null : OfdPackage.resolve("", Dom.text(loc));
        if (part == null || !pkg.exists(part)) {
            return out;
        }
        try {
            Element list = pkg.xml(part);
            for (Element s : Dom.kids(list, "Signature")) {
                String file = OfdPackage.resolve(part, s.getAttribute("BaseLoc"));
                if (file == null || !pkg.exists(file)) {
                    continue;
                }
                Element sig = pkg.xml(file);
                Element info = Dom.kid(sig, "SignedInfo");
                byte[] picture = sealPicture(file, sig, info);
                for (Element a : Dom.kids(info, "StampAnnot")) {
                    Rectangle2D.Double b = OfdNumbers.box(a.getAttribute("Boundary"));
                    if (b == null) {
                        continue;
                    }
                    if (picture == null) {
                        notes.add("The appearance of a seal in the OFD document could not be read and was left out");
                        continue;
                    }
                    out.computeIfAbsent(a.getAttribute("PageRef").trim(), k -> new ArrayList<>())
                            .add(new Stamp(b, picture));
                }
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            notes.add("The seals of the OFD document could not be read and were left out");
        }
        return out;
    }

    private byte[] sealPicture(String file, Element sig, Element info) throws IOException {
        List<String> parts = new ArrayList<>();
        Element value = Dom.kid(sig, "SignedValue");
        if (value != null) {
            parts.add(OfdPackage.resolve(file, Dom.text(value)));
        }
        Element seal = Dom.kid(info, "Seal");
        if (seal != null) {
            parts.add(OfdPackage.resolve(file, Dom.text(Dom.kid(seal, "BaseLoc"))));
        }
        for (String p : parts) {
            if (p != null && pkg.exists(p)) {
                byte[] found = OfdSeals.picture(pkg.read(p, OfdPackage.MAX_MEDIA_BYTES));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void stamp(PdfCanvas canvas, Stamp s) throws IOException {
        Rectangle2D.Double b = s.box();
        if (OfdSeals.zip(s.picture())) {
            if (sealDepth == 0 && sealDocuments++ < MAX_SEAL_DOCUMENTS) {
                sealDocument(canvas, s.picture(), b);
            }
            return;
        }
        Optional<DecodedPicture> known = sealPictures.get(s.picture());
        if (known == null) {
            known = Optional.ofNullable(new OfdImages(pkg, new OfdResources(), output.document(), notes, pictures)
                    .decode(s.picture(), "seal"));
            sealPictures.put(s.picture(), known);
        }
        DecodedPicture picture = known.orElse(null);
        if (picture != null) {
            canvas.image(picture, (float) b.x, (float) b.y, (float) b.width, (float) b.height, Crop.NONE, 0, false,
                    false, 1);
        }
    }

    private void sealDocument(PdfCanvas canvas, byte[] data, Rectangle2D.Double b) throws IOException {
        Path tmp = Files.createTempFile("office-to-pdf-seal-", ".ofd");
        try {
            Files.write(tmp, data);
            try (OfdPackage seal = OfdPackage.open(tmp)) {
                OfdRenderer inner = new OfdRenderer(seal, output, library, notes, sealDepth + 1);
                Element ofd = seal.xml(seal.name("OFD.xml"));
                Element body = Dom.kid(ofd, "DocBody");
                String rootLoc = body == null ? null : OfdPackage.resolve("", Dom.text(Dom.kid(body, "DocRoot")));
                if (rootLoc == null || !seal.exists(rootLoc)) {
                    return;
                }
                Doc doc = inner.load(rootLoc);
                if (doc.pages().isEmpty()) {
                    return;
                }
                String part = doc.pages().get(0)[1];
                Element page = seal.xml(part);
                Rectangle2D.Double pb = box(page, doc.pageArea());
                canvas.save();
                try {
                    canvas.translate((float) b.x, (float) b.y);
                    canvas.scale((float) (b.width / pb.width), (float) (b.height / pb.height));
                    canvas.translate((float) -pb.x, (float) -pb.y);
                    inner.content(canvas, doc, page, part);
                } finally {
                    canvas.restore();
                }
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            notes.add("The appearance of a seal in the OFD document could not be read and was left out");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void outlines(Doc doc, Map<String, Integer> index, int first) {
        Element outlines = Dom.kid(doc.root(), "Outlines");
        if (outlines == null) {
            return;
        }
        int[] budget = {MAX_OUTLINES};
        for (Element e : Dom.kids(outlines, "OutlineElem")) {
            outline(e, 1, index, first, budget);
        }
    }

    private void outline(Element e, int level, Map<String, Integer> index, int first, int[] budget) {
        if (budget[0]-- <= 0 || level > 32) {
            return;
        }
        String title = e.getAttribute("Title").strip();
        Element dest = null;
        for (Element a : Dom.kids(Dom.kid(e, "Actions"), "Action")) {
            Element go = Dom.kid(a, "Goto");
            if (go != null && Dom.kid(go, "Dest") != null) {
                dest = Dom.kid(go, "Dest");
                break;
            }
        }
        Integer page = dest == null ? null : index.get(dest.getAttribute("PageID").trim());
        if (!title.isEmpty()) {
            int target = page != null ? page : first;
            if (target < output.pageCount()) {
                double top = dest == null ? 0 : OfdNumbers.number(dest.getAttribute("Top"), 0);
                output.outline(title, level, target, (float) (Math.max(0, top) * POINTS_PER_MM));
            }
        }
        for (Element k : Dom.kids(e, "OutlineElem")) {
            outline(k, level + 1, index, first, budget);
        }
    }
}
