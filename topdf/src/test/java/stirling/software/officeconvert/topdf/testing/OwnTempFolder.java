package stirling.software.officeconvert.topdf.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

// Test JVMs share the system temp folder, so a check for files left there runs in a JVM with its own
public final class OwnTempFolder {

    private OwnTempFolder() {}

    /** Runs the main class in a new JVM whose temp folder is {@code tmp} and returns what it left there. */
    public static List<String> run(Path tmp, Class<?> main, String... args) throws IOException, InterruptedException {
        Files.createDirectories(tmp);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(java, "-Xmx512m", "-Djava.io.tmpdir=" + tmp, "-cp",
                System.getProperty("java.class.path"), main.getName()));
        command.addAll(List.of(args));
        Path log = tmp.resolveSibling(tmp.getFileName() + ".log");
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        int exit = p.waitFor();
        assertEquals(0, exit, () -> read(log));
        try (Stream<Path> files = Files.list(tmp)) {
            return files.map(f -> f.getFileName().toString()).toList();
        }
    }

    private static String read(Path log) {
        try {
            return Files.readString(log);
        } catch (IOException e) {
            return e.toString();
        }
    }
}
