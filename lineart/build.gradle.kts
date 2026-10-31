plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(libs.pdfbox)
    implementation(project(":core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.jai.imageio.jpeg2000)
    testRuntimeOnly(libs.junit.platform.launcher)
}

base {
    archivesName.set("stirling-office-convert-lineart")
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "stirling.software.officeconvert.lineart")
    }
}

publishing.publications.named<MavenPublication>("mavenJava") {
    artifactId = "stirling-office-convert-lineart"
    pom {
        name.set("Stirling Office Convert line art")
        description.set("Turns the pictures in a PDF into 1-bit line art (JBIG2, CCITT G4 or Flate) in plain Java on Apache PDFBox")
    }
}
