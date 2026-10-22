package stirling.software.officeconvert.topdf.pdf;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;

import stirling.software.officeconvert.model.FormField;

final class AcroForms {

    record Widget(PDPage page, PDRectangle rect, FormField field, float size, Color color, PDFormXObject look) {}

    private static final COSName FONT = COSName.getPDFName("Helv");

    private static final int READ_ONLY = 1;
    private static final int REQUIRED = 1 << 1;
    private static final int MULTILINE = 1 << 12;
    private static final int PASSWORD = 1 << 13;
    private static final int NO_TOGGLE_TO_OFF = 1 << 14;
    private static final int RADIO = 1 << 15;
    private static final int COMBO = 1 << 17;
    private static final int MULTI_SELECT = 1 << 21;

    private final List<Widget> widgets = new ArrayList<>();

    void add(Widget w) {
        widgets.add(w);
    }

    boolean isEmpty() {
        return widgets.isEmpty();
    }

    void write(PDDocument document) throws IOException {
        if (widgets.isEmpty()) {
            return;
        }
        PDAcroForm form = new PDAcroForm(document);
        PDResources resources = new PDResources();
        resources.put(FONT, new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        form.setDefaultResources(resources);
        form.setDefaultAppearance("/Helv 0 Tf 0 g");
        Names names = new Names(form);
        Map<String, PDRadioButton> groups = new HashMap<>();
        Map<PDRadioButton, List<PDAnnotationWidget>> kids = new LinkedHashMap<>();
        int unnamed = 0;
        for (Widget w : widgets) {
            FormField f = w.field();
            String name = f.name().isBlank() ? "unnamed-" + ++unnamed : f.name();
            if (f.kind() == FormField.Kind.RADIO) {
                PDRadioButton group = groups.get(name);
                if (group == null) {
                    group = new PDRadioButton(form);
                    group.getCOSObject().setInt(COSName.FF, RADIO | NO_TOGGLE_TO_OFF | flags(f));
                    names.place(group, name, f);
                    group.getCOSObject().setItem(COSName.V, COSName.Off);
                    groups.put(name, group);
                    kids.put(group, new ArrayList<>());
                }
                PDAnnotationWidget widget = widget(w);
                widget.getCOSObject().setItem(COSName.PARENT, group);
                String on = stateName(f.value());
                widget.setAppearance(states(document, w, on));
                widget.setAppearanceState(f.checked() ? on : COSName.Off.getName());
                if (f.checked()) {
                    group.getCOSObject().setName(COSName.V, on);
                }
                kids.get(group).add(widget);
                PdfCanvas.addAnnotation(w.page(), widget);
                continue;
            }
            PDTerminalField field = field(form, w);
            names.place(field, name, f);
            PDAnnotationWidget widget = field.getWidgets().get(0);
            fillWidget(widget, w);
            if (f.kind() == FormField.Kind.CHECKBOX) {
                String on = stateName(f.value());
                widget.setAppearance(states(document, w, on));
                widget.setAppearanceState(f.checked() ? on : COSName.Off.getName());
                field.getCOSObject().setName(COSName.V, f.checked() ? on : COSName.Off.getName());
            } else if (w.look() != null) {
                PDAppearanceDictionary ap = new PDAppearanceDictionary();
                ap.setNormalAppearance(new PDAppearanceStream(w.look().getCOSObject()));
                widget.setAppearance(ap);
            }
            PdfCanvas.addAnnotation(w.page(), widget);
        }
        for (Map.Entry<PDRadioButton, List<PDAnnotationWidget>> e : kids.entrySet()) {
            e.getKey().setWidgets(e.getValue());
        }
        form.setFields(names.top);
        document.getDocumentCatalog().setAcroForm(form);
    }

    private static PDTerminalField field(PDAcroForm form, Widget w) {
        FormField f = w.field();
        String da = appearance(w);
        int flags = flags(f);
        return switch (f.kind()) {
            case CHECKBOX -> {
                PDCheckBox box = new PDCheckBox(form);
                box.getCOSObject().setInt(COSName.FF, flags);
                yield box;
            }
            case COMBO, LIST -> {
                boolean list = f.kind() == FormField.Kind.LIST;
                PDTerminalField choice = list ? new PDListBox(form) : new PDComboBox(form);
                COSDictionary d = choice.getCOSObject();
                d.setInt(COSName.FF, flags | (list ? MULTI_SELECT : COMBO));
                d.setString(COSName.DA, da);
                COSArray opt = new COSArray();
                COSArray chosen = new COSArray();
                COSArray indices = new COSArray();
                for (int i = 0; i < f.choices().size(); i++) {
                    FormField.Choice c = f.choices().get(i);
                    if (c.value().equals(c.label())) {
                        opt.add(new COSString(c.value()));
                    } else {
                        COSArray pair = new COSArray();
                        pair.add(new COSString(c.value()));
                        pair.add(new COSString(c.label()));
                        opt.add(pair);
                    }
                    if (c.selected()) {
                        chosen.add(new COSString(c.value()));
                        indices.add(org.apache.pdfbox.cos.COSInteger.get(i));
                    }
                }
                d.setItem(COSName.OPT, opt);
                if (list) {
                    d.setItem(COSName.V, chosen);
                    d.setItem(COSName.I, indices);
                } else if (chosen.size() > 0) {
                    d.setItem(COSName.V, chosen.get(0));
                    d.setItem(COSName.I, indices);
                }
                yield choice;
            }
            default -> {
                PDTextField text = new PDTextField(form);
                COSDictionary d = text.getCOSObject();
                int kind = f.kind() == FormField.Kind.MULTILINE ? MULTILINE
                        : f.kind() == FormField.Kind.PASSWORD ? PASSWORD : 0;
                d.setInt(COSName.FF, flags | kind);
                d.setString(COSName.DA, da);
                if (!f.value().isEmpty()) {
                    d.setString(COSName.V, f.value());
                }
                if (f.maxLength() > 0) {
                    d.setInt(COSName.MAX_LEN, f.maxLength());
                }
                yield text;
            }
        };
    }

    private static int flags(FormField f) {
        return (f.readOnly() ? READ_ONLY : 0) | (f.required() ? REQUIRED : 0);
    }

    private static PDAnnotationWidget widget(Widget w) {
        PDAnnotationWidget widget = new PDAnnotationWidget();
        fillWidget(widget, w);
        return widget;
    }

    private static void fillWidget(PDAnnotationWidget widget, Widget w) {
        widget.setRectangle(w.rect());
        widget.setPage(w.page());
        widget.setPrinted(true);
        widget.setBorderStyle(PdfOutput.noBorder());
    }

    private static String appearance(Widget w) {
        float[] rgb = w.color().getRGBColorComponents(null);
        return "/Helv " + num(w.size()) + " Tf " + num(rgb[0]) + " " + num(rgb[1]) + " " + num(rgb[2]) + " rg";
    }

    private static String stateName(String value) {
        String v = value.isEmpty() ? "on" : value;
        return v.equals(COSName.Off.getName()) ? v + "_" : v;
    }

    private static PDAppearanceDictionary states(PDDocument document, Widget w, String on) throws IOException {
        PDRectangle r = w.rect();
        COSDictionary normal = new COSDictionary();
        normal.setItem(COSName.getPDFName(on), mark(document, r.getWidth(), r.getHeight(), w.color(),
                w.field().kind() == FormField.Kind.RADIO));
        normal.setItem(COSName.Off, mark(document, r.getWidth(), r.getHeight(), null, false));
        PDAppearanceDictionary ap = new PDAppearanceDictionary();
        ap.getCOSObject().setItem(COSName.N, normal);
        return ap;
    }

    private static PDAppearanceStream mark(PDDocument document, float w, float h, Color color, boolean dot)
            throws IOException {
        PDAppearanceStream s = new PDAppearanceStream(document);
        s.setBBox(new PDRectangle(w, h));
        s.setResources(new PDResources());
        StringBuilder ops = new StringBuilder();
        if (color != null) {
            float[] rgb = color.getRGBColorComponents(null);
            String paint = num(rgb[0]) + " " + num(rgb[1]) + " " + num(rgb[2]);
            if (dot) {
                float rx = w / 2;
                float ry = h / 2;
                float k = 0.5523f;
                ops.append(paint).append(" rg\n").append(pt(w, ry)).append(" m\n")
                        .append(pt(w, ry + ry * k)).append(' ').append(pt(rx + rx * k, h)).append(' ').append(pt(rx, h)).append(" c\n")
                        .append(pt(rx - rx * k, h)).append(' ').append(pt(0, ry + ry * k)).append(' ').append(pt(0, ry)).append(" c\n")
                        .append(pt(0, ry - ry * k)).append(' ').append(pt(rx - rx * k, 0)).append(' ').append(pt(rx, 0)).append(" c\n")
                        .append(pt(rx + rx * k, 0)).append(' ').append(pt(w, ry - ry * k)).append(' ').append(pt(w, ry)).append(" c\nf\n");
            } else {
                float stroke = Math.max(0.5f, Math.min(w, h) / 7);
                ops.append(paint).append(" RG ").append(num(stroke)).append(" w 1 J 1 j\n")
                        .append(pt(w * 0.12f, h * 0.5f)).append(" m ").append(pt(w * 0.4f, h * 0.18f)).append(" l ")
                        .append(pt(w * 0.9f, h * 0.85f)).append(" l S\n");
            }
        }
        try (OutputStream out = s.getContentStream().createOutputStream()) {
            out.write(ops.toString().getBytes(StandardCharsets.US_ASCII));
        }
        return s;
    }

    private static String num(float v) {
        return Numbers.append(new StringBuilder(), v).toString();
    }

    private static String pt(float x, float y) {
        return num(x) + " " + num(y);
    }

    private static final class Names {

        private final PDAcroForm form;
        private final Map<String, PDNonTerminalField> parents = new HashMap<>();
        private final Set<String> used = new HashSet<>();
        private final List<PDField> top = new ArrayList<>();
        private final Map<String, Integer> repeats = new HashMap<>();

        Names(PDAcroForm form) {
            this.form = form;
        }

        void place(PDField field, String name, FormField f) {
            String[] parts = name.split("\\.", -1);
            boolean nested = parts.length > 1 && !hasEmpty(parts);
            String path = "";
            for (int i = 0; nested && i < parts.length - 1; i++) {
                path = path.isEmpty() ? parts[i] : path + "." + parts[i];
                if (used.contains(path) && !parents.containsKey(path)) {
                    nested = false;
                }
            }
            PDNonTerminalField parent = null;
            String leaf = name;
            if (nested) {
                String at = "";
                for (int i = 0; i < parts.length - 1; i++) {
                    at = at.isEmpty() ? parts[i] : at + "." + parts[i];
                    PDNonTerminalField p = parents.get(at);
                    if (p == null) {
                        p = new PDNonTerminalField(form);
                        p.setPartialName(parts[i]);
                        attach(p, parent);
                        parents.put(at, p);
                        used.add(at);
                    }
                    parent = p;
                }
                leaf = parts[parts.length - 1];
            } else {
                leaf = name.replace('.', '_');
            }
            String prefix = parent == null ? "" : parent.getFullyQualifiedName() + ".";
            String unique = leaf;
            while (used.contains(prefix + unique) || parents.containsKey(prefix + unique)) {
                int n = repeats.merge(prefix + leaf, 1, Integer::sum) + 1;
                unique = leaf + "_" + n;
            }
            used.add(prefix + unique);
            field.setPartialName(unique);
            if (!f.label().isEmpty()) {
                field.setAlternateFieldName(f.label());
            }
            attach(field, parent);
        }

        private void attach(PDField field, PDNonTerminalField parent) {
            if (parent == null) {
                top.add(field);
                return;
            }
            field.getCOSObject().setItem(COSName.PARENT, parent);
            List<PDField> children = new ArrayList<>(parent.getChildren());
            children.add(field);
            parent.setChildren(children);
        }

        private static boolean hasEmpty(String[] parts) {
            for (String p : parts) {
                if (p.isEmpty()) {
                    return true;
                }
            }
            return false;
        }
    }
}
