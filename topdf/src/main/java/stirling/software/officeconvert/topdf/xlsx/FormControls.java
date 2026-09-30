package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.io.VmlXml;

final class FormControls {

    private static final String VML = "urn:schemas-microsoft-com:vml";

    private static final Set<String> DRAWN = Set.of("checkbox", "radio", "label", "button", "gbox", "drop", "list");

    private static final int MAX_CONTROLS = 2000;

    private static final int MAX_TEXT = 4000;

    private FormControls() {}

    static void read(Book book, String part, String sheetName, List<Drawings.Item> out) throws InterruptedIOException {
        RenderJob job = book.job();
        try {
            for (Relationship r : job.zip().relationships(part).ofType("vmlDrawing")) {
                if (!ActiveContent.mayFollow(r)) {
                    continue;
                }
                Document doc = VmlXml.parse(job.zip().read(r));
                NodeList shapes = doc.getElementsByTagNameNS(VML, "shape");
                for (int i = 0; i < shapes.getLength() && out.size() < MAX_CONTROLS; i++) {
                    Drawings.Item item = control(book, (Element) shapes.item(i));
                    if (item != null) {
                        out.add(item);
                    }
                }
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            job.warn("The form controls on sheet " + sheetName + " could not be read");
        }
    }

    private static Drawings.Item control(Book book, Element shape) {
        Element data = Dml.child(shape, "ClientData");
        if (data == null) {
            return null;
        }
        String type = data.getAttribute("ObjectType").toLowerCase(Locale.ROOT);
        String style = shape.getAttribute("style").replace(" ", "").toLowerCase(Locale.ROOT);
        if (!DRAWN.contains(type) || style.contains("visibility:hidden") || "false".equals(value(data, "PrintObject"))
                || "false".equals(value(data, "Visible"))) {
            return null;
        }
        String[] parts = value(data, "Anchor") == null ? new String[0] : value(data, "Anchor").split(",");
        if (parts.length != 8) {
            return null;
        }
        int[] a = new int[8];
        try {
            for (int i = 0; i < 8; i++) {
                a[i] = Math.max(0, Integer.parseInt(parts[i].strip()));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        Drawings.Anchor from = anchor(a[0], a[1], a[2], a[3]);
        Drawings.Anchor to = anchor(a[4], a[5], a[6], a[7]);
        String h = value(data, "TextHAlign");
        String v = value(data, "TextVAlign");
        String align = "center".equals(h) ? "ctr" : "right".equals(h) ? "r" : "l";
        String anchor = "center".equals(v) ? "ctr" : "bottom".equals(v) ? "b" : "t";
        if (type.equals("button")) {
            align = h == null ? "ctr" : align;
            anchor = v == null ? "ctr" : anchor;
        }
        double[] insets = switch (type) {
            case "checkbox", "radio" -> new double[] {align.equals("ctr") ? 2.3 : 17, 1.8, 2.3, 1.8};
            case "gbox" -> new double[] {6, 0, 2, 0};
            default -> new double[] {1.8, 1.8, 1.8, 1.8};
        };
        List<Drawings.Para> text = type.equals("drop") || type.equals("list") ? List.of()
                : text(book, Dml.child(shape, "textbox"), align);
        return new Drawings.Item(from, to, 0, 0, 0, 0, null, null, null, null, null, null, 0, text, insets, anchor, 0,
                false, false, false, null, null, null, null, null, type, "1".equals(value(data, "Checked")));
    }

    private static Drawings.Anchor anchor(int col, int colPx, int row, int rowPx) {
        return new Drawings.Anchor(Math.min(Columns.MAX - 1, col), Math.min(colPx, 100_000) * 0.75,
                Math.min(Grid.MAX_ROWS - 1, row), Math.min(rowPx, 100_000) * 0.75);
    }

    private static String value(Element data, String name) {
        Element e = Dml.child(data, name);
        return e == null ? null : e.getTextContent().strip().toLowerCase(Locale.ROOT);
    }

    private static List<Drawings.Para> text(Book book, Element box, String align) {
        List<Drawings.Para> paras = new ArrayList<>();
        if (box == null) {
            return paras;
        }
        StringBuilder all = new StringBuilder();
        Element font = collect(box, all, 0);
        String family = "Tahoma";
        double size = 8;
        Color color = Color.BLACK;
        boolean bold = false;
        boolean italic = false;
        if (font != null) {
            family = font.getAttribute("face").isBlank() ? family : font.getAttribute("face").strip();
            try {
                double s = Double.parseDouble(font.getAttribute("size").strip());
                size = s >= 20 && s <= 8000 ? s / 20 : size;
            } catch (NumberFormatException e) {
                size = 8;
            }
            String c = font.getAttribute("color").strip();
            if (c.startsWith("#") && c.length() == 7) {
                try {
                    color = new Color(Integer.parseInt(c.substring(1), 16));
                } catch (NumberFormatException e) {
                    color = Color.BLACK;
                }
            }
            for (Node n = font; n != null && n != box; n = n.getParentNode()) {
                bold |= "b".equalsIgnoreCase(n.getLocalName());
                italic |= "i".equalsIgnoreCase(n.getLocalName());
            }
        }
        FontSpec spec = new FontSpec(family, size, bold, italic, FontSpec.Underline.NONE, false, color,
                FontSpec.Offset.NONE);
        String s = all.toString().strip().replace('\r', '\n');
        if (s.isEmpty()) {
            return paras;
        }
        for (String line : s.split("\n", 200)) {
            if (!line.isBlank()) {
                paras.add(new Drawings.Para(List.of(new TextRun(line.strip(), spec)), align));
            }
        }
        return paras;
    }

    // The text of a text box, a line per division or break, and the first font element in it
    private static Element collect(Node node, StringBuilder out, int depth) {
        Element font = null;
        for (Node n = node.getFirstChild(); n != null && depth < 32 && out.length() < MAX_TEXT; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                out.append(n.getNodeValue().replaceAll("[\\t\\n\\r ]+", " "));
            } else if (n instanceof Element e) {
                String name = e.getLocalName() == null ? "" : e.getLocalName().toLowerCase(Locale.ROOT);
                if (name.equals("br")) {
                    out.append('\n');
                    continue;
                }
                if (name.equals("font") && font == null) {
                    font = e;
                }
                Element inner = collect(e, out, depth + 1);
                font = font == null ? inner : font;
                if (name.equals("div") || name.equals("p")) {
                    out.append('\n');
                }
            }
        }
        return font;
    }
}
