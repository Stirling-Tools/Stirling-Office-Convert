package stirling.software.officeconvert.pdfa;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;

import stirling.software.officeconvert.extract.PdfFiles;

final class Transparency {

    static final long MAX_PIXELS = 40_000_000L;

    private final PDDocument doc;

    private final float dpi;

    private final Report report;

    private final TransparencyScan scan = new TransparencyScan();

    private final Set<COSDictionary> flattenedAnnots = Collections.newSetFromMap(new IdentityHashMap<>());

    private PDFRenderer renderer;

    private int counter;

    private Transparency(PDDocument doc, float dpi, Report report) {
        this.doc = doc;
        this.dpi = dpi;
        this.report = report;
    }

    static void run(PDDocument doc, float dpi, Report report) throws IOException {
        Transparency t = new Transparency(doc, dpi, report);
        int index = 0;
        for (PDPage page : doc.getPages()) {
            PdfFiles.stopIfInterrupted();
            try {
                t.page(page, index);
            } catch (IOException | RuntimeException e) {
                PdfFiles.stopIfInterrupted();
                report.warn("Could not flatten the transparency on page " + (index + 1) + ": " + e.getMessage());
            }
            index++;
        }
        t.neutralise();
    }

    private void page(PDPage page, int index) throws IOException {
        COSDictionary p = page.getCOSObject();
        COSDictionary res = page.getResources() == null ? null : page.getResources().getCOSObject();
        List<COSStream> streams = ContentGraph.contents(p);
        List<Object> tokens = streams.isEmpty() ? new ArrayList<>() : ContentTokens.parse(streams);
        List<TransparencyScan.Op> ops = scan.scan(tokens, res, new Matrix(), 0);
        List<COSDictionary> annots = transparentAnnotations(page, res);
        if (ops.isEmpty() && annots.isEmpty()) {
            return;
        }
        PDRectangle crop = page.getCropBox();
        Rectangle2D cropBox = new Rectangle2D.Double(crop.getLowerLeftX(), crop.getLowerLeftY(), crop.getWidth(),
                crop.getHeight());
        Rectangle2D region = null;
        for (TransparencyScan.Op op : ops) {
            Rectangle2D b = op.box() == null || op.text() ? cropBox : op.box();
            region = region == null ? (Rectangle2D) b.clone() : region.createUnion(b);
        }
        for (COSDictionary a : annots) {
            COSArray r = ContentGraph.array(a.getDictionaryObject(COSName.RECT));
            if (r != null && r.size() == 4) {
                PDRectangle rect = new PDRectangle(r);
                Rectangle2D b = new Rectangle2D.Double(rect.getLowerLeftX() - 1, rect.getLowerLeftY() - 1,
                        rect.getWidth() + 2, rect.getHeight() + 2);
                region = region == null ? b : region.createUnion(b);
            }
        }
        region = region == null ? null : region.createIntersection(cropBox);
        int insert = annots.isEmpty() ? insertionPoint(tokens, ops.get(ops.size() - 1).end() + 1) : tokens.size();
        PDImageXObject image = null;
        if (region != null && region.getWidth() >= 0.5 && region.getHeight() >= 0.5) {
            COSBase original = p.getItem(COSName.CONTENTS);
            COSStream prefix = doc.getDocument().createCOSStream();
            ContentTokens.write(prefix, tokens.subList(0, insert));
            p.setItem(COSName.CONTENTS, prefix);
            try {
                image = render(page, index, region, cropBox, annots);
            } finally {
                p.setItem(COSName.CONTENTS, original);
            }
        }
        List<Object> out = rewrite(tokens, ops, insert, image, region, res, page);
        COSStream target = streams.isEmpty() ? doc.getDocument().createCOSStream() : streams.get(0);
        ContentTokens.write(target, out);
        p.setItem(COSName.CONTENTS, target);
        for (COSDictionary a : annots) {
            flattenedAnnots.add(a);
        }
        report.flattened(index);
    }

    private List<COSDictionary> transparentAnnotations(PDPage page, COSDictionary pageRes) {
        List<COSDictionary> out = new ArrayList<>();
        COSArray annots = ContentGraph.array(page.getCOSObject().getDictionaryObject(COSName.ANNOTS));
        if (annots == null) {
            return out;
        }
        for (int i = 0; i < annots.size(); i++) {
            COSDictionary a = ContentGraph.dict(annots.getObject(i));
            if (a == null) {
                continue;
            }
            boolean t = a.getDictionaryObject(COSName.CA) instanceof COSNumber n && n.floatValue() < 0.999f;
            COSDictionary ap = ContentGraph.dict(a.getDictionaryObject(COSName.AP));
            if (!t && ap != null && ap.getDictionaryObject(COSName.N) instanceof COSStream n) {
                t = scan.xobjectTransparent(n, pageRes, 0);
            }
            if (t) {
                out.add(a);
            }
        }
        return out;
    }

    private PDImageXObject render(PDPage page, int index, Rectangle2D region, Rectangle2D crop,
            List<COSDictionary> annots) throws IOException {
        if (renderer == null) {
            renderer = new PDFRenderer(doc);
        }
        Set<COSDictionary> include = Collections.newSetFromMap(new IdentityHashMap<>());
        include.addAll(annots);
        renderer.setAnnotationsFilter(a -> include.contains(a.getCOSObject()));
        double scale = dpi / 72.0;
        double pixels = crop.getWidth() * scale * crop.getHeight() * scale;
        if (pixels > MAX_PIXELS) {
            scale *= Math.sqrt(MAX_PIXELS / pixels);
        }
        int rotation = page.getRotation();
        page.setRotation(0);
        BufferedImage full;
        try {
            full = renderer.renderImage(index, (float) scale, ImageType.RGB);
        } finally {
            page.setRotation(rotation);
        }
        int x0 = clamp((int) Math.floor((region.getMinX() - crop.getMinX()) * scale), full.getWidth());
        int x1 = clamp((int) Math.ceil((region.getMaxX() - crop.getMinX()) * scale), full.getWidth());
        int y0 = clamp((int) Math.floor((crop.getMaxY() - region.getMaxY()) * scale), full.getHeight());
        int y1 = clamp((int) Math.ceil((crop.getMaxY() - region.getMinY()) * scale), full.getHeight());
        if (x1 <= x0 || y1 <= y0) {
            return null;
        }
        BufferedImage part = full.getSubimage(x0, y0, x1 - x0, y1 - y0);
        region.setRect(crop.getMinX() + x0 / scale, crop.getMaxY() - y1 / scale, (x1 - x0) / scale, (y1 - y0) / scale);
        return photographic(part) ? JPEGFactory.createFromImage(doc, part, 0.92f)
                : LosslessFactory.createFromImage(doc, part);
    }

    static boolean photographic(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        long step = Math.max(1, (long) w * h / 65_536);
        java.util.HashSet<Integer> colours = new java.util.HashSet<>();
        for (long i = 0; i < (long) w * h; i += step) {
            colours.add(img.getRGB((int) (i % w), (int) (i / w)) & 0xFFFFFF);
            if (colours.size() > 1500) {
                return true;
            }
        }
        return false;
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    private static int insertionPoint(List<Object> tokens, int after) {
        int depth = 0;
        boolean text = false;
        for (int i = 0; i < tokens.size(); i++) {
            if (i >= after && depth <= 0 && !text) {
                return i;
            }
            if (tokens.get(i) instanceof Operator op) {
                switch (op.getName()) {
                    case "q" -> depth++;
                    case "Q" -> depth--;
                    case "BT" -> text = true;
                    case "ET" -> text = false;
                    default -> {
                    }
                }
            }
        }
        return tokens.size();
    }

    private List<Object> rewrite(List<Object> tokens, List<TransparencyScan.Op> ops, int insert, PDImageXObject image,
            Rectangle2D region, COSDictionary res, PDPage page) {
        java.util.Map<Integer, TransparencyScan.Op> byEnd = new java.util.HashMap<>();
        for (TransparencyScan.Op op : ops) {
            byEnd.put(op.end(), op);
        }
        Set<Integer> dropped = new java.util.HashSet<>();
        for (TransparencyScan.Op op : ops) {
            if (!op.text() && !op.path()) {
                for (int i = op.start(); i <= op.end(); i++) {
                    dropped.add(i);
                }
            }
        }
        List<Object> out = new ArrayList<>(tokens.size() + 16);
        Deque<Matrix> ctmStack = new ArrayDeque<>();
        Deque<Integer> trStack = new ArrayDeque<>();
        Matrix ctm = new Matrix();
        int tr = 0;
        int start = 0;
        for (int i = 0; i <= tokens.size(); i++) {
            if (i == insert && image != null) {
                out.addAll(draw(image, region, ctm, res, page));
            }
            if (i == tokens.size()) {
                break;
            }
            Object t = tokens.get(i);
            if (!(t instanceof Operator op)) {
                continue;
            }
            List<Object> operation = tokens.subList(start, i + 1);
            int begin = start;
            start = i + 1;
            String name = op.getName();
            switch (name) {
                case "q" -> {
                    ctmStack.push(ctm.clone());
                    trStack.push(tr);
                }
                case "Q" -> {
                    if (!ctmStack.isEmpty()) {
                        ctm = ctmStack.pop();
                        tr = trStack.pop();
                    }
                }
                case "cm" -> {
                    Matrix m = TransparencyScan.matrix(operation.subList(0, operation.size() - 1));
                    if (m != null) {
                        ctm = m.multiply(ctm);
                    }
                }
                case "Tr" -> {
                    if (operation.size() == 2 && operation.get(0) instanceof COSNumber n) {
                        tr = n.intValue();
                    }
                }
                default -> {
                }
            }
            if (dropped.contains(begin) || dropped.contains(i)) {
                continue;
            }
            TransparencyScan.Op op2 = byEnd.get(i);
            if (op2 != null && op2.path()) {
                out.addAll(operation.subList(0, operation.size() - 1));
                out.add(Operator.getOperator("n"));
            } else if (op2 != null && op2.text()) {
                out.add(COSInteger.get(3));
                out.add(Operator.getOperator("Tr"));
                out.addAll(operation);
                out.add(COSInteger.get(tr));
                out.add(Operator.getOperator("Tr"));
            } else {
                out.addAll(operation);
            }
        }
        return out;
    }

    private List<Object> draw(PDImageXObject image, Rectangle2D region, Matrix ctm, COSDictionary res, PDPage page) {
        COSDictionary resources = res;
        if (resources == null) {
            resources = new COSDictionary();
            page.getCOSObject().setItem(COSName.RESOURCES, resources);
        }
        COSDictionary x = ContentGraph.dict(resources.getDictionaryObject(COSName.XOBJECT));
        if (x == null) {
            x = new COSDictionary();
            resources.setItem(COSName.XOBJECT, x);
        }
        COSName name;
        do {
            name = COSName.getPDFName("PdfAFlat" + counter++);
        } while (x.containsKey(name));
        x.setItem(name, image.getCOSObject());
        Matrix place = new Matrix((float) region.getWidth(), 0, 0, (float) region.getHeight(), (float) region.getX(),
                (float) region.getY());
        Matrix inverse;
        try {
            inverse = new Matrix(ctm.createAffineTransform().createInverse());
        } catch (java.awt.geom.NoninvertibleTransformException e) {
            inverse = new Matrix();
        }
        Matrix m = place.multiply(inverse);
        List<Object> ops = new ArrayList<>();
        ops.add(COSName.getPDFName("Artifact"));
        ops.add(Operator.getOperator("BMC"));
        ops.add(Operator.getOperator("q"));
        for (float v : new float[] {m.getScaleX(), m.getShearY(), m.getShearX(), m.getScaleY(), m.getTranslateX(),
                m.getTranslateY()}) {
            ops.add(new COSFloat(v));
        }
        ops.add(Operator.getOperator("cm"));
        ops.add(name);
        ops.add(Operator.getOperator("Do"));
        ops.add(Operator.getOperator("Q"));
        ops.add(Operator.getOperator("EMC"));
        return ops;
    }

    private void neutralise() throws IOException {
        CosWalk.walk(doc, b -> {
            if (!(b instanceof COSDictionary d)) {
                return;
            }
            if (COSName.EXT_G_STATE.equals(d.getCOSName(COSName.TYPE)) || d.containsKey(COSName.CA_NS)
                    || d.containsKey(COSName.SMASK) && !(d instanceof COSStream)) {
                d.removeItem(COSName.SMASK);
                d.removeItem(COSName.CA);
                d.removeItem(COSName.CA_NS);
                COSBase bm = d.getDictionaryObject(COSName.BM);
                if (bm != null) {
                    d.setItem(COSName.BM, COSName.getPDFName("Normal"));
                }
            }
            if (d instanceof COSStream s && COSName.IMAGE.equals(s.getCOSName(COSName.SUBTYPE))) {
                s.removeItem(COSName.SMASK);
                s.removeItem(COSName.getPDFName("SMaskInData"));
            }
            COSDictionary group = ContentGraph.dict(d.getDictionaryObject(COSName.GROUP));
            if (group != null && COSName.TRANSPARENCY.equals(group.getCOSName(COSName.S))) {
                d.removeItem(COSName.GROUP);
            }
            if (COSName.ANNOT.equals(d.getCOSName(COSName.TYPE)) || d.containsKey(COSName.RECT) && d.containsKey(COSName.SUBTYPE)) {
                d.removeItem(COSName.CA);
            }
        });
        for (COSDictionary a : flattenedAnnots) {
            COSDictionary ap = new COSDictionary();
            COSStream empty = doc.getDocument().createCOSStream();
            empty.setItem(COSName.TYPE, COSName.XOBJECT);
            empty.setItem(COSName.SUBTYPE, COSName.FORM);
            COSArray r = ContentGraph.array(a.getDictionaryObject(COSName.RECT));
            PDRectangle rect = r == null || r.size() < 4 ? new PDRectangle(0, 0) : new PDRectangle(r);
            empty.setItem(COSName.BBOX, new PDRectangle(0, 0, rect.getWidth(), rect.getHeight()).getCOSArray());
            try (OutputStream o = empty.createOutputStream()) {
                o.write(new byte[0]);
            }
            ap.setItem(COSName.N, empty);
            a.setItem(COSName.AP, ap);
        }
    }
}
