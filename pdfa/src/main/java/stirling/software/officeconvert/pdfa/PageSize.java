package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

final class PageSize {

    static final float MAX = 14_400;

    static final float MIN = 3;

    private static final COSName USER_UNIT = COSName.getPDFName("UserUnit");

    private static final List<COSName> BOXES = List.of(COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.BLEED_BOX,
            COSName.TRIM_BOX, COSName.ART_BOX);

    private static final List<COSName> OPTIONAL = List.of(COSName.CROP_BOX, COSName.BLEED_BOX, COSName.TRIM_BOX,
            COSName.ART_BOX);

    private static final Set<String> FITS = Set.of("XYZ", "FitH", "FitV", "FitR", "FitBH", "FitBV");

    private static final List<COSName> ANNOT_POINTS = List.of(COSName.RECT, COSName.QUADPOINTS,
            COSName.getPDFName("Vertices"), COSName.L, COSName.getPDFName("CL"), COSName.getPDFName("RD"));

    private PageSize() {}

    static void run(PDDocument doc, PdfALevel level, Report report) throws IOException {
        if (level.part() == 1) {
            return;
        }
        Map<COSDictionary, Float> scaled = new IdentityHashMap<>();
        for (PDPage page : doc.getPages()) {
            COSDictionary p = page.getCOSObject();
            for (COSName box : OPTIONAL) {
                COSBase value = box.equals(COSName.CROP_BOX)
                        ? org.apache.pdfbox.pdmodel.PDPageTree.getInheritableAttribute(p, box)
                        : p.getDictionaryObject(box);
                float[] r = box(value);
                if (r != null && (Math.abs(r[2] - r[0]) < MIN || Math.abs(r[3] - r[1]) < MIN
                        || Math.abs(r[2] - r[0]) > MAX || Math.abs(r[3] - r[1]) > MAX)) {
                    if (box.equals(COSName.CROP_BOX)) {
                        p.setItem(box, page.getMediaBox().getCOSArray());
                    } else {
                        p.removeItem(box);
                    }
                    report.warn("Replaced page boxes outside the size range " + level.label() + " allows");
                }
            }
            float[] media = box(page.getMediaBox().getCOSArray());
            if (media == null) {
                continue;
            }
            float width = Math.abs(media[2] - media[0]);
            float height = Math.abs(media[3] - media[1]);
            float largest = Math.max(width, height);
            float smallest = Math.min(width, height);
            if (largest <= MAX && smallest >= MIN) {
                continue;
            }
            float unit = largest > MAX ? (float) Math.ceil(largest / MAX * 1000) / 1000
                    : (float) Math.floor(smallest / MIN * 1000) / 1000;
            if (!(unit > 0) || largest / unit > MAX || smallest / unit < MIN) {
                throw new IOException(String.format(Locale.ROOT, "Page %d is %.4g by %.4g units, and %s needs every "
                        + "page side from %d to %d units, which no UserUnit can reach", index(doc, page), width,
                        height, level.label(), (int) MIN, (int) MAX));
            }
            scale(doc, page, unit);
            scaled.put(p, 1 / unit);
        }
        if (!scaled.isEmpty()) {
            destinations(doc, scaled);
            report.warn("Gave pages larger than " + (int) MAX + " or smaller than " + (int) MIN + " units a UserUnit, "
                    + "as " + level.label() + " allows, so they keep their size");
        }
    }

    private static int index(PDDocument doc, PDPage page) {
        return doc.getPages().indexOf(page) + 1;
    }

    private static void scale(PDDocument doc, PDPage page, float unit) throws IOException {
        COSDictionary p = page.getCOSObject();
        float s = 1 / unit;
        p.setItem(COSName.MEDIA_BOX, page.getMediaBox().getCOSArray());
        p.setItem(COSName.CROP_BOX, page.getCropBox().getCOSArray());
        for (COSName key : BOXES) {
            if (p.getDictionaryObject(key) instanceof COSArray a) {
                p.setItem(key, scaled(a, s));
            }
        }
        float old = p.getDictionaryObject(USER_UNIT) instanceof COSNumber n ? n.floatValue() : 1;
        p.setItem(USER_UNIT, new COSFloat(old * unit));
        COSArray contents = new COSArray();
        contents.add(stream(doc, String.format(Locale.ROOT, "q %s 0 0 %s 0 0 cm\n", num(s), num(s))));
        COSBase c = p.getDictionaryObject(COSName.CONTENTS);
        if (c instanceof COSStream one) {
            contents.add(one);
        } else if (c instanceof COSArray many) {
            for (int i = 0; i < many.size(); i++) {
                contents.add(many.get(i));
            }
        }
        contents.add(stream(doc, "\nQ\n"));
        p.setItem(COSName.CONTENTS, contents);
        COSArray annots = ContentGraph.array(p.getDictionaryObject(COSName.ANNOTS));
        for (int i = 0; annots != null && i < annots.size(); i++) {
            COSDictionary a = ContentGraph.dict(annots.getObject(i));
            if (a != null) {
                annotation(a, s);
            }
        }
    }

    private static void annotation(COSDictionary a, float s) {
        for (COSName key : ANNOT_POINTS) {
            if (a.getDictionaryObject(key) instanceof COSArray arr) {
                a.setItem(key, scaled(arr, s));
            }
        }
        if (a.getDictionaryObject(COSName.INKLIST) instanceof COSArray ink) {
            COSArray out = new COSArray();
            for (int i = 0; i < ink.size(); i++) {
                out.add(ink.getObject(i) instanceof COSArray path ? scaled(path, s) : ink.get(i));
            }
            a.setItem(COSName.INKLIST, out);
        }
        COSDictionary bs = ContentGraph.dict(a.getDictionaryObject(COSName.BS));
        if (bs != null && bs.getDictionaryObject(COSName.W) instanceof COSNumber w) {
            bs.setItem(COSName.W, new COSFloat(w.floatValue() * s));
        }
    }

    private static void destinations(PDDocument doc, Map<COSDictionary, Float> scaled) throws IOException {
        Set<COSArray> done = Collections.newSetFromMap(new IdentityHashMap<>());
        CosWalk.walk(doc, b -> {
            if (!(b instanceof COSArray a) || a.size() < 2 || !done.add(a)) {
                return;
            }
            COSDictionary target = ContentGraph.dict(a.get(0));
            Float s = target == null ? null : scaled.get(target);
            if (s == null || !(a.getObject(1) instanceof COSName fit) || !FITS.contains(fit.getName())) {
                return;
            }
            int last = "XYZ".equals(fit.getName()) ? Math.min(a.size(), 4) : a.size();
            for (int i = 2; i < last; i++) {
                if (a.getObject(i) instanceof COSNumber n) {
                    a.set(i, new COSFloat(n.floatValue() * s));
                }
            }
        });
    }

    private static COSArray scaled(COSArray a, float s) {
        COSArray out = new COSArray();
        for (int i = 0; i < a.size(); i++) {
            out.add(a.getObject(i) instanceof COSNumber n ? new COSFloat(n.floatValue() * s) : a.get(i));
        }
        return out;
    }

    private static float[] box(COSBase b) {
        COSArray a = ContentGraph.array(b);
        if (a == null || a.size() != 4) {
            return null;
        }
        float[] r = new float[4];
        for (int i = 0; i < 4; i++) {
            if (!(a.getObject(i) instanceof COSNumber n)) {
                return null;
            }
            r[i] = n.floatValue();
        }
        return r;
    }

    private static COSStream stream(PDDocument doc, String content) throws IOException {
        COSStream s = doc.getDocument().createCOSStream();
        try (OutputStream out = s.createOutputStream()) {
            out.write(content.getBytes(StandardCharsets.US_ASCII));
        }
        return s;
    }

    private static String num(float v) {
        return new java.math.BigDecimal(v).setScale(8, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
                .toPlainString();
    }
}
