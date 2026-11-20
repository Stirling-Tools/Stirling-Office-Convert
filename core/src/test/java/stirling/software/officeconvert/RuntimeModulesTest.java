package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RuntimeModulesTest {

    @Test
    void aFullRuntimePassesWithoutWarnings() {
        RuntimeModules.Report r = RuntimeModules.inspect(name -> true);
        assertDoesNotThrow(r::check);
        assertEquals(List.of(), r.warnings());
    }

    @Test
    void aMissingRequiredModuleFailsNamingIt() {
        RuntimeModules.Report r = RuntimeModules.inspect(name -> !name.equals("java.management"));
        assertEquals(List.of("java.management"), r.missingRequired());
        IllegalStateException e = assertThrows(IllegalStateException.class, r::check);
        assertTrue(e.getMessage().startsWith("This Java runtime lacks the module java.management,"), e.getMessage());
        assertTrue(e.getMessage().contains("jlink --add-modules java.desktop,java.xml,java.management,jdk.charsets,"
                + "jdk.localedata"), e.getMessage());
    }

    @Test
    void severalMissingRequiredModulesAreAllNamed() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RuntimeModules.inspect(name -> name.startsWith("jdk.")).check());
        assertTrue(e.getMessage().startsWith("This Java runtime lacks the modules java.desktop, java.xml, "
                + "java.management,"), e.getMessage());
    }

    @Test
    void missingCharsetsAndLocaleDataWarnWithWhatIsLost() {
        Set<String> absent = Set.of("jdk.charsets", "jdk.localedata");
        RuntimeModules.Report r = RuntimeModules.inspect(name -> !absent.contains(name));
        assertDoesNotThrow(r::check);
        assertEquals(List.of("jdk.charsets", "jdk.localedata"), r.missingRecommended());
        List<String> w = r.warnings();
        assertEquals(2, w.size(), w.toString());
        assertTrue(w.get(0).startsWith("This Java runtime lacks the module jdk.charsets, so documents in Big5"), w.get(0));
        assertTrue(w.get(1).contains("jdk.localedata") && w.get(1).contains("Thai line breaking"), w.get(1));
    }

    @Test
    void thisRuntimeHasEveryModule() {
        assertDoesNotThrow(RuntimeModules::check);
        assertEquals(List.of(), RuntimeModules.warnings());
    }
}
