package stirling.software.officeconvert.topdf.doc;

final class Stories {

    private Stories() {}

    static int trim(CharSequence text, int start, int end) {
        int e = Math.min(end, text.length());
        if (e - start >= 2 && text.charAt(e - 1) == '\r' && text.charAt(e - 2) == '\r') {
            return e - 1;
        }
        return e;
    }
}
