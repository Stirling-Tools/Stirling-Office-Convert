package stirling.software.officeconvert;

import java.util.Objects;

public record EbookOptions(boolean chapters, Device device) {

    public enum Device {
        TABLET,
        KINDLE
    }

    public EbookOptions {
        Objects.requireNonNull(device, "device");
    }

    public static EbookOptions defaults() {
        return new EbookOptions(true, Device.TABLET);
    }

    public EbookOptions chapters(boolean on) {
        return new EbookOptions(on, device);
    }

    public EbookOptions device(Device target) {
        return new EbookOptions(chapters, target);
    }
}
