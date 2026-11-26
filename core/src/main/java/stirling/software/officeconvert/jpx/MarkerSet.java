package stirling.software.officeconvert.jpx;

import java.io.ByteArrayOutputStream;
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

    final TreeMap<Integer, ByteArrayOutputStream> packed = new TreeMap<>();

    byte[] packedHeaders() {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (ByteArrayOutputStream p : packed.values()) {
            all.writeBytes(p.toByteArray());
        }
        return all.toByteArray();
    }
}
