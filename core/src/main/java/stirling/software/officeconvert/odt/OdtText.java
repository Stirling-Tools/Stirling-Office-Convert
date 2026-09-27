package stirling.software.officeconvert.odt;

final class OdtText {

    private boolean afterSpace = true;

    void append(StringBuilder sb, String s) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ') {
                int run = 0;
                while (i < n && s.charAt(i) == ' ') {
                    run++;
                    i++;
                }
                if (!afterSpace) {
                    sb.append(' ');
                    run--;
                }
                if (run == 1) {
                    sb.append("<text:s/>");
                } else if (run > 1) {
                    sb.append("<text:s text:c=\"").append(run).append("\"/>");
                }
                afterSpace = true;
                continue;
            }
            if (c == '\t') {
                tab(sb);
            } else if (c == '\n') {
                lineBreak(sb);
            } else if (c != '\r') {
                int end = i + 1;
                while (end < n && " \t\n\r".indexOf(s.charAt(end)) < 0) {
                    end++;
                }
                OdtXml.esc(sb, s.substring(i, end));
                afterSpace = false;
                i = end;
                continue;
            }
            i++;
        }
    }

    void tab(StringBuilder sb) {
        sb.append("<text:tab/>");
        afterSpace = false;
    }

    void lineBreak(StringBuilder sb) {
        sb.append("<text:line-break/>");
        afterSpace = true;
    }

    void content() {
        afterSpace = false;
    }
}
