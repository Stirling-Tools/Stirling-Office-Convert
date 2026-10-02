package stirling.software.officeconvert.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class Guard {

    private final List<Path> inputs;
    private final Set<String> inputKeys = new HashSet<>();
    private final List<Path> produced = new ArrayList<>();
    private final Set<String> producedKeys = new HashSet<>();
    private final boolean overwrite;

    Guard(List<Path> inputs, boolean overwrite) {
        this.inputs = List.copyOf(inputs);
        this.overwrite = overwrite;
        for (Path in : inputs) {
            inputKeys.add(Targets.key(in));
        }
    }

    String refusal(Path input, Path target) {
        if (producedKeys.contains(Targets.key(input)) || same(input, produced)) {
            return "it was written by this run; it is not converted again";
        }
        if (inputKeys.contains(Targets.key(target)) || same(target, inputs)) {
            return target + " is an input of this run and is never overwritten";
        }
        if (!overwrite && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return target + " already exists; give --overwrite to replace it";
        }
        return null;
    }

    void wrote(Path target) {
        produced.add(target);
        producedKeys.add(Targets.key(target));
    }

    private static boolean same(Path file, List<Path> others) {
        if (!Files.exists(file)) {
            return false;
        }
        for (Path other : others) {
            try {
                if (Files.exists(other) && Files.isSameFile(file, other)) {
                    return true;
                }
            } catch (IOException e) {
                return true;
            }
        }
        return false;
    }
}
