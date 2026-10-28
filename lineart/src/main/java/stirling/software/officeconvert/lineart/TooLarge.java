package stirling.software.officeconvert.lineart;

import java.io.IOException;

final class TooLarge extends IOException {
    TooLarge(long limit) {
        super("The data decodes to more than " + limit + " bytes");
    }
}
