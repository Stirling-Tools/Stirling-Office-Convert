package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;

final class Limits {

    static final int MAX_KIDS = 8191;

    private static final int NODE = 1024;

    private final PdfALevel level;

    private final Report report;

    private Limits(PdfALevel level, Report report) {
        this.level = level;
        this.report = report;
    }

    static void run(PDDocument doc, PdfALevel level, Report report) throws IOException {
        Limits l = new Limits(level, report);
        if (level.part() == 1) {
            l.pageTree(doc);
        }
        CosWalk.walk(doc, l::visit);
    }

    static final int MAX_NAME_BYTES = 127;

    static int maxString(PdfALevel level) {
        return level.part() == 1 ? 65_535 : 32_767;
    }

    static int maxArray(PdfALevel level) {
        return level.part() == 1 ? 8191 : Integer.MAX_VALUE;
    }

    static COSName name(COSName n) {
        byte[] b = n.getName().getBytes(StandardCharsets.UTF_8);
        if (b.length <= MAX_NAME_BYTES) {
            return n;
        }
        String s = n.getName();
        int keep = s.length();
        while (s.substring(0, keep).getBytes(StandardCharsets.UTF_8).length > 100) {
            keep--;
        }
        if (keep > 0 && Character.isHighSurrogate(s.charAt(keep - 1))) {
            keep--;
        }
        return COSName.getPDFName(s.substring(0, keep) + "_" + Integer.toHexString(s.hashCode()));
    }

    static void names(PDDocument doc, Report report) throws IOException {
        boolean[] changed = {false};
        CosWalk.walk(doc, b -> {
            if (b instanceof COSDictionary d) {
                for (Map.Entry<COSName, COSBase> e : new ArrayList<>(d.entrySet())) {
                    COSName k = name(e.getKey());
                    COSBase v = e.getValue() instanceof COSName n ? name(n) : e.getValue();
                    if (k != e.getKey() || v != e.getValue()) {
                        d.removeItem(e.getKey());
                        d.setItem(k, v);
                        changed[0] = true;
                    }
                }
            } else if (b instanceof COSArray a) {
                for (int i = 0; i < a.size(); i++) {
                    if (a.get(i) instanceof COSName n && name(n) != n) {
                        a.set(i, name(n));
                        changed[0] = true;
                    }
                }
            }
        });
        if (changed[0]) {
            report.warn("Shortened names longer than " + MAX_NAME_BYTES + " bytes");
        }
    }

    static COSBase number(COSBase b, PdfALevel level) {
        if (b instanceof COSFloat f) {
            double v = f.floatValue();
            double max = level.part() == 1 ? 32_767 : 3.4e38;
            if (Math.abs(v) > max) {
                return new COSFloat((float) Math.copySign(max, v));
            }
            if (level.part() > 1 && v != 0 && Math.abs(v) < 1.2e-38) {
                return COSInteger.ZERO;
            }
        } else if (b instanceof COSInteger i && (i.longValue() > Integer.MAX_VALUE || i.longValue() < Integer.MIN_VALUE)) {
            return COSInteger.get(i.longValue() > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE);
        }
        return b;
    }

    private COSBase fix(COSBase b) {
        COSBase n = number(b, level);
        if (n != b) {
            report.warn("Clamped numbers beyond the range " + level.label() + " allows");
            return n;
        }
        int max = maxString(level);
        if (b instanceof COSString s && s.getBytes().length > max) {
            report.warn("Shortened a string longer than " + level.label() + " allows");
            return new COSString(java.util.Arrays.copyOf(s.getBytes(), max));
        }
        return b;
    }

    private void visit(COSBase b) {
        if (b instanceof COSDictionary d) {
            for (Map.Entry<COSName, COSBase> e : new ArrayList<>(d.entrySet())) {
                COSBase v = e.getValue();
                COSBase f = fix(v);
                if (f != v) {
                    d.setItem(e.getKey(), f);
                }
            }
        } else if (b instanceof COSArray a) {
            for (int i = 0; i < a.size(); i++) {
                COSBase v = a.get(i);
                COSBase f = fix(v);
                if (f != v) {
                    a.set(i, f);
                }
            }
        }
    }

    private void pageTree(PDDocument doc) {
        COSDictionary root = ContentGraph.dict(doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.PAGES));
        if (root == null) {
            return;
        }
        List<COSDictionary> pages = new ArrayList<>();
        boolean wide = false;
        List<COSDictionary> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            COSDictionary n = stack.remove(stack.size() - 1);
            COSArray kids = ContentGraph.array(n.getDictionaryObject(COSName.KIDS));
            if (kids != null && kids.size() > MAX_KIDS) {
                wide = true;
            }
            if (kids != null) {
                for (int i = kids.size() - 1; i >= 0; i--) {
                    COSDictionary k = ContentGraph.dict(kids.getObject(i));
                    if (k != null && stack.size() < 1_000_000) {
                        stack.add(k);
                    }
                }
            }
        }
        if (!wide) {
            return;
        }
        for (var page : doc.getPages()) {
            pages.add(page.getCOSObject());
        }
        for (COSDictionary p : pages) {
            inherit(p);
        }
        List<COSDictionary> level = pages;
        while (level.size() > NODE) {
            List<COSDictionary> up = new ArrayList<>();
            for (int i = 0; i < level.size(); i += NODE) {
                COSDictionary node = new COSDictionary();
                node.setItem(COSName.TYPE, COSName.PAGES);
                COSArray kids = new COSArray();
                int count = 0;
                for (COSDictionary k : level.subList(i, Math.min(level.size(), i + NODE))) {
                    kids.add(k);
                    k.setItem(COSName.PARENT, node);
                    count += COSName.PAGES.equals(k.getCOSName(COSName.TYPE)) ? k.getInt(COSName.COUNT) : 1;
                }
                node.setItem(COSName.KIDS, kids);
                node.setInt(COSName.COUNT, count);
                up.add(node);
            }
            level = up;
        }
        COSArray kids = new COSArray();
        int count = 0;
        for (COSDictionary k : level) {
            kids.add(k);
            k.setItem(COSName.PARENT, root);
            count += COSName.PAGES.equals(k.getCOSName(COSName.TYPE)) ? k.getInt(COSName.COUNT) : 1;
        }
        root.setItem(COSName.KIDS, kids);
        root.setInt(COSName.COUNT, count);
        report.warn("Split a page tree node with more than " + MAX_KIDS + " pages, which PDF/A-1 does not allow");
    }

    private static void inherit(COSDictionary page) {
        for (COSName key : new COSName[] {COSName.RESOURCES, COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.ROTATE}) {
            if (page.getDictionaryObject(key) != null) {
                continue;
            }
            COSDictionary p = ContentGraph.dict(page.getDictionaryObject(COSName.PARENT));
            for (int i = 0; p != null && i < 64; i++) {
                COSBase v = p.getDictionaryObject(key);
                if (v != null) {
                    page.setItem(key, v);
                    break;
                }
                p = ContentGraph.dict(p.getDictionaryObject(COSName.PARENT));
            }
        }
    }
}
