package stirling.software.officeconvert.rtf;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;

final class RtfParts {

    static final String COMPAT = "\\noxlattoyen\\expshrtn\\noultrlspc\\dntblnsbdb\\nospaceforul\\formshade\\horzdoc"
            + "\\dgmargin\\dghspace180\\dgvspace180\\dghorigin1440\\dgvorigin1440\\dghshow1\\dgvshow1\\jexpand\\viewkind1"
            + "\\viewscale100\\pgbrdrhead\\pgbrdrfoot\\splytwnine\\ftnlytwnine\\htmautsp\\nolnhtadjtbl\\useltbaln\\alntblind"
            + "\\lytcalctblwd\\lyttblrtgr\\lnbrkrule\\nobrkwrptbl\\snaptogridincell\\allowfieldendsel\\wrppunct\\asianbrkrule"
            + "\\newtblstyruls\\nogrowautofit\\usenormstyforlist\\noindnmbrts\\felnbrelev\\nocxsptable\\indrlsweleven"
            + "\\noafcnsttbl\\afelev\\utinl\\hwelev\\spltpgpar\\notcvasp\\notbrkcnstfrctbl\\notvatxbx\\krnprsnet\\cachedcolbal"
            + " \\nouicompat ";

    private RtfParts() {}

    static void stylesheet(StringBuilder sb, StyleSheet sheet, Map<String, Integer> numbers, RtfBody body) {
        sb.append("{\\stylesheet");
        for (StyleSheet.Style s : sheet.all()) {
            body.styleNumber(s.id());
        }
        for (Map.Entry<String, Integer> e : numbers.entrySet()) {
            StyleSheet.Style s = sheet.get(e.getKey());
            int n = e.getValue();
            sb.append('{');
            if (n != 0) {
                sb.append("\\s").append(n);
            }
            sb.append("\\ql\\li0\\ri0\\sl240\\slmult1\\nowidctlpar");
            if (s.id().equals("ListParagraph")) {
                sb.append("\\li720\\lin720");
            }
            if (s.outlineLevel() >= 0) {
                sb.append("\\outlinelevel").append(Math.min(8, s.outlineLevel()));
            }
            RunStyle run = s.run() != null ? s.run() : sheet.normal;
            sb.append(' ').append(body.props(run.font() == null ? run.withFont(sheet.normal.font()) : run, false));
            if (n != 0) {
                sb.append("\\sbasedon0");
            }
            sb.append("\\snext0\\sqformat ");
            RtfText.text(sb, s.name());
            sb.append(";}");
        }
        sb.append("{\\*\\cs").append(numbers.size()).append(" \\additive\\ssemihidden Default Paragraph Font;}}");
    }

    static void lists(StringBuilder sb, Numbering numbering, StyleSheet sheet, RtfBody body) {
        if (numbering.isEmpty()) {
            return;
        }
        sb.append("{\\*\\listtable");
        for (Numbering.Instance inst : numbering.instances) {
            sb.append("{\\list\\listtemplateid").append(inst.numId).append("\\listhybrid");
            for (int lvl = 0; lvl < 9; lvl++) {
                Numbering.Level l = inst.definition.levels[lvl];
                String format = l != null ? l.format() : "decimal";
                String text = l != null ? l.text() : "%" + (lvl + 1) + ".";
                float left = l != null ? l.indentLeft() : 36f * (lvl + 1);
                float hanging = l != null ? l.hanging() : 18f;
                int nfc = nfc(format);
                sb.append("{\\listlevel\\levelnfc").append(nfc).append("\\levelnfcn").append(nfc)
                        .append("\\leveljc0\\leveljcn0\\levelfollow0\\levelstartat").append(Math.max(0, inst.starts[lvl]))
                        .append("\\levelspace0\\levelindent0");
                levelText(sb, "bullet".equals(format), text);
                if (l != null && l.markerStyle() != null) {
                    RunStyle m = l.markerStyle();
                    RunStyle marker = m.font() == null ? m.withFont(sheet.normal.font()) : m;
                    sb.append(body.props(marker.withVertAlign(0), false));
                }
                sb.append("\\fi-").append(RtfText.twips(hanging)).append("\\li").append(RtfText.twips(left))
                        .append("\\lin").append(RtfText.twips(left)).append(" }");
            }
            sb.append("{\\listname ;}\\listid").append(inst.numId).append('}');
        }
        sb.append("}{\\*\\listoverridetable");
        for (Numbering.Instance inst : numbering.instances) {
            sb.append("{\\listoverride\\listid").append(inst.numId).append("\\listoverridecount0\\ls").append(inst.numId).append('}');
        }
        sb.append('}');
    }

    private static void levelText(StringBuilder sb, boolean bullet, String text) {
        StringBuilder t = new StringBuilder();
        StringBuilder positions = new StringBuilder();
        int length = 0;
        if (bullet) {
            String ch = text.isEmpty() ? "\u2022" : text.substring(0, text.offsetByCodePoints(0, 1));
            RtfText.text(t, ch);
            length = ch.length();
        } else {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '%' && i + 1 < text.length() && text.charAt(i + 1) >= '1' && text.charAt(i + 1) <= '9') {
                    int level = text.charAt(++i) - '1';
                    t.append(String.format("\\'%02x", level));
                    length++;
                    positions.append(String.format("\\'%02x", length));
                } else {
                    RtfText.text(t, String.valueOf(c));
                    length++;
                }
            }
        }
        sb.append("{\\leveltext\\'").append(String.format("%02x", Math.min(255, length))).append(t).append(";}{\\levelnumbers")
                .append(positions).append(";}");
    }

    private static int nfc(String format) {
        return switch (format) {
            case "upperRoman" -> 1;
            case "lowerRoman" -> 2;
            case "upperLetter" -> 3;
            case "lowerLetter" -> 4;
            case "bullet" -> 23;
            case "hebrew1" -> 45;
            case "arabicAlpha" -> 46;
            case "arabicAbjad" -> 48;
            case "chineseCounting" -> 39;
            case "decimalFullWidth" -> 14;
            case "ganada" -> 24;
            case "none" -> 255;
            default -> 0;
        };
    }

    static void info(StringBuilder sb, String title, String author) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        sb.append("{\\info");
        if (title != null && !title.isBlank()) {
            sb.append("{\\title ");
            RtfText.text(sb, title);
            sb.append('}');
        }
        if (author != null && !author.isBlank()) {
            sb.append("{\\author ");
            RtfText.text(sb, author);
            sb.append('}');
        }
        sb.append("{\\creatim\\yr").append(now.getYear()).append("\\mo").append(now.getMonthValue()).append("\\dy")
                .append(now.getDayOfMonth()).append("\\hr").append(now.getHour()).append("\\min").append(now.getMinute())
                .append("}{\\*\\company Stirling-PDF}}");
    }

    static void page(StringBuilder sb, Section s) {
        sb.append("\\pgwsxn").append(RtfText.twips(s.pageWidth)).append("\\pghsxn").append(RtfText.twips(s.pageHeight));
        if (s.pageWidth > s.pageHeight) {
            sb.append("\\lndscpsxn");
        }
        sb.append("\\marglsxn").append(RtfText.twips(s.marginLeft)).append("\\margrsxn").append(RtfText.twips(s.marginRight))
                .append("\\margtsxn").append(RtfText.twips(s.marginTop)).append("\\margbsxn").append(RtfText.twips(s.marginBottom))
                .append("\\headery").append(RtfText.twips(s.headerDistance)).append("\\footery").append(RtfText.twips(s.footerDistance));
        if (s.columns.size() > 1) {
            sb.append("\\cols").append(s.columns.size()).append("\\colsx").append(RtfText.twips(s.columns.getFirst()[1]));
            for (int i = 0; i < s.columns.size(); i++) {
                sb.append("\\colno").append(i + 1).append("\\colw").append(RtfText.twips(s.columns.get(i)[0]));
                if (i + 1 < s.columns.size()) {
                    sb.append("\\colsr").append(RtfText.twips(s.columns.get(i)[1]));
                }
            }
        }
    }

    static void document(StringBuilder sb, Section s, boolean facing) {
        sb.append("\\paperw").append(RtfText.twips(s.pageWidth)).append("\\paperh").append(RtfText.twips(s.pageHeight))
                .append("\\margl").append(RtfText.twips(s.marginLeft)).append("\\margr").append(RtfText.twips(s.marginRight))
                .append("\\margt").append(RtfText.twips(s.marginTop)).append("\\margb").append(RtfText.twips(s.marginBottom))
                .append("\\gutter0\\ltrsect");
        if (s.pageWidth > s.pageHeight) {
            sb.append("\\landscape");
        }
        if (facing) {
            sb.append("\\facingp");
        }
        sb.append("\\deftab720\\ftnbj\\ftnnar\\ftnstart1\\ftnrstcont\\fet0").append(COMPAT);
    }
}
