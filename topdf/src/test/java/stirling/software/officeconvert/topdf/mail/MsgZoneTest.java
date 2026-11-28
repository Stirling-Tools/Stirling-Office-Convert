package stirling.software.officeconvert.topdf.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class MsgZoneTest {

    private static byte[] struct(int bias, int daylightBias, int[] standard, int[] daylight) {
        ByteBuffer b = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(bias).putInt(0).putInt(daylightBias).putShort((short) 0);
        for (int v : standard) {
            b.putShort((short) v);
        }
        b.putShort((short) 0);
        for (int v : daylight) {
            b.putShort((short) v);
        }
        return b.array();
    }

    @Test
    void daylightSavingFollowsTheStoredRules() {
        MsgZone eastern = MsgZone.struct(struct(300, -60, new int[] {0, 11, 0, 1, 2, 0, 0, 0},
                new int[] {0, 3, 0, 2, 2, 0, 0, 0}));
        assertEquals(ZoneOffset.ofHours(-5), eastern.offset(Instant.parse("2026-01-15T12:00:00Z")));
        assertEquals(ZoneOffset.ofHours(-4), eastern.offset(Instant.parse("2026-07-15T12:00:00Z")));
        assertEquals(ZoneOffset.ofHours(-5), eastern.offset(Instant.parse("2026-03-08T06:59:00Z")));
        assertEquals(ZoneOffset.ofHours(-4), eastern.offset(Instant.parse("2026-03-08T07:00:00Z")));

        MsgZone sydney = MsgZone.struct(struct(-600, -60, new int[] {0, 4, 0, 1, 3, 0, 0, 0},
                new int[] {0, 10, 0, 1, 2, 0, 0, 0}));
        assertEquals(ZoneOffset.ofHours(11), sydney.offset(Instant.parse("2026-01-15T00:00:00Z")));
        assertEquals(ZoneOffset.ofHours(10), sydney.offset(Instant.parse("2026-07-15T00:00:00Z")));

        MsgZone tokyo = MsgZone.struct(struct(-540, 0, new int[8], new int[8]));
        assertEquals(ZoneOffset.ofHours(9), tokyo.offset(Instant.parse("2026-07-15T00:00:00Z")));
    }

    @Test
    void brokenDefinitionsAreIgnored() {
        assertNull(MsgZone.definition(new byte[] {2, 1, (byte) 0xFF, (byte) 0xFF}, 2026));
        assertNull(MsgZone.struct(new byte[10]));
        MsgZone wild = MsgZone.struct(struct(Integer.MAX_VALUE, -60, new int[] {0, 11, 0, 1, 2, 0, 0, 0},
                new int[] {0, 3, 0, 2, 2, 0, 0, 0}));
        assertEquals(ZoneOffset.ofHours(-18), wild.offset(Instant.parse("2026-07-15T12:00:00Z")));
        assertNull(MsgZone.definition(new byte[] {2, 1, 48, 0, 2, 0, (byte) 0xFF, (byte) 0xFF, 0, 0}, 2026));
    }

    @Test
    void oneTimeTransitionsUseTheirStoredDates() {
        MsgZone pacific = MsgZone.struct(struct(480, -60, new int[] {2023, 11, 0, 5, 2, 0, 0, 0},
                new int[] {2023, 3, 0, 12, 2, 0, 0, 0}));
        assertEquals(ZoneOffset.ofHours(-8), pacific.offset(Instant.parse("2023-01-15T12:00:00Z")));
        assertEquals(ZoneOffset.ofHours(-7), pacific.offset(Instant.parse("2023-07-15T12:00:00Z")));
    }
}
