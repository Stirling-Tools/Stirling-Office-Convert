package stirling.software.officeconvert.topdf.rtf;

import java.util.List;

import stirling.software.officeconvert.topdf.field.DatePicture;

final class DateField {

    private DateField() {}

    static boolean dated(String kind) {
        return DatePicture.dated(kind);
    }

    static String text(String kind, List<String> args, int[] created, int[] saved, int[] printed) {
        int[] t = switch (kind) {
            case "CREATEDATE" -> created;
            case "PRINTDATE" -> printed;
            default -> saved != null ? saved : created;
        };
        return DatePicture.text(kind, args, t);
    }
}
