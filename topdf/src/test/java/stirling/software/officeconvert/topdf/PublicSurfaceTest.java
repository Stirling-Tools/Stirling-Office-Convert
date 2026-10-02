package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class PublicSurfaceTest {

    private static final String PREFIX = "stirling.software.officeconvert.topdf.";

    private static final Set<String> CROSS_PACKAGE_INTERNALS = Set.of("doc6.Word6Upgrade", "doc6.Word6Upgrade$Upgraded",
            "io.LegacyOffice", "io.PoiPackages", "io.SafeImageRenderer");

    @Test
    void onlyCrossPackageInternalsShowRuntimeOnlyLibraryTypes() throws Exception {
        assertEquals(new TreeSet<>(CROSS_PACKAGE_INTERNALS), exposing());
    }

    private static Set<String> exposing() throws IOException, URISyntaxException, ClassNotFoundException {
        Path root = Path.of(OfficeToPdf.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(f -> f.toString().endsWith(".class")).forEach(f -> names.add(root.relativize(f).toString()
                    .replace(java.io.File.separatorChar, '.').replaceFirst("\\.class$", "")));
        }
        Set<String> out = new TreeSet<>();
        for (String name : names) {
            Class<?> c = Class.forName(name, false, PublicSurfaceTest.class.getClassLoader());
            if (visible(c) && exposes(c)) {
                out.add(name.substring(PREFIX.length()));
            }
        }
        return out;
    }

    private static boolean visible(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getDeclaringClass()) {
            if (!Modifier.isPublic(k.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    private static boolean exposes(Class<?> c) {
        List<Type> types = new ArrayList<>();
        types.add(c.getGenericSuperclass());
        types.addAll(List.of(c.getGenericInterfaces()));
        for (Method m : c.getDeclaredMethods()) {
            if (reachable(m.getModifiers()) && !m.isSynthetic()) {
                types.add(m.getGenericReturnType());
                types.addAll(List.of(m.getGenericParameterTypes()));
                types.addAll(List.of(m.getGenericExceptionTypes()));
            }
        }
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            if (reachable(k.getModifiers())) {
                types.addAll(List.of(k.getGenericParameterTypes()));
            }
        }
        for (Field f : c.getDeclaredFields()) {
            if (reachable(f.getModifiers())) {
                types.add(f.getGenericType());
            }
        }
        return types.stream().filter(t -> t != null).map(Type::getTypeName)
                .anyMatch(t -> t.contains("org.apache.poi.") || t.contains("de.rototor."));
    }

    private static boolean reachable(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }
}
