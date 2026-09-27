package stirling.software.officeconvert.odp;

final class OdpLines {

    private static final float DESCENT = 0.2f;

    private OdpLines() {}

    static float baseline(float height, float size) {
        return height - DESCENT * size;
    }
}
