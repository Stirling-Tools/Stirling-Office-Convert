package stirling.software.officeconvert.topdf.ppt;

import java.util.List;

import org.apache.poi.ddf.AbstractEscherOptRecord;
import org.apache.poi.ddf.EscherProperty;
import org.apache.poi.ddf.EscherPropertyTypes;
import org.apache.poi.ddf.EscherSimpleProperty;
import org.apache.poi.hslf.usermodel.HSLFGroupShape;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSheet;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;

final class ShapeShadows {

    private static final int SHADOW_FLAGS = 0x023F;

    private static final int SHADOW_ON = 0x2;

    private static final int SHADOW_ON_USED = 0x20000;

    private static final int DEFAULT_OFFSET = 25_400;

    private static final int DEFAULT_COLOR = 0x808080;

    private static final int MAX_DEPTH = 64;

    private ShapeShadows() {}

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
        if (opt == null) {
            return;
        }
        if (!on(opt.lookup(SHADOW_FLAGS))) {
            opt.removeEscherProperty(EscherPropertyTypes.SHADOWSTYLE__TYPE);
            return;
        }
        fill(opt, EscherPropertyTypes.SHADOWSTYLE__TYPE, 0);
        fill(opt, EscherPropertyTypes.SHADOWSTYLE__COLOR, DEFAULT_COLOR);
        fill(opt, EscherPropertyTypes.SHADOWSTYLE__OFFSETX, DEFAULT_OFFSET);
        fill(opt, EscherPropertyTypes.SHADOWSTYLE__OFFSETY, DEFAULT_OFFSET);
        opt.sortProperties();
    }

    private static boolean on(EscherProperty flags) {
        if (!(flags instanceof EscherSimpleProperty p)) {
            return false;
        }
        int v = p.getPropertyValue();
        return (v & SHADOW_ON_USED) != 0 && (v & SHADOW_ON) != 0;
    }

    private static void fill(AbstractEscherOptRecord opt, EscherPropertyTypes type, int value) {
        if (opt.lookup(type) == null) {
            opt.addEscherProperty(new EscherSimpleProperty(type, value));
        }
    }
}
