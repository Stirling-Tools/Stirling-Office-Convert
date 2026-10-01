package stirling.software.officeconvert.topdf.doc;

import java.io.IOException;
import java.util.List;

import org.apache.poi.ddf.DefaultEscherRecordFactory;
import org.apache.poi.ddf.EscherBSERecord;
import org.apache.poi.ddf.EscherBlipRecord;
import org.apache.poi.ddf.EscherChildAnchorRecord;
import org.apache.poi.ddf.EscherContainerRecord;
import org.apache.poi.ddf.EscherRecord;
import org.apache.poi.ddf.EscherSpRecord;
import org.apache.poi.ddf.EscherSpgrRecord;
import org.apache.poi.hwpf.model.OfficeArtContent;

final class Groups {

    static final String WPG = Xml.WPG;

    static final int MAX_DEPTH = 8;

    static final int MAX_CHILDREN = 2000;

    private Groups() {}

    static String group(Conv c, Story story, int spid, long cx, long cy) throws IOException {
        EscherContainerRecord spgr = find(c, spid);
        if (spgr == null) {
            return null;
        }
        StringBuilder b = new StringBuilder("<wpg:wgp><wpg:cNvGrpSpPr/>");
        if (!members(c, story, spgr, 0, 0, cx, cy, b, 0, true)) {
            return null;
        }
        return b.append("</wpg:wgp>").toString();
    }

    private static boolean members(Conv c, Story story, EscherContainerRecord spgr, long x, long y, long cx, long cy,
            StringBuilder b, int depth, boolean top) throws IOException {
        List<EscherRecord> kids = spgr.getChildRecords();
        if (kids.isEmpty() || !(kids.get(0) instanceof EscherContainerRecord head)) {
            return false;
        }
        EscherSpgrRecord frame = head.getChildById(EscherSpgrRecord.RECORD_ID);
        EscherSpRecord rec = head.getChildById(EscherSpRecord.RECORD_ID);
        if (frame == null || rec == null) {
            return false;
        }
        long chx = frame.getRectX1();
        long chy = frame.getRectY1();
        long chw = Math.max(1, (long) frame.getRectX2() - frame.getRectX1());
        long chh = Math.max(1, (long) frame.getRectY2() - frame.getRectY1());
        b.append(top ? "<wpg:grpSpPr>" : "<wpg:grpSp><wpg:cNvGrpSpPr/><wpg:grpSpPr>");
        b.append("<a:xfrm><a:off x=\"").append(x).append("\" y=\"").append(y).append("\"/><a:ext cx=\"").append(cx)
                .append("\" cy=\"").append(cy).append("\"/><a:chOff x=\"").append(chx).append("\" y=\"").append(chy)
                .append("\"/><a:chExt cx=\"").append(chw).append("\" cy=\"").append(chh)
                .append("\"/></a:xfrm></wpg:grpSpPr>");
        int count = 0;
        for (int i = 1; i < kids.size() && count < MAX_CHILDREN; i++) {
            if (!(kids.get(i) instanceof EscherContainerRecord kid)) {
                continue;
            }
            c.checkpoint();
            count++;
            if (kid.getRecordId() == (short) 0xF003) {
                if (depth + 1 < MAX_DEPTH && !kid.getChildRecords().isEmpty()
                        && kid.getChildRecords().get(0) instanceof EscherContainerRecord first) {
                    long[] r = anchor(first);
                    if (r != null) {
                        members(c, story, kid, r[0], r[1], r[2], r[3], b, depth + 1, false);
                    }
                }
                continue;
            }
            long[] r = anchor(kid);
            if (r == null) {
                continue;
            }
            String child = child(c, story, kid, r);
            if (child != null) {
                b.append(child);
            }
        }
        b.append(top ? "" : "</wpg:grpSp>");
        return true;
    }

    private static long[] anchor(EscherContainerRecord sp) {
        EscherChildAnchorRecord a = sp.getChildById(EscherChildAnchorRecord.RECORD_ID);
        if (a == null) {
            return null;
        }
        return new long[] {a.getDx1(), a.getDy1(), Math.max(1, (long) a.getDx2() - a.getDx1()),
            Math.max(1, (long) a.getDy2() - a.getDy1())};
    }

    private static String child(Conv c, Story story, EscherContainerRecord sp, long[] r) throws IOException {
        long pib = Shapes.prop(sp, 0x0104, 0);
        if (pib > 0) {
            String name = c.media.add(blip(c, (int) pib));
            if (name == null) {
                return null;
            }
            EscherSpRecord rec = sp.getChildById(EscherSpRecord.RECORD_ID);
            int id = c.nextId();
            StringBuilder b = new StringBuilder("<pic:pic><pic:nvPicPr><pic:cNvPr id=\"").append(id)
                    .append("\" name=\"Picture ").append(id).append("\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill>")
                    .append("<a:blip r:embed=\"").append(story.rels.image(name)).append("\"/><a:stretch><a:fillRect/>")
                    .append("</a:stretch></pic:blipFill><pic:spPr>");
            if (rec != null) {
                Shapes.xfrm(b, rec, r[0], r[1], r[2], r[3]);
            }
            return b.append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>").toString();
        }
        return Shapes.shape(c, story, sp, r[0], r[1], r[2], r[3], false);
    }

    static byte[] blip(Conv c, int pib) {
        try {
            OfficeArtContent art = c.src.doc.getOfficeArtContent();
            EscherContainerRecord store = art == null ? null : art.getBStoreContainer();
            if (store == null || pib < 1 || pib > store.getChildRecords().size()) {
                return null;
            }
            if (!(store.getChild(pib - 1) instanceof EscherBSERecord bse)) {
                return null;
            }
            EscherBlipRecord blip = bse.getBlipRecord();
            if (blip != null) {
                return blip.getPicturedata();
            }
            byte[] main = c.src.doc.getMainStream();
            int at = bse.getOffset();
            if (at < 0 || at + 8 > main.length) {
                return null;
            }
            DefaultEscherRecordFactory factory = new DefaultEscherRecordFactory();
            EscherRecord r = factory.createRecord(main, at);
            r.fillFields(main, at, factory);
            return r instanceof EscherBlipRecord b ? b.getPicturedata() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static EscherContainerRecord head(Conv c, int spid) {
        EscherContainerRecord spgr = find(c, spid);
        return spgr == null || !(spgr.getChildRecords().get(0) instanceof EscherContainerRecord h) ? null : h;
    }

    private static EscherContainerRecord find(Conv c, int spid) {
        try {
            OfficeArtContent art = c.src.doc.getOfficeArtContent();
            if (art == null) {
                return null;
            }
            for (EscherContainerRecord top : art.getSpgrContainers()) {
                EscherContainerRecord hit = search(top, spid, 0);
                if (hit != null) {
                    return hit;
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static EscherContainerRecord search(EscherContainerRecord spgr, int spid, int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        List<EscherRecord> kids = spgr.getChildRecords();
        for (int i = 0; i < kids.size(); i++) {
            if (!(kids.get(i) instanceof EscherContainerRecord kid)) {
                continue;
            }
            if (kid.getRecordId() == (short) 0xF003) {
                if (!kid.getChildRecords().isEmpty() && kid.getChildRecords().get(0) instanceof EscherContainerRecord h
                        && h.getChildById(EscherSpRecord.RECORD_ID) instanceof EscherSpRecord r && r.getShapeId() == spid) {
                    return kid;
                }
                EscherContainerRecord deeper = search(kid, spid, depth + 1);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }
}
