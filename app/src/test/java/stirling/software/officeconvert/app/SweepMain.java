package stirling.software.officeconvert.app;

import java.nio.file.Path;
import java.time.Instant;

final class SweepMain {

    private SweepMain() {}

    public static void main(String[] args) {
        System.out.println(WorkDir.sweep(Path.of(args[0]), Instant.now()));
    }
}
