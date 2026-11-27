package stirling.software.officeconvert.topdf.mail;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

final class MsgZone {

    private static final int MAX_RULES = 1_024;

    private final int bias;
    private final int standardBias;
    private final int daylightBias;
    private final int[] standard;
    private final int[] daylight;

    private MsgZone(int bias, int standardBias, int daylightBias, int[] standard, int[] daylight) {
        this.bias = bias;
        this.standardBias = standardBias;
        this.daylightBias = daylightBias;
        this.standard = standard;
        this.daylight = daylight;
    }

    static MsgZone definition(byte[] b, int year) {
        if (b == null || b.length < 8 || b[0] != 2) {
            return null;
        }
        int header = u16(b, 2);
        int at = 4 + header;
        if (header < 6 || at + 2 > b.length) {
            return null;
        }
        int rules = u16(b, 8 + u16(b, 6) * 2);
        MsgZone chosen = null;
        int chosenYear = Integer.MIN_VALUE;
        for (int r = 0; r < rules && r < MAX_RULES && at + 66 <= b.length; r++, at += 66) {
            int ruleYear = u16(b, at + 6);
            MsgZone z = new MsgZone(s32(b, at + 22), s32(b, at + 26), s32(b, at + 30), time(b, at + 34),
                    time(b, at + 50));
            if (chosen == null || ruleYear <= year && ruleYear > chosenYear) {
                chosen = z;
                chosenYear = ruleYear <= year ? ruleYear : Integer.MIN_VALUE;
            }
        }
        return chosen;
    }

    static MsgZone struct(byte[] b) {
        if (b == null || b.length < 48) {
            return null;
        }
        return new MsgZone(s32(b, 0), s32(b, 4), s32(b, 8), time(b, 14), time(b, 32));
    }

    private static int[] time(byte[] b, int at) {
        int[] t = new int[8];
        for (int i = 0; i < 8; i++) {
            t[i] = u16(b, at + 2 * i);
        }
        return t;
    }

    private static int u16(byte[] b, int at) {
        return at + 1 < b.length ? (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 : 0;
    }

    private static int s32(byte[] b, int at) {
        return at + 3 < b.length
                ? (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | b[at + 3] << 24 : 0;
    }

    ZoneOffset offset(Instant t) {
        ZoneOffset std = minutes(-((long) bias + standardBias));
        ZoneOffset dst = minutes(-((long) bias + daylightBias));
        if (!valid(standard) || !valid(daylight) || std.equals(dst)) {
            return std;
        }
        int year = LocalDateTime.ofInstant(t, std).getYear();
        Instant start = change(daylight, year).toInstant(std);
        Instant end = change(standard, year).toInstant(dst);
        boolean summer = start.isBefore(end) ? !t.isBefore(start) && t.isBefore(end)
                : !t.isBefore(start) || t.isBefore(end);
        return summer ? dst : std;
    }

    private static ZoneOffset minutes(long m) {
        return ZoneOffset.ofTotalSeconds((int) Math.max(-18 * 60, Math.min(18 * 60, m)) * 60);
    }

    private static boolean valid(int[] st) {
        return st[1] >= 1 && st[1] <= 12 && st[2] <= 6 && st[3] >= 1 && st[3] <= 5 && st[4] < 24 && st[5] < 60;
    }

    private static LocalDateTime change(int[] st, int year) {
        DayOfWeek day = DayOfWeek.of(st[2] == 0 ? 7 : st[2]);
        LocalDate first = LocalDate.of(year, st[1], 1);
        LocalDate date = st[3] >= 5 ? first.with(TemporalAdjusters.lastInMonth(day))
                : first.with(TemporalAdjusters.dayOfWeekInMonth(st[3], day));
        return date.atTime(st[4], st[5]);
    }
}
