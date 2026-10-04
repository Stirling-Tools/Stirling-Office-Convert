package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.io.VmlXml;
import stirling.software.officeconvert.topdf.dml.DmlColors;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Stroke;

// Preview pictures of controls and objects kept only in the slide's legacy VML drawing, as PowerPoint shows them
final class LegacyShapes {

    private static final String VML = "urn:schemas-microsoft-com:vml";

    private static final String OFFICE = "urn:schemas-microsoft-com:office:office";

    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private static final int MAX_SHAPES = 500;

    private static final int MAX_NODES = 200_000;

    private LegacyShapes() {}

    static void paint(Deck deck, PdfCanvas canvas, XSLFSheet sheet, int number) throws IOException {
        try {
            String part = sheet.getPackagePart().getPartName().getName();
            Set<String> drawn = null;
            for (Relationship r : deck.job().zip().relationships(part).ofType("vmlDrawing")) {
                if (!ActiveContent.mayFollow(r)) {
                    continue;
                }
                if (drawn == null) {
                    drawn = pictured(sheet.getXmlObject().getDomNode());
                }
                Document doc = VmlXml.parse(deck.job().zip().read(r));
                int n = 0;
                for (Node c = doc.getDocumentElement().getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (c instanceof Element e && VML.equals(e.getNamespaceURI()) && "shape".equals(e.getLocalName())
                            && !drawn.contains(e.getAttributeNS(OFFICE, "spid")) && !drawn.contains(e.getAttribute("id"))) {
                        if (n++ == MAX_SHAPES) {
                            deck.job().warn("Legacy shapes past the first " + MAX_SHAPES + " on slide " + number
                                    + " were left out");
                            deck.job().losePart();
                            break;
                        }
                        shape(deck, canvas, r.part(), e);
                    }
                }
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            deck.job().warn("The legacy drawing of slide " + number + " could not be read: " + e.getMessage());
        }
    }

    private static void shape(Deck deck, PdfCanvas canvas, String vmlPart, Element shape) throws IOException {
        Element data = null;
        for (Node c = shape.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element e && VML.equals(e.getNamespaceURI()) && "imagedata".equals(e.getLocalName())) {
                data = e;
            }
        }
        if (data == null) {
            return;
        }
        float[] frame = box(shape.getAttribute("style"));
        if (frame != null) {
            Color fill = paint(shape, "fill", "filled", "fillcolor", Color.WHITE);
            Color line = paint(shape, "stroke", "stroked", "strokecolor", Color.BLACK);
            if (fill != null || line != null) {
                canvas.rect(frame[0], frame[1], frame[2], frame[3], fill == null ? null : Fill.solid(fill),
                        line == null ? null : Stroke.solid(weight(child(shape, "stroke")), line));
            }
        }
        String id = data.getAttributeNS(OFFICE, "relid");
        if (id.isBlank()) {
            id = data.getAttributeNS(R, "id");
        }
        float[] box = box(shape.getAttribute("style"));
        if (id.isBlank() || box == null) {
            return;
        }
        DecodedPicture picture = deck.pictures().picture(vmlPart, id.strip());
        if (picture != null) {
            canvas.image(picture, box[0], box[1], box[2], box[3]);
        }
    }

    // A preview's own fill or outline counts only when the shape or its v:fill / v:stroke turns it on
    private static Color paint(Element shape, String element, String flag, String colorAttr, Color fallback) {
        Element e = child(shape, element);
        boolean on = e != null ? on(e.getAttribute("on")) : on(shape.getAttribute(flag));
        if (!on) {
            return null;
        }
        String spec = e != null && !e.getAttribute("color").isBlank() ? e.getAttribute("color")
                : shape.getAttribute(colorAttr);
        Color c = spec.isBlank() ? fallback : color(spec);
        if (c == null) {
            return null;
        }
        float opacity = e == null ? 1 : opacity(e.getAttribute("opacity"));
        return opacity >= 1 ? c : new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(opacity * 255));
    }

    private static boolean on(String v) {
        String s = v.strip().toLowerCase(Locale.ROOT);
        return s.equals("t") || s.equals("true");
    }

    private static Element child(Element shape, String name) {
        for (Node c = shape.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element e && VML.equals(e.getNamespaceURI()) && name.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    static Color color(String spec) {
        String s = spec.strip();
        int cut = s.indexOf(' ');
        s = cut > 0 ? s.substring(0, cut) : s;
        if (s.startsWith("#")) {
            String h = s.substring(1);
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            }
            try {
                return h.length() == 6 ? new Color(Integer.parseInt(h, 16)) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return DmlColors.preset(s);
    }

    private static float opacity(String v) {
        String s = v.strip();
        if (s.isEmpty()) {
            return 1;
        }
        try {
            float f = s.endsWith("f") ? Float.parseFloat(s.substring(0, s.length() - 1)) / 65536f : Float.parseFloat(s);
            return Float.isFinite(f) ? Math.max(0, Math.min(1, f)) : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static float weight(Element stroke) {
        float w = stroke == null ? Float.NaN : length(stroke.getAttribute("weight"));
        return Float.isFinite(w) && w > 0 ? Math.min(w, 100) : 0.75f;
    }

    // Left, top, width and height in points from an absolutely positioned shape's style
    static float[] box(String style) {
        float[] v = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};
        boolean absolute = false;
        for (String decl : style.split(";")) {
            int colon = decl.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = decl.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = decl.substring(colon + 1).strip().toLowerCase(Locale.ROOT);
            switch (key) {
                case "position" -> absolute = value.equals("absolute");
                case "left", "margin-left" -> v[0] = length(value);
                case "top", "margin-top" -> v[1] = length(value);
                case "width" -> v[2] = length(value);
                case "height" -> v[3] = length(value);
                case "visibility" -> {
                    if (value.equals("hidden")) {
                        return null;
                    }
                }
                default -> {
                }
            }
        }
        for (float f : v) {
            if (!Float.isFinite(f) || Math.abs(f) > 100_000) {
                return null;
            }
        }
        return absolute && v[2] > 0 && v[3] > 0 ? v : null;
    }

    private static float length(String s) {
        double scale;
        String n;
        if (s.endsWith("pt")) {
            scale = 1;
            n = s.substring(0, s.length() - 2);
        } else if (s.endsWith("in")) {
            scale = 72;
            n = s.substring(0, s.length() - 2);
        } else if (s.endsWith("cm")) {
            scale = 72 / 2.54;
            n = s.substring(0, s.length() - 2);
        } else if (s.endsWith("mm")) {
            scale = 72 / 25.4;
            n = s.substring(0, s.length() - 2);
        } else if (s.endsWith("px")) {
            scale = 0.75;
            n = s.substring(0, s.length() - 2);
        } else {
            scale = 0.75;
            n = s;
        }
        try {
            return (float) (Double.parseDouble(n.strip()) * scale);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    // Shape ids of controls and objects on the slide that carry their own picture, drawn with the slide already
    private static Set<String> pictured(Node root) {
        Set<String> out = new HashSet<>();
        Deque<Node> todo = new ArrayDeque<>();
        todo.push(root);
        int seen = 0;
        while (!todo.isEmpty() && seen++ < MAX_NODES) {
            Node n = todo.pop();
            if (n instanceof Element e && e.hasAttribute("spid") && hasPicture(e)) {
                out.add(e.getAttribute("spid").strip());
            }
            for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c.getNodeType() == Node.ELEMENT_NODE) {
                    todo.push(c);
                }
            }
        }
        return out;
    }

    private static boolean hasPicture(Element e) {
        Deque<Node> todo = new ArrayDeque<>();
        todo.push(e);
        int seen = 0;
        while (!todo.isEmpty() && seen++ < 10_000) {
            Node n = todo.pop();
            if (n != e && "pic".equals(n.getLocalName())) {
                return true;
            }
            for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c.getNodeType() == Node.ELEMENT_NODE) {
                    todo.push(c);
                }
            }
        }
        return false;
    }
}
