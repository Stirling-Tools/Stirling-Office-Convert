package stirling.software.officeconvert.topdf.html;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;

final class MarginBoxes {

    private static final String[] PLACES = {"left", "center", "right"};

    private static final int MAX_CONTENT_CHARS = 2_000;

    private MarginBoxes() {}

    static boolean any(Map<String, Map<String, String>> boxes) {
        for (Map<String, String> d : boxes.values()) {
            if (!content(d.get("content"), null).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    static DocSink.HeaderFooterSet running(Map<String, Map<String, String>> boxes, Look base, float width) {
        return new DocSink.HeaderFooterSet(edge(boxes, "top", base, width), edge(boxes, "bottom", base, width),
                List.of(), List.of(), false);
    }

    private static List<Paragraph> edge(Map<String, Map<String, String>> boxes, String edge, Look base, float width) {
        Map<String, List<Inline>> parts = new LinkedHashMap<>();
        RunStyle last = base.run();
        for (String place : PLACES) {
            Map<String, String> d = boxes.get(edge + "-" + place);
            if (d == null) {
                continue;
            }
            Map<String, String> own = new HashMap<>(d);
            Css.expandShorthands(own);
            RunStyle style = base.child(own, width, false).run();
            List<Inline> inlines = content(own.get("content"), style);
            if (!inlines.isEmpty()) {
                parts.put(place, inlines);
                last = style;
            }
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        Paragraph p = new Paragraph();
        if (parts.size() == 1) {
            String place = parts.keySet().iterator().next();
            p.align = switch (place) {
                case "center" -> Paragraph.Align.CENTER;
                case "right" -> Paragraph.Align.RIGHT;
                default -> Paragraph.Align.LEFT;
            };
            p.inlines.addAll(parts.get(place));
        } else {
            p.tabs.add(new Paragraph.TabStop(width / 2, Paragraph.TabStop.Kind.CENTER, (char) 0));
            p.tabs.add(new Paragraph.TabStop(width, Paragraph.TabStop.Kind.RIGHT, (char) 0));
            p.inlines.addAll(parts.getOrDefault("left", List.of()));
            p.inlines.add(new Inline.Tab(last));
            p.inlines.addAll(parts.getOrDefault("center", List.of()));
            p.inlines.add(new Inline.Tab(last));
            p.inlines.addAll(parts.getOrDefault("right", List.of()));
        }
        return List.of(p);
    }

    static List<Inline> content(String value, RunStyle style) {
        List<Inline> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        String v = value.strip();
        if (v.isEmpty() || v.equalsIgnoreCase("none") || v.equalsIgnoreCase("normal") || v.length() > MAX_CONTENT_CHARS) {
            return out;
        }
        int i = 0;
        while (i < v.length()) {
            char c = v.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"' || c == '\'') {
                StringBuilder text = new StringBuilder();
                int j = i + 1;
                while (j < v.length() && v.charAt(j) != c) {
                    char k = v.charAt(j);
                    if (k == '\\' && j + 1 < v.length()) {
                        int h = j + 1;
                        while (h < v.length() && h - j <= 6 && Character.digit(v.charAt(h), 16) >= 0) {
                            h++;
                        }
                        if (h > j + 1) {
                            int cp = Integer.parseInt(v.substring(j + 1, h), 16);
                            text.appendCodePoint(cp == 0xA || cp == 0 || cp > 0x10FFFF
                                    || cp >= 0xD800 && cp <= 0xDFFF ? ' ' : cp);
                            j = h < v.length() && Character.isWhitespace(v.charAt(h)) ? h + 1 : h;
                            continue;
                        }
                        char e = v.charAt(j + 1);
                        if (e != '\n') {
                            text.append(e);
                        }
                        j += 2;
                        continue;
                    }
                    text.append(k);
                    j++;
                }
                if (!text.isEmpty()) {
                    out.add(new Inline.Text(text.toString(), style, null, -1));
                }
                i = j + 1;
            } else {
                int j = i;
                int depth = 0;
                while (j < v.length() && (depth > 0 || !Character.isWhitespace(v.charAt(j)))) {
                    if (v.charAt(j) == '(') {
                        depth++;
                    } else if (v.charAt(j) == ')') {
                        depth--;
                    }
                    j++;
                }
                String token = v.substring(i, j).toLowerCase(Locale.ROOT).replace(" ", "");
                if (token.startsWith("counter(page") && !token.startsWith("counter(pages")) {
                    out.add(new Inline.PageNumber(style, false));
                } else if (token.startsWith("counter(pages")) {
                    out.add(new Inline.PageNumber(style, true));
                }
                i = j;
            }
        }
        return out;
    }
}
