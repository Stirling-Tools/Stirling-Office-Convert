package stirling.software.officeconvert.pdfa;

import java.io.IOException;

final class LevelANotMet extends IOException {

    LevelANotMet(String message) {
        super(message);
    }
}
