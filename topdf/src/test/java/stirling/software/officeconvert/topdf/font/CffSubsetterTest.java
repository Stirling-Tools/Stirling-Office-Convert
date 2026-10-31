package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class CffSubsetterTest {

    @Test
    void subroutinesThatCallEachOtherManyTimesAreScannedQuickly() {
        List<byte[]> globals = new ArrayList<>();
        for (int s = 0; s < 9; s++) {
            byte[] body = new byte[2 * 200 + 1];
            for (int k = 0; k < 200; k++) {
                body[2 * k] = (byte) (s + 1 - 107 + 139);
                body[2 * k + 1] = 29;
            }
            body[body.length - 1] = 11;
            globals.add(body);
        }
        globals.add(new byte[] {11});
        byte[] cff = nameKeyed(globals, List.of(new byte[] {(byte) (-107 + 139), 29, 14}));
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> CffSubsetter.subset(cff, "X", new int[] {0}, new int[] {0}));
    }

    @Test
    void manyFontDictionariesSharingOneHugePrivateDictionaryAreRefused() {
        int fds = 65_535;
        byte[] big = new byte[1 << 20];
        Arrays.fill(big, (byte) 139);
        big[big.length - 1] = 6;
        ByteArrayOutputStream top = new ByteArrayOutputStream();
        top.writeBytes(new byte[] {(byte) 139, (byte) 140, (byte) 139, 12, 30});
        int headerAndName = 4 + 6;
        int topIndex = 2 + 1 + 2 + 5 + 3 * 6 + 2;
        int strings = 2;
        int globals = 2;
        int select = headerAndName + topIndex + strings + globals;
        int charStrings = select + 8;
        int fdArray = charStrings + 2 + 1 + 2 + 1;
        int fdDict = 11;
        int fdArrayLength = 2 + 1 + 4 * (fds + 1) + fds * fdDict;
        int priv = fdArray + fdArrayLength;
        top.writeBytes(int5(charStrings));
        top.write(17);
        top.writeBytes(int5(fdArray));
        top.writeBytes(new byte[] {12, 36});
        top.writeBytes(int5(select));
        top.writeBytes(new byte[] {12, 37});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {1, 0, 4, 4, 0, 1, 1, 1, 2, 'X'});
        out.writeBytes(index(List.of(top.toByteArray()), 1));
        out.writeBytes(new byte[] {0, 0, 0, 0});
        out.writeBytes(new byte[] {3, 0, 1, 0, 0, 0, 0, 1});
        out.writeBytes(index(List.of(new byte[] {14}), 1));
        List<byte[]> dicts = new ArrayList<>(fds);
        byte[] dict = concat(int5(big.length), int5(priv), new byte[] {18});
        for (int i = 0; i < fds; i++) {
            dicts.add(dict);
        }
        out.writeBytes(index(dicts, 4));
        out.writeBytes(big);
        byte[] cff = out.toByteArray();
        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThrows(IOException.class, () -> CffSubsetter.subset(cff, "X", new int[] {0}, new int[] {0})));
    }

    private static byte[] nameKeyed(List<byte[]> globals, List<byte[]> charStrings) {
        byte[] globalIndex = index(globals, 4);
        byte[] charStringIndex = index(charStrings, 4);
        int topIndex = 2 + 1 + 2 + 17;
        int charStringsAt = 4 + 6 + topIndex + 2 + globalIndex.length;
        int privateAt = charStringsAt + charStringIndex.length;
        byte[] top = concat(int5(charStringsAt), new byte[] {17}, int5(0), int5(privateAt), new byte[] {18});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {1, 0, 4, 4, 0, 1, 1, 1, 2, 'X'});
        out.writeBytes(index(List.of(top), 1));
        out.writeBytes(new byte[] {0, 0});
        out.writeBytes(globalIndex);
        out.writeBytes(charStringIndex);
        return out.toByteArray();
    }

    private static byte[] index(List<byte[]> items, int offSize) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(items.size() >> 8);
        o.write(items.size());
        o.write(offSize);
        long off = 1;
        for (int i = 0; i <= items.size(); i++) {
            for (int k = offSize - 1; k >= 0; k--) {
                o.write((int) (off >> 8 * k));
            }
            if (i < items.size()) {
                off += items.get(i).length;
            }
        }
        for (byte[] b : items) {
            o.writeBytes(b);
        }
        return o.toByteArray();
    }

    private static byte[] int5(int v) {
        return new byte[] {29, (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }
}
