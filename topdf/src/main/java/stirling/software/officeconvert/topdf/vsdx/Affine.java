package stirling.software.officeconvert.topdf.vsdx;

record Affine(double a, double b, double c, double d, double e, double f) {

    static final Affine IDENTITY = new Affine(1, 0, 0, 1, 0, 0);

    static Affine of(double pinX, double pinY, double locPinX, double locPinY, double angle, boolean flipX,
            boolean flipY) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double fx = flipX ? -1 : 1;
        double fy = flipY ? -1 : 1;
        double a = cos * fx;
        double b = sin * fx;
        double c = -sin * fy;
        double d = cos * fy;
        return new Affine(a, b, c, d, pinX - a * locPinX - c * locPinY, pinY - b * locPinX - d * locPinY);
    }

    Affine then(Affine outer) {
        return new Affine(outer.a * a + outer.c * b, outer.b * a + outer.d * b, outer.a * c + outer.c * d,
                outer.b * c + outer.d * d, outer.a * e + outer.c * f + outer.e, outer.b * e + outer.d * f + outer.f);
    }

    double x(double px, double py) {
        return a * px + c * py + e;
    }

    double y(double px, double py) {
        return b * px + d * py + f;
    }

    double scale() {
        return Math.sqrt(Math.abs(a * d - b * c));
    }

    boolean mirrored() {
        return a * d - b * c < 0;
    }

    double angle() {
        return Math.atan2(b, a);
    }

    boolean finite() {
        return Double.isFinite(a) && Double.isFinite(b) && Double.isFinite(c) && Double.isFinite(d)
                && Double.isFinite(e) && Double.isFinite(f);
    }
}
