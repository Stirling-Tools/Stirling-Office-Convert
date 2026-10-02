package stirling.software.officeconvert.jpx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class MarkerSet {

    CodingDefaults cod;

    final Map<Integer, ComponentStyle> coc = new HashMap<>();

    Quant qcd;

    final Map<Integer, Quant> qcc = new HashMap<>();

    final Map<Integer, Integer> roi = new HashMap<>();

    final List<Poc> pocs = new ArrayList<>();

    final TreeMap<Integer, byte[]> packed = new TreeMap<>();

    byte[] packedHeaders() {
        int total = 0;
        for (byte[] p : packed.values()) {
            total += p.length;
        }
        byte[] all = new byte[total];
        int at = 0;
        for (byte[] p : packed.values()) {
            System.arraycopy(p, 0, all, at, p.length);
            at += p.length;
        }
        return all;
    }
}
