package stirling.software.gradle

import com.github.jk1.license.License
import com.github.jk1.license.LicenseFileDetails
import com.github.jk1.license.ModuleData
import com.github.jk1.license.ProjectData
import com.github.jk1.license.filter.DependencyFilter

class DeclaredLicenseFilter implements DependencyFilter {

    @Override
    ProjectData filter(ProjectData projectData) {
        projectData.configurations.each { configuration ->
            configuration.dependencies.each { module ->
                if (declaresLicense(module)) {
                    ignoreBundledFileLicenses(module, projectData)
                }
            }
        }
        return projectData
    }

    private static void ignoreBundledFileLicenses(ModuleData module, ProjectData projectData) {
        List<LicenseFileDetails> detectedFiles = (module.licenseFiles ?: [])
                .collectMany { licenseFile -> licenseFile.fileDetails ?: [] }
                .findAll { details -> details.license != null }
        if (detectedFiles.isEmpty()) {
            return
        }
        projectData.project.logger.info(
                "Ignoring licenses detected in bundled files of ${module.group}:${module.name}:${module.version}: "
                        + detectedFiles.collect { details -> details.license }.unique().join(', '))
        detectedFiles.each { details ->
            details.license = null
            details.licenseUrl = null
        }
    }

    static boolean declaresLicense(ModuleData module) {
        Collection<License> declared = []
        (module.poms ?: []).each { pom -> declared.addAll(pom.licenses ?: []) }
        (module.manifests ?: []).each { manifest -> declared.addAll(manifest.licenses ?: []) }
        return declared.any { license -> license.name != null && !license.name.isBlank() }
    }
}
