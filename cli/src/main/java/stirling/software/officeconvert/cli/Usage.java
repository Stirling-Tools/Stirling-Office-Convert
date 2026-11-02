package stirling.software.officeconvert.cli;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

final class Usage extends Exception {

    Usage(String message) {
        super(message, null, false, false);
    }

    static String value(String[] args, int i, String option) throws Usage {
        if (i >= args.length) {
            throw new Usage(option + " needs a value");
        }
        return args[i];
    }

    static Path path(String s) throws Usage {
        try {
            return Path.of(s);
        } catch (InvalidPathException e) {
            throw new Usage("not a valid path: " + s);
        }
    }

    static float number(String s, String option) throws Usage {
        try {
            return Float.parseFloat(s.strip());
        } catch (NumberFormatException e) {
            throw new Usage(option + " needs a number, not '" + s + "'");
        }
    }
}
