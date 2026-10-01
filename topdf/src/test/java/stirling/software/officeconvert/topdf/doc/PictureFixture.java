package stirling.software.officeconvert.topdf.doc;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.imageio.ImageIO;

import org.apache.poi.ddf.EscherBSERecord;
import org.apache.poi.ddf.EscherBitmapBlip;
import org.apache.poi.ddf.EscherContainerRecord;
import org.apache.poi.ddf.EscherOptRecord;
import org.apache.poi.ddf.EscherPropertyTypes;
import org.apache.poi.ddf.EscherSimpleProperty;
import org.apache.poi.ddf.EscherSpRecord;

final class PictureFixture {

    private PictureFixture() {}

    static byte[] png(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, c.getRGB());
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] picf(byte[] png, int dxaGoal, int dyaGoal) {
        EscherContainerRecord sp = new EscherContainerRecord();
        sp.setRecordId((short) 0xF004);
        sp.setOptions((short) 0x000F);
        EscherSpRecord fsp = new EscherSpRecord();
        fsp.setRecordId(EscherSpRecord.RECORD_ID);
        fsp.setOptions((short) (75 << 4 | 2));
        fsp.setShapeId(1025);
        fsp.setFlags(0x0A00);
        sp.addChildRecord(fsp);
        EscherOptRecord opt = new EscherOptRecord();
        opt.setRecordId(EscherOptRecord.RECORD_ID);
        opt.addEscherProperty(new EscherSimpleProperty(EscherPropertyTypes.forPropertyID(0x0104), false, true, 1));
        sp.addChildRecord(opt);
        EscherBitmapBlip blip = new EscherBitmapBlip();
        blip.setRecordId(EscherBitmapBlip.RECORD_ID_PNG);
        blip.setOptions((short) 0x6E00);
        blip.setUID(new byte[16]);
        blip.setMarker((byte) 0xFF);
        blip.setPictureData(png);
        EscherBSERecord bse = new EscherBSERecord();
        bse.setRecordId(EscherBSERecord.RECORD_ID);
        bse.setOptions((short) (6 << 4 | 2));
        bse.setBlipTypeWin32((byte) 6);
        bse.setBlipTypeMacOS((byte) 6);
        bse.setUid(new byte[16]);
        bse.setSize(png.length + 25);
        bse.setRef(1);
        bse.setBlipRecord(blip);
        byte[] art = WordFixture.concat(sp.serialize(), bse.serialize());
        ByteBuffer b = ByteBuffer.allocate(68 + art.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(68 + art.length).putShort((short) 68).putShort((short) 0x64).putShort((short) 0).putShort((short) 0)
                .putShort((short) 0);
        b.position(28);
        b.putShort((short) dxaGoal).putShort((short) dyaGoal).putShort((short) 1000).putShort((short) 1000);
        b.position(68);
        b.put(art);
        return b.array();
    }
}
