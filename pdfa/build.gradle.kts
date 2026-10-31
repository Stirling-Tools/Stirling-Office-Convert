plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(libs.pdfbox)
    implementation(project(":core"))
    api(project(":topdf"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.verapdf.validation.model)
    testImplementation(libs.jai.imageio.jpeg2000)
    testRuntimeOnly(libs.junit.platform.launcher)
}

base {
    archivesName.set("stirling-office-convert-pdfa")
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "stirling.software.officeconvert.pdfa")
    }
}

publishing.publications.named<MavenPublication>("mavenJava") {
    artifactId = "stirling-office-convert-pdfa"
    pom {
        name.set("Stirling Office Convert PDF/A")
        description.set("PDF to PDF/A (1a, 1b, 2a, 2b, 2u, 3a, 3b, 3u, 4) in plain Java on Apache PDFBox")
    }
}
