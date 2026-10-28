package stirling.software.officeconvert.topdf.ppt;

import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ddf.AbstractEscherOptRecord;
import org.apache.poi.ddf.EscherArrayProperty;
import org.apache.poi.ddf.EscherProperty;
import org.apache.poi.ddf.EscherPropertyTypes;
import org.apache.poi.ddf.EscherSimpleProperty;
import org.apache.poi.hslf.usermodel.HSLFGroupShape;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSheet;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.util.LittleEndian;

final class Freeforms {

    private static final int MAX_DEPTH = 64;

    private static final int MAX_SEGMENTS = 65_535;

    private static final int LINE = 0;

    private static final int CURVE = 1;

    private Freeforms() {}

    static void apply(HSLFSlideShow ppt) {
        for (HSLFSheet sheet : ppt.getSlides()) {
            shapes(sheet.getShapes(), 0);
        }
        for (HSLFSheet sheet : ppt.getSlideMasters()) {
            shapes(sheet.getShapes(), 0);
        }
        for (HSLFSheet sheet : ppt.getTitleMasters()) {
            shapes(sheet.getShapes(), 0);
        }
    }

    private static void shapes(List<HSLFShape> list, int depth) {
        if (list == null || depth > MAX_DEPTH) {
            return;
        }
        for (HSLFShape s : list) {
            if (s instanceof HSLFGroupShape g) {
                shapes(g.getShapes(), depth + 1);
            }
            try {
                fix(s.getEscherOptRecord());
            } catch (RuntimeException e) {
                continue;
            }
        }
    }

    static void fix(AbstractEscherOptRecord opt) {
        if (opt == null || !(opt.lookup(EscherPropertyTypes.GEOMETRY__VERTICES) instanceof EscherArrayProperty vertices)) {
            return;
        }
        extent(opt, vertices);
        if (opt.lookup(EscherPropertyTypes.GEOMETRY__SEGMENTINFO) instanceof EscherArrayProperty segments) {
            split(opt, segments);
        }
    }

    private static void extent(AbstractEscherOptRecord opt, EscherArrayProperty vertices) {
        int left = value(opt, EscherPropertyTypes.GEOMETRY__LEFT, 0);
        int top = value(opt, EscherPropertyTypes.GEOMETRY__TOP, 0);
        int right = value(opt, EscherPropertyTypes.GEOMETRY__RIGHT, Integer.MAX_VALUE);
        int bottom = value(opt, EscherPropertyTypes.GEOMETRY__BOTTOM, Integer.MAX_VALUE);
        if (right > left && bottom > top) {
            return;
        }
        long maxX = Long.MIN_VALUE;
        long maxY = Long.MIN_VALUE;
        for (byte[] e : vertices) {
            boolean wide = e.length >= 8;
            maxX = Math.max(maxX, wide ? LittleEndian.getInt(e, 0) : LittleEndian.getShort(e, 0));
            maxY = Math.max(maxY, wide ? LittleEndian.getInt(e, 4) : LittleEndian.getShort(e, 2));
        }
        if (right <= left && maxX > left) {
            set(opt, EscherPropertyTypes.GEOMETRY__RIGHT, (int) maxX);
        }
        if (bottom <= top && maxY > top) {
            set(opt, EscherPropertyTypes.GEOMETRY__BOTTOM, (int) maxY);
        }
    }

    private static void split(AbstractEscherOptRecord opt, EscherArrayProperty segments) {
        List<byte[]> out = new ArrayList<>();
        boolean changed = false;
        for (byte[] e : segments) {
            if (e.length < 2) {
                return;
            }
            int v = LittleEndian.getUShort(e, 0);
            int type = v >>> 13;
            int count = v & 0x1FFF;
            if ((type == LINE || type == CURVE) && count > 1) {
                changed = true;
                for (int k = 0; k < count; k++) {
                    byte[] one = e.clone();
                    LittleEndian.putUShort(one, 0, (type << 13) | 1);
                    out.add(one);
                }
            } else {
                out.add(e);
            }
            if (out.size() > MAX_SEGMENTS) {
                return;
            }
        }
        if (!changed) {
            return;
        }
        int size = out.getFirst().length;
        EscherArrayProperty split = new EscherArrayProperty(EscherPropertyTypes.GEOMETRY__SEGMENTINFO, false, 0);
        split.setNumberOfElementsInArray(out.size());
        split.setNumberOfElementsInMemory(out.size());
        split.setSizeOfElements(size);
        for (int i = 0; i < out.size(); i++) {
            split.setElement(i, out.get(i));
        }
        opt.removeEscherProperty(EscherPropertyTypes.GEOMETRY__SEGMENTINFO);
        opt.addEscherProperty(split);
        opt.sortProperties();
    }

    private static int value(AbstractEscherOptRecord opt, EscherPropertyTypes type, int otherwise) {
        EscherProperty p = opt.lookup(type);
        return p instanceof EscherSimpleProperty s ? s.getPropertyValue() : otherwise;
    }

    private static void set(AbstractEscherOptRecord opt, EscherPropertyTypes type, int value) {
        opt.removeEscherProperty(type);
        opt.addEscherProperty(new EscherSimpleProperty(type, value));
        opt.sortProperties();
    }
}
