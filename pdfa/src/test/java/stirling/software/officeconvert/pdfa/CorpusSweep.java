package stirling.software.officeconvert.pdfa;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Runs the conformance check over a folder of PDFs; run it only inside a container with no network. */
final class CorpusSweep {

    private static final PdfALevel[] LEVELS = {PdfALevel.A1B, PdfALevel.A2B, PdfALevel.A2U, PdfALevel.A3B,
        PdfALevel.A2A};

    private CorpusSweep() {}

    public static void main(String[] args) throws Exception {
        Path corpus = Path.of(args[0]);
        Path work = Files.createDirectories(Path.of(args[1]));
        List<Path> files;
        try (Stream<Path> s = Files.walk(corpus)) {
            files = s.filter(p -> p.toString().toLowerCase().endsWith(".pdf")).sorted().toList();
        }
        List<String> lines = new ArrayList<>();
        int checked = 0;
        for (Path f : files) {
            for (PdfALevel level : LEVELS) {
                Path out = work.resolve("out.pdf");
                Files.deleteIfExists(out);
                String d;
                try {
                    d = Conformance.outcome(f, out, PdfToPdfA.Options.defaults().level(level).levelFallback(true)
                            .timeout(java.time.Duration.ofSeconds(90)));
                } catch (Exception | StackOverflowError e) {
                    d = "harness " + e;
                }
                checked++;
                String line = corpus.relativize(f) + " " + level + ": " + d;
                lines.add(line);
                System.out.println(line);
            }
            Files.write(work.resolve("results.txt"), lines);
        }
        System.out.println("checked " + checked);
    }
}
