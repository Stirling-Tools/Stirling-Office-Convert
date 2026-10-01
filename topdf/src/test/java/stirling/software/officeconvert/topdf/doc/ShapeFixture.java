package stirling.software.officeconvert.topdf.doc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.ddf.EscherComplexProperty;
import org.apache.poi.ddf.EscherContainerRecord;
import org.apache.poi.ddf.EscherDgRecord;
import org.apache.poi.ddf.EscherDggRecord;
import org.apache.poi.ddf.EscherOptRecord;
import org.apache.poi.ddf.EscherProperty;
import org.apache.poi.ddf.EscherPropertyTypes;
import org.apache.poi.ddf.EscherSimpleProperty;
import org.apache.poi.ddf.EscherSpRecord;
import org.apache.poi.ddf.EscherSpgrRecord;

final class ShapeFixture {

    record Shape(int spid, int type, int[] rect, int flags, Map<Integer, Integer> props, byte[] polygon) {}

    private ShapeFixture() {}

    static int fspaFlags(int bx, int by, int wr, int wrk, boolean below) {
        return bx << 1 | by << 3 | wr << 5 | wrk << 9 | (below ? 1 << 14 : 0);
    }

    static byte[] fspa(List<Shape> shapes, List<Integer> cps, int lastCp) {
        ByteBuffer b = ByteBuffer.allocate(4 * (shapes.size() + 1) + 26 * shapes.size()).order(ByteOrder.LITTLE_ENDIAN);
        for (int cp : cps) {
            b.putInt(cp);
        }
        b.putInt(lastCp);
        for (Shape s : shapes) {
            b.putInt(s.spid()).putInt(s.rect()[0]).putInt(s.rect()[1]).putInt(s.rect()[2]).putInt(s.rect()[3])
                    .putShort((short) s.flags()).putInt(0);
        }
        return b.array();
    }

    static byte[] dggInfo(List<Shape> shapes) {
        EscherContainerRecord dgg = container(0xF000);
        EscherDggRecord fdgg = new EscherDggRecord();
        fdgg.setRecordId(EscherDggRecord.RECORD_ID);
        fdgg.setOptions((short) 0x0000);
        fdgg.setShapeIdMax(1024 + shapes.size() + 2);
        fdgg.setDrawingsSaved(1);
        fdgg.setNumShapesSaved(shapes.size() + 1);
        fdgg.addCluster(1, shapes.size() + 1);
        dgg.addChildRecord(fdgg);
        EscherContainerRecord dg = container(0xF002);
        EscherDgRecord fdg = new EscherDgRecord();
        fdg.setRecordId(EscherDgRecord.RECORD_ID);
        fdg.setOptions((short) (1 << 4));
        fdg.setNumShapes(shapes.size() + 1);
        fdg.setLastMSOSPID(1024 + shapes.size() + 1);
        dg.addChildRecord(fdg);
        EscherContainerRecord group = container(0xF003);
        EscherContainerRecord patriarch = container(0xF004);
        EscherSpgrRecord spgr = new EscherSpgrRecord();
        spgr.setRecordId(EscherSpgrRecord.RECORD_ID);
        spgr.setOptions((short) 0x0001);
        patriarch.addChildRecord(spgr);
        EscherSpRecord top = new EscherSpRecord();
        top.setRecordId(EscherSpRecord.RECORD_ID);
        top.setOptions((short) 0x0002);
        top.setShapeId(1024);
        top.setFlags(0x0005);
        patriarch.addChildRecord(top);
        group.addChildRecord(patriarch);
        for (Shape s : shapes) {
            EscherContainerRecord sp = container(0xF004);
            EscherSpRecord fsp = new EscherSpRecord();
            fsp.setRecordId(EscherSpRecord.RECORD_ID);
            fsp.setOptions((short) (s.type() << 4 | 2));
            fsp.setShapeId(s.spid());
            fsp.setFlags(0x0A00);
            sp.addChildRecord(fsp);
            EscherOptRecord opt = new EscherOptRecord();
            opt.setRecordId(EscherOptRecord.RECORD_ID);
            List<EscherProperty> props = new ArrayList<>();
            s.props().forEach((k, v) -> props.add(new EscherSimpleProperty(EscherPropertyTypes.forPropertyID(k), v)));
            if (s.polygon() != null) {
                EscherComplexProperty poly = new EscherComplexProperty(EscherPropertyTypes.forPropertyID(0x0383), false,
                        s.polygon().length);
                poly.setComplexData(s.polygon());
                props.add(poly);
            }
            props.forEach(opt::addEscherProperty);
            opt.sortProperties();
            sp.addChildRecord(opt);
            group.addChildRecord(sp);
        }
        dg.addChildRecord(group);
        return WordFixture.concat(dgg.serialize(), new byte[] {0}, dg.serialize());
    }

    private static EscherContainerRecord container(int id) {
        EscherContainerRecord c = new EscherContainerRecord();
        c.setRecordId((short) id);
        c.setOptions((short) 0x000F);
        return c;
    }
}
