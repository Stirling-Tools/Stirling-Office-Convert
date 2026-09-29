package stirling.software.officeconvert.topdf.xlsx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.pdf.PdfCanvas;

final class CellPainter {

    record Box(double x0, double y0, double x1, double y1) {

        double width() {
            return x1 - x0;
        }

        double height() {
            return y1 - y0;
        }
    }

    private final Grid grid;

    private final Typesetter type;

    private final PdfCanvas canvas;

    CellPainter(Grid grid, PdfCanvas canvas) {
        this.grid = grid;
        this.type = grid.book().typesetter();
        this.canvas = canvas;
    }

    void paint(CellEntry cell, Box box, double spillLeft, double spillRight, double descent, Box clip)
            throws IOException {
        CellFormat f = cell.format();
        CellText text = cell.text();
        if (text == null || text.isEmpty() || box.width() <= 0 || box.height() <= 0) {
            return;
        }
        text = colored(text);
        int rotation = f.rotation();
        if (rotation != 0) {
            rotated(cell, text, box, rotation, clip);
            return;
        }
        CellFormat.HAlign h = CellLayout.horizontal(f, text);
        double indent = f.indent() > 0 ? f.indent() * grid.book().indentPoints() : 0;
        double pad = CellLayout.pad(type, text, f);
        double avail = box.width() - 2 * pad - indent;
        if (f.wraps() && h != CellFormat.HAlign.FILL) {
            wrapped(text, f, h, box, indent, pad, descent, clip);
            return;
        }
        double scale = 1;
        double width = type.width(text.runs(), 1);
        boolean hashes = false;
        if (text.numeric() && width > box.width() - 2 * pad + 0.01 && !f.shrink()) {
            text = fitNumber(text, box.width() - 2 * pad, box.width() - 2 * CellLayout.PAD);
            width = type.width(text.runs(), 1);
            hashes = isHashes(text.plain());
        }
        if (f.shrink() && width > avail && avail > 0) {
            scale = Math.max(0.05, avail / width);
            width *= scale;
        }
        if (h == CellFormat.HAlign.FILL) {
            text = filled(text, avail);
            width = type.width(text.runs(), 1);
            h = CellFormat.HAlign.LEFT;
        }
        double baseline = baseline(text, f.vAlign(), box, descent, scale);
        String plain = text.plain();
        int fillAt = fillMarker(plain);
        boolean overflow;
        double x;
        if (fillAt >= 0 && text.runs().size() == 1) {
            drawAccounting(text, fillAt, box, pad, baseline, scale);
            return;
        }
        switch (hashes ? CellFormat.HAlign.FILL : h) {
            case FILL -> {
                x = hashX(box, width);
                overflow = x < box.x0() || x + width > box.x1();
            }
            case RIGHT -> {
                x = box.x1() - pad - indent - width;
                overflow = x < box.x0();
            }
            case CENTER, CENTER_CONTINUOUS -> {
                x = box.x0() + (box.width() - width) / 2;
                overflow = width > box.width();
            }
            case DISTRIBUTED, JUSTIFY -> {
                x = box.x0() + pad + indent;
                overflow = width > avail;
            }
            default -> {
                x = box.x0() + pad + indent;
                overflow = x + width > box.x1();
            }
        }
        double top = baseline - maxAscent(text, scale);
        double bottom = baseline + maxDescent(text, scale);
        boolean needsClip = overflow || top < box.y0() - 0.5 || bottom > box.y1() + 0.5;
        if (needsClip) {
            canvas.save();
            double cx0 = Math.max(clip.x0(), box.x0() - spillLeft);
            double cx1 = Math.min(clip.x1(), box.x1() + spillRight);
            canvas.clipRect((float) cx0, (float) Math.max(clip.y0(), box.y0()), (float) (cx1 - cx0),
                    (float) (Math.min(clip.y1(), box.y1()) - Math.max(clip.y0(), box.y0())));
        }
        drawRuns(text.runs(), x, baseline, scale);
        if (needsClip) {
            canvas.restore();
        }
    }

    private CellText colored(CellText text) {
        if (text.color() == null) {
            return text;
        }
        List<TextRun> runs = new ArrayList<>();
        for (TextRun r : text.runs()) {
            runs.add(new TextRun(r.text(), r.font().color(text.color())));
        }
        return new CellText(text.kind(), runs, null, text.general(), text.number());
    }

    private double drawRuns(List<TextRun> runs, double x, double baseline, double scale) throws IOException {
        double at = x;
        for (TextRun r : runs) {
            double size = r.font().drawSize() * scale;
            double shift = shift(r.font(), scale);
            at += type.draw(canvas, r.text(), r.font(), size, at, baseline + shift);
        }
        return at - x;
    }

    private static double shift(FontSpec f, double scale) {
        return switch (f.offset()) {
            case SUPER -> -f.size() * scale * 0.33;
            case SUB -> f.size() * scale * 0.14;
            default -> 0;
        };
    }

    private double baseline(CellText text, CellFormat.VAlign v, Box box, double descent, double scale) {
        double pitch = 0;
        for (TextRun r : text.runs()) {
            pitch = Math.max(pitch, grid.linePitch(r.font()) * scale);
        }
        double d = descent * scale;
        return switch (v) {
            case TOP -> box.y0() + pitch - d;
            case CENTER, JUSTIFY, DISTRIBUTED -> box.y0() + (box.height() - pitch) / 2 + pitch - d;
            default -> box.y1() - d;
        };
    }

    private double maxAscent(CellText text, double scale) {
        double a = 0;
        for (TextRun r : text.runs()) {
            a = Math.max(a, type.measure(r.font()).ascent(r.font().drawSize() * scale));
        }
        return a;
    }

    private double maxDescent(CellText text, double scale) {
        double d = 0;
        for (TextRun r : text.runs()) {
            d = Math.max(d, type.measure(r.font()).descent(r.font().drawSize() * scale));
        }
        return d;
    }

    private void wrapped(CellText text, CellFormat f, CellFormat.HAlign h, Box box, double indent, double pad,
            double descent, Box clip) throws IOException {
        double avail = Math.max(1, box.width() - 2 * pad - indent);
        double scale = 1;
        List<CellLayout.Line> lines = CellLayout.wrap(type, text.runs(), avail + CellLayout.WRAP_SLACK, 1);
        double[] pitch = new double[lines.size()];
        double total = 0;
        for (int i = 0; i < lines.size(); i++) {
            double p = 0;
            for (TextRun r : lines.get(i).runs()) {
                p = Math.max(p, grid.linePitch(r.font()));
            }
            if (p == 0) {
                p = grid.linePitch(text.runs().get(0).font());
            }
            pitch[i] = p;
            total += p;
        }
        double y;
        double gap = 0;
        CellFormat.VAlign v = f.vAlign();
        switch (v) {
            case TOP -> y = box.y0();
            case CENTER -> y = box.y0() + (box.height() - total) / 2;
            case JUSTIFY, DISTRIBUTED -> {
                y = box.y0();
                if (lines.size() > 1 && total < box.height()) {
                    gap = (box.height() - total) / (lines.size() - 1);
                } else if (total < box.height()) {
                    y = box.y0() + (box.height() - total) / 2;
                }
            }
            default -> y = box.y1() - total;
        }
        if (total > box.height()) {
            y = box.y0();
        }
        boolean needsClip = total > box.height() + 0.5;
        for (CellLayout.Line line : lines) {
            if (line.width() > avail + 0.5) {
                needsClip = true;
            }
        }
        if (needsClip) {
            canvas.save();
            double cy0 = Math.max(clip.y0(), box.y0());
            double cx0 = Math.max(clip.x0(), box.x0());
            canvas.clipRect((float) cx0, (float) cy0, (float) (Math.min(clip.x1(), box.x1()) - cx0),
                    (float) (Math.min(clip.y1(), box.y1()) - cy0));
        }
        double d = descent;
        for (int i = 0; i < lines.size(); i++) {
            CellLayout.Line line = lines.get(i);
            double baseline = y + pitch[i] - Math.min(d, pitch[i] * 0.5);
            double x;
            switch (h) {
                case RIGHT -> x = box.x1() - pad - indent - line.width();
                case CENTER, CENTER_CONTINUOUS -> x = box.x0() + (box.width() - line.width()) / 2;
                default -> x = box.x0() + pad + indent;
            }
            boolean spread = (h == CellFormat.HAlign.JUSTIFY && !line.last())
                    || h == CellFormat.HAlign.DISTRIBUTED;
            if (spread && line.runs().size() == 1) {
                drawSpread(line.runs().get(0), box.x0() + pad + indent, avail, baseline);
            } else {
                drawRuns(line.runs(), x, baseline, scale);
            }
            y += pitch[i] + gap;
        }
        if (needsClip) {
            canvas.restore();
        }
    }

    private void drawSpread(TextRun run, double x, double avail, double baseline) throws IOException {
        String[] words = run.text().trim().split(" +");
        if (words.length < 2) {
            drawRuns(List.of(run), x, baseline, 1);
            return;
        }
        double size = run.font().drawSize();
        double total = 0;
        for (String w : words) {
            total += type.width(w, run.font(), size);
        }
        double gap = Math.max(type.width(" ", run.font(), size), (avail - total) / (words.length - 1));
        double at = x;
        for (String w : words) {
            at += type.draw(canvas, w, run.font(), size, at, baseline) + gap;
        }
    }

    private static double hashX(Box box, double width) {
        return box.x0() + CellLayout.CENTRED_SHIFT + (box.width() - width) / 2;
    }

    private static boolean isHashes(String s) {
        return !s.isEmpty() && s.chars().allMatch(c -> c == '#');
    }

    private CellText fitNumber(CellText text, double avail, double hashAvail) {
        if (text.general()) {
            for (int chars = 10; chars >= 1; chars--) {
                String s = ValueFormatter.general(text.number(), chars);
                CellText t = text.withText(s);
                if (type.width(t.runs(), 1) <= avail + 0.01) {
                    return t;
                }
            }
        }
        FontSpec f = text.runs().get(0).font();
        double hash = type.width("#", f, f.drawSize());
        int n = hash > 0 ? (int) Math.max(1, Math.floor(hashAvail / hash + 1e-9)) : 1;
        return text.withText("#".repeat(Math.min(n, 255)));
    }

    private CellText filled(CellText text, double avail) {
        String plain = text.plain();
        if (plain.isEmpty()) {
            return text;
        }
        FontSpec f = text.runs().get(0).font();
        double w = type.width(plain, f, f.drawSize());
        if (w <= 0) {
            return text;
        }
        int n = (int) Math.max(1, Math.floor(avail / w));
        return text.withText(plain.repeat(Math.min(n, 1000)));
    }

    private static int fillMarker(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (FormatCode.isFill(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private void drawAccounting(CellText text, int at, Box box, double pad, double baseline, double scale)
            throws IOException {
        TextRun run = text.runs().get(0);
        String s = run.text();
        String left = s.substring(0, at);
        String right = s.substring(at + 1);
        char fill = FormatCode.marked(s.charAt(at));
        FontSpec f = run.font();
        double size = f.drawSize() * scale;
        double wl = type.width(left, f, size);
        double wr = type.width(right, f, size);
        double avail = box.width() - 2 * pad;
        if (wl + wr > avail + 0.01) {
            CellText hashes = fitNumber(text.withText(left + right), avail, box.width() - 2 * CellLayout.PAD);
            double w = type.width(hashes.runs(), 1);
            drawRuns(hashes.runs(), isHashes(hashes.plain()) ? hashX(box, w) : box.x1() - pad - w, baseline, 1);
            return;
        }
        double xl = box.x0() + pad;
        double xr = box.x1() - pad - wr;
        type.draw(canvas, left, f, size, xl, baseline);
        type.draw(canvas, right, f, size, xr, baseline);
        if (fill != ' ' && fill != 0) {
            double fw = type.width(String.valueOf(fill), f, size);
            if (fw > 0) {
                int n = (int) Math.floor((xr - xl - wl) / fw);
                if (n > 0) {
                    type.draw(canvas, String.valueOf(fill).repeat(Math.min(n, 1000)), f, size, xl + wl, baseline);
                }
            }
        }
    }

    private void rotated(CellEntry cell, CellText text, Box box, int rotation, Box clip) throws IOException {
        CellFormat f = cell.format();
        canvas.save();
        canvas.clipRect((float) Math.max(clip.x0(), box.x0()), (float) Math.max(clip.y0(), box.y0()),
                (float) (Math.min(clip.x1(), box.x1()) - Math.max(clip.x0(), box.x0())),
                (float) (Math.min(clip.y1(), box.y1()) - Math.max(clip.y0(), box.y0())));
        try {
            if (rotation == 255) {
                stacked(text, f, box);
                return;
            }
            double angle = rotation <= 90 ? rotation : 90 - rotation;
            double rad = Math.toRadians(angle);
            double w = type.width(text.runs(), 1);
            double asc = maxAscent(text, 1);
            double desc = maxDescent(text, 1);
            double hgt = asc + desc;
            double cos = Math.abs(Math.cos(rad));
            double sin = Math.abs(Math.sin(rad));
            double bw = w * cos + hgt * sin;
            double bh = w * sin + hgt * cos;
            CellFormat.HAlign h = CellLayout.horizontal(f, text);
            // Vertical text keeps its baseline side against the cell edge: the right when it reads up, else the left
            if (f.hAlign() == CellFormat.HAlign.GENERAL) {
                h = angle > 0 ? CellFormat.HAlign.LEFT : angle < 0 ? CellFormat.HAlign.RIGHT : h;
                if (Math.abs(angle) == 90) {
                    h = angle > 0 ? CellFormat.HAlign.RIGHT : CellFormat.HAlign.LEFT;
                }
            }
            double bx = switch (h) {
                case RIGHT -> box.x1() - CellLayout.PAD - bw;
                case CENTER, CENTER_CONTINUOUS, DISTRIBUTED, JUSTIFY -> box.x0() + (box.width() - bw) / 2;
                default -> box.x0() + CellLayout.PAD;
            };
            double by = switch (f.vAlign()) {
                case TOP -> box.y0() + CellLayout.PAD;
                case CENTER, JUSTIFY, DISTRIBUTED -> box.y0() + (box.height() - bh) / 2;
                default -> box.y1() - CellLayout.PAD - bh;
            };
            double cx = bx + bw / 2;
            double cy = by + bh / 2;
            canvas.rotate((float) -angle, (float) cx, (float) cy);
            double x = cx - w / 2;
            double baseline = cy - hgt / 2 + asc;
            drawRuns(text.runs(), x, baseline, 1);
        } finally {
            canvas.restore();
        }
    }

    private void stacked(CellText text, CellFormat f, Box box) throws IOException {
        List<TextRun> chars = new ArrayList<>();
        for (TextRun r : text.runs()) {
            for (int i = 0; i < r.text().length(); ) {
                int cp = r.text().codePointAt(i);
                chars.add(new TextRun(new String(Character.toChars(cp)), r.font()));
                i += Character.charCount(cp);
            }
        }
        double total = 0;
        for (TextRun r : chars) {
            total += grid.linePitch(r.font());
        }
        double y = switch (f.vAlign()) {
            case TOP -> box.y0();
            case CENTER, JUSTIFY, DISTRIBUTED -> box.y0() + (box.height() - total) / 2;
            default -> box.y1() - total;
        };
        for (TextRun r : chars) {
            double p = grid.linePitch(r.font());
            double w = type.width(r.text(), r.font(), r.font().drawSize());
            double asc = type.measure(r.font()).ascent(r.font().drawSize());
            type.draw(canvas, r.text(), r.font(), r.font().drawSize(), box.x0() + (box.width() - w) / 2,
                    y + (p - asc) / 2 + asc * 0.9);
            y += p;
        }
    }
}
