package stirling.software.officeconvert.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import stirling.software.officeconvert.topdf.OfficeToPdf;

final class Targets {

    private Targets() {}

    static List<Path> of(List<Path> inputs, Path output, String format, boolean folder) {
        boolean many = folder || inputs.size() > 1;
        boolean named = !many && output != null && !Files.isDirectory(output);
        List<Path> out = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        Set<String> sources = new HashSet<>();
        for (Path in : inputs) {
            Path t = target(in, output, many, extension(in, format));
            out.add(t);
            counts.merge(key(t), 1, Integer::sum);
            sources.add(key(in));
        }
        if (named) {
            return out;
        }
        Set<String> used = new HashSet<>();
        for (int i = 0; i < out.size(); i++) {
            Path t = out.get(i);
            if (counts.get(key(t)) > 1 || sources.contains(key(t))) {
                t = t.resolveSibling(inputs.get(i).getFileName().toString() + "." + extension(inputs.get(i), format));
            }
            Path unique = t;
            for (int n = 2; !used.add(key(unique)) && n < 10_000; n++) {
                String name = t.getFileName().toString();
                int dot = name.lastIndexOf('.');
                unique = t.resolveSibling(name.substring(0, dot) + " (" + n + ")" + name.substring(dot));
            }
            out.set(i, unique);
        }
        return out;
    }

    static String key(Path p) {
        return p.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    private static String extension(Path in, String format) {
        return in.getFileName() != null && OfficeToPdf.Format.recognises(in) ? "pdf" : format;
    }

    private static Path target(Path in, Path output, boolean many, String format) {
        String base = in.getFileName().toString();
        String name = ("pdf".equals(format) ? base.replaceFirst("\\.[^.]+$", "") : base.replaceFirst("(?i)\\.pdf$", ""))
                + "." + format;
        if (output == null) {
            return in.resolveSibling(name);
        }
        if (many || Files.isDirectory(output)) {
            return output.resolve(name);
        }
        return output;
    }
}
