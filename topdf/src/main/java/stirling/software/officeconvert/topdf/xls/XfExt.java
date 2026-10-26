package stirling.software.officeconvert.topdf.xls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.poi.hssf.record.Record;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

final class XfExt {

    static final int MAX_THEME_BYTES = 1 << 20;

    private static final int XFEXT = 0x087D;

    private static final int THEME = 0x0896;

    private static final int CONTINUE_FRT12 = 0x087F;

    private static final int DEFAULT_THEME_VERSION = 124226;

    private static final int FILL = 4;

    private static final int BACK = 5;

    private static final int TOP = 7;

    private static final int BOTTOM = 8;

    private static final int LEFT = 9;

    private static final int RIGHT = 10;

    private static final int TEXT = 13;

    record Colours(String fill, String back, String text, String top, String bottom, String left, String right) {}

    private final Map<Integer, Colours> byXf = new HashMap<>();

    private String theme;

    private boolean themed;

    static XfExt read(HSSFWorkbook wb) {
        XfExt x = new XfExt();
        ByteArrayOutputStream themeBytes = null;
        boolean inTheme = false;
        for (Record r : wb.getInternalWorkbook().getRecords()) {
            int sid = r.getSid();
            if (sid == XFEXT) {
                x.xf(body(r));
                inTheme = false;
            } else if (sid == THEME) {
                byte[] d = body(r);
                int version = d.length >= 16 ? u32(d, 12) : DEFAULT_THEME_VERSION;
                if (version == 0 && d.length > 16) {
                    themeBytes = new ByteArrayOutputStream();
                    themeBytes.write(d, 16, d.length - 16);
                    inTheme = true;
                } else {
                    x.theme = DEFAULT;
                }
            } else if (sid == CONTINUE_FRT12 && inTheme && themeBytes != null) {
                byte[] d = body(r);
                if (d.length > 12 && themeBytes.size() + d.length <= MAX_THEME_BYTES) {
                    themeBytes.write(d, 12, d.length - 12);
                }
            } else {
                inTheme = false;
            }
        }
        if (themeBytes != null) {
            String xml = themeXml(themeBytes.toByteArray());
            x.theme = xml != null ? xml : DEFAULT;
        }
        return x;
    }

    Colours at(int xf) {
        return byXf.get(xf);
    }

    String theme() {
        return themed || !byXf.isEmpty() ? theme == null ? DEFAULT : theme : null;
    }

    private void xf(byte[] d) {
        if (d.length < 20) {
            return;
        }
        int ixfe = u16(d, 14);
        int count = u16(d, 18);
        String[] c = new String[14];
        int at = 20;
        for (int k = 0; k < count && at + 4 <= d.length; k++) {
            int type = u16(d, at);
            int cb = u16(d, at + 2);
            if (cb < 4 || at + cb > d.length) {
                break;
            }
            if ((type == FILL || type == BACK || type == TEXT || type >= TOP && type <= RIGHT) && cb >= 20) {
                c[type] = colour(d, at + 4);
            }
            at += cb;
        }
        if (c[FILL] != null || c[BACK] != null || c[TEXT] != null || c[TOP] != null || c[BOTTOM] != null
                || c[LEFT] != null || c[RIGHT] != null) {
            byXf.put(ixfe, new Colours(c[FILL], c[BACK], c[TEXT], c[TOP], c[BOTTOM], c[LEFT], c[RIGHT]));
        }
    }

    private String colour(byte[] d, int at) {
        int type = u16(d, at);
        double tint = (short) u16(d, at + 2) / 32767.0;
        String t = tint == 0 || !Double.isFinite(tint) ? "" : " tint=\"" + Math.max(-1, Math.min(1, tint)) + "\"";
        return switch (type) {
            case 1 -> u32(d, at + 4) >= 0 && u32(d, at + 4) < 64 ? "indexed=\"" + u32(d, at + 4) + "\"" + t : null;
            case 2 -> String.format("rgb=\"FF%02X%02X%02X\"", d[at + 4] & 0xFF, d[at + 5] & 0xFF, d[at + 6] & 0xFF) + t;
            case 3 -> {
                int index = u32(d, at + 4);
                if (index < 0 || index > 11) {
                    yield null;
                }
                themed = true;
                yield "theme=\"" + index + "\"" + t;
            }
            default -> null;
        };
    }

    private static String themeXml(byte[] zip) {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            int entries = 0;
            while ((e = in.getNextEntry()) != null && entries++ < 64) {
                if (e.getName().matches("theme/theme/theme\\d*\\.xml")) {
                    byte[] xml = in.readNBytes(MAX_THEME_BYTES + 1);
                    return xml.length > MAX_THEME_BYTES ? null : new String(xml, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    private static byte[] body(Record r) {
        byte[] all = r.serialize();
        byte[] d = new byte[Math.max(0, all.length - 4)];
        System.arraycopy(all, 4, d, 0, d.length);
        return d;
    }

    private static int u16(byte[] d, int at) {
        return at + 1 < d.length ? (d[at] & 0xFF) | (d[at + 1] & 0xFF) << 8 : 0;
    }

    private static int u32(byte[] d, int at) {
        return u16(d, at) | u16(d, at + 2) << 16;
    }

    static final String DEFAULT = Xml.HEAD + "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
            + " name=\"Office Theme\"><a:themeElements><a:clrScheme name=\"Office\"><a:dk1><a:sysClr val=\"windowText\""
            + " lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1><a:dk2>"
            + "<a:srgbClr val=\"1F497D\"/></a:dk2><a:lt2><a:srgbClr val=\"EEECE1\"/></a:lt2><a:accent1><a:srgbClr"
            + " val=\"4F81BD\"/></a:accent1><a:accent2><a:srgbClr val=\"C0504D\"/></a:accent2><a:accent3><a:srgbClr"
            + " val=\"9BBB59\"/></a:accent3><a:accent4><a:srgbClr val=\"8064A2\"/></a:accent4><a:accent5><a:srgbClr"
            + " val=\"4BACC6\"/></a:accent5><a:accent6><a:srgbClr val=\"F79646\"/></a:accent6><a:hlink><a:srgbClr"
            + " val=\"0000FF\"/></a:hlink><a:folHlink><a:srgbClr val=\"800080\"/></a:folHlink></a:clrScheme>"
            + "<a:fontScheme name=\"Office\"><a:majorFont><a:latin typeface=\"Cambria\"/><a:ea typeface=\"\"/>"
            + "<a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/>"
            + "<a:cs typeface=\"\"/></a:minorFont></a:fontScheme><a:fmtScheme name=\"Office\"><a:fillStyleLst>"
            + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/>"
            + "</a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst><a:lnStyleLst>"
            + "<a:ln w=\"9525\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"25400\">"
            + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"38100\"><a:solidFill>"
            + "<a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle>"
            + "<a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle>"
            + "<a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr"
            + " val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill>"
            + "<a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements>"
            + "</a:theme>";
}
