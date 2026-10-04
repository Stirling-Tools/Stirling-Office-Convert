package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;

final class LongArrays {

    static final int MAX = 8191;

    private static final int LEAF = 4000;

    private static final COSName INK_LIST = COSName.getPDFName("InkList");

    private LongArrays() {}

    static boolean fix(COSDictionary d) throws IOException {
        if (d.getDictionaryObject(COSName.CONTENTS) instanceof COSArray c && c.size() > MAX
                && COSName.PAGE.equals(d.getCOSName(COSName.TYPE))) {
            return contents(d, c);
        }
        if (d.getDictionaryObject(INK_LIST) instanceof COSArray ink && longPath(ink)) {
            d.setItem(INK_LIST, ink(ink));
            return true;
        }
        if (d.getDictionaryObject(COSName.W) instanceof COSArray w && tooLong(w)
                && (COSName.CID_FONT_TYPE0.equals(d.getCOSName(COSName.SUBTYPE))
                        || COSName.CID_FONT_TYPE2.equals(d.getCOSName(COSName.SUBTYPE)))) {
            d.setItem(COSName.W, widths(w, d.getFloat(COSName.DW, 1000)));
            return true;
        }
        for (COSName key : new COSName[] {COSName.NUMS, COSName.NAMES}) {
            if (d.getDictionaryObject(key) instanceof COSArray entries && entries.size() > MAX) {
                leaves(d, key, entries);
                kids(d);
                return true;
            }
        }
        if (d.getDictionaryObject(COSName.KIDS) instanceof COSArray k && k.size() > MAX && isTreeNode(k)) {
            return kids(d);
        }
        return false;
    }

    private static boolean contents(COSDictionary page, COSArray streams) throws IOException {
        List<COSStream> parts = new ArrayList<>();
        for (int i = 0; i < streams.size(); i++) {
            if (streams.getObject(i) instanceof COSStream s) {
                parts.add(s);
            }
        }
        COSStream merged = new COSStream();
        try (OutputStream out = merged.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(ContentTokens.bytes(parts));
        }
        page.setItem(COSName.CONTENTS, merged);
        return true;
    }

    private static boolean longPath(COSArray ink) {
        for (int i = 0; i < ink.size(); i++) {
            if (ink.getObject(i) instanceof COSArray path && path.size() > MAX) {
                return true;
            }
        }
        return false;
    }

    private static COSArray ink(COSArray ink) {
        COSArray out = new COSArray();
        int step = MAX - 1 - (MAX - 1) % 2;
        for (int i = 0; i < ink.size(); i++) {
            if (!(ink.getObject(i) instanceof COSArray path) || path.size() <= MAX) {
                out.add(ink.get(i));
                continue;
            }
            for (int start = 0; start + 2 < path.size(); start += step - 2) {
                COSArray part = new COSArray();
                for (int k = start; k < Math.min(path.size(), start + step); k++) {
                    part.add(path.get(k));
                }
                out.add(part);
            }
        }
        return out;
    }

    private static boolean tooLong(COSArray w) {
        if (w.size() > MAX) {
            return true;
        }
        for (int i = 0; i < w.size(); i++) {
            if (w.getObject(i) instanceof COSArray inner && inner.size() > MAX) {
                return true;
            }
        }
        return false;
    }

    private static COSArray widths(COSArray w, float dw) {
        TreeMap<Integer, Float> map = new TreeMap<>();
        int i = 0;
        while (i < w.size()) {
            if (!(w.getObject(i) instanceof COSNumber first)) {
                break;
            }
            if (i + 1 < w.size() && w.getObject(i + 1) instanceof COSArray list) {
                for (int k = 0; k < list.size(); k++) {
                    if (list.getObject(k) instanceof COSNumber n) {
                        map.put(first.intValue() + k, n.floatValue());
                    }
                }
                i += 2;
            } else if (i + 2 < w.size() && w.getObject(i + 1) instanceof COSNumber last
                    && w.getObject(i + 2) instanceof COSNumber value) {
                for (int c = first.intValue(); c <= last.intValue() && c - first.intValue() < 70_000; c++) {
                    map.put(c, value.floatValue());
                }
                i += 3;
            } else {
                break;
            }
        }
        COSArray out = new COSArray();
        if (map.isEmpty()) {
            return out;
        }
        int lo = map.firstKey();
        int hi = map.lastKey();
        for (int start = lo; start <= hi; start += MAX) {
            COSArray list = new COSArray();
            for (int c = start; c <= hi && c < start + MAX; c++) {
                float v = map.getOrDefault(c, dw);
                list.add(v == Math.rint(v) ? COSInteger.get((long) v) : new COSFloat(v));
            }
            out.add(COSInteger.get(start));
            out.add(list);
        }
        return out;
    }

    private static void leaves(COSDictionary node, COSName key, COSArray entries) {
        COSArray kids = new COSArray();
        int pair = entries.size() - entries.size() % 2;
        for (int i = 0; i < pair; i += 2 * LEAF) {
            COSDictionary leaf = new COSDictionary();
            COSArray part = new COSArray();
            for (int k = i; k < Math.min(pair, i + 2 * LEAF); k++) {
                part.add(entries.get(k));
            }
            leaf.setItem(key, part);
            COSArray limits = new COSArray();
            limits.add(part.get(0));
            limits.add(part.get(part.size() - 2));
            leaf.setItem(COSName.LIMITS, limits);
            kids.add(leaf);
        }
        node.removeItem(key);
        node.setItem(COSName.KIDS, kids);
    }

    private static boolean kids(COSDictionary node) {
        COSArray kids = ContentGraph.array(node.getDictionaryObject(COSName.KIDS));
        if (kids == null || kids.size() <= MAX) {
            return false;
        }
        while (kids.size() > MAX) {
            COSArray up = new COSArray();
            for (int i = 0; i < kids.size(); i += LEAF) {
                COSDictionary mid = new COSDictionary();
                COSArray part = new COSArray();
                for (int k = i; k < Math.min(kids.size(), i + LEAF); k++) {
                    part.add(kids.get(k));
                }
                mid.setItem(COSName.KIDS, part);
                COSArray first = limits(part.getObject(0));
                COSArray last = limits(part.getObject(part.size() - 1));
                if (first != null && last != null) {
                    COSArray limits = new COSArray();
                    limits.add(first.get(0));
                    limits.add(last.get(1));
                    mid.setItem(COSName.LIMITS, limits);
                }
                up.add(mid);
            }
            kids = up;
        }
        node.setItem(COSName.KIDS, kids);
        return true;
    }

    private static COSArray limits(COSBase kid) {
        COSDictionary d = ContentGraph.dict(kid);
        return d == null ? null : ContentGraph.array(d.getDictionaryObject(COSName.LIMITS));
    }

    private static boolean isTreeNode(COSArray kids) {
        COSDictionary first = ContentGraph.dict(kids.get(0));
        return first != null && (first.containsKey(COSName.LIMITS) || first.containsKey(COSName.NUMS)
                || first.containsKey(COSName.NAMES) && first.getDictionaryObject(COSName.NAMES) instanceof COSArray);
    }

    static List<String> unfixable(COSBase b) {
        List<String> out = new ArrayList<>();
        if (b instanceof COSArray a && a.size() > MAX) {
            out.add("an array of " + a.size() + " entries");
        } else if (b instanceof COSDictionary d && d.size() > 4095) {
            out.add("a dictionary of " + d.size() + " entries");
        }
        return out;
    }
}
