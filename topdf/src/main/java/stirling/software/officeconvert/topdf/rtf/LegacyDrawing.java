package stirling.software.officeconvert.topdf.rtf;

final class LegacyDrawing {

    private int x;
    private int y;
    private int width;
    private int height;
    private int fill = -1;
    private int fillRed;
    private int fillGreen;
    private int fillBlue;
    private int lineRed;
    private int lineGreen;
    private int lineBlue;
    private int lineWidth = 15;
    private boolean hollow;
    private int type = 1;

    boolean word(Shape s, String w, int v) {
        switch (w) {
            case "dobxpage" -> s.bx = "page";
            case "dobxmargin" -> s.bx = "margin";
            case "dobxcolumn" -> s.bx = "column";
            case "dobypage" -> s.by = "page";
            case "dobymargin" -> s.by = "margin";
            case "dobypara" -> s.by = "paragraph";
            case "dpx" -> x = v;
            case "dpy" -> y = v;
            case "dpxsize" -> width = v;
            case "dpysize" -> height = v;
            case "dptxbx" -> type = 202;
            case "dprect" -> type = 1;
            case "dproundr" -> type = 2;
            case "dpellipse" -> type = 3;
            case "dpline" -> type = 20;
            case "dpfillpat" -> fill = v;
            case "dpfillfgcr" -> fillRed = v;
            case "dpfillfgcg" -> fillGreen = v;
            case "dpfillfgcb" -> fillBlue = v;
            case "dplinecor" -> lineRed = v;
            case "dplinecog" -> lineGreen = v;
            case "dplinecob" -> lineBlue = v;
            case "dplinew" -> lineWidth = Math.max(0, v);
            case "dplinehollow" -> hollow = true;
            case "dodhgt" -> s.z = v;
            default -> {
                return false;
            }
        }
        return true;
    }

    void finish(Shape s) {
        s.left = x;
        s.top = y;
        s.right = x + width;
        s.bottom = y + height;
        s.wrap = 3;
        s.prop("shapeType", Integer.toString(type));
        s.prop("fFilled", fill > 0 ? "1" : "0");
        s.prop("fillColor", Integer.toString(bgr(fillRed, fillGreen, fillBlue)));
        s.prop("fLine", hollow ? "0" : "1");
        s.prop("lineColor", Integer.toString(bgr(lineRed, lineGreen, lineBlue)));
        s.prop("lineWidth", Integer.toString(lineWidth * PictureXml.EMU_PER_TWIP));
    }

    private static int bgr(int r, int g, int b) {
        return (r & 0xFF) | (g & 0xFF) << 8 | (b & 0xFF) << 16;
    }
}
