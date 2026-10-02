package stirling.software.officeconvert.pdfa;

import java.io.IOException;

final class AttachmentSyntax {

    private AttachmentSyntax() {}

    static void check(byte[] bytes) throws IOException {
        int depth = 0;
        int literal = 0;
        boolean comment = false;
        boolean hex = false;
        for (int i = 0; i < bytes.length; i++) {
            int c = bytes[i] & 255;
            if (comment) {
                comment = c != '\n' && c != '\r';
            } else if (literal > 0) {
                if (c == '\\') {
                    i++;
                } else if (c == '(') {
                    literal++;
                } else if (c == ')') {
                    literal--;
                }
            } else if (hex) {
                hex = c != '>';
            } else if (c == '%') {
                comment = true;
            } else if (c == '(') {
                literal = 1;
            } else if (c == '<' && i + 1 < bytes.length && bytes[i + 1] == '<') {
                depth++;
                i++;
            } else if (c == '<') {
                hex = true;
            } else if (c == '>' && i + 1 < bytes.length && bytes[i + 1] == '>') {
                depth--;
                i++;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
            if (depth > 64 || depth < 0) {
                throw new IOException("An attachment object stream is too deeply nested or invalid");
            }
        }
        if (depth != 0 || literal != 0 || hex) {
            throw new IOException("An attachment object stream has unbalanced delimiters");
        }
    }
}
