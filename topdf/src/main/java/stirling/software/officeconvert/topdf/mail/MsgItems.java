package stirling.software.officeconvert.topdf.mail;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.datatypes.ByteChunk;
import org.apache.poi.hsmf.datatypes.Chunk;
import org.apache.poi.hsmf.datatypes.Chunks;
import org.apache.poi.hsmf.datatypes.NameIdChunks;

final class MsgItems {

    static final String APPOINTMENT = "00062002-0000-0000-C000-000000000046";
    static final String TASK = "00062003-0000-0000-C000-000000000046";
    static final String ADDRESS = "00062004-0000-0000-C000-000000000046";

    private static final int MAX_NAMES = 65_536;

    private static final Pattern LINES = Pattern.compile("\\s*\\R\\s*");

    private static final String[] BUSY = {"Free", "Tentative", "Busy", "Out of office", "Working elsewhere"};
    private static final String[] TASK_STATUS = {"Not started", "In progress", "Completed", "Waiting on someone else",
            "Deferred"};

    private MsgItems() {}

    static Map<String, Integer> names(MAPIMessage msg) {
        Map<String, Integer> names = new HashMap<>();
        NameIdChunks chunks;
        try {
            chunks = msg.getNameIdChunks();
        } catch (RuntimeException e) {
            return names;
        }
        if (chunks == null) {
            return names;
        }
        byte[] guids = null;
        byte[] entries = null;
        for (Chunk c : chunks.getAll()) {
            if (c instanceof ByteChunk b && b.getValue() != null) {
                if (c.getChunkId() == 0x0002) {
                    guids = b.getValue();
                } else if (c.getChunkId() == 0x0003) {
                    entries = b.getValue();
                }
            }
        }
        if (guids == null || entries == null) {
            return names;
        }
        for (int i = 0; i + 8 <= entries.length && names.size() < MAX_NAMES; i += 8) {
            long id = u32(entries, i);
            long x = u32(entries, i + 4);
            int guidIndex = (int) (x >> 1 & 0x7FFF);
            int property = (int) (x >>> 16);
            if ((x & 1) != 0 || guidIndex < 3 || (guidIndex - 2) * 16 > guids.length) {
                continue;
            }
            names.putIfAbsent(guid(guids, (guidIndex - 3) * 16) + ":" + id, 0x8000 + property);
        }
        return names;
    }

    private static long u32(byte[] b, int at) {
        return (b[at] & 0xFFL) | (b[at + 1] & 0xFFL) << 8 | (b[at + 2] & 0xFFL) << 16 | (b[at + 3] & 0xFFL) << 24;
    }

    private static String guid(byte[] b, int at) {
        return String.format(Locale.ROOT, "%08X-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X", u32(b, at),
                (b[at + 4] & 0xFF) | (b[at + 5] & 0xFF) << 8, (b[at + 6] & 0xFF) | (b[at + 7] & 0xFF) << 8,
                b[at + 8], b[at + 9], b[at + 10], b[at + 11], b[at + 12], b[at + 13], b[at + 14], b[at + 15]);
    }

    static void describe(Mail mail, Chunks main, Map<String, Integer> names) {
        String type = MsgReader.stringProperty(main, 0x001A);
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        Props p = new Props(main, names);
        if (t.startsWith("ipm.appointment") || t.startsWith("ipm.schedule.meeting")) {
            mail.kind = t.startsWith("ipm.appointment") ? "Appointment"
                    : t.contains(".canceled") ? "Meeting cancellation"
                    : t.contains(".resp.pos") ? "Meeting accepted"
                    : t.contains(".resp.tent") ? "Meeting tentatively accepted"
                    : t.contains(".resp.neg") ? "Meeting declined"
                    : "Meeting request";
            appointment(mail, p, !t.contains(".resp."));
        } else if (t.startsWith("ipm.task")) {
            mail.kind = "Task";
            task(mail, p);
        } else if (t.startsWith("ipm.contact")) {
            mail.kind = "Contact";
            contact(mail, p);
        } else if (t.startsWith("ipm.stickynote")) {
            mail.kind = "Note";
        } else if (t.startsWith("ipm.distlist")) {
            mail.kind = "Contact group";
        }
    }

    private static void appointment(Mail mail, Props p, boolean fromOrganizer) {
        field(mail, "Location", p.string(APPOINTMENT, 0x8208));
        boolean allDay = Boolean.TRUE.equals(p.value(APPOINTMENT, 0x8215));
        Calendar start = p.time(APPOINTMENT, 0x820D);
        Calendar end = p.time(APPOINTMENT, 0x820E);
        int year = start == null ? 2000 : start.get(Calendar.YEAR);
        MsgZone startZone = MsgZone.definition(p.binary(APPOINTMENT, 0x825E), year);
        MsgZone endZone = MsgZone.definition(p.binary(APPOINTMENT, 0x825F), year);
        if (startZone == null) {
            startZone = MsgZone.struct(p.binary(APPOINTMENT, 0x8233));
        }
        if (endZone == null) {
            endZone = startZone;
        }
        if (allDay && end != null) {
            end = (Calendar) end.clone();
            end.add(Calendar.MINUTE, -1);
        }
        field(mail, "Start", local(start, startZone, !allDay));
        field(mail, "End", local(end, endZone, !allDay));
        if (allDay) {
            field(mail, "All day", "Yes");
        }
        String recurrence = p.string(APPOINTMENT, 0x8232);
        if (recurrence == null && Boolean.TRUE.equals(p.value(APPOINTMENT, 0x8223))) {
            recurrence = "Recurring";
        }
        field(mail, "Recurrence", recurrence);
        field(mail, "Time zone", p.string(APPOINTMENT, 0x8234));
        if (p.value(APPOINTMENT, 0x8205) instanceof Number n && n.intValue() >= 0 && n.intValue() < BUSY.length) {
            field(mail, "Show as", BUSY[n.intValue()]);
        }
        if (!fromOrganizer) {
            return;
        }
        String required = p.string(APPOINTMENT, 0x823B);
        String optional = p.string(APPOINTMENT, 0x823C);
        field(mail, "Required attendees", required != null ? required.replace(";", ",") : mail.to);
        field(mail, "Optional attendees", optional != null ? optional.replace(";", ",") : mail.cc);
        mail.to = null;
        mail.cc = null;
        mail.organizer = mail.from != null;
    }

    private static void task(Mail mail, Props p) {
        if (p.value(TASK, 0x8101) instanceof Number n && n.intValue() >= 0 && n.intValue() < TASK_STATUS.length) {
            field(mail, "Status", TASK_STATUS[n.intValue()]);
        }
        if (p.value(TASK, 0x8102) instanceof Number n) {
            field(mail, "% Complete", Math.round(Math.max(0, Math.min(1, n.doubleValue())) * 100) + "%");
        }
        field(mail, "Start date", time(p.time(TASK, 0x8104), false));
        field(mail, "Due date", time(p.time(TASK, 0x8105), false));
        field(mail, "Date completed", time(p.time(TASK, 0x810F), false));
        field(mail, "Owner", p.string(TASK, 0x811F));
        field(mail, "Total work", minutes(p.value(TASK, 0x8111)));
        field(mail, "Actual work", minutes(p.value(TASK, 0x8110)));
        if (p.value(null, 0x0017) instanceof Number n && n.intValue() != 1) {
            field(mail, "Priority", n.intValue() == 2 ? "High" : "Low");
        }
    }

    private static void contact(Mail mail, Props p) {
        field(mail, "Full name", p.string(null, 0x3001));
        field(mail, "Company", p.string(null, 0x3A16));
        field(mail, "Job title", p.string(null, 0x3A17));
        field(mail, "Department", p.string(null, 0x3A18));
        field(mail, "Office", p.string(null, 0x3A19));
        field(mail, "File as", p.string(ADDRESS, 0x8005));
        field(mail, "Business address", address(p.string(ADDRESS, 0x801B), p, 0x3A29, 0x3A27, 0x3A28, 0x3A2A,
                0x3A26));
        field(mail, "Home address", address(p.string(ADDRESS, 0x801A), p, 0x3A5D, 0x3A59, 0x3A5C, 0x3A5B, 0x3A5A));
        field(mail, "Other address", address(p.string(ADDRESS, 0x801C), p, 0x3A63, 0x3A5F, 0x3A62, 0x3A61,
                0x3A60));
        field(mail, "Business phone", p.string(null, 0x3A08));
        field(mail, "Business phone 2", p.string(null, 0x3A1B));
        field(mail, "Home phone", p.string(null, 0x3A09));
        field(mail, "Home phone 2", p.string(null, 0x3A2F));
        field(mail, "Mobile", p.string(null, 0x3A1C));
        field(mail, "Primary phone", p.string(null, 0x3A1A));
        field(mail, "Company phone", p.string(null, 0x3A57));
        field(mail, "Business fax", p.string(null, 0x3A24));
        field(mail, "Home fax", p.string(null, 0x3A25));
        field(mail, "Pager", p.string(null, 0x3A21));
        field(mail, "Car phone", p.string(null, 0x3A1E));
        field(mail, "Other phone", p.string(null, 0x3A1F));
        field(mail, "Assistant", p.string(null, 0x3A30));
        field(mail, "Assistant phone", p.string(null, 0x3A2E));
        field(mail, "E-mail", email(p, 0x8083, 0x8080));
        field(mail, "E-mail 2", email(p, 0x8093, 0x8090));
        field(mail, "E-mail 3", email(p, 0x80A3, 0x80A0));
        field(mail, "IM address", p.string(ADDRESS, 0x8062));
        String web = p.string(ADDRESS, 0x802B);
        field(mail, "Web page", web != null ? web : p.string(null, 0x3A51));
        field(mail, "Personal web page", p.string(null, 0x3A50));
        field(mail, "Birthday", time(p.time(null, 0x3A42), false));
        field(mail, "Anniversary", time(p.time(null, 0x3A41), false));
        field(mail, "Nickname", p.string(null, 0x3A4F));
        field(mail, "Spouse", p.string(null, 0x3A48));
        field(mail, "Manager", p.string(null, 0x3A4E));
    }

    private static String email(Props p, int address, int display) {
        String a = p.string(ADDRESS, address);
        if (a == null) {
            return null;
        }
        String d = p.string(ADDRESS, display);
        return d == null || d.contains(a) ? a : d + " <" + a + ">";
    }

    private static String address(String whole, Props p, int street, int city, int state, int postal, int country) {
        if (whole != null) {
            return whole;
        }
        List<String> parts = new ArrayList<>();
        for (int id : new int[] {street, city, state, postal, country}) {
            String s = p.string(null, id);
            if (s != null) {
                parts.add(s);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String minutes(Object v) {
        if (!(v instanceof Number n) || n.longValue() <= 0) {
            return null;
        }
        long m = n.longValue();
        return m % 60 == 0 ? m / 60 + (m == 60 ? " hour" : " hours") : m + " minutes";
    }

    private static String local(Calendar c, MsgZone zone, boolean withTime) {
        if (zone == null || c == null || c.get(Calendar.YEAR) < 1700 || c.get(Calendar.YEAR) > 4000) {
            return time(c, withTime);
        }
        java.time.ZonedDateTime t = c.toInstant().atZone(zone.offset(c.toInstant()));
        return java.time.format.DateTimeFormatter.ofPattern(withTime ? "EEE, d MMM yyyy HH:mm" : "EEE, d MMM yyyy",
                Locale.ROOT).format(t);
    }

    static String time(Calendar c, boolean withTime) {
        if (c == null || c.get(Calendar.YEAR) < 1700 || c.get(Calendar.YEAR) > 4000) {
            return null;
        }
        SimpleDateFormat f = new SimpleDateFormat(withTime ? "EEE, d MMM yyyy HH:mm 'UTC'" : "EEE, d MMM yyyy",
                Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(c.getTime());
    }

    private static void field(Mail mail, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        mail.fields.add(new String[] {label, LINES.matcher(value.strip()).replaceAll(", ")});
    }

    private record Props(Chunks main, Map<String, Integer> names) {

        private int id(String set, int dispid) {
            if (set == null) {
                return dispid;
            }
            Integer id = names.get(set + ":" + dispid);
            return id == null ? -1 : id;
        }

        String string(String set, int dispid) {
            int id = id(set, dispid);
            String s = id < 0 ? null : MsgReader.stringProperty(main, id);
            return s == null || s.isBlank() ? null : EmlReader.clean(LINES.matcher(s.strip()).replaceAll(", "));
        }

        Calendar time(String set, int dispid) {
            int id = id(set, dispid);
            return id < 0 ? null : MsgReader.systemTime(main, id);
        }

        byte[] binary(String set, int dispid) {
            int id = id(set, dispid);
            return id < 0 ? null : MsgReader.binaryProperty(main, id);
        }

        Object value(String set, int dispid) {
            int id = id(set, dispid);
            return id < 0 ? null : MsgReader.fixed(main, id);
        }
    }
}
