package stirling.software.officeconvert;

import java.io.IOException;

public class UnsupportedFormatException extends IOException {

    public UnsupportedFormatException(String message) {
        super(message);
    }

    public UnsupportedFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
