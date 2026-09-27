package stirling.software.officeconvert.slides;

import stirling.software.officeconvert.layout.Marker;
import stirling.software.officeconvert.model.RunStyle;

public record Bullet(Marker marker, RunStyle style) {

    public boolean numbered() {
        return !marker.isBullet();
    }
}
