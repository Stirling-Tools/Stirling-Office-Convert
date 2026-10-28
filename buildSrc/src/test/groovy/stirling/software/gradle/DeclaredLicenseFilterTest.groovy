package stirling.software.gradle

import com.github.jk1.license.ConfigurationData
import com.github.jk1.license.License
import com.github.jk1.license.LicenseFileData
import com.github.jk1.license.LicenseFileDetails
import com.github.jk1.license.ManifestData
import com.github.jk1.license.ModuleData
import com.github.jk1.license.PomData
import com.github.jk1.license.ProjectData
import com.github.jk1.license.render.LicenseDataCollector
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

class DeclaredLicenseFilterTest {
    private static final String AGPL_NAME = 'GNU Affero General Public License v3'
    private static final String APACHE_NAME = 'Apache License, Version 2.0'
    private static final String APACHE_URL = 'https://www.apache.org/licenses/LICENSE-2.0'
    private static final String BUNDLED_FILE = 'example-library-1.0.jar/LICENSE'

    @Test
    void ignoresBundledFileLicenseWhenPomDeclaresLicense() {
        ModuleData module = createModule(
                [pom(AGPL_NAME)] as LinkedHashSet<PomData>,
                new LinkedHashSet<ManifestData>(),
                bundledFile(APACHE_NAME))

        new DeclaredLicenseFilter().filter(createProjectData(module))

        assertEquals([AGPL_NAME], licenseNames(module))
    }

    @Test
    void ignoresBundledFileLicenseWhenManifestDeclaresLicense() {
        ModuleData module = createModule(
                new LinkedHashSet<PomData>(),
                [manifest(AGPL_NAME)] as LinkedHashSet<ManifestData>,
                bundledFile(APACHE_NAME))

        new DeclaredLicenseFilter().filter(createProjectData(module))

        assertEquals([AGPL_NAME], licenseNames(module))
    }

    @Test
    void keepsBundledFileLicenseWhenNothingIsDeclared() {
        ModuleData module = createModule(
                new LinkedHashSet<PomData>(),
                new LinkedHashSet<ManifestData>(),
                bundledFile(APACHE_NAME))

        new DeclaredLicenseFilter().filter(createProjectData(module))

        assertEquals([APACHE_NAME], licenseNames(module))
    }

    @Test
    void keepsEveryDeclaredLicenseOfDualLicensedModule() {
        ModuleData module = createModule(
                [pom(AGPL_NAME, APACHE_NAME)] as LinkedHashSet<PomData>,
                new LinkedHashSet<ManifestData>(),
                bundledFile('MIT License'))

        new DeclaredLicenseFilter().filter(createProjectData(module))

        assertEquals([AGPL_NAME, APACHE_NAME].sort(), licenseNames(module))
    }

    @Test
    void keepsBundledFileListWhenIgnoringItsLicense() {
        ModuleData module = createModule(
                [pom(AGPL_NAME)] as LinkedHashSet<PomData>,
                new LinkedHashSet<ManifestData>(),
                bundledFile(APACHE_NAME))

        new DeclaredLicenseFilter().filter(createProjectData(module))

        List<String> files = module.licenseFiles.collectMany { data -> data.fileDetails*.file }
        assertEquals([BUNDLED_FILE], files)
    }

    private static PomData pom(String... licenseNames) {
        Set<License> licenses = licenseNames.collect { name -> new License(name, null) } as LinkedHashSet<License>
        return new PomData('example-library', null, null, null, licenses)
    }

    private static ManifestData manifest(String licenseName) {
        return new ManifestData(
                'example-library', '1.0', null, null, null,
                [new License(licenseName, null)] as LinkedHashSet<License>, false)
    }

    private static Set<LicenseFileData> bundledFile(String licenseName) {
        LicenseFileDetails details = new LicenseFileDetails(BUNDLED_FILE, licenseName, APACHE_URL)
        return [new LicenseFileData([details])] as LinkedHashSet<LicenseFileData>
    }

    private static ModuleData createModule(
            Set<PomData> poms, Set<ManifestData> manifests, Set<LicenseFileData> licenseFiles) {
        return new ModuleData('com.example', 'example-library', '1.0', true, manifests, licenseFiles, poms)
    }

    private static ProjectData createProjectData(ModuleData module) {
        ConfigurationData configuration =
                new ConfigurationData('runtimeClasspath', [module] as LinkedHashSet<ModuleData>)
        return new ProjectData(
                ProjectBuilder.builder().build(),
                [configuration] as LinkedHashSet<ConfigurationData>)
    }

    private static List<String> licenseNames(ModuleData module) {
        Set<License> licenses = LicenseDataCollector.multiModuleLicenseInfo(module).licenses
        return licenses.collect { license -> license.name }.sort()
    }
}
