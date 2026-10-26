package stirling.software.officeconvert.optimize;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSName;

final class Content {

    enum Category {
        FONT(COSName.FONT), XOBJECT(COSName.XOBJECT), EXT_G_STATE(COSName.EXT_G_STATE), SHADING(COSName.SHADING),
        PATTERN(COSName.PATTERN), COLOR_SPACE(COSName.COLORSPACE), PROPERTIES(COSName.PROPERTIES);

        final COSName key;

        Category(COSName key) {
            this.key = key;
        }
    }

    record Usage(Map<Category, Set<COSName>> names, boolean complete) {

        boolean any() {
            return names.values().stream().anyMatch(s -> !s.isEmpty());
        }

        static Usage unknown() {
            return new Usage(new EnumMap<>(Category.class), false);
        }
    }

    private Content() {}

    static byte[] minify(byte[] in) {
        try {
            int count = 0;
            ContentLexer counter = new ContentLexer(in);
            while (counter.next()) {
                if (counter.operator("BI") || counter.operator("ID") || counter.operator("EI")) {
                    return null;
                }
                count++;
            }
            if (counter.commentAtEnd()) {
                return null;
            }
            boolean leading = in.length > 0 && Lexical.whitespace(in[0] & 0xFF);
            boolean trailing = in.length > 0 && Lexical.whitespace(in[in.length - 1] & 0xFF);
            if (count == 0) {
                return leading || trailing ? new byte[] {'\n'} : in;
            }
            ContentLexer lex = new ContentLexer(in);
            Bytes out = new Bytes(in.length);
            if (leading) {
                out.write('\n');
            }
            boolean regularEnd = false;
            for (int t = 0; t < count; t++) {
                lex.next();
                boolean edge = t == 0 && !leading || t == count - 1 && !trailing;
                byte[] form = edge ? raw(lex) : shortest(lex);
                if (regularEnd && Lexical.regular(form[0] & 0xFF)) {
                    out.write(' ');
                }
                out.write(form);
                regularEnd = Lexical.regular(form[form.length - 1] & 0xFF);
                boolean operator = lex.kind() == ContentLexer.Kind.OPERATOR;
                if (t == count - 1) {
                    if (trailing) {
                        out.write('\n');
                    }
                } else if (operator) {
                    out.write('\n');
                    regularEnd = false;
                }
            }
            return out.toByteArray();
        } catch (ContentLexer.Malformed e) {
            return null;
        }
    }

    private static byte[] raw(ContentLexer lex) {
        byte[] b = new byte[lex.end() - lex.start()];
        System.arraycopy(lex.input(), lex.start(), b, 0, b.length);
        return b;
    }

    private static byte[] shortest(ContentLexer lex) {
        return switch (lex.kind()) {
            case NUMBER -> Lexical.plainNumber(lex.text()).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            case STRING -> {
                Bytes b = new Bytes(lex.end() - lex.start());
                Lexical.string(lex.stringBytes(), b);
                yield b.toByteArray();
            }
            default -> raw(lex);
        };
    }

    static Usage scan(byte[] in) {
        Map<Category, Set<COSName>> names = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            names.put(c, new HashSet<>());
        }
        List<COSName> operandNames = new ArrayList<>();
        List<ContentLexer.Kind> operandKinds = new ArrayList<>();
        try {
            ContentLexer lex = new ContentLexer(in);
            while (lex.next()) {
                ContentLexer.Kind kind = lex.kind();
                if (kind != ContentLexer.Kind.OPERATOR) {
                    operandKinds.add(kind);
                    operandNames.add(kind == ContentLexer.Kind.NAME ? COSName.getPDFName(lex.nameBytes()) : null);
                    continue;
                }
                if (lex.operator("BI") || lex.operator("ID") || lex.operator("EI")) {
                    return new Usage(names, false);
                }
                Category category = null;
                int operand = -1;
                if (lex.operator("Tf")) {
                    category = Category.FONT;
                    operand = operandNames.size() - 2;
                } else if (lex.operator("Do")) {
                    category = Category.XOBJECT;
                    operand = operandNames.size() - 1;
                } else if (lex.operator("gs")) {
                    category = Category.EXT_G_STATE;
                    operand = operandNames.size() - 1;
                } else if (lex.operator("sh")) {
                    category = Category.SHADING;
                    operand = operandNames.size() - 1;
                } else if (lex.operator("cs") || lex.operator("CS")) {
                    category = Category.COLOR_SPACE;
                    operand = operandNames.size() - 1;
                } else if (lex.operator("scn") || lex.operator("SCN")) {
                    category = Category.PATTERN;
                    operand = operandNames.size() - 1;
                } else if (lex.operator("BDC") || lex.operator("DP")) {
                    category = Category.PROPERTIES;
                    operand = operandNames.size() - 1;
                }
                if (category != null) {
                    for (int i = 0; i < operandNames.size(); i++) {
                        COSName n = operandNames.get(i);
                        if (n != null && (i == operand || category != Category.PATTERN
                                && category != Category.PROPERTIES)) {
                            names.get(category).add(n);
                        }
                    }
                }
                operandNames.clear();
                operandKinds.clear();
            }
            if (lex.commentAtEnd()) {
                return new Usage(names, true);
            }
        } catch (ContentLexer.Malformed e) {
            return new Usage(names, false);
        }
        return new Usage(names, true);
    }
}
